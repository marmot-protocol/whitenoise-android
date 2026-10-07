"""Conservative merge-group selection using the existing fuzz PR path list."""
import fnmatch
import os
import re
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.check_ci_changes import complete_diff
from scripts.check_fuzz_pr_triggers import pr_paths, TriggerPolicyError


def selected(event, base, head, workflow):
    if event != 'merge_group':
        return True
    try:
        patterns = pr_paths(workflow)
        # GitHub supports more glob syntax than fnmatch. Unknown syntax runs
        # fuzz rather than approximating an exclusion or silently skipping it.
        if any(any(c in pattern for c in '![]?+') for pattern in patterns):
            return True
        raw = complete_diff(event, base, head)
        if raw is None:
            return True
        fields = raw.split(b'\0')
        if not raw or fields[-1] != b'' or len(fields) % 2 != 1:
            return True
        paths = []
        for index in range(0, len(fields) - 1, 2):
            meta = fields[index].decode('ascii').split()
            path = fields[index + 1].decode('utf-8')
            if (len(meta) != 5 or meta[0] not in {':000000', ':100644'}
                    or meta[1] not in {'000000', '100644'} or meta[4] not in {'A', 'M', 'D'}
                    or not all(re.fullmatch(r'[a-f0-9]{40}', oid) for oid in meta[2:4])
                    or not path or path.startswith('/') or '..' in path.split('/')
                    or '\\' in path or any(ord(c) < 32 for c in path)):
                return True
            paths.append(path)
        return any(fnmatch.fnmatchcase(path, pattern)
                                    for path in paths for pattern in patterns)
    except (OSError, ValueError, UnicodeError, TriggerPolicyError):
        return True


def main():
    workflow = Path(__file__).resolve().parents[1] / '.github/workflows/fuzz-pr.yml'
    value = selected(os.environ.get('CI_EVENT'), os.environ.get('CI_BASE'),
                     os.environ.get('CI_HEAD'), workflow.read_text())
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write('should_fuzz=' + str(value).lower() + '\n')


if __name__ == '__main__':
    main()
