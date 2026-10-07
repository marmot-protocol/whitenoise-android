#!/usr/bin/env python3
"""Retain an already-built dev APK and verify it for a disposable Maestro pilot."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import zipfile


REPOSITORY = 'marmot-protocol/whitenoise-android'
WORKFLOW = '.github/workflows/android-ci.yml'
PACKAGE = 'dev.ipf.whitenoise.android.dev'
MAX_APK_BYTES = 512 * 1024 * 1024
NATIVE_LIBRARY = 'lib/x86_64/libmarmot_uniffi.so'
LIGHT_WORKFLOWS = {'.github/workflows/pr-screenshots.yml'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def device_locale():
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'Locale probe requires the disposable CI emulator')
    def property_value(name):
        value = subprocess.check_output(
            ['adb', '-s', 'emulator-5554', 'shell', 'getprop', name], text=True, timeout=10,
        ).strip()
        return '' if value == 'null' else value
    # API 34 fresh images use ro.product.locale until a locale is persisted.
    # Legacy components preserve Android's fallback order without guessing English.
    locale = property_value('persist.sys.locale')
    if not locale:
        language = property_value('persist.sys.language')
        if language:
            locale = '-'.join(filter(None, [language, property_value('persist.sys.country'),
                                           property_value('persist.sys.localevar')]))
    if not locale:
        locale = property_value('ro.product.locale')
    if not locale:
        locale = '-'.join(filter(None, [property_value('ro.product.locale.language'),
                                       property_value('ro.product.locale.region')]))
    require(locale, 'Cannot determine emulator locale')
    return locale


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def api(path):
    raw = subprocess.check_output(
        ['gh', 'api', f'repos/{REPOSITORY}/{path}'], timeout=60,
    )
    return json.loads(raw)


def validate_inputs(artifact_id, source, repetitions):
    require(re.fullmatch(r'[1-9][0-9]{0,19}', artifact_id or ''), 'Provide a numeric artifact ID')
    require(re.fullmatch(r'[0-9a-f]{40}', source or ''), 'Provide the full source commit SHA')
    require(re.fullmatch(r'[1-9]|1[0-9]|20', repetitions or ''), 'Repetitions must be 1 through 20')


def check_idle_runs(payload, current_run):
    require(isinstance(payload, dict) and isinstance(payload.get('workflow_runs'), list),
            'Cannot determine current CI capacity')
    require(type(payload.get('total_count')) is int and payload['total_count'] < 100,
            'Too many active runs to establish spare CI capacity')
    for run in payload['workflow_runs']:
        require(type(run.get('id')) is int and isinstance(run.get('path'), str),
                'Incomplete active-run metadata')
        if str(run['id']) != current_run and run['path'] not in LIGHT_WORKFLOWS:
            raise ValueError(f"CI capacity is busy (run {run['id']}); dispatch again when it is idle")


def idle():
    current = os.environ['GITHUB_RUN_ID']
    for status in ('queued', 'in_progress', 'waiting', 'pending', 'requested'):
        check_idle_runs(api(f'actions/runs?status={status}&per_page=100'), current)


def validate_metadata(artifact, run, artifact_id, source):
    require(artifact.get('id') == int(artifact_id), 'Artifact ID mismatch')
    require(artifact.get('expired') is False and
            datetime.fromisoformat(artifact['expires_at'].replace('Z', '+00:00')) > datetime.now(timezone.utc),
            'Artifact has expired')
    require(type(artifact.get('size_in_bytes')) is int and
            0 < artifact['size_in_bytes'] <= MAX_APK_BYTES + 65536, 'Artifact size exceeds pilot bounds')
    require(matches(r'sha256:[0-9a-f]{64}', artifact.get('digest')), 'Artifact digest is missing')
    require(run.get('repository', {}).get('full_name') == REPOSITORY and
            run.get('head_repository', {}).get('full_name') == REPOSITORY, 'Producer must be in this repository')
    require(run.get('path') == WORKFLOW and run.get('event') in ('push', 'pull_request'),
            'Producer must be the normal Android CI workflow')
    require(run.get('event') != 'push' or run.get('head_branch') == 'master', 'Push producer must use master')
    require(run.get('status') == 'completed' and run.get('conclusion') == 'success',
            'Producer CI must have completed successfully')
    require(run.get('head_sha') == source and artifact.get('workflow_run', {}).get('head_sha') == source,
            'APK source does not match the requested commit')
    require(type(run.get('id')) is int and type(run.get('run_attempt')) is int and run['run_attempt'] > 0,
            'Invalid producer identity')
    require(artifact['workflow_run'].get('id') == run['id'], 'Artifact belongs to another run')
    expected_name = f"maestro-dev-apk-{source}-{run['id']}-{run['run_attempt']}"
    require(artifact.get('name') == expected_name, 'Artifact does not match the producer contract/attempt')


def inspect_apk(apk):
    require(apk.is_file() and not apk.is_symlink() and 0 < apk.stat().st_size <= MAX_APK_BYTES,
            'APK must be a bounded regular file')
    with zipfile.ZipFile(apk) as archive:
        require(archive.namelist().count(NATIVE_LIBRARY) == 1, 'APK must carry the x86_64 Marmot runtime')
        with archive.open(NATIVE_LIBRARY) as native:
            header = native.read(20)
        require(header[:6] == b'\x7fELF\x02\x01' and header[18:20] == b'\x3e\x00',
                'Marmot runtime must be an x86_64 ELF binary')


def extract_verified(archive, destination, artifact, run, source):
    require(sha256(archive) == artifact['digest'].removeprefix('sha256:'), 'Artifact archive digest mismatch')
    with zipfile.ZipFile(archive) as zipped:
        entries = zipped.infolist()
        require(len(entries) == 2 and {e.filename for e in entries} == {'app.apk', 'provenance.json'},
                'Artifact must contain exactly one flat APK and its provenance')
        for entry in entries:
            require(not stat.S_ISLNK(entry.external_attr >> 16) and not entry.is_dir(), 'Invalid ZIP entry type')
            limit = MAX_APK_BYTES if entry.filename == 'app.apk' else 65536
            require(0 < entry.file_size <= limit, 'Artifact expansion exceeds bounds')
        destination.mkdir()
        for entry in entries:
            with zipped.open(entry) as incoming, (destination / entry.filename).open('wb') as outgoing:
                shutil.copyfileobj(incoming, outgoing)
    manifest = json.loads((destination / 'provenance.json').read_text())
    expected = {'schema': 1, 'repository': REPOSITORY, 'workflow': WORKFLOW,
                'source_sha': source, 'run_id': run['id'], 'run_attempt': run['run_attempt'],
                'application_id': PACKAGE, 'variant': 'devZapstoreBenchmarkRelease'}
    require(all(manifest.get(k) == v for k, v in expected.items()), 'Producer provenance mismatch')
    require(matches(r'[0-9a-f]{40}', manifest.get('checkout_sha')), 'Missing integration checkout SHA')
    require(manifest.get('apk_sha256') == sha256(destination / 'app.apk'), 'APK checksum mismatch')
    inspect_apk(destination / 'app.apk')
    return manifest


def stage(apk, destination):
    inspect_apk(apk)
    source = os.environ['APK_SOURCE_SHA']
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'Invalid producer source')
    destination.mkdir(parents=True)
    shutil.copyfile(apk, destination / 'app.apk')
    manifest = {'schema': 1, 'repository': REPOSITORY, 'workflow': WORKFLOW,
                'source_sha': source, 'checkout_sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
                'application_id': PACKAGE, 'variant': 'devZapstoreBenchmarkRelease', 'apk_sha256': sha256(apk)}
    (destination / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')


def fetch(artifact_id, source, repetitions, destination):
    validate_inputs(artifact_id, source, repetitions)
    idle()
    artifact = api(f'actions/artifacts/{artifact_id}')
    require(type(artifact.get('workflow_run', {}).get('id')) is int, 'Missing artifact producer run')
    run = api(f"actions/runs/{artifact['workflow_run']['id']}")
    validate_metadata(artifact, run, artifact_id, source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=destination.parent) as temporary:
        archive = Path(temporary) / 'artifact.zip'
        # gh follows the GitHub storage redirect without a token in command arguments.
        with archive.open('wb') as output:
            subprocess.run(['gh', 'api', f'repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip'],
                           stdout=output, check=True, timeout=120)
        require(archive.stat().st_size == artifact['size_in_bytes'], 'Artifact archive size mismatch')
        manifest = extract_verified(archive, Path(temporary) / 'verified', artifact, run, source)
        require(not destination.exists(), 'Output destination must be fresh')
        shutil.move(Path(temporary) / 'verified', destination)
    evidence = {'artifact_id': int(artifact_id), 'artifact_digest': artifact['digest'],
                'producer': manifest, 'pilot_sha': os.environ['GITHUB_SHA'],
                'pilot_run_id': os.environ['GITHUB_RUN_ID'], 'repetitions': int(repetitions)}
    (destination.parent / 'selection.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(f"Verified artifact {artifact_id}, source {source}, producer {run['id']}/{run['run_attempt']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    producer = commands.add_parser('stage')
    producer.add_argument('--apk', required=True, type=Path)
    producer.add_argument('--destination', required=True, type=Path)
    consumer = commands.add_parser('fetch')
    consumer.add_argument('--artifact-id', required=True)
    consumer.add_argument('--source', required=True)
    consumer.add_argument('--repetitions', required=True)
    consumer.add_argument('--destination', required=True, type=Path)
    commands.add_parser('idle')
    commands.add_parser('locale')
    args = parser.parse_args()
    try:
        if args.command == 'stage':
            stage(args.apk, args.destination)
        elif args.command == 'fetch':
            fetch(args.artifact_id, args.source, args.repetitions, args.destination)
        elif args.command == 'idle':
            idle()
        else:
            locale = device_locale()
            print(locale, flush=True)
            require(locale.replace('_', '-') in ('en-US', 'en'), 'Pilot selectors require English')
    except (ValueError, TypeError, KeyError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Maestro APK preflight failed: {error}\n')


if __name__ == '__main__':
    main()
