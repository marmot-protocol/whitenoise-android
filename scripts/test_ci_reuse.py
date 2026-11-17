"""Unit tests for scripts/ci_reuse.py: reuse is granted only for a verified, trusted match."""

import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import unittest.mock

from scripts import ci_reuse

REPO = 'marmot-protocol/whitenoise-android'
TREE = 'a' * 40
SHA = 'b' * 40
HEAD = 'd' * 40
BEFORE = 'e' * 40
WORKFLOW = '.github/workflows/android-ci.yml'
BLOBS = {path: 'c' * 40 for path in ci_reuse.TRUSTED_CODE}


class FakeApi:
    """Serve canned GitHub responses and fail like `gh api` for anything unknown."""

    def __init__(self, responses):
        """Remember the path-to-body mapping and the order paths were requested."""
        self.responses = responses
        self.paths = []

    def get(self, path):
        """Return the canned body or raise the error a failed `gh api` call raises."""
        self.paths.append(path)
        if path not in self.responses:
            raise subprocess.CalledProcessError(1, ['gh', 'api', path])
        return self.responses[path]


def artifact_path(lane, tree=TREE):
    """Build the artifact listing path the script requests for one lane."""
    return f'repos/{REPO}/actions/artifacts?name={ci_reuse.attestation_name(lane, tree)}&per_page=100'


def artifact(lane, run_id=11, tree=TREE, **overrides):
    """Build one listed attestation artifact that came from a same-repository run."""
    body = {'name': ci_reuse.attestation_name(lane, tree), 'expired': False,
            'created_at': '2026-11-17T10:00:00Z',
            'workflow_run': {'id': run_id, 'repository_id': 7, 'head_repository_id': 7}}
    body.update(overrides)
    return body


def run(event='pull_request', conclusion='success', **overrides):
    """Build one workflow run record that is trusted unless an override breaks it."""
    body = {'path': WORKFLOW, 'event': event, 'status': 'completed', 'conclusion': conclusion,
            'head_sha': HEAD, 'head_repository': {'full_name': REPO}}
    body.update(overrides)
    return body


def contents(sha=HEAD, blobs=BLOBS):
    """Serve the trusted code's blob hashes at one commit."""
    return {f'repos/{REPO}/contents/{path}?ref={sha}': {'sha': blob} for path, blob in blobs.items()}


def decide(api, event='pull_request', action='ready_for_review', mode='full', docs_only='false', tree=TREE,
           head_sha=SHA, setting='enforce', blobs=BLOBS, cache_inputs=lambda: False,
           lint_inputs=lambda: False):
    """Run the production decision with a fake API and return its outputs and reason."""
    return ci_reuse.decide(api, REPO, event, action, mode, docs_only, tree, head_sha, setting,
                           blobs, cache_inputs, lint_inputs)


