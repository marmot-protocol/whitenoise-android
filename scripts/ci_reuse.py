"""Decide whether Android CI can reuse an already verified result for the same content.

Two situations repeat validation that has already passed on identical content:

* A ready-for-review run on a head and base whose tree already passed the draft lane.
  Only the aggregate check name differs between the two runs.
* A master push of the exact commit that a merge group already validated, because the
  queue lands the tested commit unchanged.

A successful pull request validation uploads an artifact named for its lane and tree
(`android-ci-attest-<lane>-<tree>`). The ready-for-review lookup finds those artifacts and
re-verifies every candidate against the Actions API: a completed successful run of this
workflow, started by a pull request from this repository (never a fork), whose workflow and
classification scripts are byte-identical to the code running now.

A proof is only an artifact name, and any code that runs inside a same-repository pull
request run, such as a build script or a test, can upload an artifact with any name. A proof
therefore only relieves the pull request's own check, which the merge queue re-runs in full
on every group, and it never relaxes the queue. The master-push lookup does not use proofs at
all: it relies on the merge group runs that GitHub itself creates for that exact commit.

Every uncertainty, error and disabled setting selects full validation, so a wrong answer here
can only cost time and never skip a check.
"""

import argparse
import json
import os
import re
import subprocess

WORKFLOW_PATH = '.github/workflows/android-ci.yml'
# The code that names, computes and uploads a proof. A proof is trusted only when the run
# that produced it executed these exact files, so a forged workflow cannot mint one.
TRUSTED_CODE = (WORKFLOW_PATH, 'scripts/ci_reuse.py', 'scripts/check_ci_changes.py')
REQUIRED_JOB = 'Compile, test, ktlint, detekt, Android lint'
SHA_PATTERN = re.compile(r'[0-9a-f]{40}')
MAX_CANDIDATES = 5
NONE = 'none'
# An unset variable only reports what reuse would do; `enforce` turns it on.
SETTINGS = {'': 'shadow', 'shadow': 'shadow', 'enforce': 'enforce', 'off': 'off'}
LOOKUP_ERRORS = (OSError, ValueError, TypeError, KeyError, AttributeError, subprocess.SubprocessError)
# Master pushes are the only cache writers for PRs and merge groups. A push that changes what
# those caches hold must keep running its writer jobs instead of reusing the queue's result.
CACHE_INPUT_PREFIXES = ('gradle/', 'app/src/main/marmotkit/')
CACHE_INPUT_FILES = {'gradle.properties', 'gradlew', 'scripts/prepare_marmotkit_artifact.py',
                     'scripts/prepare-marmotkit-artifact.sh', 'scripts/prefetch-robolectric-android-all.sh',
                     *TRUSTED_CODE}
CACHE_INPUT_SUFFIXES = ('.gradle', '.gradle.kts', '.lockfile')


class GhApi:
    """Read-only GitHub REST access through the authenticated `gh` command."""

    def get(self, path):
        """Return the decoded JSON body of one GET request, raising on any failure."""
        completed = subprocess.run(['gh', 'api', path], capture_output=True, text=True,
                                   check=True, timeout=60)
        return json.loads(completed.stdout)


def attestation_name(lane, tree):
    """Name the artifact that proves a pull request lane passed on one exact tree."""
    return f'android-ci-attest-{lane}-{tree}'


def accepted_lanes(ci_mode):
    """List the attested lanes that prove everything the given lane would run.

    The full lane is a strict superset of the precheck lane, so a full result satisfies
    a precheck request but never the other way around.
    """
    return {'full': ('full',), 'precheck': ('precheck', 'full')}.get(ci_mode, ())


def current_tree():
    """Return the tree hash of the checked-out commit."""
    completed = subprocess.run(['git', 'rev-parse', 'HEAD^{tree}'], capture_output=True,
                               text=True, check=True, timeout=30)
    return completed.stdout.strip()


