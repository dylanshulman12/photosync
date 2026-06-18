"""
server.py  -  PhotoSync endpoint
================================
A single file FastAPI service that the phone app talks to. It stores every
image/video once, addressed by its SHA-256, in a content addressed tree, and
keeps a SQLite index. Both the DB and the media live under one persistent
volume (the $DATA dir, default /data), so a single docker volume holds
everything.

Identity = SHA-256 of the file bytes. That is how dedup, "synced vs not
synced", and pull all work: the phone hashes its library, asks /api/check what
the server already has, and only transfers the difference.

Routes
------
  GET  /healthz                      liveness + item count
  GET  /                             tiny status page
  POST /api/check                    {"hashes":[...]} -> {"have":[...]}
  GET  /api/media?limit=&offset=     paged list of everything on the server
  GET  /api/upload/status?hash=H     {"complete":bool,"offset":N}  (resume point)
  PUT  /api/upload                   resumable chunked upload (see headers below)
  GET  /api/download/{hash}          range aware, resumable download
  GET  /api/thumb/{hash}             jpeg thumbnail (best effort)
  GET  /api/meta/{hash}              metadata for one item

Resumable upload protocol (tus-like, kept tiny):
  query:   ?hash=&ext=&name=&type=&taken=
  headers: Upload-Offset: <bytes already on server>   Upload-Total: <full size>
  body:    the chunk of bytes starting at Upload-Offset
  The server appends to tmp/<hash>.part. When the part reaches Upload-Total it
  verifies the SHA-256 and moves it into the store. A client resumes by reading
  /api/upload/status first.

Auth: if env SYNC_API_KEY is set, every /api route requires header
  X-Api-Key: <that value>.

Run locally:  DATA=./data uvicorn server:app --host 0.0.0.0 --port 8000
"""

from __future__ import annotations

import os
import re
import json
import shutil
import sqlite3
import hashlib
import threading
import subprocess
from pathlib import Path
from time import time
from datetime import datetime

from fastapi import FastAPI, Request, Header, HTTPException, Query
from fastapi.responses import JSONResponse, HTMLResponse, StreamingResponse, Response

# --------------------------------------------------------------------------
# Storage layout on the persistent volume
# --------------------------------------------------------------------------
DATA = Path(os.environ.get("DATA", "/data")).resolve()
MEDIA = DATA / "media"
TMP = DATA / "tmp"
THUMBS = DATA / "thumbs"
DB_PATH = DATA / "index.db"
API_KEY = os.environ.get("SYNC_API_KEY", "")          # empty = open
CHUNK = 1024 * 1024                                    # 1 MiB streaming chunk

for d in (MEDIA, TMP, THUMBS):
    d.mkdir(parents=True, exist_ok=True)

IMAGE_EXT = {"jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
             "tif", "tiff", "dng", "avif"}
VIDEO_EXT = {"mp4", "mov", "m4v", "3gp", "avi", "mkv", "webm", "mts", "m2ts"}

_db_lock = threading.Lock()


def media_type_for(ext: str) -> str:
    ext = ext.lower().lstrip(".")
    if ext in VIDEO_EXT:
        return "video"
    if ext in IMAGE_EXT:
        return "image"
    return "other"


def store_path(h: str, ext: str, taken_ms: int | None = None) -> Path:
    # Bucket by capture year so the store stays scrollable:
    #   media/2024/<hash>.<ext>
    # taken_ms is the client's capture/modified time in milliseconds. If it's
    # missing or unparseable, the file lands in media/unknown/ rather than
    # being dropped.
    ext = ext.lower().lstrip(".")
    year = "unknown"
    if taken_ms:
        try:
            year = datetime.fromtimestamp(taken_ms / 1000).strftime("%Y")
        except (OSError, ValueError, OverflowError):
            year = "unknown"
    sub = MEDIA / year
    sub.mkdir(parents=True, exist_ok=True)
    return sub / (f"{h}.{ext}" if ext else h)


# --------------------------------------------------------------------------
# SQLite index (created inside the same volume)
# --------------------------------------------------------------------------
def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH, check_same_thread=False, timeout=30)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA journal_mode=WAL;")
    return con


