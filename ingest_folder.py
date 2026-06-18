#!/usr/bin/env python3
"""
ingest_folder.py  -  add a folder of media to the PhotoSync store
=================================================================
Use this for media you already have on the computer but are not sure is on
your phone. It hashes every image/video in a folder you choose and, for any
hash the DB does not already have, copies the file into the content addressed
store and records it. After running this, hit "Pull" in the phone app and the
phone will download whatever it is missing.

It writes to the SAME persistent volume the server uses, so the DB and media
stay in one place.

Examples
--------
  # index a folder, copying new files into the store at /data
  python ingest_folder.py --data /data --folder ~/Pictures/old_phone

  # the folder already lives inside the store and you just want it indexed
  python ingest_folder.py --data /data --folder /data/import --in-place

  # move instead of copy (frees space in the source folder)
  python ingest_folder.py --data /data --folder ~/dump --move

Run it on the same machine/volume as the server (it touches files directly,
not over HTTP). Safe to run repeatedly: already known hashes are skipped.
"""

from __future__ import annotations

import os
import argparse
import hashlib
import shutil
import sqlite3
from pathlib import Path
from time import time

IMAGE_EXT = {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
             "tif", "tiff", "dng", "avif"}
VIDEO_EXT = {"mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm", "mts", "m2ts"}
ALL_EXT = IMAGE_EXT | VIDEO_EXT


def media_type_for(ext: str) -> str:
    ext = ext.lower().lstrip(".")
    if ext in VIDEO_EXT:
        return "video"
    if ext in IMAGE_EXT:
        return "image"
    return "other"


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for blk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(blk)
    return h.hexdigest()


def open_db(data: Path) -> sqlite3.Connection:
    """Open (and if needed create) the same index the server uses."""
    con = sqlite3.connect(data / "index.db", timeout=30)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL;")
    con.execute("""
        CREATE TABLE IF NOT EXISTS media (
            hash        TEXT PRIMARY KEY,
            ext         TEXT,
            orig_name   TEXT,
            size        INTEGER,
            media_type  TEXT,
            taken_at    INTEGER,
            added_at    INTEGER,
            rel_path    TEXT
        );""")
    con.execute("CREATE INDEX IF NOT EXISTS idx_added ON media(added_at);")
    con.commit()
    return con


def store_path(media_root: Path, h: str, ext: str) -> Path:
    ext = ext.lower().lstrip(".")
    sub = media_root / h[:2] / h[2:4]
    sub.mkdir(parents=True, exist_ok=True)
    return sub / (f"{h}.{ext}" if ext else h)


def iter_media(folder: Path):
    for p in folder.rglob("*"):
        if p.is_file() and p.suffix.lower().lstrip(".") in ALL_EXT:
            yield p


def write_dupe_log(folder: Path, dupes: list[tuple[Path, Path]]) -> Path | None:
    """Write a text log of duplicate files into the scanned folder.
    A "duplicate" is a file in --folder whose hash is already in the store,
    so it was skipped. Each line is:  <source in folder>  ->  <file in store>.
    The source copies are the ones safe to delete. Returns the log path, or
    None if there were no duplicates."""
    if not dupes:
        return None
    log = folder / f"ingest_duplicates_{int(time())}.txt"
    with open(log, "w", encoding="utf-8") as f:
        f.write(f"# {len(dupes)} duplicate(s) found during ingest\n")
        f.write("# already in the store; the source copy on the left is safe to delete\n\n")
        for src, existing in dupes:
            f.write(f"{src}  ->  {existing}\n")
    return log


def main() -> None:
    ap = argparse.ArgumentParser(description="Add a folder of media to the PhotoSync store.")
    ap.add_argument("--data", required=True, help="persistent volume root (same as the server's DATA)")
    ap.add_argument("--folder", required=True, help="folder to scan recursively")
    ap.add_argument("--move", action="store_true", help="move new files into the store instead of copying")
    ap.add_argument("--in-place", action="store_true",
                    help="files already live inside --data/media; just index them, do not copy")
    args = ap.parse_args()

    data = Path(args.data).resolve()
    folder = Path(args.folder).resolve()
    media_root = data / "media"
    media_root.mkdir(parents=True, exist_ok=True)
    if not folder.is_dir():
        raise SystemExit(f"not a folder: {folder}")

    con = open_db(data)
    known = {r["hash"] for r in con.execute("SELECT hash FROM media")}

    scanned = added = skipped = 0
    dupes: list[tuple[Path, Path]] = []
    t0 = time()
    for src in iter_media(folder):
        scanned += 1
        try:
            h = sha256_file(src)
        except OSError as e:
            print(f"  ! cannot read {src}: {e}")
            continue

        if h in known:
            skipped += 1
            row = con.execute("SELECT rel_path FROM media WHERE hash=?", (h,)).fetchone()
            if row:
                dupes.append((src, data / row["rel_path"]))
            if scanned % 200 == 0:
                print(f"  ... {scanned} scanned, {added} new, {skipped} already known")
            continue

        ext = src.suffix.lower().lstrip(".")
        if args.in_place:
            rel = src.relative_to(data) if data in src.parents or src.is_relative_to(data) \
                else None
            if rel is None:
                # not actually inside the volume; fall back to copy
                dest = store_path(media_root, h, ext)
                shutil.copy2(src, dest)
                rel = dest.relative_to(data)
            else:
                dest = src
        else:
            dest = store_path(media_root, h, ext)
            if not dest.exists():
                if args.move:
                    shutil.move(str(src), str(dest))
                else:
                    shutil.copy2(src, dest)
            rel = dest.relative_to(data)

        con.execute(
            """INSERT OR REPLACE INTO media
               (hash, ext, orig_name, size, media_type, taken_at, added_at, rel_path)
               VALUES (?,?,?,?,?,?,?,?)""",
            (h, ext, src.name, dest.stat().st_size, media_type_for(ext),
             int(src.stat().st_mtime * 1000), int(time() * 1000), str(rel)))
        known.add(h)
        added += 1
        if added % 50 == 0:
            con.commit()

    con.commit()
    con.close()
    log = write_dupe_log(folder, dupes)
    dt = time() - t0
    print(f"\nDone in {dt:.1f}s")
    print(f"  scanned : {scanned}")
    print(f"  added   : {added}")
    print(f"  skipped : {skipped} (already in db)")
    print(f"  db      : {data / 'index.db'}")
    if log:
        print(f"  dupes   : {len(dupes)} logged -> {log}")


if __name__ == "__main__":
    main()
