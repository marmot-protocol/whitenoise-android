#!/usr/bin/env python3
"""Compare this lane's observations with retained successful exact-base CI evidence."""
import argparse
import io
import json
from pathlib import Path
import re
import subprocess
import zipfile

if __package__:
    from .dependency_inventory import compare, decode_json, observations, read_json, require
else:
    from dependency_inventory import compare, decode_json, observations, read_json, require

REPOSITORY = 'marmot-protocol/whitenoise-android'


def api(path, binary=False):
    """Read bounded GitHub metadata or a size-checked artifact with the job token."""
    value = subprocess.check_output(['gh', 'api', f'repos/{REPOSITORY}/{path}'], timeout=60)
    if binary:
        return value
    try:
        return json.loads(value)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise OSError('GitHub API response is not JSON') from error


def baseline(source, artifact_name):
    """Use the newest retained artifact from a successful master push at the tested base."""
    require(re.fullmatch(r'[a-f0-9]{40}', source or ''), 'Invalid baseline source')
    runs = api('actions/workflows/android-ci.yml/runs?event=push&status=success&head_sha=' + source + '&per_page=20')
    for run in runs['workflow_runs']:
        require(run['head_sha'] == source and run['head_branch'] == 'master' and
                run['event'] == 'push' and run['conclusion'] == 'success' and
                run['path'] == '.github/workflows/android-ci.yml' and
                run['repository']['full_name'] == REPOSITORY and
                run['head_repository']['full_name'] == REPOSITORY, 'Wrong-source baseline run')
        artifacts = api(f"actions/runs/{run['id']}/artifacts?per_page=100")['artifacts']
        matches = [a for a in artifacts if a['name'] == artifact_name and not a['expired']]
        if not matches:
            continue
        # A rerun retains previous attempts under the same artifact name.
        artifact = max(matches, key=lambda a: (a['created_at'], a['id']))
        require(0 < artifact['size_in_bytes'] <= 64 * 1024 * 1024, 'Oversized baseline artifact')
        with zipfile.ZipFile(io.BytesIO(api(f"actions/artifacts/{artifact['id']}/zip", binary=True))) as archive:
            entries = [info for info in archive.infolist() if info.filename == 'inventory/selected.json']
            if not entries:
                continue  # Legacy security-report artifacts predate inventory export.
            require(len(entries) == 1 and entries[0].file_size <= 64 * 1024 * 1024,
                    'Missing or oversized baseline inventory')
            inventory = decode_json(archive.read(entries[0]))
            require(inventory.get('source_sha') == source, 'Stale baseline inventory source')
            observations(inventory)
            return inventory, {'run_id': run['id'], 'artifact_id': artifact['id']}
    return None, {'reason': 'No retained successful exact-base artifact for this lane'}


def main():
    """Retain a delta or an explicit baseline availability receipt without rebuilding."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-source', required=True)
    parser.add_argument('--artifact-name', required=True)
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--after-source', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    after = read_json(args.after)
    require(after.get('source_sha') == args.after_source, 'Stale candidate inventory source')
    observations(after)
    try:
        before, evidence = baseline(args.base_source, args.artifact_name)
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        before = None
        evidence = {'reason': 'GitHub baseline retrieval unavailable: ' + type(error).__name__}
        print('::warning::' + evidence['reason'])
    except (ValueError, KeyError, TypeError, AttributeError, zipfile.BadZipFile) as error:
        before = None
        evidence = {'status': 'rejected', 'reason': 'Baseline evidence failed validation',
                    'error_type': type(error).__name__}
        print('::warning::Baseline evidence rejected: ' + evidence['error_type'])
    result = {'status': 'unavailable', **evidence, 'before_source': args.base_source,
              'after_source': args.after_source}
    if before is not None:
        result = {'status': 'compared', 'baseline': evidence,
                  **compare(before, after, args.base_source, args.after_source)}
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print('Dependency comparison: ' + result['status'])
    if result['status'] == 'rejected':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