def init_db() -> None:
    with db() as con:
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


init_db()


def db_has(hashes: list[str]) -> set[str]:
    if not hashes:
        return set()
    out: set[str] = set()
    with db() as con:
        # chunk the IN clause to stay under SQLite's variable limit
        for i in range(0, len(hashes), 500):
            part = hashes[i:i + 500]
            q = "SELECT hash FROM media WHERE hash IN (%s)" % ",".join("?" * len(part))
            out.update(r["hash"] for r in con.execute(q, part))
    return out


def db_insert(row: dict) -> None:
    with _db_lock, db() as con:
        con.execute("""INSERT OR REPLACE INTO media
            (hash, ext, orig_name, size, media_type, taken_at, added_at, rel_path)
            VALUES (:hash,:ext,:orig_name,:size,:media_type,:taken_at,:added_at,:rel_path)""",
                    row)
        con.commit()


def db_get(h: str):
    with db() as con:
        r = con.execute("SELECT * FROM media WHERE hash=?", (h,)).fetchone()
        return dict(r) if r else None


# --------------------------------------------------------------------------
# App + auth
# --------------------------------------------------------------------------
app = FastAPI(title="PhotoSync", version="1.0")


def check_key(x_api_key: str | None) -> None:
    if API_KEY and x_api_key != API_KEY:
        raise HTTPException(401, "bad or missing X-Api-Key")


@app.get("/healthz")
def healthz():
    with db() as con:
        n = con.execute("SELECT COUNT(*) c FROM media").fetchone()["c"]
    return {"ok": True, "items": n, "data_dir": str(DATA)}


@app.get("/", response_class=HTMLResponse)
def home():
    with db() as con:
        n = con.execute("SELECT COUNT(*) c FROM media").fetchone()["c"]
        sz = con.execute("SELECT COALESCE(SUM(size),0) s FROM media").fetchone()["s"]
    gib = sz / (1024 ** 3)
    return f"""<html><body style="font:15px system-ui;background:#0d0f12;color:#e8eaed;padding:40px">
    <h1>PhotoSync endpoint</h1>
    <p>{n} items stored, {gib:.2f} GiB.</p>
    <p>Volume: <code>{DATA}</code> &nbsp; Auth: <code>{'on' if API_KEY else 'open'}</code></p>
    <p style="color:#8b94a3">Point the phone app at this server's address.</p>
    </body></html>"""