class TreeReuseTest(unittest.TestCase):
    """A ready run may reuse a PR result only for the exact same tree and code."""

    def attested(self, lane, **run_overrides):
        """Return a fake API that holds one attested pull request run for the lane."""
        return FakeApi({artifact_path(lane): {'artifacts': [artifact(lane)]},
                        f'repos/{REPO}/actions/runs/11': run(**run_overrides), **contents()})

    def test_ready_run_reuses_a_full_pull_request_result_for_the_same_tree(self):
        """A full-lane ready run on an attested tree skips the heavy jobs."""
        outputs, reason = decide(self.attested('full'))
        self.assertEqual(outputs, {'reuse': 'tree', 'reuse_run': '11', 'tree': TREE, 'lint_writer': 'false'})
        self.assertIn('run 11', reason)

    def test_full_ready_run_rejects_a_precheck_result(self):
        """A full-lane run needs the complete lane, so a precheck proof never satisfies it."""
        api = FakeApi({artifact_path('full'): {'artifacts': []},
                       artifact_path('precheck'): {'artifacts': [artifact('precheck')]},
                       f'repos/{REPO}/actions/runs/11': run(), **contents()})
        self.assertEqual(decide(api)[0]['reuse'], 'none')
        self.assertNotIn(artifact_path('precheck'), api.paths)

    def test_ready_run_reuses_the_draft_precheck_result(self):
        """Marking ready on an unchanged tree reuses the draft lane's result."""
        outputs, _ = decide(self.attested('precheck'), event='pull_request',
                            action='ready_for_review', mode='precheck')
        self.assertEqual(outputs['reuse'], 'tree')

    def test_precheck_ready_run_accepts_the_stronger_full_result(self):
        """A full-lane result proves the precheck lane too."""
        api = FakeApi({artifact_path('precheck'): {'artifacts': []},
                       artifact_path('full'): {'artifacts': [artifact('full')]},
                       f'repos/{REPO}/actions/runs/11': run(), **contents()})
        outputs, _ = decide(api, event='pull_request', action='ready_for_review', mode='precheck')
        self.assertEqual(outputs['reuse'], 'tree')

    def test_other_pull_request_actions_never_reuse(self):
        """Pushes and reopenings always validate, so a rerun cannot hide a flake."""
        for action in ('opened', 'synchronize', 'reopened', ''):
            with self.subTest(action=action):
                api = self.attested('full')
                outputs, _ = decide(api, event='pull_request', action=action, mode='full')
                self.assertEqual(outputs['reuse'], 'none')
                self.assertEqual(api.paths, [])

    def test_unselected_or_legacy_lanes_never_reuse(self):
        """Only the precheck and full lanes have a defined proof."""
        for mode in ('legacy-pr', '', 'unknown'):
            with self.subTest(mode=mode):
                outputs, _ = decide(self.attested('full'), event='pull_request',
                                    action='ready_for_review', mode=mode)
                self.assertEqual(outputs['reuse'], 'none')

    def test_a_merge_group_never_reuses_a_pull_request_proof(self):
        """Any code in a same-repository PR run can mint a proof, so the queue always validates fully."""
        for mode in ('full', 'precheck', 'legacy-pr', ''):
            for action in ('', 'checks_requested', 'ready_for_review'):
                with self.subTest(mode=mode, action=action):
                    api = self.attested('full')
                    outputs, _ = decide(api, event='merge_group', action=action, mode=mode)
                    self.assertEqual(outputs['reuse'], 'none')
                    self.assertEqual(api.paths, [])

    def test_malformed_tree_never_reuses(self):
        """A missing or malformed tree hash selects full validation without a lookup."""
        for tree in ('', 'a' * 39, 'A' * 40, 'z' * 40):
            with self.subTest(tree=tree):
                api = self.attested('full')
                self.assertEqual(decide(api, tree=tree)[0]['reuse'], 'none')
                self.assertEqual(api.paths, [])

    def test_fork_artifacts_never_crowd_out_a_real_proof(self):
        """Fork artifacts are dropped before the candidate limit, so they cannot hide the real one."""
        forks = [artifact('full', run_id=200 + index, created_at='2026-11-17T11:00:00Z',
                          workflow_run={'id': 200 + index, 'repository_id': 7, 'head_repository_id': 9})
                 for index in range(20)]
        api = FakeApi({artifact_path('full'): {'artifacts': forks + [artifact('full')]},
                       f'repos/{REPO}/actions/runs/11': run(), **contents()})
        self.assertEqual(decide(api)[0]['reuse'], 'tree')
        self.assertFalse([path for path in api.paths if '/runs/2' in path])

    def test_expired_or_misnamed_artifacts_are_ignored(self):
        """Only a live artifact with the exact name counts."""
        for bad in ({'expired': True}, {'name': 'android-ci-attest-full-other'}):
            with self.subTest(bad=bad):
                api = FakeApi({artifact_path('full'): {'artifacts': [artifact('full', **bad)]}})
                self.assertEqual(decide(api)[0]['reuse'], 'none')

    def test_untrusted_runs_are_rejected(self):
        """The producing run must be a completed success of this workflow from this repository."""
        for bad in ({'conclusion': 'failure'}, {'conclusion': None, 'status': 'in_progress'},
                    {'event': 'push'}, {'path': '.github/workflows/other.yml'},
                    {'head_repository': {'full_name': 'someone/else'}}, {'head_repository': None}):
            with self.subTest(bad=bad):
                self.assertEqual(decide(self.attested('full', **bad))[0]['reuse'], 'none')

    def test_run_path_may_carry_a_ref_suffix(self):
        """The Actions API sometimes appends the ref to a workflow path."""
        api = self.attested('full', path=WORKFLOW + '@refs/heads/master')
        self.assertEqual(decide(api)[0]['reuse'], 'tree')

    def test_a_forged_workflow_or_script_cannot_mint_a_proof(self):
        """A run that executed different workflow or classification code is never trusted."""
        for path in ci_reuse.TRUSTED_CODE:
            with self.subTest(path=path):
                forged = contents()
                forged[f'repos/{REPO}/contents/{path}?ref={HEAD}'] = {'sha': 'f' * 40}
                api = FakeApi({artifact_path('full'): {'artifacts': [artifact('full')]},
                               f'repos/{REPO}/actions/runs/11': run(), **forged})
                self.assertEqual(decide(api)[0]['reuse'], 'none')

    def test_missing_local_code_or_run_commit_never_reuses(self):
        """Without the current blob hashes or a well-formed run commit, nothing can be compared."""
        self.assertEqual(decide(self.attested('full'), blobs={})[0]['reuse'], 'none')
        for head in (None, '', 'abc'):
            with self.subTest(head=head):
                self.assertEqual(decide(self.attested('full', head_sha=head))[0]['reuse'], 'none')

    def test_the_newest_verified_proof_wins_and_candidates_are_bounded(self):
        """Candidates are tried newest first and only a bounded number are ever verified."""
        newer = artifact('full', run_id=12, created_at='2026-11-17T12:00:00Z')
        api = FakeApi({artifact_path('full'): {'artifacts': [artifact('full'), newer]},
                       f'repos/{REPO}/actions/runs/12': run(conclusion='failure'),
                       f'repos/{REPO}/actions/runs/11': run(), **contents()})
        self.assertEqual(decide(api)[0]['reuse_run'], '11')
        self.assertLess(api.paths.index(f'repos/{REPO}/actions/runs/12'),
                        api.paths.index(f'repos/{REPO}/actions/runs/11'))
        many = [artifact('full', run_id=100 + index) for index in range(40)]
        api = FakeApi({artifact_path('full'): {'artifacts': many},
                       **{f'repos/{REPO}/actions/runs/{100 + index}': run(conclusion='failure')
                          for index in range(40)}})
        self.assertEqual(decide(api)[0]['reuse'], 'none')
        self.assertLessEqual(len(api.paths), 1 + ci_reuse.MAX_CANDIDATES)


