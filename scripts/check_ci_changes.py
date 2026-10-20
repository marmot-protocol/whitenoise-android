"""Select required validation and supplemental campaigns from the complete Git diff."""

import argparse
import json
import hashlib
import os
from pathlib import Path, PurePosixPath
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
    if event != 'pull_request':
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



def precheck_mode(event, configured_mode, labels, raw):
    """Only an explicitly activated queue may replace full PR CI.

    Reuse the whole-diff conservative campaign classifier: unknown, executable,
    build, dependency, native or workflow inputs all retain full PR validation.
    Labels come from a current GitHub API read, not the possibly stale event.
    """
    if event != 'pull_request' or configured_mode != 'merge-queue':
        return False
    if not isinstance(labels, list) or not all(isinstance(label, str) for label in labels):
        return False
    if 'ci:full' in labels:
        return False
    return raw is not None and not supplemental_campaigns_diff(raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--event', required=True)
    parser.add_argument('--base', default='')
    parser.add_argument('--head', required=True)
    args = parser.parse_args()
    value = 'true' if classify(args.event, args.base, args.head) else 'false'
    campaigns = 'true' if supplemental_campaigns(args.event, args.base, args.head) else 'false'
    try:
        labels = json.loads(os.environ.get('CI_CURRENT_LABELS', 'null'))
    except (ValueError, TypeError):
        labels = None
    raw = complete_diff(args.event, args.base, args.head)
    activated = os.environ.get('ANDROID_CI_MODE', '') == 'merge-queue'
    full_matrix = (args.event != 'pull_request' or
                   (isinstance(labels, list) and 'ci:full' in labels) or
                   (activated and (not isinstance(labels, list) or
                                  not all(isinstance(label, str) for label in labels) or
                                  raw is None or supplemental_campaigns_diff(raw))))
    if full_matrix:
        value, campaigns = 'false', 'true'
    precheck = precheck_mode(args.event, os.environ.get('ANDROID_CI_MODE', ''),
                             labels, raw)
    line = f'docs_only={value}\nsupplemental_campaigns={campaigns}\nprecheck={str(precheck).lower()}\nfull_matrix={str(full_matrix).lower()}\nci_mode={"precheck" if precheck else "full"}\n'
    # Retained diagnostics bind the selected mode to the observed run. External
    # authorization must still verify authenticated workflow/job evidence itself.
    if receipt := os.environ.get('CI_MODE_RECEIPT'):
        payload = dict(item.split('=', 1) for item in line.splitlines())
        payload.update(schema=1, event=args.event, base=args.base, head=args.head,
                       diff_sha256=hashlib.sha256(raw).hexdigest() if raw is not None else None,
                       labels=labels, configured_mode=os.environ.get('ANDROID_CI_MODE', ''))
        payload['run'] = {key: os.environ.get(key, '') for key in
                          ('GITHUB_REPOSITORY', 'GITHUB_SHA', 'GITHUB_WORKFLOW_REF',
                           'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')}
        Path(receipt).write_text(json.dumps(payload, sort_keys=True, indent=2) + '\n')
    print(line, end='')
    if output := os.environ.get('GITHUB_OUTPUT'):
        with open(output, 'a', encoding='utf-8') as stream:
            stream.write(line)


if __name__ == '__main__':
    main()
