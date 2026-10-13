"""Exercise real Gradle cache invalidation for a registered, ordinary androidTest gate."""

import argparse
import json
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
REGISTRY = ROOT / 'docs/invariant-gates.md'
PROBE_PATH = 'app/src/androidTest/java/dev/ipf/whitenoise/android/RegistryCacheProbeTest.kt'
PROBE = ROOT / PROBE_PATH
TASK = ':app:testDevZapstoreDebugUnitTest'
INPUTS = {
    'app/build.gradle.kts',
    'docs/invariant-gates.md',
    'scripts/check_invariant_gate_cache.py',
    'app/src/test/java/dev/ipf/whitenoise/android/InvariantGateRegistryTest.kt',
    'app/src/test/java/dev/ipf/whitenoise/android/InvariantGateRow.kt',
}


def inputs_changed():
    """Scope the mutation campaign to registry/cache edits; missing diff evidence runs it."""
    event_file = os.environ.get('GITHUB_EVENT_PATH')
    event = json.loads(Path(event_file).read_text()) if event_file else {}
    base = event.get('pull_request', {}).get('base', {}).get('sha') or event.get('before') or 'HEAD^1'
    diff = subprocess.run(
        ['git', 'diff', '--name-only', base, 'HEAD'], cwd=ROOT, capture_output=True, text=True,
    )
    return diff.returncode != 0 or bool(INPUTS.intersection(diff.stdout.splitlines()))


def run_validator(label):
    """Run the focused real validator and retain its output for cache and failure assertions."""
    result = subprocess.run(
        [str(ROOT / 'gradlew'), TASK, '--tests', '*InvariantGateRegistryTest*',
         '--build-cache', '--daemon', '--console=plain', '--stacktrace'],
        cwd=ROOT, capture_output=True, text=True,
    )
    output = result.stdout + result.stderr
    log = ROOT / 'build/reports/invariant-gate-cache' / f'{label}.log'
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_text(output)
    print(f'{label}: exit {result.returncode}; {log.relative_to(ROOT)}', flush=True)
    return result.returncode, output


def verify_cache():
    """Require a cached baseline, a rerun rejecting deletion, and restored-tree validation."""
    if PROBE.exists():
        raise RuntimeError(f'refusing to overwrite {PROBE_PATH}')
    original = REGISTRY.read_bytes()
    row = f'| [`RegistryCacheProbeTest`](../{PROBE_PATH}) | Cache probe. | Registry validator |'
    lines = original.decode().splitlines()
    start = lines.index('<!-- invariant-gates:start -->') + 3
    end = lines.index('<!-- invariant-gates:end -->')
    lines[start:end] = sorted([*lines[start:end], row])
    try:
        PROBE.parent.mkdir(parents=True, exist_ok=True)
        PROBE.write_text('package dev.ipf.whitenoise.android\n\ninternal class RegistryCacheProbeTest\n')
        REGISTRY.write_text('\n'.join(lines) + '\n')
        code, _ = run_validator('registered-baseline')
        if code != 0:
            raise RuntimeError('registered ordinary instrumented gate did not pass validation')
        code, output = run_validator('cached-baseline')
        if code != 0 or f'> Task {TASK} UP-TO-DATE' not in output:
            raise RuntimeError('repeat baseline did not reuse the successful task result')
        PROBE.unlink()
        code, output = run_validator('deleted-gate')
        if code == 0 or f'registered test {PROBE_PATH} does not exist' not in output:
            raise RuntimeError('deleted registered gate did not rerun and fail for its missing path')
    finally:
        REGISTRY.write_bytes(original)
        PROBE.unlink(missing_ok=True)
    code, _ = run_validator('restored-tree')
    if code != 0:
        raise RuntimeError('restored registry failed validation')
    print('Invariant registry cache mutation: PASS', flush=True)


def main():
    """Run on affected CI candidates, or explicitly on demand with --force."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--force', action='store_true')
    args = parser.parse_args()
    if args.force or inputs_changed():
        verify_cache()
    else:
        print('Invariant registry cache inputs unchanged; mutation campaign skipped.')


if __name__ == '__main__':
    main()
