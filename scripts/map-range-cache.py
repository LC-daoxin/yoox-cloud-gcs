#!/usr/bin/env python3
"""Build-only resumable range mirror for an immutable public PMTiles archive.

Every received prefix is checkpointed on disk. Large ranges are streamed without
an arbitrary size limit; retries replay cached bytes and resume the upstream
range. Keep this cache outside the deliverable and remove it after extraction.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True)
parser.add_argument('--cache', required=True)
parser.add_argument('--port-file', required=True)
args = parser.parse_args()
if not args.source.startswith('https://'):
    raise SystemExit('The remote source must use HTTPS')
cache = Path(args.cache)
cache.mkdir(parents=True, exist_ok=True)
cache_lock = (cache / 'server.lock').open('a')
try:
    fcntl.flock(cache_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit('Another download is already using this range cache')
mutex = threading.Lock()
root_lock = threading.Lock()
locks = {}
processes = set()
root = None


def save_json(filename, value):
    temporary = filename.with_name(filename.name + '.tmp')
    with temporary.open('w') as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(filename)


def load_json(filename):
    try:
        return json.loads(filename.read_text())
    except (OSError, ValueError):
        return None


source_file = cache / 'source.json'
previous_source = load_json(source_file)
if previous_source is not None and previous_source != args.source:
    raise SystemExit('Range cache belongs to a different source')
save_json(source_file, args.source)


def register(process):
    with mutex:
        processes.add(process)
    return process


def finish(process):
    if process.poll() is None:
        process.terminate()
    process.wait()
    with mutex:
        processes.discard(process)


def parse_headers(filename, start, end):
    text = filename.read_text()
    ranges = re.findall(r'(?im)^content-range:\s*bytes (\d+)-(\d+)/(\d+)\s*$', text)
    if not ranges or tuple(map(int, ranges[-1][:2])) != (start, end):
        raise RuntimeError('Source returned an incorrect byte range')
    etags = re.findall(r'(?im)^etag:\s*(.+?)\s*$', text)
    return {'total': int(ranges[-1][2]), 'etag': etags[-1] if etags else '"immutable-map"'}


def root_metadata():
    global root
    with root_lock:
        if root is not None:
            return root
        data = cache / '0-126'
        meta = load_json(cache / '0-126.json')
        if data.is_file() and data.stat().st_size == 127 and meta and 'total' in meta:
            root = {'total': meta['total'], 'etag': meta['etag']}
            return root
        headers = cache / 'root.headers'
        partial = cache / 'root.partial'
        process = register(subprocess.Popen([
            'curl', '--http1.1', '-fsSL', '--retry', '6', '--retry-all-errors',
            '--connect-timeout', '20', '--max-time', '60', '--range', '0-126',
            '-D', str(headers), '-o', str(partial), args.source,
        ], stdout=subprocess.DEVNULL))
        try:
            if process.wait() != 0:
                raise RuntimeError('Cannot read the upstream archive header')
        finally:
            finish(process)
        meta = parse_headers(headers, 0, 126)
        if partial.stat().st_size != 127 or partial.read_bytes()[:7] != b'PMTiles':
            raise RuntimeError('Invalid upstream PMTiles header')
        partial.replace(data)
        save_json(cache / '0-126.json', meta)
        root = meta
        return root


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        # Bound loopback buffers when several multi-GB cached ranges are read
        # concurrently; macOS otherwise can fail sendall with ENOBUFS.
        self.request.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 64 * 1024)
        super().setup()

    def send_range_headers(self, start, end, meta):
        self.send_response(206)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(end - start + 1))
        self.send_header('Content-Range', f"bytes {start}-{end}/{meta['total']}")
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('ETag', meta['etag'])
        self.end_headers()

    def do_HEAD(self):
        try:
            meta = root_metadata()
            self.send_response(200)
            self.send_header('Content-Length', str(meta['total']))
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('ETag', meta['etag'])
            self.end_headers()
        except Exception as error:
            self.send_error(502, str(error))

    def do_GET(self):
        match = re.fullmatch(r'bytes=(\d+)-(\d+)', self.headers.get('Range', ''))
        if not match:
            self.send_error(400, 'A single explicit byte range is required')
            return
        start, end = map(int, match.groups())
        try:
            meta = root_metadata()
        except Exception as error:
            self.send_error(502, str(error))
            return
        # Range length is not capped: national extracts legitimately request
        # contiguous regions larger than 512 MiB.
        if start > end or end >= meta['total']:
            self.send_error(416)
            return
        key = f'{start}-{end}'
        with mutex:
            lock = locks.setdefault(key, threading.Lock())
        with lock:
            self.transfer(start, end, meta, key)

    def transfer(self, start, end, meta, key):
        data = cache / key
        metadata = cache / f'{key}.json'
        partial = cache / f'{key}.partial'
        checkpoint = cache / f'{key}.checkpoint.json'
        length = end - start + 1
        stored = load_json(metadata)
        if data.is_file() and data.stat().st_size == length and stored and stored.get('etag') == meta['etag']:
            self.send_range_headers(start, end, meta)
            try:
                with data.open('rb') as stream:
                    while chunk := stream.read(256 * 1024):
                        self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError):
                pass
            return
        saved = load_json(checkpoint) or {}
        received = saved.get('received', 0)
        if (not partial.is_file() or saved.get('etag') != meta['etag'] or
                not isinstance(received, int) or received < 0 or received > length or
                partial.stat().st_size < received):
            received = 0
        partial.touch(exist_ok=True)
        with partial.open('r+b') as output:
            output.truncate(received)
            self.send_range_headers(start, end, meta)
            try:
                output.seek(0)
                remaining = received
                while remaining:
                    chunk = output.read(min(256 * 1024, remaining))
                    if not chunk:
                        raise RuntimeError('Cached range prefix is truncated')
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
                output.seek(received)
                failures = 0
                synced = received

                def sync():
                    output.flush()
                    os.fsync(output.fileno())
                    save_json(checkpoint, {**meta, 'received': received})

                while received < length:
                    offset = start + received
                    before = received
                    headers = cache / f'{key}.headers'
                    process = register(subprocess.Popen([
                        'curl', '--http1.1', '-fsSL', '--connect-timeout', '20',
                        '--speed-time', '60', '--speed-limit', '1024',
                        '--range', f'{offset}-{end}', '-D', str(headers), args.source,
                    ], stdout=subprocess.PIPE))
                    try:
                        first = True
                        while chunk := process.stdout.read1(256 * 1024):
                            if first:
                                actual = parse_headers(headers, offset, end)
                                if actual != meta:
                                    raise RuntimeError('Upstream archive identity changed')
                                first = False
                            if received + len(chunk) > length:
                                raise RuntimeError('Source returned too many bytes')
                            output.write(chunk)
                            received += len(chunk)
                            if received - synced >= 1024 * 1024:
                                sync()
                                synced = received
                            self.wfile.write(chunk)
                        process.wait()
                    finally:
                        finish(process)
                        process.stdout.close()
                        sync()
                        headers.unlink(missing_ok=True)
                    if received == before:
                        failures += 1
                        if failures >= 8:
                            raise RuntimeError('Upstream repeatedly failed without progress')
                        time.sleep(min(2 ** failures, 15))
                    else:
                        failures = 0
                sync()
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception as error:
                print(f'Range {key}: {error}', flush=True)
                self.close_connection = True
                return
        save_json(metadata, {**meta, 'received': length})
        partial.replace(data)
        checkpoint.unlink(missing_ok=True)

    def log_message(self, format, *values):
        pass


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
Path(args.port_file).write_text(str(server.server_port))


def stop(*_):
    with mutex:
        for process in processes:
            process.terminate()
    os._exit(0)


signal.signal(signal.SIGTERM, stop)
signal.signal(signal.SIGINT, stop)
server.serve_forever()
