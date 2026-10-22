#!/usr/bin/env python3
"""Build Linux amd64 images and stream a complete ZIP64 offline distribution.

Requires Docker (image save --platform), Compose v2, JDK 17, Maven and npm.
The archive contains no build contexts and never reuses ARM image variants.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent


def run(*args, capture=False, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None, **kwargs)


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(4 * 1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--env-file', type=Path, default=ROOT / '.env')
    parser.add_argument('--env-template', type=Path, default=ROOT / '.env.example')
    parser.add_argument('--map-dir', type=Path, default=ROOT / 'maps')
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'offline/dist')
    parser.add_argument('--minio-source-dir', type=Path,
                        help='Package source-rebuilt MinIO images plus corresponding source archives/build instructions')
    parser.add_argument('--skip-verify', action='store_true',
                        help='Only when make verify already passed for the current sources')
    parser.add_argument('--skip-build', action='store_true',
                        help='Reuse already built amd64 images; still validate every image')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}', args.version):
        parser.error('Invalid Docker tag')
    os.umask(0o077)
    os.environ['YOOX_VERSION'] = args.version
    name = f'yoox-cloud-gcs-{args.version}'
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / f'{name}.zip'
    if output.exists():
        parser.error(f'Refusing to overwrite {output}')
    run('bash', str(ROOT / 'scripts/install-offline-map.sh'),
        str(args.map_dir), str(args.map_dir), '--verify-only')
    map_version = json.loads((args.map_dir / 'current.json').read_text())['version']
    if not args.skip_verify:
        run('make', 'verify')
    compose = ['docker', 'compose', '--env-file', str(args.env_file),
               '-f', str(ROOT / 'compose.yml'), '--profile', '*']
    run(*compose, 'config', '--quiet')
    images = sorted(set(run(*compose, 'config', '--images', capture=True).stdout.split()))

    with tempfile.TemporaryDirectory(prefix='yoox-x86-') as temp:
        stage = Path(temp)
        package = stage / name
        package.mkdir()
        deploy = package / 'deploy'
        deploy.mkdir()
        # Preserve variable interpolation while removing source-only build contexts.
        config = (ROOT / 'compose.yml').read_text()
        config, count = re.subn(r'^    build:\n(?:^      .*\n)+', '', config, flags=re.M)
        if count != 4:
            raise RuntimeError(f'Expected 4 business build contexts, found {count}')
        config = re.sub(r'^(    image: .*)$', r'\1\n    platform: linux/amd64\n    pull_policy: never',
                        config, flags=re.M)
        config = config.replace('yoox/mediamtx:1.19.3', f'yoox/mediamtx:{args.version}')
        images = [f'yoox/mediamtx:{args.version}' if i == 'yoox/mediamtx:1.19.3' else i for i in images]
        if args.minio_source_dir:
            replacements = {
                'minio/minio:RELEASE.2025-04-22T22-12-26Z': 'yoox/minio:RELEASE.2025-04-22T22-12-26Z-amd64-source',
                'minio/mc:RELEASE.2025-04-16T18-13-26Z': 'yoox/minio-mc:RELEASE.2025-04-16T18-13-26Z-amd64-source',
            }
            for original, replacement in replacements.items():
                config = config.replace(original, replacement)
            images = [replacements.get(i, i) for i in images]
            source_dest = package / 'sources/minio'
            source_dest.mkdir(parents=True)
            for filename in ('minio-source.tar.gz', 'mc-source.tar.gz', 'build.py',
                             'build-metadata.json', 'README.md', 'Dockerfile.minio', 'Dockerfile.mc'):
                shutil.copy2(args.minio_source_dir / filename, source_dest / filename)
        (deploy / 'compose.yml').write_text(config)
        for folder in ('emqx', 'mediamtx', 'grafana', 'loki', 'prometheus', 'promtail'):
            shutil.copytree(ROOT / 'deploy' / folder, deploy / 'deploy' / folder)
        (deploy / 'scripts').mkdir()
        for script in ('preflight.sh', 'smoke-test.sh', 'smoke-map.sh', 'install-offline-map.sh'):
            shutil.copy2(ROOT / 'scripts' / script, deploy / 'scripts' / script)
        (deploy / 'sql').mkdir()
        shutil.copy2(ROOT / 'sql/cloud_api.sql', deploy / 'sql/cloud_api.sql')
        for filename in ('install.sh', 'README.md'):
            shutil.copy2(ROOT / 'offline/x86-deploy' / filename, package / filename)
        shutil.copy2(args.env_template, package / '.env.template')
        (package / 'VERSION').write_text(args.version + '\n')
        (package / 'PLATFORM').write_text('linux/amd64\n')
        (package / 'IMAGE-MANIFEST.txt').write_text('\n'.join(images) + '\n')
        (package / 'BUILD-INFO.json').write_text(json.dumps({
            'version': args.version, 'platform': 'linux/amd64', 'mapVersion': map_version,
            'gitCommit': run('git', 'rev-parse', 'HEAD', capture=True).stdout.strip(),
            'workingTreeDirty': bool(run('git', 'status', '--porcelain', capture=True).stdout),
            'source': 'Current working tree; runtime images use make verify outputs',
        }, indent=2) + '\n')
        run('docker', 'compose', '--env-file', str(args.env_file), '-f', str(deploy / 'compose.yml'),
            '--profile', '*', 'config', '--quiet')

        if not args.skip_build:
            # Java bytecode and static web files are architecture independent.
            # Build only their Linux amd64 runtimes, using the original runtime instructions.
            api = stage / 'api'
            api.mkdir()
            jars = list((ROOT / 'cloud-service/target').glob('cloud-service-*.jar'))
            if len(jars) != 1:
                raise RuntimeError('Expected exactly one freshly built cloud-service JAR')
            shutil.copy2(jars[0], api / 'app.jar')
            runtime = 'FROM eclipse-temurin:17-jre-jammy' + (ROOT / 'Dockerfile').read_text().split(
                'FROM eclipse-temurin:17-jre-jammy', 1)[1]
            runtime = runtime.replace('COPY --from=builder /build/cloud-service/target/cloud-service-*.jar',
                                      'COPY app.jar')
            runtime = runtime.replace(
                '&& apt-get update \\\n    && apt-get install -y --no-install-recommends curl \\\n    && rm -rf /var/lib/apt/lists/*',
                '&& (command -v curl >/dev/null || (apt-get update '
                '&& apt-get install -y --no-install-recommends curl '
                '&& rm -rf /var/lib/apt/lists/*))')
            (api / 'Dockerfile').write_text(runtime)
            web = stage / 'web'
            web.mkdir()
            shutil.copytree(ROOT / 'web-console/dist', web / 'dist')
            for filename in ('nginx.conf', 'runtime-config.js.template'):
                shutil.copy2(ROOT / 'web-console' / filename, web / filename)
            runtime = 'FROM nginx:1.27-alpine' + (ROOT / 'web-console/Dockerfile').read_text().split(
                'FROM nginx:1.27-alpine', 1)[1]
            (web / 'Dockerfile').write_text(runtime.replace('COPY --from=builder /app/dist', 'COPY dist'))
            for image, context in (
                ('cloud-gcs-api', api), ('cloud-gcs-web', web),
                ('cloud-gcs-api-portal', ROOT / 'api-portal'), ('mediamtx', ROOT / 'deploy/mediamtx')
            ):
                print(f'Building linux/amd64 yoox/{image}:{args.version}', flush=True)
                run('docker', 'buildx', 'build', '--platform', 'linux/amd64', '--load',
                    '-t', f'yoox/{image}:{args.version}', str(context))
            for image in images:
                if not image.startswith('yoox/'):
                    cached = subprocess.run(
                        ['docker', 'image', 'inspect', '--platform', 'linux/amd64', image],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
                    if not cached:
                        run('docker', 'pull', '--platform', 'linux/amd64', image)

        inspection = []
        for image in images:
            info = json.loads(run('docker', 'image', 'inspect', '--platform', 'linux/amd64',
                                  image, capture=True).stdout)[0]
            if (info['Os'], info['Architecture']) != ('linux', 'amd64'):
                raise RuntimeError(f'Wrong platform: {image}')
            inspection.append({'image': image, 'id': info['Id'], 'platform': 'linux/amd64'})
        (package / 'IMAGE-DETAILS.json').write_text(json.dumps(inspection, indent=2) + '\n')
        print(f'Validated {len(images)} linux/amd64 images; writing {output}', flush=True)
        partial = output.with_suffix('.zip.partial')
        try:
            with zipfile.ZipFile(partial, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
                for path in sorted(package.rglob('*')):
                    if path.is_file():
                        archive.write(path, path.relative_to(stage))
                # Export images before maps: Docker itself needs temporary layer space.
                # Stream tar through gzip into ZIP; avoid an additional local image tar.
                with archive.open(f'{name}/images/{name}.tar.gz', 'w', force_zip64=True) as member:
                    with subprocess.Popen(['docker', 'image', 'save', '--platform', 'linux/amd64', *images],
                                          stdout=subprocess.PIPE) as process:
                        try:
                            with gzip.GzipFile(fileobj=member, mode='wb', compresslevel=1, mtime=0) as zipped:
                                shutil.copyfileobj(process.stdout, zipped, 4 * 1024 * 1024)
                        except BaseException:
                            process.kill()
                            raise
                        finally:
                            process.stdout.close()
                        if process.wait() != 0:
                            raise RuntimeError('docker save failed')
                archive.write(args.map_dir / 'current.json', f'{name}/maps/current.json')
                release = args.map_dir / 'releases' / map_version
                for path in sorted(release.rglob('*')):
                    if path.is_file():
                        archive.write(path, f'{name}/maps/releases/{map_version}/{path.relative_to(release)}')
            print('Verifying ZIP CRC and calculating SHA-256', flush=True)
            with zipfile.ZipFile(partial) as archive:
                bad = archive.testzip()
                if bad:
                    raise RuntimeError(f'ZIP checksum failed: {bad}')
            partial.rename(output)
            output.with_suffix('.zip.sha256').write_text(f'{digest(output)}  {output.name}\n')
        except BaseException:
            partial.unlink(missing_ok=True)
            raise
    print(f'Complete: {output} ({output.stat().st_size:,} bytes)', flush=True)


if __name__ == '__main__':
    main()
