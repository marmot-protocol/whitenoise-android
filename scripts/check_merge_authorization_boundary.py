"""Reject Actions permissions and job names that can spoof merge authorization."""
from pathlib import Path
import sys
import yaml

CONTEXT = 'Android merge authorization'
# Existing publishers need these capabilities, never statuses/checks writes.
WRITE_ALLOW = {
    ('android-pr-preview-publish.yml', 'publish'): {'contents', 'pull-requests'},
    ('android-release-distribute.yml', 'github-draft'): {'contents'},
    ('dependency-submission.yml', None): {'contents'},
    ('pr-screenshots.yml', None): {'issues', 'pull-requests'},
    ('codeql.yml', None): {'security-events'},
}


def validate(name, workflow):
    if not isinstance(workflow, dict) or not isinstance(workflow.get('jobs'), dict):
        raise ValueError('malformed workflow')
    for scope, permissions in [(None, workflow.get('permissions'))] + [
            (job_id, job.get('permissions', workflow.get('permissions')))
            for job_id, job in workflow['jobs'].items()]:
        if not isinstance(permissions, dict):
            raise ValueError('explicit permission mapping required')
        for permission, level in permissions.items():
            if level not in {'read', 'write', 'none'}:
                raise ValueError('unknown permission level')
            allowed = WRITE_ALLOW.get((name, scope), WRITE_ALLOW.get((name, None), set()))
            if level == 'write' and permission not in allowed:
                raise ValueError('unreviewed write permission: ' + permission)
            if permission in {'statuses', 'checks'} and level == 'write':
                raise ValueError('CI cannot write merge authorization')
    for job_id, job in workflow['jobs'].items():
        value = job.get('name', job_id)
        # Dynamic names must have a different static prefix; a pure expression
        # can be influenced by the PR/matrix and must not claim this context.
        if not isinstance(value, str) or not value.strip():
            raise ValueError('unknown job name')
        prefix = value.split('${{', 1)[0].strip()
        if not prefix or CONTEXT.startswith(prefix) or prefix.startswith(CONTEXT):
            raise ValueError('reserved merge authorization job name')


def main():
    directory = Path(__file__).resolve().parents[1] / '.github/workflows'
    for path in sorted(p for p in directory.iterdir() if p.suffix in {'.yml','.yaml'}):
        try:
            validate(path.name, yaml.safe_load(path.read_text()))
        except (ValueError, yaml.YAMLError) as error:
            print(f'{path.name}: {error}', file=sys.stderr)
            return 1
    print('Actions merge authorization boundary passed')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
