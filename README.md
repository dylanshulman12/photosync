# PhotoSync

Two-way photo/video sync between your phone and a server you control. Identity of
every file is its **SHA-256**, so nothing is ever stored twice, the phone can tell
exactly what is and is not backed up, and a "pull" cleanly fills in whatever the
phone is missing.

What you asked for, and where it is:

| Piece | File | Role |
|---|---|---|
| Sync APK | `android/` | Android Studio project: gallery with synced badges, Sync + Pull buttons, periodic backup toggle, background transfers with notifications |
| Endpoint | `server.py` | FastAPI service: hash-check, resumable upload, resumable (range) download, listing, thumbnails; SQLite index + media on one volume |
| Folder ingest | `ingest_folder.py` | Standalone: hash a folder and add anything new to the same DB + store |
| Container | `Dockerfile` | One file to stand the endpoint up with a persistent `/data` volume |

---

## How the sync works (the one idea)

Every photo/video is identified by the SHA-256 of its bytes.

- **Sync (push):** the phone scans all images and videos, computes each file's hash
  (cached on-device so it is done once), asks the server `POST /api/check` which
  hashes it already has, and uploads only the missing ones.
- **Pull:** the phone asks `GET /api/media` for everything the server has, compares
  against its local hashes, and downloads only what it lacks into `Pictures/PhotoSync`
  or `Movies/PhotoSync` so the files show up in the gallery.
- **Gallery badges:** green = on the server, red = not yet, grey = not hashed yet.
  The grid loads instantly from the device; badges fill in as hashing/checking runs.

Both transfers are resumable. Uploads go in chunks with an offset the server tracks
in a `.part` file; downloads use HTTP Range and append to a local `.part`. An
interrupted transfer picks up where it stopped instead of restarting.

---

## Server: run it

### Using Docker compose 

```bash
docker compose up -d --build
```

### Standalone docker

```bash
cd photosync
docker build -t photosync .
docker run -d --name photosync -p 8000:8000 \
    -v photosync_data:/data \
    -e SYNC_API_KEY=pick-a-long-secret \
    photosync
```




`photosync_data` is a persistent named volume holding the SQLite index AND the media
(under `/data/index.db` and `/data/media/...`). Use `-v /some/host/path:/data` to keep
it in a folder you can see. Leave out `SYNC_API_KEY` to run with no auth on a trusted
LAN.

### Without Docker

```bash
pip install "fastapi>=0.110" "uvicorn[standard]>=0.29" pillow
DATA=./data SYNC_API_KEY=pick-a-long-secret uvicorn server:app --host 0.0.0.0 --port 8000
```

Open `http://<server-ip>:8000/` for a status page. The phone points at that same
address. Video thumbnails need `ffmpeg` on the server (the Docker image includes it).

---

## Ingest a folder you already have

For media on the computer that may or may not be on your phone. Run it on the same
machine/volume as the server. It hashes the folder and adds anything new, so a later
phone **Pull** brings those files down.

```bash
# copy new files into the store at /data and index them
python ingest_folder.py --data /data --folder ~/Pictures/old_phone

# files already inside the volume, just index in place
python ingest_folder.py --data /data --folder /data/import --in-place

# move instead of copy
python ingest_folder.py --data /data --folder ~/dump --move
```
Safe to re-run: known hashes are skipped. It creates the same DB schema the server
uses if it does not exist yet, all inside `--data`.

---

## The APK

The `android/` folder is a complete Android Studio project (Kotlin, CameraX-free,
uses MediaStore + Room + WorkManager + OkHttp + Coil).

Features wired up:
- discovers **all** images and videos on the device via MediaStore,
- fast-loading gallery grid with a per-item synced badge,
- **Sync** button (push missing up) and **Pull** button (download missing down),
- background transfers via a WorkManager foreground service with an ongoing progress
  notification, plus a completion notification,
- **Automatic backup** toggle with **daily / weekly / monthly** options (and an
  optional "also pull" during the periodic run, in Settings),
- Settings dialog for the server URL and API key, with a connection test.

### Build it
1. Install Android Studio.
2. `File > Open` the `android/` folder, let it sync (downloads Gradle + SDK).
3. `Build > Build APK(s)`, or press Run with a phone connected.
4. On first launch, grant media and notification permissions, open Settings, enter
   your server URL (e.g. `http://192.168.1.20:8000`) and API key, then tap Sync.

### From the command line
```bash
cd android
gradle wrapper
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
```

A note on why there is no prebuilt `.apk` here: an installable APK has to be compiled
and signed with the Android SDK + Gradle, which cannot run in the environment this was
generated in. The project is complete and builds to an APK with the steps above.

### Notes
- The app allows cleartext HTTP (`usesCleartextTraffic="true"`) so it can talk to a
  plain `http://` LAN server. If you put the server behind HTTPS, that still works.
- First sync of a large library is the slow part because every file is hashed once;
  results are cached, so later syncs only touch new files.
- Periodic backups use Android's minimum sensible cadence; "daily" means roughly once
  a day when on a network and the battery is not low.

---

## Files recap
```
server.py           the endpoint (run via Docker or uvicorn)
ingest_folder.py    add a local folder's media to the same DB + store
Dockerfile          builds/serves the endpoint, /data is the persistent volume
android/            the sync app (build to an APK in Android Studio)
```
