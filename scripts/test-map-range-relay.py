#!/usr/bin/env python3
"""A broken upstream response must not truncate or duplicate extractor bytes."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

payload = bytes(range(256)) * 4096
requests = []


class Upstream(BaseHTTPRequestHandler):
    def do_GET(self):
        requests.append(self.headers['Range'])
        self.send_response(206)
        self.send_header('Content-Range', f'bytes 1024-{1023 + len(payload)}/16777216')
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('ETag', '"immutable-test"')
        self.end_headers()
        # Interrupt the first response after a non-aligned number of bytes.
        self.wfile.write(payload[:345678] if len(requests) == 1 else payload)
        self.close_connection = True

    def log_message(self, *_):
        pass


upstream = ThreadingHTTPServer(('127.0.0.1', 0), Upstream)
threading.Thread(target=upstream.serve_forever, daemon=True).start()
with tempfile.TemporaryDirectory() as directory:
    port = Path(directory) / 'port'
    relay = subprocess.Popen([
        sys.executable, str(Path(__file__).with_name('map-range-relay.py')),
        '--source', f'http://127.0.0.1:{upstream.server_port}/map.pmtiles',
        '--port-file', str(port), '--stall-timeout', '15'])
    try:
        for _ in range(100):
            if port.exists():
                break
            time.sleep(.05)
        byte_range = f'bytes=1024-{1023 + len(payload)}'
        request = urllib.request.Request(
            f'http://127.0.0.1:{port.read_text()}/map.pmtiles',
            headers={'Range': byte_range})
        with urllib.request.urlopen(request, timeout=20) as response:
            assert response.status == 206
            assert response.read() == payload
        assert requests == [byte_range, byte_range]
        print('Range relay passed: interrupted upstream resumed without duplicate or missing bytes.')
    finally:
        relay.terminate()
        relay.wait(timeout=10)
upstream.shutdown()
