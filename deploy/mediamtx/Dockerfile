FROM busybox:1.37.0-musl AS tools
FROM bluenviron/mediamtx:1.19.3
COPY --from=tools /bin/busybox /busybox