class PushReuseTest(unittest.TestCase):
    """A master push may reuse the merge group result for the identical commit."""

    @staticmethod
    def runs_path(sha=SHA):
        """Build the listing path the script requests for one commit's queue runs."""
        return f'repos/{REPO}/actions/workflows/android-ci.yml/runs?event=merge_group&head_sha={sha}&per_page=20'

    @staticmethod
    def jobs(conclusion='success', status='completed', name=ci_reuse.REQUIRED_JOB):
        """Build one run's jobs response with the aggregate in the given state."""
        return {'jobs': [{'name': 'Merge-group fail-fast', 'status': 'in_progress', 'conclusion': None},
                         {'name': name, 'status': status, 'conclusion': conclusion}]}

    def api(self, run_overrides=None, **jobs):
        """Return a fake API holding one merge group run and its jobs."""
        record = dict(run('merge_group'), id=21, head_sha=SHA)
        record.update(run_overrides or {})
        return FakeApi({self.runs_path(): {'workflow_runs': [record]},
                        f'repos/{REPO}/actions/runs/21/jobs?per_page=100': self.jobs(**jobs)})

    def test_push_reuses_a_merge_group_whose_aggregate_passed(self):
        """The queue lands the tested commit unchanged, so its result applies."""
        outputs, _ = decide(self.api(), event='push')
        self.assertEqual(outputs, {'reuse': 'push', 'reuse_run': '21', 'tree': TREE, 'lint_writer': 'false'})

    def test_push_reuses_a_run_whose_watcher_is_still_finishing(self):
        """The queue merges when the aggregate passes, before the run itself has finished."""
        api = self.api({'status': 'in_progress', 'conclusion': None})
        self.assertEqual(decide(api, event='push')[0]['reuse'], 'push')

    def test_push_requires_a_passed_aggregate_job(self):
        """A failed, unfinished, missing or differently named aggregate never satisfies a push."""
        for jobs in ({'conclusion': 'failure'}, {'conclusion': None, 'status': 'in_progress'},
                     {'conclusion': 'cancelled'}, {'name': 'Android lint (Zapstore)'}):
            with self.subTest(jobs=jobs):
                self.assertEqual(decide(self.api(**jobs), event='push')[0]['reuse'], 'none')

    def test_push_requires_the_exact_commit_and_a_trusted_run(self):
        """A different commit, failed run or foreign run never satisfies a push."""
        for bad in ({'head_sha': 'c' * 40}, {'conclusion': 'failure'}, {'event': 'pull_request'},
                    {'head_repository': {'full_name': 'someone/else'}}):
            with self.subTest(bad=bad):
                self.assertEqual(decide(self.api(bad), event='push')[0]['reuse'], 'none')

    def test_reused_push_keeps_the_lint_writers_only_when_lint_inputs_changed(self):
        """Pushes are the only lint cache writers, so a reused push that changed lint inputs still lints."""
        outputs, _ = decide(self.api(), event='push', lint_inputs=lambda: True)
        self.assertEqual((outputs['reuse'], outputs['lint_writer']), ('push', 'true'))
        outputs, _ = decide(self.api(), event='push', lint_inputs=lambda: False)
        self.assertEqual((outputs['reuse'], outputs['lint_writer']), ('push', 'false'))
        # A result that does not reuse a push never asks for the lint writers.
        for kwargs in ({'event': 'push', 'cache_inputs': lambda: True}, {'event': 'push', 'setting': 'off'}):
            self.assertEqual(decide(self.api(), lint_inputs=lambda: True, **kwargs)[0]['lint_writer'], 'false')

    def test_lint_inputs_are_app_source_sets_but_never_tests(self):
        """Main and flavor sources feed lint, while tests, docs, scripts and workflows do not."""
        for path in ('app/src/main/java/org/example/Screen.kt', 'app/src/main/res/values/strings.xml',
                     'app/src/zapstore/java/org/example/Update.kt', 'app/src/play/AndroidManifest.xml'):
            with self.subTest(path=path):
                self.assertTrue(ci_reuse.is_lint_input(path))
        for path in ('app/src/test/java/org/example/ScreenTest.kt', 'app/src/androidTest/java/org/example/A.kt',
                     'app/src/test/snapshots/screen.png', 'docs/ci-request-policy.md', 'scripts/ci_reuse.py'):
            with self.subTest(path=path):
                self.assertFalse(ci_reuse.is_lint_input(path))

    def test_push_range_listing_serves_both_questions(self):
        """One pushed-range listing answers the cache question and the lint question, failing closed."""
        listing = subprocess.CompletedProcess(['git'], 0, stdout=b'docs/a.md\0app/src/main/java/A.kt\0')
        with unittest.mock.patch('subprocess.run', return_value=listing):
            self.assertEqual(ci_reuse.pushed_paths(BEFORE), ['docs/a.md', 'app/src/main/java/A.kt'])
            self.assertTrue(ci_reuse.lint_inputs_changed(BEFORE))
            self.assertFalse(ci_reuse.cache_inputs_changed(BEFORE))
        tests_only = subprocess.CompletedProcess(['git'], 0, stdout=b'app/src/test/java/ATest.kt\0docs/a.md\0')
        with unittest.mock.patch('subprocess.run', return_value=tests_only):
            self.assertFalse(ci_reuse.lint_inputs_changed(BEFORE))
        self.assertIsNone(ci_reuse.pushed_paths(''))
        self.assertTrue(ci_reuse.lint_inputs_changed(None))
        with unittest.mock.patch('subprocess.run', side_effect=subprocess.CalledProcessError(1, ['git'])):
            self.assertIsNone(ci_reuse.pushed_paths(BEFORE))
            self.assertTrue(ci_reuse.lint_inputs_changed(BEFORE))

    @staticmethod
    def push_runs_path():
        """Build the listing path the script requests for earlier master push runs."""
        return f'repos/{REPO}/actions/workflows/android-ci.yml/runs?event=push&branch=master&per_page=30'

    @staticmethod
    def push_run(run_id, sha, **overrides):
        """Build one master push run record that is trusted unless an override breaks it."""
        return dict(run('push'), id=run_id, head_sha=sha, **overrides)

    @staticmethod
    def lint_jobs(*outcomes):
        """Build a jobs response with the Zapstore and Play lint jobs in the given conclusions."""
        return {'jobs': [{'name': name, 'status': 'completed' if outcome else 'queued', 'conclusion': outcome}
                         for name, outcome in zip(ci_reuse.LINT_JOBS, outcomes)]}

    def test_lint_baseline_is_the_newest_push_whose_lint_completed(self):
        """A reused or cancelled preceding push proves nothing, so the baseline skips back to a real writer."""
        cancelled, reused, writer = 'a1' * 20, 'a2' * 20, 'a3' * 20
        api = FakeApi({self.push_runs_path(): {'workflow_runs': [
            self.push_run(30, HEAD), self.push_run(31, cancelled), self.push_run(32, reused),
            self.push_run(33, writer)]},
            f'repos/{REPO}/actions/runs/31/jobs?per_page=100': self.lint_jobs('success', 'cancelled'),
            f'repos/{REPO}/actions/runs/32/jobs?per_page=100': {'jobs': []},
            f'repos/{REPO}/actions/runs/33/jobs?per_page=100': self.lint_jobs('success', 'success')})
        self.assertEqual(ci_reuse.last_lint_writer(api, REPO, HEAD), writer)
        # The run for the pushed commit itself is never consulted.
        self.assertNotIn(f'repos/{REPO}/actions/runs/30/jobs?per_page=100', api.paths)

    def test_lint_baseline_ignores_untrusted_or_unreadable_runs_and_gives_up_when_none_completed(self):
        """Foreign, wrong-event or unreadable runs are skipped, and no writer in range means none."""
        writer = 'a3' * 20
        listing = [self.push_run(40, 'a4' * 20, head_repository={'full_name': 'someone/else'}),
                   self.push_run(41, 'a5' * 20, event='pull_request'),
                   self.push_run(42, 'a6' * 20, path='.github/workflows/other.yml'),
                   self.push_run(43, 'not-a-sha'), self.push_run(44, 'a7' * 20),
                   self.push_run(45, writer)]
        api = FakeApi({self.push_runs_path(): {'workflow_runs': listing},
                       f'repos/{REPO}/actions/runs/45/jobs?per_page=100': self.lint_jobs('success', 'success')})
        self.assertEqual(ci_reuse.last_lint_writer(api, REPO, HEAD), writer)
        self.assertEqual(api.paths.count(f'repos/{REPO}/actions/runs/44/jobs?per_page=100'), 1)
        for skipped in (40, 41, 42, 43):
            self.assertNotIn(f'repos/{REPO}/actions/runs/{skipped}/jobs?per_page=100', api.paths)
        none_completed = FakeApi({self.push_runs_path(): {'workflow_runs': [self.push_run(50, 'a8' * 20)]},
                                  f'repos/{REPO}/actions/runs/50/jobs?per_page=100': self.lint_jobs('success')})
        self.assertIsNone(ci_reuse.last_lint_writer(none_completed, REPO, HEAD))

    def test_lint_inputs_are_measured_from_the_last_completed_writer_and_fail_closed(self):
        """Test-only pushes after an unfinished lint writer still refresh lint, and errors choose lint."""
        writer = 'a3' * 20
        api = FakeApi({self.push_runs_path(): {'workflow_runs': [self.push_run(33, writer)]},
                       f'repos/{REPO}/actions/runs/33/jobs?per_page=100': self.lint_jobs('success', 'success')})
        diffs = []

        def changed(command, **kwargs):
            """Record the diff request and report main sources that changed since the baseline."""
            diffs.append(command)
            return subprocess.CompletedProcess(command, 0, stdout=b'app/src/main/java/A.kt\0app/src/test/ATest.kt\0')

        # The diff spans every push since the writer, including an earlier push that changed main sources.
        with unittest.mock.patch('subprocess.run', side_effect=changed):
            self.assertTrue(ci_reuse.lint_inputs_since_last_writer(api, REPO, HEAD))
        self.assertEqual(diffs[0][-2:], [writer, 'HEAD'])
        tests_only = subprocess.CompletedProcess(['git'], 0, stdout=b'app/src/test/java/ATest.kt\0')
        empty = FakeApi({self.push_runs_path(): {'workflow_runs': []}})
        garbled = FakeApi({self.push_runs_path(): {'workflow_runs': 'garbled'}})
        with unittest.mock.patch('subprocess.run', return_value=tests_only):
            self.assertFalse(ci_reuse.lint_inputs_since_last_writer(api, REPO, HEAD))
            # No completed writer, or an unreadable listing, always keeps the lint jobs.
            for unknown in (empty, FakeApi({}), garbled):
                self.assertTrue(ci_reuse.lint_inputs_since_last_writer(unknown, REPO, HEAD))

    def test_lint_jobs_match_the_workflow_matrix(self):
        """The writer check names the same lint jobs that a master push runs for each distribution."""
        text = (pathlib.Path(ci_reuse.__file__).resolve().parents[1] / '.github/workflows/android-ci.yml').read_text()
        self.assertIn('name: Android lint (${{ matrix.flavor }})', text)
        self.assertIn('\'["Zapstore", "Play"]\'', text)
        self.assertEqual(ci_reuse.LINT_JOBS, ('Android lint (Zapstore)', 'Android lint (Play)'))

    def test_required_job_matches_the_validate_job_name(self):
        """The aggregate job the push lookup trusts is the workflow's required check."""
        workflow = pathlib.Path(ci_reuse.__file__).resolve().parents[1] / '.github/workflows/android-ci.yml'
        match = re.search(r'^  validate:\n    name: (.+?)\$\{\{', workflow.read_text(), re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertEqual(match.group(1), ci_reuse.REQUIRED_JOB)

    def test_push_that_changes_a_cache_input_keeps_refreshing_the_caches(self):
        """Master pushes are the cache writers, so one touching cache inputs runs its writer jobs."""
        outputs, reason = decide(self.api(), event='push', cache_inputs=lambda: True)
        self.assertEqual(outputs['reuse'], 'none')
        self.assertIn('cache input', reason)

    def test_cache_inputs_cover_build_and_native_pins_but_not_sources(self):
        """Dependency, wrapper, MarmotKit and workflow changes refresh caches; ordinary sources do not."""
        for path in ('gradle/libs.versions.toml', 'gradle/wrapper/gradle-wrapper.properties', 'gradle.properties',
                     'app/build.gradle.kts', 'settings.gradle.kts', 'app/gradle.lockfile',
                     'app/src/main/marmotkit/MARMOT_VERSION', '.github/workflows/android-ci.yml',
                     'scripts/ci_reuse.py', 'scripts/check_ci_changes.py',
                     'scripts/prepare_marmotkit_artifact.py', 'scripts/prefetch-robolectric-android-all.sh'):
            with self.subTest(path=path):
                self.assertTrue(ci_reuse.is_cache_input(path))
        for path in ('app/src/main/java/org/example/Screen.kt', 'docs/ci-request-policy.md',
                     'app/src/test/snapshots/screen.png', 'scripts/test_ci_reuse.py'):
            with self.subTest(path=path):
                self.assertFalse(ci_reuse.is_cache_input(path))

    def test_the_whole_push_is_inspected_and_unknowns_count_as_changes(self):
        """The diff spans the pushed range without rename hiding, and any doubt refreshes caches."""
        for before in ('', None, '0' * 40, 'abc'):
            with self.subTest(before=before):
                self.assertTrue(ci_reuse.cache_inputs_changed(before))
        failure = subprocess.CalledProcessError(1, ['git'])
        with unittest.mock.patch('subprocess.run', side_effect=failure):
            self.assertTrue(ci_reuse.cache_inputs_changed(BEFORE))
        empty = subprocess.CompletedProcess(['git'], 0, stdout=b'')
        with unittest.mock.patch('subprocess.run', return_value=empty):
            self.assertTrue(ci_reuse.cache_inputs_changed(BEFORE))
        listing = subprocess.CompletedProcess(['git'], 0, stdout=b'docs/a.md\0app/src/main/java/A.kt\0')
        with unittest.mock.patch('subprocess.run', return_value=listing) as call:
            self.assertFalse(ci_reuse.cache_inputs_changed(BEFORE))
        self.assertEqual(call.call_args.args[0],
                         ['git', 'diff', '--no-renames', '--name-only', '-z', BEFORE, 'HEAD'])
        renamed = subprocess.CompletedProcess(['git'], 0, stdout=b'docs/a.md\0gradle/libs.versions.toml\0')
        with unittest.mock.patch('subprocess.run', return_value=renamed):
            self.assertTrue(ci_reuse.cache_inputs_changed(BEFORE))

    def test_push_without_a_queue_run_validates_fully(self):
        """A direct or bypassed push has no queue result and runs everything."""
        api = FakeApi({self.runs_path(): {'workflow_runs': []}})
        self.assertEqual(decide(api, event='push')[0]['reuse'], 'none')

    def test_push_needs_a_well_formed_commit(self):
        """A malformed commit never reaches the API."""
        api = FakeApi({})
        self.assertEqual(decide(api, event='push', head_sha='abc')[0]['reuse'], 'none')
        self.assertEqual(api.paths, [])

    def test_unreadable_checkout_code_does_not_block_a_push(self):
        """Push reuse rests on the queue's own run, so it needs no local code comparison."""
        self.assertEqual(decide(self.api(), event='push', blobs={})[0]['reuse'], 'push')


class SettingTest(unittest.TestCase):
    """The repository variable can only make reuse more conservative."""

    def api(self):
        """Return a fake API that would otherwise grant tree reuse."""
        return FakeApi({artifact_path('full'): {'artifacts': [artifact('full')]},
                        f'repos/{REPO}/actions/runs/11': run(), **contents()})

    def test_only_enforce_reuses(self):
        """Reuse needs an explicit enforce setting."""
        self.assertEqual(decide(self.api(), setting='enforce')[0]['reuse'], 'tree')

    def test_unset_and_shadow_report_the_decision_without_skipping(self):
        """By default the decision is recorded while every job still runs."""
        for setting in ('', 'shadow'):
            with self.subTest(setting=setting):
                outputs, reason = decide(self.api(), setting=setting)
                self.assertEqual(outputs['reuse'], 'none')
                self.assertIn('would reuse tree', reason)

    def test_off_and_unknown_settings_disable_reuse_without_lookups(self):
        """A kill switch or unrecognized value never queries GitHub."""
        for setting in ('off', 'unknown', 'ENFORCE'):
            with self.subTest(setting=setting):
                api = self.api()
                self.assertEqual(decide(api, setting=setting)[0]['reuse'], 'none')
                self.assertEqual(api.paths, [])

    def test_documentation_only_changes_never_reuse(self):
        """The documentation shortcut owns its own skips, so reuse stays out of it."""
        api = self.api()
        self.assertEqual(decide(api, docs_only='true')[0]['reuse'], 'none')
        self.assertEqual(api.paths, [])

    def test_lookup_failures_select_full_validation(self):
        """An API error, bad JSON shape or timeout can never skip validation."""
        broken = FakeApi({artifact_path('full'): {'artifacts': None}})
        outputs, reason = decide(broken)
        self.assertEqual(outputs['reuse'], 'none')
        self.assertIn('lookup failed', reason)
        self.assertEqual(decide(FakeApi({}))[0]['reuse'], 'none')


class CandidateResilienceTest(unittest.TestCase):
    """One unreadable candidate may cost a lookup, never the other candidates."""

    def test_an_unreadable_proof_run_does_not_hide_an_older_good_one(self):
        """A 404 on the newest candidate still lets the older verified proof win."""
        newer = artifact('full', run_id=12, created_at='2026-11-17T12:00:00Z')
        api = FakeApi({artifact_path('full'): {'artifacts': [artifact('full'), newer]},
                       f'repos/{REPO}/actions/runs/11': run(), **contents()})
        self.assertEqual(decide(api)[0]['reuse_run'], '11')
        self.assertIn(f'repos/{REPO}/actions/runs/12', api.paths)

    def test_an_unreadable_merge_group_run_does_not_hide_a_good_one(self):
        """An error reading one queue run's jobs still lets another passed run satisfy a push."""
        listing = f'repos/{REPO}/actions/workflows/android-ci.yml/runs?event=merge_group&head_sha={SHA}&per_page=20'
        runs = [dict(run('merge_group'), id=21, head_sha=SHA), dict(run('merge_group'), id=22, head_sha=SHA)]
        api = FakeApi({listing: {'workflow_runs': runs},
                       f'repos/{REPO}/actions/runs/22/jobs?per_page=100':
                           {'jobs': [{'name': ci_reuse.REQUIRED_JOB, 'status': 'completed', 'conclusion': 'success'}]}})
        outputs, _ = decide(api, event='push')
        self.assertEqual((outputs['reuse'], outputs['reuse_run']), ('push', '22'))


class IsolationTest(unittest.TestCase):
    """The workflow runs the lookup in isolated mode, which a planted module cannot subvert."""

    def test_a_planted_standard_library_module_cannot_run_inside_the_lookup(self):
        """Isolated mode keeps a PR-planted `json.py` beside the script from replacing the standard library."""
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            shutil.copy(ci_reuse.__file__, root / 'ci_reuse.py')
            (root / 'json.py').write_text("raise RuntimeError('planted module ran')\n")
            plain = subprocess.run([sys.executable, 'ci_reuse.py', '--help'], cwd=root,
                                   capture_output=True, text=True, check=False)
            isolated = subprocess.run([sys.executable, '-I', 'ci_reuse.py', '--help'], cwd=root,
                                      capture_output=True, text=True, check=False)
        self.assertNotEqual(plain.returncode, 0)
        self.assertIn('planted module ran', plain.stderr)
        self.assertEqual(isolated.returncode, 0, isolated.stderr)


class PublishTest(unittest.TestCase):
    """The decision reaches the workflow only through step outputs."""

    def test_outputs_and_summary_are_written(self):
        """The reuse kind, run and tree land in GITHUB_OUTPUT and the job summary."""
        with tempfile.TemporaryDirectory() as directory:
            output, summary = (os.path.join(directory, name) for name in ('output', 'summary'))
            saved = {key: os.environ.get(key) for key in ('GITHUB_OUTPUT', 'GITHUB_STEP_SUMMARY')}
            os.environ.update(GITHUB_OUTPUT=output, GITHUB_STEP_SUMMARY=summary)
            try:
                ci_reuse.publish({'reuse': 'tree', 'reuse_run': '11', 'tree': TREE}, 'because')
            finally:
                for key, value in saved.items():
                    if value is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = value
            with open(output, encoding='utf-8') as stream:
                self.assertEqual(stream.read(), f'reuse=tree\nreuse_run=11\ntree={TREE}\n')
            with open(summary, encoding='utf-8') as stream:
                self.assertIn('**tree**', stream.read())


if __name__ == '__main__':
    unittest.main()
