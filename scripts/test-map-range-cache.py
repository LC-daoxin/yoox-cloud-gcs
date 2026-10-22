#!/usr/bin/env python3
"""Exercise large ranges and checkpoint recovery without downloading any data."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.request

with tempfile.TemporaryDirectory() as directory:
    cache = Path(directory)
    meta = {'total': 2 ** 34, 'etag': '"test-archive"'}
    (cache / '0-126').write_bytes(b'PMTiles' + bytes(120))
    (cache / '0-126.json').write_text(json.dumps(meta))
    length = 512 * 1024 * 1024 + 1
    start = 1024
    end = start + length - 1
    with (cache / f'{start}-{end}').open('wb') as stream:
        stream.write(b'A')
        stream.seek(length - 1)
        stream.write(b'Z')
    (cache / f'{start}-{end}.json').write_text(json.dumps(meta))
    checkpoint_data = b'checkpoint recovery' * 4096
    checkpoint_start = 2 ** 32
    checkpoint_end = checkpoint_start + len(checkpoint_data) - 1
    key = f'{checkpoint_start}-{checkpoint_end}'
    (cache / f'{key}.partial').write_bytes(checkpoint_data)
    (cache / f'{key}.checkpoint.json').write_text(json.dumps({**meta, 'received': len(checkpoint_data)}))
    port_file = cache / 'port'
    process = subprocess.Popen([
        sys.executable, str(Path(__file__).with_name('map-range-cache.py')),
        '--source', 'https://example.invalid/map.pmtiles',
        '--cache', str(cache), '--port-file', str(port_file)
    ])
    try:
        for _ in range(100):
            if port_file.exists():
                break
            if process.poll() is not None:
                raise RuntimeError('Range mirror failed to start')
            time.sleep(.05)
        url = f'http://127.0.0.1:{port_file.read_text()}/china.pmtiles'
        request = urllib.request.Request(url, headers={'Range': f'bytes={start}-{end}'})
        with urllib.request.urlopen(request, timeout=30) as response:
            assert response.status == 206
            assert response.headers['Content-Range'] == f"bytes {start}-{end}/{meta['total']}"
            total = 0
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                last = chunk[-1:]
            assert total == length and last == b'Z'
        request = urllib.request.Request(url, headers={'Range': f'bytes={checkpoint_start}-{checkpoint_end}'})
        with urllib.request.urlopen(request, timeout=10) as response:
            assert response.status == 206 and response.read() == checkpoint_data
        for _ in range(100):
            if (cache / key).exists():
                break
            time.sleep(.05)
        assert (cache / key).read_bytes() == checkpoint_data
        assert not (cache / f'{key}.partial').exists()
        print('Range mirror passed: >512 MiB response, >4 GiB offset, checkpoint finalization.')
    finally:
        process.terminate()
        process.wait(timeout=10)
