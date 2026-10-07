"""Reject Actions permissions and job names that can spoof merge authorization."""
from pathlib import Path
import sys
import re
import json
import yaml

CONTEXT = 'Android merge authorization'
RESERVED=[CONTEXT,'Android merge queue canary hold']
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
    events=workflow.get('on',workflow.get(True))
    event_names=set(events) if isinstance(events,(dict,list)) else {events} if isinstance(events,str) else set()
    queue_trigger=bool(event_names & {'merge_group','pull_request','pull_request_target'})
    if 'push' in event_names:
        settings=events.get('push') if isinstance(events,dict) else None
        branches=settings.get('branches') if isinstance(settings,dict) else None
        # Only explicit master-only pushes are known to exclude native queue
        # branches. Unknown glob/tag/filter combinations stay conservative.
        queue_trigger=queue_trigger or branches!=['master']
    if queue_trigger and re.search(r'\bsecrets\b',json.dumps(workflow),re.IGNORECASE):
        raise ValueError('queue workflows cannot access repository secrets')
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
        if queue_trigger and ('uses' in job or 'environment' in job):
            raise ValueError('candidate workflows cannot delegate jobs or access environments')
        value = job.get('name', job_id)
        # Dynamic names must have a different static prefix; a pure expression
        # can be influenced by the PR/matrix and must not claim this context.
        if not isinstance(value, str) or not value.strip():
            raise ValueError('unknown job name')
        prefix = value.split('${{', 1)[0].strip()
        if not prefix or any(c.casefold().startswith(prefix.casefold()) or prefix.casefold().startswith(c.casefold()) for c in RESERVED):
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
