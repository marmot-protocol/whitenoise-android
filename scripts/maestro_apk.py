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
import time
import zipfile


REPOSITORY = 'marmot-protocol/whitenoise-android'
WORKFLOW = '.github/workflows/android-ci.yml'
PACKAGE = 'dev.ipf.whitenoise.android.dev'
MAX_APK_BYTES = 512 * 1024 * 1024
NATIVE_LIBRARY = 'lib/x86_64/libmarmot_uniffi.so'


def require(condition, message):
    """Raise ValueError for a failed contract without changing external state."""
    if not condition:
        raise ValueError(message)


def sha256(path):
    """Stream a file into a SHA-256 hex digest; filesystem errors propagate."""
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def device_locale():
    """Read the disposable CI emulator locale using Android's fallback order.

    Probe only emulator-5554 with bounded read-only ADB commands. Unknown
    locale or a non-CI environment raises ValueError; command failures propagate.
    No locale settings are changed."""
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'Locale probe requires the disposable CI emulator')
    def property_value(name):
        """Read one Android property, treating the literal null as unset."""
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
    """Return whether a string fully matches the contract, rejecting other types."""
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def offline():
    """Disable emulator radios and print evidence of no active default network.

    Only the disposable CI emulator is admitted. Commands have five-second
    bounds and disconnection gets five observations. Unexpected airplane-mode
    state, unknown dump format or a remaining network raises ValueError; ADB
    failures propagate. The last connectivity dump is printed on failure too.
    """
    require(os.environ.get('GITHUB_ACTIONS') == 'true', 'Network probe requires the disposable CI emulator')
    def adb(*command):
        """Run one bounded shell command on emulator-5554 and return its text."""
        return subprocess.check_output(
            ['adb', '-s', 'emulator-5554', 'shell', *command], text=True, timeout=5,
        )
    adb('cmd', 'connectivity', 'airplane-mode', 'enable')
    adb('svc', 'wifi', 'disable')
    adb('svc', 'data', 'disable')
    state = adb('cmd', 'connectivity', 'airplane-mode').strip()
    print(f'airplane_mode={state}', flush=True)
    require(state == 'enabled', 'Airplane mode was not applied')
    for attempt in range(5):
        dump = adb('dumpsys', 'connectivity')
        require(len(dump) <= 256 * 1024, 'Connectivity dump exceeds pilot bounds')
        if re.search(r'^\s*Active default network: none\s*$', dump, re.MULTILINE):
            print(dump, flush=True)
            return
        if attempt < 4:
            time.sleep(1)
    print(dump, flush=True)
    raise ValueError('Emulator must have no active default network before launching the app')


def api(path):
    """Read JSON from the fixed repository through a bounded GitHub CLI call.

    This makes no GitHub writes. Transport, timeout and JSON errors propagate;
    callers validate the returned API data before admitting an artifact."""
    raw = subprocess.check_output(
        ['gh', 'api', f'repos/{REPOSITORY}/{path}'], timeout=60,
    )
    return json.loads(raw)


def validate_inputs(artifact_id, source, repetitions):
    """Require a numeric artifact ID, full lowercase SHA and 1–20 repetitions."""
    require(re.fullmatch(r'[1-9][0-9]{0,19}', artifact_id or ''), 'Provide a numeric artifact ID')
    require(re.fullmatch(r'[0-9a-f]{40}', source or ''), 'Provide the full source commit SHA')
    require(re.fullmatch(r'[1-9]|1[0-9]|20', repetitions or ''), 'Repetitions must be 1 through 20')


