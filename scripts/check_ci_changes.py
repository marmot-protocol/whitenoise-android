"""Select required validation and supplemental campaigns from the complete Git diff."""

import argparse
import json
import os
from pathlib import PurePosixPath
import re
import subprocess


# Kotlin test inputs never qualify as docs-only. Their validating consumers
# run in full integration; selected metadata may use the PR precheck.
BUILD_INPUT_DOCS = {
    'docs/composer-dictation-device-matrix.md',
    'docs/invariant-gates.md',
}

# Validated metadata changes affect tests but do not alter build/tool execution.
# They must never qualify as docs-only; their validators remain unconditional.
TEST_METADATA = {'docs/invariant-gates.md', 'docs/manual-release-testing-surfaces.json'}


def test_metadata_path(path):
    return (path in TEST_METADATA or
            (path.startswith('docs/manual-release-testing/surfaces/') and path.endswith('.json')))


ROOT_DOCS = {'README.md', 'AGENTS.md', 'CLAUDE.md', 'CONTRIBUTING.md',
             'SECURITY.md', 'CODE_OF_CONDUCT.md', 'CHANGELOG.md'}


def documentation_path(path):
    if path in BUILD_INPUT_DOCS:
        return False
    parts = PurePosixPath(path).parts
    if not parts or path.startswith('/') or any(p in {'.', '..'} for p in parts):
        return False
    if any(ord(c) < 32 for c in path) or '\\' in path:
        return False
    return path in ROOT_DOCS or (parts[0] == 'docs' and path.endswith('.md'))


def docs_only_diff(raw):
    """Raw --no-renames output includes deleted paths and both file modes."""
    fields = raw.split(b'\0')
    if not raw or fields[-1] != b'' or len(fields) % 2 != 1:
        return False
    for index in range(0, len(fields) - 1, 2):
        try:
            metadata = fields[index].decode('ascii').split()
            path = fields[index + 1].decode('utf-8')
        except UnicodeDecodeError:
            return False
        if len(metadata) != 5 or not metadata[0].startswith(':'):
            return False
        old_mode, new_mode = metadata[0][1:], metadata[1]
        if metadata[4] not in {'A', 'M', 'D'}:
            return False
        if (old_mode not in {'000000', '100644'} or
                new_mode not in {'000000', '100644'}):
            return False  # Executable files, symlinks and submodules run full CI.
        if not documentation_path(path):
            return False
    return True


def complete_diff(event, base, head):
    """Use the queue's tested integration, never its original PR branch."""
    if event not in {'pull_request', 'push', 'merge_group'}:
        return None
    if not all(re.fullmatch(r'[0-9a-f]{40}', ref or '') for ref in (base, head)):
        return None
    try:
        if event == 'merge_group':
            # A queue base must be contained in its integration commit. Missing
            # objects or a raced/invalid event select conservative validation.
            subprocess.run(['git', 'merge-base', '--is-ancestor', base, head],
                           capture_output=True, check=True, timeout=30)
        diff = subprocess.run(
            ['git', 'diff', '--raw', '--no-abbrev', '--no-renames', '-z',
             f'{base}{".." if event == "merge_group" else "..."}{head}'],
            capture_output=True, check=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return diff.stdout


def supplemental_campaigns_diff(raw):
    """Only known ordinary source/prose changes defer report/reproduction campaigns.

    These are nightly diagnostics, not compilation/security/regression gates.
    Build/packaging/toolchain/CI inputs and every unknown diff run the campaigns.
    """
    fields = raw.split(b'\0')
    if not raw or fields[-1] != b'' or len(fields) % 2 != 1:
        return True
    for index in range(0, len(fields) - 1, 2):
        try:
            metadata = fields[index].decode('ascii').split()
            path = fields[index + 1].decode('utf-8')
        except UnicodeDecodeError:
            return True
        if len(metadata) != 5 or not metadata[0].startswith(':'):
            return True
        old, new, before, after, status = metadata[0][1:], *metadata[1:]
        expected_modes = {'A': ('000000', '100644'), 'M': ('100644', '100644'),
                          'D': ('100644', '000000')}
        if ((old, new) != expected_modes.get(status)
                or not all(re.fullmatch(r'[0-9a-f]{40}', h) for h in (before, after))):
            return True
        parts = PurePosixPath(path).parts
        if (not parts or str(PurePosixPath(path)) != path or path.startswith('/') or '..' in parts or '.' in parts
                or '\\' in path or any(ord(c) < 32 for c in path)):
            return True
        ordinary = documentation_path(path) or test_metadata_path(path)
        if path.startswith('app/src/'):
            # Manifests, assets, native artifacts and generated inputs stay full.
            ordinary |= (len(parts) > 4 and parts[3] == 'java' and
                         path.endswith(('.kt', '.java')))
            ordinary |= (path.startswith('app/src/main/res/') and
                         path.endswith(('.xml', '.png', '.webp', '.jpg', '.jpeg')))
            ordinary |= path.startswith('app/src/test/snapshots/') and path.endswith('.png')
        if not ordinary:
            return True
    return False


def tooling_regressions_diff(raw):
    """Only defer isolated host suites for known ordinary production changes.

    Scripts/tools/fixtures/build inputs fall through the conservative classifier.
    Android test fixtures also feed host suites. Source-reading Maestro contract
    suites are intentionally unconditional in the workflow.
    """
    if raw is None or supplemental_campaigns_diff(raw):
        return True
    fields = raw.split(b'\0')
    return any(fields[index].decode('utf-8').startswith('app/src/androidTest/')
               for index in range(1, len(fields) - 1, 2))


def validation_selection(event, configured_mode, labels, raw):
    """Derive mutually exclusive modes from one complete-diff observation."""
    activated = configured_mode == 'merge-queue'
    labels_valid = isinstance(labels, list) and all(isinstance(label, str) for label in labels)
    has_full_label = labels_valid and 'ci:full' in labels
    risky = raw is None or supplemental_campaigns_diff(raw)
    if event != 'pull_request' or has_full_label or (activated and (not labels_valid or risky)):
        mode = 'full'
    else:
        mode = 'precheck' if activated else 'legacy-pr'
    full = mode == 'full'
    docs = event == 'pull_request' and raw is not None and docs_only_diff(raw) and not full
    return {'ci_mode': mode, 'precheck': str(mode == 'precheck').lower(),
            'full_matrix': str(full).lower(), 'docs_only': str(docs).lower(),
            'supplemental_campaigns': str(risky).lower(),
            'tooling_regressions': str(event != 'pull_request' or not labels_valid or
                                       has_full_label or tooling_regressions_diff(raw)).lower()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    try:
        labels = json.loads(os.environ.get('CI_CURRENT_LABELS', 'null'))
    except (ValueError, TypeError):
        labels = None
    raw = complete_diff(args.event, args.base, args.head)
    selection = validation_selection(args.event, os.environ.get('ANDROID_CI_MODE', ''), labels, raw)
    line = ''.join(f'{key}={value}\n' for key, value in selection.items())
    print(line, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(line)


if __name__ == '__main__':
    main()
