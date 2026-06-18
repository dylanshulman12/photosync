FROM python:3.12-slim

# ffmpeg gives us video thumbnails; Pillow handles image thumbnails.
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir \
    "fastapi>=0.110" "uvicorn[standard]>=0.29" pillow

WORKDIR /app
COPY server.py /app/server.py

# All state (SQLite index + media + temp + thumbs) lives here. Mount a host
# directory or a named volume at /data so it persists across restarts:
#   docker run -d -p 8000:8000 -v photosync_data:/data \
#       -e SYNC_API_KEY=change-me photosync
ENV DATA=/data
VOLUME ["/data"]

EXPOSE 8000

# 1 worker keeps the SQLite writer single, which is what we want for a
# personal sync server. Bump --workers only if you move to Postgres.
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