def validate_metadata(artifact, run, artifact_id, source):
    """Validate an unexpired, bounded artifact from successful internal Android CI.

    Require the exact repository, producer workflow, source SHA, run ID and
    current attempt, plus the API archive digest and expected artifact name.
    Only internal PRs or master pushes qualify. Missing, malformed or
    mismatched metadata raises ValueError (including invalid expiry values).
    This checks identity without downloading, extracting or installing files."""
    require(isinstance(artifact, dict) and isinstance(run, dict), 'Invalid artifact or producer metadata')
    require(isinstance(artifact.get('workflow_run'), dict), 'Missing artifact producer identity')
    require(all(isinstance(run.get(field), dict) for field in ('repository', 'head_repository')),
            'Missing producer repository identity')
    require(isinstance(artifact.get('expires_at'), str), 'Artifact expiry is missing')
    require(artifact.get('id') == int(artifact_id), 'Artifact ID mismatch')
    expiry = datetime.fromisoformat(artifact['expires_at'].replace('Z', '+00:00'))
    require(expiry.tzinfo is not None, 'Artifact expiry must include a timezone')
    require(artifact.get('expired') is False and expiry > datetime.now(timezone.utc),
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
    """Check a bounded regular APK for exactly one x86_64 ELF Marmot library.

    Read only the archive directory and native header. Invalid file, duplicate
    library or wrong architecture raises ValueError; I/O and ZIP errors
    propagate. Android package metadata is checked separately by the workflow."""
    require(apk.is_file() and not apk.is_symlink() and 0 < apk.stat().st_size <= MAX_APK_BYTES,
            'APK must be a bounded regular file')
    with zipfile.ZipFile(apk) as archive:
        require(archive.namelist().count(NATIVE_LIBRARY) == 1, 'APK must carry the x86_64 Marmot runtime')
        with archive.open(NATIVE_LIBRARY) as native:
            header = native.read(20)
        require(header[:6] == b'\x7fELF\x02\x01' and header[18:20] == b'\x3e\x00',
                'Marmot runtime must be an x86_64 ELF binary')


def extract_verified(archive, destination, artifact, run, source):
    """Verify archive integrity, flat contents, producer manifest and APK identity.

    The API digest must match before extraction. Write only app.apk and
    provenance.json into a new destination, bounding their expanded sizes and
    rejecting symlinks, extra paths, wrong source/run/attempt and APK checksum.
    Return the validated manifest. Validation, JSON, ZIP or I/O failures may
    leave extracted files; fetch supplies a temporary quarantine and cleans it."""
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
    require(isinstance(manifest, dict), 'Invalid producer provenance')
    expected = {'schema': 1, 'repository': REPOSITORY, 'workflow': WORKFLOW,
                'source_sha': source, 'run_id': run['id'], 'run_attempt': run['run_attempt'],
                'application_id': PACKAGE, 'variant': 'devZapstoreBenchmarkRelease'}
    require(all(manifest.get(k) == v for k, v in expected.items()), 'Producer provenance mismatch')
    require(matches(r'[0-9a-f]{40}', manifest.get('checkout_sha')), 'Missing integration checkout SHA')
    require(manifest.get('apk_sha256') == sha256(destination / 'app.apk'), 'APK checksum mismatch')
    inspect_apk(destination / 'app.apk')
    return manifest


def stage(apk, destination):
    """Copy an existing validated baseline APK and write its producer manifest.

    Use APK_SOURCE_SHA and GitHub run/attempt environment values with the
    checkout commit and APK digest. Never build an APK. Invalid source or
    native architecture fails validation; missing environment, Git and I/O
    errors propagate. The destination must be new; failures may leave files."""
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
    """Download and admit one source-pinned APK independently of other CI activity.

    Validate user inputs, producer identity, expiry, attempt, API archive size
    and digest before moving verified files from temporary quarantine into a
    fresh destination. Write selection.json beside it with pilot/producer
    identity. GitHub calls and download are bounded; validation, transport,
    ZIP and I/O failures propagate. No APK is built, installed or executed.
    Temporary files are cleaned on failure; a final evidence-write failure
    may leave the validated destination for diagnosis."""
    validate_inputs(artifact_id, source, repetitions)
    artifact = api(f'actions/artifacts/{artifact_id}')
    require(isinstance(artifact, dict) and isinstance(artifact.get('workflow_run'), dict),
            'Missing artifact producer identity')
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
    """Dispatch producer, artifact and disposable-emulator preflight commands.

    Invalid CLI usage exits 2; contract, I/O or subprocess failures exit 1.
    Locale and network evidence is printed for retained run artifacts. Each
    command applies its documented file or emulator side effects."""
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
    commands.add_parser('locale')
    commands.add_parser('offline')
    args = parser.parse_args()
    try:
        if args.command == 'stage':
            stage(args.apk, args.destination)
        elif args.command == 'fetch':
            fetch(args.artifact_id, args.source, args.repetitions, args.destination)
        elif args.command == 'offline':
            offline()
        else:
            locale = device_locale()
            print(locale, flush=True)
            require(locale.replace('_', '-') in ('en-US', 'en'), 'Pilot selectors require English')
    except (ValueError, TypeError, KeyError, OSError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Maestro APK preflight failed: {error}\n')


if __name__ == '__main__':
    main()
