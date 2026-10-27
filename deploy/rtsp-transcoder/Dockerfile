FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg python3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY transcode.py /app/transcode.py
USER 65534:65534
ENTRYPOINT ["python3", "/app/transcode.py"]
