"""Select docs-only PR validation conservatively from the complete Git diff."""

import argparse
import os
from pathlib import PurePosixPath
import re
import subprocess


# A Kotlin compatibility test reads this fixture; prose edits need that test.
BUILD_INPUT_DOCS = {'docs/composer-dictation-device-matrix.md'}

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


def classify(event, base, head):
    if event != 'pull_request':
        return False
    if not all(re.fullmatch(r'[0-9a-f]{40}', ref or '') for ref in (base, head)):
        return False
    try:
        diff = subprocess.run(
            ['git', 'diff', '--raw', '--no-abbrev', '--no-renames', '-z',
             f'{base}...{head}'], capture_output=True, check=True, timeout=30,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    return docs_only_diff(diff.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    value = 'true' if classify(args.event, args.base, args.head) else 'false'
    line = f'docs_only={value}\n'
    print(line, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(line)


if __name__ == '__main__':
    main()
