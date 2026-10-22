#!/usr/bin/env python3
"""Build-only loopback relay that keeps extract requests alive across SSH loss.

Retry the original range and discard its already delivered prefix. The upstream
range cache can therefore reuse the same entry without redownloading public data.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True)
parser.add_argument('--port-file', required=True)
parser.add_argument('--stall-timeout', type=float, default=900)
args = parser.parse_args()
source = urllib.parse.urlparse(args.source)
if source.scheme != 'http' or source.hostname not in ('127.0.0.1', 'localhost', '::1'):
    raise SystemExit('The relay source must be the loopback SSH tunnel')


class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.transfer(head=True)

    def do_GET(self):
        self.transfer(head=False)

    def transfer(self, head):
        byte_range = self.headers.get('Range', '')
        match = re.fullmatch(r'bytes=(\d+)-(\d+)', byte_range)
        if not head and (not match or int(match[1]) > int(match[2])):
            self.send_error(400, 'A single explicit byte range is required')
            return
        headers = {} if head else {'Range': byte_range}
        length = None if head else int(match[2]) - int(match[1]) + 1
        received = 0
        identity = None
        sent_headers = False
        last_progress = time.monotonic()
        while time.monotonic() - last_progress < args.stall_timeout:
            try:
                request = urllib.request.Request(
                    args.source, headers=headers, method='HEAD' if head else 'GET')
                with urllib.request.urlopen(request, timeout=15) as response:
                    current = (
                        response.headers.get('Content-Range'),
                        response.headers.get('ETag'),
                        response.headers.get('Content-Length'))
                    if not head:
                        expected = f'bytes {match[1]}-{match[2]}/'
                        if (response.status != 206 or
                                not (current[0] or '').startswith(expected) or
                                current[2] != str(length)):
                            raise ValueError('Unexpected upstream range response')
                    if identity is not None and identity != current:
                        raise ValueError('Upstream archive identity changed')
                    identity = current
                    if not sent_headers:
                        self.send_response(response.status)
                        for name in ('Content-Range', 'Content-Length', 'ETag', 'Content-Type'):
                            if response.headers.get(name) is not None:
                                self.send_header(name, response.headers[name])
                        self.send_header('Accept-Ranges', 'bytes')
                        self.end_headers()
                        sent_headers = True
                    if head:
                        return
                    skip = received
                    while True:
                        chunk = response.read1(256 * 1024)
                        if not chunk:
                            break
                        if skip:
                            skipped = min(skip, len(chunk))
                            skip -= skipped
                            chunk = chunk[skipped:]
                        if not chunk:
                            continue
                        if received + len(chunk) > length:
                            raise ValueError('Upstream returned excess data')
                        try:
                            self.wfile.write(chunk)
                        except OSError:
                            return
                        received += len(chunk)
                        last_progress = time.monotonic()
                    if received == length:
                        return
            except (OSError, urllib.error.URLError):
                pass
            except ValueError as error:
                print(str(error), flush=True)
                break
            time.sleep(2)
        if not sent_headers:
            self.send_error(502, 'The build tunnel did not recover')
        self.close_connection = True

    def log_message(self, *_):
        pass


server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
server.daemon_threads = True
Path(args.port_file).write_text(str(server.server_port))
server.serve_forever()
