#!/usr/bin/env python3
"""Mac VideoToolbox RTSP playback with a bounded decoded-frame jitter buffer."""
import argparse
import collections
import json
import math
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('--size', default='960x720')
    parser.add_argument('--fps', type=int, default=30)
    parser.add_argument('--buffer-ms', type=int, default=100)
    parser.add_argument('--sync', choices=['ext', 'video'], default='ext')
    parser.add_argument('--duration', type=int, default=0, help='0 keeps playing until q or Ctrl+C')
    parser.add_argument('--log-dir', help='Defaults to a new temporary directory for each run')
    args = parser.parse_args()
    if not args.url.startswith('rtsp://'):
        parser.error('Provide an rtsp:// URL')
    match = re.fullmatch(r'(\d+)x(\d+)', args.size)
    if not match:
        parser.error('--size must be WIDTHxHEIGHT')
    width, height = map(int, match.groups())
    if not (2 <= width <= 3840 and 2 <= height <= 2160 and width % 2 == height % 2 == 0):
        parser.error('Use even dimensions within 3840x2160')
    if not (1 <= args.fps <= 60 and 0 <= args.buffer_ms <= 2000 and args.duration >= 0):
        parser.error('Invalid fps, buffer or duration')
    ffmpeg, ffplay = shutil.which('ffmpeg'), shutil.which('ffplay')
    if not ffmpeg or not ffplay:
        parser.error('ffmpeg and ffplay must be installed')
    if args.log_dir:
        os.makedirs(args.log_dir, mode=0o700, exist_ok=True)
    else:
        args.log_dir = tempfile.mkdtemp(prefix='yoox-rtsp-player-')
    target = max(1, math.ceil(args.buffer_ms * args.fps / 1000))
    capacity = target + max(3, math.ceil(args.fps / 10))
    frames = collections.deque()
    condition = threading.Condition()
    finished = threading.Event()
    stats = dict(received=0, sent_frames=0, repeated=0, discarded=0, scheduler_late_ticks=0,
                 buffer_frames=target, capacity_frames=capacity)
    size = width * height * 3 // 2
    logs = [open(os.path.join(args.log_dir, name), 'w') for name in ['decode.log', 'play.log']]
    decode = [ffmpeg, '-hide_banner', '-loglevel', 'warning', '-nostdin',
              '-rtsp_transport', 'tcp', '-timeout', '5000000', '-hwaccel', 'videotoolbox',
              '-fflags', 'nobuffer', '-flags', 'low_delay',
              '-analyzeduration', '100000', '-probesize', '65536', '-i', args.url,
              '-map', '0:v:0', '-an', '-vf', f'scale={width}:{height},format=yuv420p',
              '-c:v', 'rawvideo', '-fps_mode', 'passthrough', '-f', 'rawvideo', 'pipe:1']
    play = [ffplay, '-hide_banner', '-loglevel', 'info', '-stats',
            '-window_title', 'YOOX RTSP - buffered hardware decode',
            '-f', 'rawvideo', '-pixel_format', 'yuv420p', '-video_size', args.size,
            '-framerate', str(args.fps), '-probesize', '32', '-analyzeduration', '0',
            '-sync', args.sync, '-framedrop', '-an', 'pipe:0']
    decoder = subprocess.Popen(decode, stdout=subprocess.PIPE, stderr=logs[0], bufsize=0)
    player = None
    start = None

    def receive():
        try:
            while not finished.is_set():
                chunk = bytearray()
                while len(chunk) < size:
                    part = decoder.stdout.read(size - len(chunk))
                    if not part:
                        return
                    chunk.extend(part)
                with condition:
                    if len(frames) == capacity:
                        frames.popleft()
                        stats['discarded'] += 1
                    frames.append(bytes(chunk))
                    stats['received'] += 1
                    condition.notify_all()
        finally:
            finished.set()
            with condition:
                condition.notify_all()

    reader = threading.Thread(target=receive, daemon=True)
    reader.start()

    def stop(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    try:
        deadline = time.monotonic() + 15
        with condition:
            while len(frames) < target and not finished.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError('No decodable stream within 15 seconds; inspect decode.log')
                condition.wait(remaining)
            if not frames:
                raise RuntimeError('Decoder ended without video; inspect decode.log')
        player = subprocess.Popen(play, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                                  stderr=logs[1], bufsize=0)
        print(f'Hardware decode -> {width}x{height}, {args.fps} fps, buffer {target} frames', flush=True)
        print(f'Playback logs: {args.log_dir}', flush=True)
        period = 1 / args.fps
        start = time.monotonic()
        due = start
        previous = None
        while player.poll() is None:
            now = time.monotonic()
            if args.duration and now - start >= args.duration:
                break
            if due > now:
                time.sleep(due - now)
            elif now - due > period:
                # Rawvideo timestamps are implicit frame numbers. Resetting due
                # here would lose timeline slots and accumulate clock drift.
                # Catch up instead; ffplay's external clock drops late frames.
                stats['scheduler_late_ticks'] += 1
            with condition:
                if frames:
                    frame = frames.popleft()
                elif finished.is_set():
                    break
                elif previous is not None:
                    frame = previous
                    stats['repeated'] += 1
                else:
                    continue
            # FileIO.write can be short on a pipe; write the complete frame.
            pending = memoryview(frame)
            while pending:
                written = player.stdin.write(pending)
                if not written:
                    raise BrokenPipeError()
                pending = pending[written:]
            previous = frame
            stats['sent_frames'] += 1
            due += period
    except (KeyboardInterrupt, BrokenPipeError):
        pass
    except RuntimeError as error:
        print(str(error), file=sys.stderr)
        return 1
    finally:
        finished.set()
        for process in [player, decoder]:
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        reader.join(timeout=2)
        for log in logs:
            log.close()
        stats['elapsed_seconds'] = round(time.monotonic() - start, 2) if start else 0
        with open(os.path.join(args.log_dir, 'play.log')) as playback_log:
            drops = re.findall(r'\bfd=\s*(\d+)', playback_log.read())
        stats['renderer_dropped_frames'] = max(map(int, drops)) if drops else None
        with open(os.path.join(args.log_dir, 'stats.json'), 'w') as output:
            json.dump(stats, output, indent=2)
        print(json.dumps(stats), flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
