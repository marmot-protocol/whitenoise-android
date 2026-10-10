"""Select required validation and supplemental campaigns from the complete Git diff."""

import argparse
import os
from pathlib import PurePosixPath
import re
import subprocess


# Kotlin tests read these documents; their edits require the validating suites.
BUILD_INPUT_DOCS = {
    'docs/composer-dictation-device-matrix.md',
    'docs/invariant-gates.md',
}

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


def classify(event, base, head):
    if event not in {'pull_request', 'merge_group'}:
        return False
    diff = complete_diff(event, base, head)
    return diff is not None and docs_only_diff(diff)



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
        ordinary = documentation_path(path)
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


def supplemental_campaigns(event, base, head):
    diff = complete_diff(event, base, head)
    return diff is None or supplemental_campaigns_diff(diff)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    value = 'true' if classify(args.event, args.base, args.head) else 'false'
    campaigns = 'true' if supplemental_campaigns(args.event, args.base, args.head) else 'false'
    line = f'docs_only={value}\nsupplemental_campaigns={campaigns}\n'
    print(line, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(line)


if __name__ == '__main__':
    main()