# --------------------------------------------------------------------------
# Which hashes does the server already have?
# --------------------------------------------------------------------------
@app.post("/api/check")
async def check(request: Request, x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    body = await request.json()
    hashes = body.get("hashes", [])
    have = db_has(hashes)
    return {"have": sorted(have)}


# --------------------------------------------------------------------------
# List everything (for the phone's pull comparison)
# --------------------------------------------------------------------------
@app.get("/api/media")
def list_media(limit: int = Query(2000, le=10000), offset: int = 0,
               x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    with db() as con:
        total = con.execute("SELECT COUNT(*) c FROM media").fetchone()["c"]
        rows = con.execute(
            """SELECT hash, ext, orig_name, size, media_type, taken_at, added_at
               FROM media ORDER BY added_at DESC LIMIT ? OFFSET ?""",
            (limit, offset)).fetchall()
    return {"total": total, "limit": limit, "offset": offset,
            "items": [dict(r) for r in rows]}


# --------------------------------------------------------------------------
# Resumable upload
# --------------------------------------------------------------------------
@app.get("/api/upload/status")
def upload_status(hash: str, x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    if db_get(hash):
        return {"complete": True, "offset": -1}
    part = TMP / f"{hash}.part"
    return {"complete": False, "offset": part.stat().st_size if part.exists() else 0}


@app.put("/api/upload")
async def upload(request: Request,
                 hash: str, ext: str = "", name: str = "", type: str = "",
                 taken: int = 0,
                 upload_offset: int = Header(default=0),
                 upload_total: int = Header(default=0),
                 x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)

    if db_get(hash):
        return {"complete": True, "offset": -1}

    part = TMP / f"{hash}.part"
    current = part.stat().st_size if part.exists() else 0
    if upload_offset != current:
        # client is out of sync; tell it the real resume point
        return JSONResponse({"complete": False, "offset": current}, status_code=409)

    # append the streamed chunk
    with open(part, "ab") as f:
        async for chunk in request.stream():
            f.write(chunk)
    new_size = part.stat().st_size

    total = upload_total or new_size
    if new_size < total:
        return {"complete": False, "offset": new_size}

    # we have the whole file: verify hash, then finalize
    digest = sha256_file(part)
    if digest != hash:
        part.unlink(missing_ok=True)
        raise HTTPException(400, f"hash mismatch (got {digest})")

    dest = store_path(hash, ext, taken)
    shutil.move(str(part), str(dest))
    db_insert({
        "hash": hash, "ext": ext.lower().lstrip("."),
        "orig_name": name or dest.name,
        "size": new_size,
        "media_type": type or media_type_for(ext),
        "taken_at": taken or None,
        "added_at": int(time() * 1000),
        "rel_path": str(dest.relative_to(DATA)),
    })
    return {"complete": True, "offset": -1, "hash": hash}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for blk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(blk)
    return h.hexdigest()


# --------------------------------------------------------------------------
# Resumable (range) download
# --------------------------------------------------------------------------
@app.get("/api/download/{hash}")
def download(hash: str, request: Request,
             x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    row = db_get(hash)
    if not row:
        raise HTTPException(404, "unknown hash")
    path = DATA / row["rel_path"]
    if not path.exists():
        raise HTTPException(410, "file missing on disk")

    size = path.stat().st_size
    rng = request.headers.get("range")
    start, end = 0, size - 1
    status = 200
    headers = {
        "Accept-Ranges": "bytes",
        "Content-Type": "application/octet-stream",
        "Content-Disposition": f'attachment; filename="{row["orig_name"]}"',
        "X-Content-Hash": hash,
    }

    if rng:
        m = re.match(r"bytes=(\d+)-(\d*)", rng)
        if m:
            start = int(m.group(1))
            if m.group(2):
                end = min(int(m.group(2)), size - 1)
            status = 206
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"

    length = end - start + 1
    headers["Content-Length"] = str(length)

    def streamer():
        with open(path, "rb") as f:
            f.seek(start)
            remaining = length
            while remaining > 0:
                buf = f.read(min(CHUNK, remaining))
                if not buf:
                    break
                remaining -= len(buf)
                yield buf

    return StreamingResponse(streamer(), status_code=status, headers=headers)


# --------------------------------------------------------------------------
# Metadata + best effort thumbnail
# --------------------------------------------------------------------------
@app.get("/api/meta/{hash}")
def meta(hash: str, x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    row = db_get(hash)
    if not row:
        raise HTTPException(404, "unknown hash")
    return row


@app.get("/api/thumb/{hash}")
def thumb(hash: str, x_api_key: str | None = Header(default=None)):
    check_key(x_api_key)
    row = db_get(hash)
    if not row:
        raise HTTPException(404, "unknown hash")
    out = THUMBS / f"{hash}.jpg"
    if not out.exists():
        if not make_thumb(DATA / row["rel_path"], out, row["media_type"]):
            raise HTTPException(404, "no thumbnail")
    return Response(out.read_bytes(), media_type="image/jpeg")


def make_thumb(src: Path, out: Path, mtype: str) -> bool:
    try:
        if mtype == "image":
            from PIL import Image, ImageOps
            im = Image.open(src)
            im = ImageOps.exif_transpose(im).convert("RGB")
            im.thumbnail((512, 512))
            im.save(out, "JPEG", quality=80)
            return True
        if mtype == "video" and shutil.which("ffmpeg"):
            subprocess.run(
                ["ffmpeg", "-y", "-i", str(src), "-vframes", "1",
                 "-vf", "scale=512:-1", str(out)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
            return out.exists()
    except Exception:
        return False
    return False


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("server:app", host="0.0.0.0", port=8000)