def trusted_blobs():
    """Return the blob hash of each trusted code path in the checkout, or {} when unreadable."""
    blobs = {}
    for path in TRUSTED_CODE:
        try:
            completed = subprocess.run(['git', 'rev-parse', f'HEAD:{path}'], capture_output=True,
                                       text=True, check=True, timeout=30)
        except (OSError, subprocess.SubprocessError):
            return {}
        blobs[path] = completed.stdout.strip()
    return blobs


def is_cache_input(path):
    """Tell whether a path can change what the master Gradle and MarmotKit caches hold."""
    return (path in CACHE_INPUT_FILES or path.startswith(CACHE_INPUT_PREFIXES)
            or path.endswith(CACHE_INPUT_SUFFIXES))


def cache_inputs_changed(before):
    """Return True when the push touched a cache input, or when that cannot be told."""
    if not SHA_PATTERN.fullmatch(before or '') or set(before) == {'0'}:
        return True
    try:
        completed = subprocess.run(['git', 'diff', '--no-renames', '--name-only', '-z', before, 'HEAD'],
                                   capture_output=True, check=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return True
    paths = [path.decode('utf-8', 'replace') for path in completed.stdout.split(b'\0') if path]
    return not paths or any(is_cache_input(path) for path in paths)


def ci_run_is_trusted(run, repo, event, complete=True):
    """Check that a run is a successful (or, when allowed, still running) run of this workflow.

    The run must come from this repository. A run still in progress qualifies only when
    the caller verifies a finished job itself.
    """
    head_repository = (run.get('head_repository') or {}).get('full_name')
    finished = run.get('status') == 'completed' and run.get('conclusion') == 'success'
    running = not complete and run.get('status') == 'in_progress' and not run.get('conclusion')
    return (str(run.get('path', '')).split('@', 1)[0] == WORKFLOW_PATH
            and run.get('event') == event and (finished or running) and head_repository == repo)


def ran_the_same_code(api, repo, sha, blobs):
    """Check that the trusted code at a commit is byte-identical to the code running now."""
    if not blobs or not SHA_PATTERN.fullmatch(sha or ''):
        return False
    return all(api.get(f'repos/{repo}/contents/{path}?ref={sha}').get('sha') == blob
               for path, blob in blobs.items())


def attested_run(api, repo, tree, lanes, blobs):
    """Return the id of a trusted pull request run that validated this tree, if any."""
    for lane in lanes:
        name = attestation_name(lane, tree)
        listing = api.get(f'repos/{repo}/actions/artifacts?name={name}&per_page=100')
        candidates = []
        for artifact in listing.get('artifacts', []):
            origin = artifact.get('workflow_run') or {}
            # A fork can never supply a proof; keep only live, exactly named, same-repository ones.
            if (artifact.get('name') == name and not artifact.get('expired')
                    and origin.get('id') and origin.get('head_repository_id') == origin.get('repository_id')):
                candidates.append(artifact)
        candidates.sort(key=lambda artifact: artifact.get('created_at') or '', reverse=True)
        for artifact in candidates[:MAX_CANDIDATES]:
            run_id = artifact['workflow_run']['id']
            try:
                run = api.get(f'repos/{repo}/actions/runs/{run_id}')
                if (ci_run_is_trusted(run, repo, 'pull_request')
                        and ran_the_same_code(api, repo, run.get('head_sha'), blobs)):
                    return str(run_id)
            except LOOKUP_ERRORS:
                continue  # One unreadable candidate must not hide a good one.
    return None


def aggregate_passed(api, repo, run_id):
    """Check that a run's required aggregate job finished successfully."""
    jobs = api.get(f'repos/{repo}/actions/runs/{run_id}/jobs?per_page=100')
    return any(job.get('name') == REQUIRED_JOB and job.get('status') == 'completed'
               and job.get('conclusion') == 'success' for job in jobs.get('jobs', []))


def queue_run(api, repo, sha):
    """Return the id of a merge group run whose aggregate passed on this exact commit.

    The queue merges as soon as the aggregate succeeds, which can be before the run itself
    finishes, so the aggregate job is checked rather than the run's final status.
    """
    listing = api.get(f'repos/{repo}/actions/workflows/android-ci.yml/runs'
                      f'?event=merge_group&head_sha={sha}&per_page=20')
    for run in listing.get('workflow_runs', [])[:MAX_CANDIDATES]:
        try:
            if (run.get('head_sha') == sha and ci_run_is_trusted(run, repo, 'merge_group', complete=False)
                    and aggregate_passed(api, repo, run['id'])):
                return str(run['id'])
        except LOOKUP_ERRORS:
            continue  # One unreadable run must not hide a good one.
    return None


def lookup(api, repo, event, action, ci_mode, tree, head_sha, blobs):
    """Find the verified run that makes this run redundant, as a (kind, run id) pair."""
    # A merge group never reuses a pull request proof: see the module docstring.
    if event == 'pull_request' and action == 'ready_for_review' and SHA_PATTERN.fullmatch(tree):
        run_id = attested_run(api, repo, tree, accepted_lanes(ci_mode), blobs)
        return ('tree', run_id) if run_id else (NONE, '')
    if event == 'push' and SHA_PATTERN.fullmatch(head_sha):
        run_id = queue_run(api, repo, head_sha)
        return ('push', run_id) if run_id else (NONE, '')
    return NONE, ''


def decide(api, repo, event, action, ci_mode, docs_only, tree, head_sha, setting,
           blobs=None, cache_inputs=lambda: True):
    """Return the reuse outputs. Anything but a verified match selects full validation."""
    outputs = {'reuse': NONE, 'reuse_run': '', 'tree': tree}
    mode = SETTINGS.get(setting, 'off')
    if mode == 'off':
        return outputs, 'reuse disabled'
    if docs_only == 'true':
        return outputs, 'documentation-only change'
    try:
        kind, run_id = lookup(api, repo, event, action, ci_mode, tree, head_sha, blobs or {})
        if kind == 'push' and cache_inputs():
            return outputs, 'a cache input changed, so this push refreshes the caches'
    except LOOKUP_ERRORS as error:
        return outputs, f'lookup failed, running full validation: {type(error).__name__}'
    if kind == NONE:
        return outputs, 'no verified result for this content'
    if mode == 'shadow':
        return outputs, f'shadow: would reuse {kind} result from run {run_id}'
    outputs.update(reuse=kind, reuse_run=run_id)
    return outputs, f'reusing {kind} result from run {run_id}'


def publish(outputs, reason):
    """Print the decision and write it to the step outputs and job summary."""
    lines = ''.join(f'{key}={value}\n' for key, value in outputs.items())
    print(lines, end='')
    print(f'reuse_reason={reason}')
    if path := os.environ.get('GITHUB_OUTPUT'):
        with open(path, 'a', encoding='utf-8') as stream:
            stream.write(lines)
    if path := os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(path, 'a', encoding='utf-8') as stream:
            stream.write(f'Validation reuse: **{outputs["reuse"]}** ({reason})\n')


def main():
    """Read the run context, decide, and publish the outputs."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--event', required=True)
    parser.add_argument('--action', default='')
    parser.add_argument('--mode', required=True, help='Lane selected by check_ci_changes.py')
    parser.add_argument('--docs-only', default='false')
    parser.add_argument('--head-sha', default='')
    parser.add_argument('--before', default='', help='Commit the pushed branch pointed at before the push')
    parser.add_argument('--setting', default='', help='Repository variable ANDROID_CI_REUSE')
    args = parser.parse_args()
    try:
        tree = current_tree()
    except (OSError, subprocess.SubprocessError):
        tree = ''
    outputs, reason = decide(GhApi(), args.repo, args.event, args.action, args.mode,
                             args.docs_only, tree, args.head_sha, args.setting,
                             trusted_blobs(), lambda: cache_inputs_changed(args.before))
    publish(outputs, reason)


if __name__ == '__main__':
    main()
