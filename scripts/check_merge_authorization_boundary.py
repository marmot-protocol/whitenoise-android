"""Reject Actions permissions and job names that can spoof merge authorization."""
from pathlib import Path
import sys
import re
import json
import yaml

CONTEXT = 'Android merge authorization'
RESERVED=[CONTEXT,'Android merge queue canary hold']
# These jobs need scoped writes, never statuses/checks writes.
WRITE_ALLOW = {
    # Cancels a failed or dropped merge-group run; it runs no repository code.
    ('android-ci.yml', 'merge-group-fail-fast'): {'actions'},
    ('android-pr-preview-publish.yml', 'publish'): {'contents', 'pull-requests'},
    # Deletes release assets using the trusted close-event cleanup script.
    ('android-pr-preview-cleanup.yml', 'prune'): {'contents'},
    ('android-release-distribute.yml', 'github-draft'): {'contents'},
    # Creates one staging tag from a manually selected, verified APK build.
    ('android-staging-tag.yml', 'tag'): {'contents'},
    ('dependency-submission.yml', None): {'contents'},
    ('pr-screenshots.yml', None): {'issues', 'pull-requests'},
    ('codeql.yml', None): {'security-events'},
}


def validate(name, workflow):
    """Enforce the permission and trigger boundaries for repository workflows."""
    if not isinstance(workflow, dict) or not isinstance(workflow.get('jobs'), dict):
        raise ValueError('malformed workflow')
    events=workflow.get('on',workflow.get(True))
    event_names=set(events) if isinstance(events,(dict,list)) else {events} if isinstance(events,str) else set()
    if name == 'android-staging-tag.yml' and event_names != {'workflow_dispatch'}:
        raise ValueError('staging tag workflow must be manual')
    queue_trigger=bool(event_names & {'merge_group','pull_request','pull_request_target'})
    if 'push' in event_names:
        settings=events.get('push') if isinstance(events,dict) else None
        branches=settings.get('branches') if isinstance(settings,dict) else None
        # The signing workflow's exact develop filter also excludes native queue branches.
        staging_push = name == 'android-staging-apk.yml' and settings == {'branches': ['develop']}
        queue_trigger = queue_trigger or (branches != ['master'] and not staging_push)
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
