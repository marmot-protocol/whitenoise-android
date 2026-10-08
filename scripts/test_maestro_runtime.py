"""Regression contracts for fixture isolation, result identity and manual-only admission."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET
import yaml

from scripts import maestro_runtime as runtime
from scripts.maestro_coverage import EDGE_CHECKS, EDGE_DIMENSIONS, family_plans, inventory, markdown_inventory, named_edges, screen_catalog
from scripts.maestro_runtime_summary import campaign
from scripts.maestro_runtime_pair import pair
from scripts.maestro_runtime_selection import selection, matrix_selection
from scripts.manual_test_fragments import definitions, load_guide


class CampaignSummaryTest(unittest.TestCase):
    def test_matrix_cli_works_outside_package_import_context(self):
        """Regress the hosted summary's direct-script import without a repository PYTHONPATH."""
        environment = dict(os.environ)
        environment.pop('PYTHONPATH', None)
        with tempfile.TemporaryDirectory() as temporary:
            result = subprocess.run(
                [sys.executable, str(runtime.ROOT / 'scripts/maestro_runtime_selection.py'), 'runtime-settings'],
                cwd=temporary, env=environment, capture_output=True, text=True, timeout=10,
            )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout.removeprefix('slices=')), matrix_selection('runtime-settings'))

    def test_summary_cli_retains_missing_cases_outside_package_import_context(self):
        """The exact hosted CLI writes a failed ledger even when no shard can be qualified."""
        environment = dict(os.environ)
        environment.pop('PYTHONPATH', None)
        environment.update(GITHUB_SHA='a' * 40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='1')
        environment.pop('GITHUB_STEP_SUMMARY', None)
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'summary.json'
            result = subprocess.run(
                [sys.executable, str(runtime.ROOT / 'scripts/maestro_runtime_summary.py'),
                 '--artifacts', str(Path(temporary) / 'missing'), '--suite', 'runtime-settings',
                 '--output', str(output)],
                cwd=temporary, env=environment, capture_output=True, text=True, timeout=10,
            )
            self.assertEqual(result.returncode, 1, result.stderr)
            ledger = json.loads(output.read_text())
        self.assertEqual(ledger['expected_count'], sum(c['suite'] == 'settings' for c in runtime.CASES.values()))
        self.assertEqual(ledger['passed_count'], 0)
        self.assertFalse(ledger['evidence_complete'])
        self.assertTrue(all(c['not_run'] for c in ledger['results']))
        self.assertNotIn('ModuleNotFoundError', result.stderr)

    def test_workflow_upload_root_matches_actual_download_layout(self):
        """Keep the real multi-path artifact search root aligned with the reconciler's sibling directories."""
        workflow = yaml.safe_load((runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text())
        upload = next(step for step in workflow['jobs']['maestro-runtime']['steps']
                      if step.get('name') == 'Retain runtime results, including failed or incomplete cases')
        paths = upload['with']['path'].splitlines()
        # upload-artifact uses the least common ancestor of multiple search paths as its root.
        # Real hosted navigation artifacts contain these same two sibling directories.
        prefix = os.path.commonpath(paths)
        self.assertEqual(prefix, 'build')
        paths = [str(Path(path).relative_to(prefix)) for path in paths]
        self.assertEqual(paths, ['maestro-runtime-${{ matrix.slice }}-${{ matrix.partition }}',
                                 'maestro-runtime-pair/pair.json', 'maestro-runtime-environment.json'])
        self.assertNotIn('build/', '\n'.join(paths))

    def prepare(self, root):
        """Create six independently identified synthetic success leaves for reconciler boundary tests."""
        identity = {'schema': 1, 'source_sha': 'a' * 40, 'run_id': '123', 'run_attempt': '1',
                    'distribution': 'Zapstore', 'package': runtime.PACKAGE,
                    'apk_sha256': 'b' * 64, 'test_apk_sha256': 'c' * 64}
        leaves, pairs = [], []
        for partition in (1, 2):
            artifact = root / f'maestro-runtime-results-navigation-{partition}-123-1'
            pair_path = artifact / 'maestro-runtime-pair/pair.json'
            pair_path.parent.mkdir(parents=True)
            pair_path.write_text(json.dumps(identity))
            pairs.append(pair_path)
            environment = {'schema': 1, 'source_sha': 'a' * 40, 'run_id': '123', 'run_attempt': '1',
                           'api': '34', 'sdk': 34, 'navigation': 'button', 'navigation_mode': '0',
                           'navigation_overlay': 'com.android.internal.systemui.navbar.threebutton',
                           'image_fingerprint': 'synthetic-image', 'qemu': True}
            (artifact / 'maestro-runtime-environment.json').write_text(json.dumps(environment))
            reports = artifact / f'maestro-runtime-navigation-{partition}'
            names = runtime.case_selection('navigation', partition)
            rows = []
            for name in names:
                leaf = reports / name
                leaf.mkdir(parents=True)
                generation = f'{len(leaves) + 1:032x}'
                row = {'case': name, 'generation': generation, 'passed': True, 'cleanup_safe': True}
                rows.append(row)
                (leaf / 'result.json').write_text(json.dumps(row))
                for flag in ('ready', 'verified', 'closed'):
                    record = {'generation': generation, flag: True}
                    if flag == 'ready':
                        record.update(accounts=3, fixture='basic', uiObserver='maestro')
                    (leaf / f'{flag}.json').write_text(json.dumps(record))
                (leaf / 'junit.xml').write_text(
                    f'<testsuite><testcase name="{name}" status="SUCCESS" time="1"/></testsuite>')
                (leaf / 'instrumentation.txt').write_text('OK (1 test)')
                leaves.append(leaf)
            (reports / 'results.json').write_text(json.dumps(
                {'suite': 'navigation', 'partition': partition, 'expected': names, 'results': rows, 'evidence_complete': True}))
        return leaves, pairs

    def result(self, root):
        """Reconcile the same source/run selection after a controlled evidence mutation."""
        return campaign(root, 'runtime-navigation', 'a' * 40, '123', '1')

    def test_complete_campaign_requires_six_leaves_and_keeps_release_gap(self):
        """All requested UI leaves can pass without becoming complete release certification."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            result = self.result(root)
            self.assertTrue(result['evidence_complete'])
            self.assertEqual(result['passed_count'], 6)
            self.assertFalse(result['full_release_coverage'])

    def test_ui_only_retry_reuses_matching_prior_successful_shards(self):
        """Failed-job retries can retain successful same-run shards and the unchanged build producer."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            result = campaign(root, 'runtime-navigation', 'a' * 40, '123', '2')
            self.assertTrue(result['evidence_complete'])
            self.assertEqual(result['passed_count'], 6)

    def test_new_failed_leaf_overrides_older_pass(self):
        """A rerun failure cannot be hidden by selecting an earlier green artifact."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            source = root / 'maestro-runtime-results-navigation-1-123-1'
            target = root / 'maestro-runtime-results-navigation-1-123-2'
            shutil.copytree(source, target)
            environment_path = target / 'maestro-runtime-environment.json'
            environment = json.loads(environment_path.read_text())
            environment['run_attempt'] = '2'
            environment_path.write_text(json.dumps(environment))
            leaf = target / 'maestro-runtime-navigation-1' / runtime.case_selection('navigation', 1)[0]
            (leaf / 'junit.xml').unlink()
            result = campaign(root, 'runtime-navigation', 'a' * 40, '123', '2')
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['passed_count'], 5)

    def test_missing_or_mixed_platform_cannot_qualify_complete_ui_results(self):
        """Matching APKs and UI/native success still require one observed OS/navigation environment."""
        for mutation in ('missing', 'sdk', 'navigation', 'image_fingerprint', 'run_attempt'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                self.prepare(root)
                path = root / 'maestro-runtime-results-navigation-2-123-1/maestro-runtime-environment.json'
                if mutation == 'missing':
                    path.unlink()
                else:
                    record = json.loads(path.read_text())
                    record[mutation] = {'sdk': 36, 'navigation': 'gesture',
                                        'image_fingerprint': 'different-image', 'run_attempt': '2'}[mutation]
                    path.write_text(json.dumps(record))
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertTrue(result['errors'])

    def test_generic_native_success_cannot_certify_activity_recreation(self):
        """A recreation case needs its explicit native instance proof as well as normal UI/cleanup success."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            replacement = {**runtime.CASES[name], 'postcondition': 'composer-recreated'}
            with patch.dict(runtime.CASES, {name: replacement}):
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertEqual(result['passed_count'], 5)
                self.assertIn('recreation', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['activityRecreated'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_external_contact_privacy(self):
        """Private-edit results need actual external-contact/scoped-store proof, not a generic success receipt."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            replacement = {**runtime.CASES[name], 'postcondition': 'contact-private-saved'}
            with patch.dict(runtime.CASES, {name: replacement}):
                result = self.result(root)
                self.assertEqual(result['passed_count'], 5)
                self.assertFalse(result['evidence_complete'])
                self.assertIn('contact privacy', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['privateContactVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_persisted_speech_rate(self):
        """Require the actual preference-owner/fresh-reader proof, not a generic native receipt."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            replacement = {**runtime.CASES[name], 'postcondition': 'speech-rate-custom'}
            with patch.dict(runtime.CASES, {name: replacement}):
                result = self.result(root)
                self.assertEqual(result['passed_count'], 5)
                self.assertFalse(result['evidence_complete'])
                self.assertIn('speech rate', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['speechRateVerified'] = False
                path.write_text(json.dumps(record))
                self.assertFalse(self.result(root)['evidence_complete'])
                record['speechRateVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_saved_public_profile(self):
        """A successful UI leaf cannot replace whole-profile and other-account persistence proof."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            replacement = {**runtime.CASES[name], 'postcondition': 'public-profile-text-saved'}
            with patch.dict(runtime.CASES, {name: replacement}):
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertEqual(result['passed_count'], 5)
                self.assertIn('public profile', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['publicProfileVerified'] = False
                path.write_text(json.dumps(record))
                self.assertFalse(self.result(root)['evidence_complete'])
                record['publicProfileVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_complete_folder_rules(self):
        """Require persisted rule/metadata/account isolation proof alongside visible editor success."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            with patch.dict(runtime.CASES, {name: {**runtime.CASES[name], 'postcondition': 'smart-rule-read'}}):
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertEqual(result['passed_count'], 5)
                self.assertIn('smart-folder', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['smartFolderRuleVerified'] = False
                path.write_text(json.dumps(record))
                self.assertFalse(self.result(root)['evidence_complete'])
                record['smartFolderRuleVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_unchanged_relay_lists(self):
        """Relay cancellation must compare all native account projections before it qualifies."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            with patch.dict(runtime.CASES, {name: {**runtime.CASES[name], 'postcondition': 'relay-lists-unchanged'}}):
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertEqual(result['passed_count'], 5)
                self.assertIn('native relay lists', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['relayListsVerified'] = False
                path.write_text(json.dumps(record))
                self.assertFalse(self.result(root)['evidence_complete'])
                record['relayListsVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_generic_native_success_cannot_certify_account_actions(self):
        """Signed-out/wiped UI must have matching native account, survivor and durable-draft proof."""
        for postcondition in ('account-action-signed-out', 'account-action-wiped'):
            with self.subTest(postcondition=postcondition), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                leaves, _ = self.prepare(root)
                name = runtime.case_selection('navigation', 1)[0]
                with patch.dict(runtime.CASES, {name: {**runtime.CASES[name], 'postcondition': postcondition}}):
                    result = self.result(root)
                    self.assertFalse(result['evidence_complete'])
                    self.assertIn('account action', result['results'][0]['failure'])
                    path = leaves[0] / 'verified.json'
                    record = json.loads(path.read_text())
                    record['accountActionVerified'] = False
                    path.write_text(json.dumps(record))
                    self.assertFalse(self.result(root)['evidence_complete'])
                    record['accountActionVerified'] = True
                    path.write_text(json.dumps(record))
                    self.assertTrue(self.result(root)['evidence_complete'])

    def test_app_lock_needs_typed_real_credential_state_before_and_after_ui(self):
        """Final OS state cannot certify a fake prerequisite or replace its native postcondition."""
        for phase, field in (('ready', 'appLockFixtureNoCredential'), ('verified', 'appLockVerified')):
            for proof in (None, False, 'true', 1, True):
                with self.subTest(phase=phase, proof=proof), tempfile.TemporaryDirectory() as temporary:
                    root = Path(temporary)
                    leaves, _ = self.prepare(root)
                    name = runtime.case_selection('navigation', 1)[0]
                    for receipt_phase, receipt_field in (
                        ('ready', 'appLockFixtureNoCredential'), ('verified', 'appLockVerified'),
                    ):
                        path = leaves[0] / f'{receipt_phase}.json'
                        record = json.loads(path.read_text())
                        if receipt_phase != phase:
                            record[receipt_field] = True
                        elif proof is not None:
                            record[receipt_field] = proof
                        path.write_text(json.dumps(record))
                    with patch.dict(runtime.CASES, {name: {**runtime.CASES[name],
                                                          'postcondition': 'app-lock-unavailable'}}):
                        result = self.result(root)
                    self.assertEqual(result['evidence_complete'], proof is True)
                    if proof is not True:
                        self.assertIn('app-lock', result['results'][0]['failure'])

    def test_generic_native_success_cannot_certify_inbound_share_recovery(self):
        """UI navigation needs real imported-error/request-cleanup/no-send native evidence."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            name = runtime.case_selection('navigation', 1)[0]
            with patch.dict(runtime.CASES, {name: {**runtime.CASES[name], 'postcondition': 'share-request-staged'}}):
                result = self.result(root)
                self.assertFalse(result['evidence_complete'])
                self.assertEqual(result['passed_count'], 5)
                self.assertIn('inbound share', result['results'][0]['failure'])
                path = leaves[0] / 'verified.json'
                record = json.loads(path.read_text())
                record['shareImportVerified'] = False
                path.write_text(json.dumps(record))
                self.assertFalse(self.result(root)['evidence_complete'])
                record['shareImportVerified'] = True
                path.write_text(json.dumps(record))
                self.assertTrue(self.result(root)['evidence_complete'])

    def test_future_shard_attempt_cannot_certify_current_run(self):
        """A future artifact is invalid even when older matching evidence passes."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            (root / 'maestro-runtime-results-navigation-1-123-2').mkdir()
            self.assertFalse(self.result(root)['evidence_complete'])

    def test_missing_shards_remain_named_unexecuted_cases(self):
        """An empty artifact download cannot silently shorten the requested campaign."""
        with tempfile.TemporaryDirectory() as temporary:
            result = self.result(Path(temporary))
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['expected_count'], 6)
            self.assertTrue(all(row['not_run'] for row in result['results']))

    def test_native_success_without_junit_never_certifies_ui(self):
        """Even success-labelled native receipts and ledgers need an actual UI leaf."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            (leaves[0] / 'junit.xml').unlink()
            result = self.result(root)
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['passed_count'], 5)

    def test_cleanup_receipt_cannot_belong_to_another_generation(self):
        """A stale teardown cannot certify a fresh fixture even if its UI passed."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            (leaves[0] / 'closed.json').write_text(json.dumps({'generation': 'f' * 32, 'closed': True}))
            self.assertFalse(self.result(root)['evidence_complete'])

    def test_foreign_source_rejects_its_shard(self):
        """UI leaves from another revision cannot be counted in this selected run."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, pairs = self.prepare(root)
            data = json.loads(pairs[0].read_text())
            data['source_sha'] = 'd' * 40
            pairs[0].write_text(json.dumps(data))
            result = self.result(root)
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['expected_count'], 6)

    def test_different_apk_pairs_cannot_share_one_campaign(self):
        """Source equality alone cannot reconcile different test APK bytes across shards."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, pairs = self.prepare(root)
            data = json.loads(pairs[1].read_text())
            data['test_apk_sha256'] = 'd' * 64
            pairs[1].write_text(json.dumps(data))
            self.assertFalse(self.result(root)['evidence_complete'])

    def test_skipped_ui_leaf_is_not_a_success(self):
        """An aggregate passed flag never overrides a skipped UI assertion."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            path = leaves[0] / 'junit.xml'
            path.write_text(path.read_text().replace('/>', '><skipped/></testcase>'))
            self.assertFalse(self.result(root)['evidence_complete'])

    def test_malformed_ui_preserves_other_case_results(self):
        """A corrupted leaf fails explicitly while the remaining selected leaves stay visible."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            (leaves[0] / 'junit.xml').write_text('<broken')
            result = self.result(root)
            self.assertEqual(result['expected_count'], 6)
            self.assertEqual(result['passed_count'], 5)
            self.assertFalse(result['evidence_complete'])

    def test_unexpected_shard_artifact_invalidates_the_campaign(self):
        """Extra artifacts cannot quietly masquerade as selected coverage."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.prepare(root)
            (root / 'foreign-shard').mkdir()
            result = self.result(root)
            self.assertFalse(result['evidence_complete'])
            self.assertTrue(result['errors'])

    def test_duplicate_case_ledger_keeps_requested_cases_unexecuted(self):
        """Duplicating one case cannot conceal a different missing case in the shard."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            leaves, _ = self.prepare(root)
            path = leaves[0].parent / 'results.json'
            data = json.loads(path.read_text())
            data['results'][1] = data['results'][0]
            path.write_text(json.dumps(data))
            result = self.result(root)
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['expected_count'], 6)


class RuntimeEvidenceTest(unittest.TestCase):
    def test_every_configured_case_is_admitted_by_the_actual_native_host(self):
        """Prefix-based Python reconciliation cannot certify a condition the Kotlin host refuses."""
        host = (runtime.ROOT / 'app/src/androidTest/java/dev/ipf/whitenoise/android/maestro/'
                'MaestroRuntimeHostTest.kt').read_text()
        match = re.search(r'MAESTRO_POSTCONDITIONS\s*=\s*setOf\((.*?)\n    \)', host, re.S)
        self.assertIsNotNone(match)
        allowed = set(re.findall(r'"([^"]+)"', match.group(1)))
        configured = {case['postcondition'] for case in runtime.CASES.values()}
        self.assertFalse(configured - allowed, configured - allowed)
        seeds = (runtime.ROOT / 'app/src/androidTest/java/dev/ipf/whitenoise/android/maestro/'
                 'MaestroFixtureSeeds.kt').read_text()
        match = re.search(r'require\(\s*fixture in\s*listOf\((.*?)\),', seeds, re.S)
        self.assertIsNotNone(match)
        allowed = set(re.findall(r'"([^"]+)"', match.group(1)))
        configured = {case.get('fixture', 'basic') for case in runtime.CASES.values()}
        self.assertFalse(configured - allowed, configured - allowed)

    def test_named_warm_resume_cases_map_to_return_not_rotation(self):
        """A Home/foreground journey must not credit the rotation requirement in the inventory."""
        for name, case in runtime.CASES.items():
            if 'warm-resume' in name:
                with self.subTest(case=name):
                    self.assertIn('NAV-010', case['manual_ids'])
                    if 'rotation' not in name:
                        self.assertNotIn('NAV-009', case['manual_ids'])

    def test_canonical_runtime_recipe_matches_the_selected_inventory(self):
        """An expanding suite must not leave operators reconciling an obsolete case/partition count."""
        guide = (runtime.ROOT / 'docs/automated-testing.md').read_text()
        cases = re.search(r'`runtime-all` \((\d+)\)', guide)
        partitions = re.search(r'executes all (\d+) partitions', guide)
        journeys = re.search(r'written UI inventory is (\d+) journeys', guide)
        self.assertIsNotNone(cases)
        self.assertIsNotNone(partitions)
        self.assertIsNotNone(journeys)
        self.assertEqual(int(cases.group(1)), len(runtime.CASES))
        self.assertEqual(int(partitions.group(1)), len(matrix_selection('runtime-all')))
        self.assertEqual(int(journeys.group(1)), inventory()['case_count'])

    def test_long_input_timeout_fits_existing_fixture_and_cleanup_reserves(self):
        """A measured typing overrun cannot silently enlarge the host lifetime or shard allowance."""
        self.assertEqual(set(runtime.UI_TIMEOUTS), {'polls-question-boundary'})
        for name, seconds in runtime.UI_TIMEOUTS.items():
            self.assertIn(name, runtime.CASES)
            self.assertLess(seconds + 30, 300)  # Native host's existing handoff deadline.
            self.assertLess(120 + seconds + 60 + 60 + 30 + 30 + 30 + 20, runtime.CASE_RESERVE_SECONDS)
        with tempfile.TemporaryDirectory() as temporary, patch.object(runtime.subprocess, 'run') as run:
            runtime.run_ui('polls-question-boundary', Path(temporary))
            self.assertEqual(run.call_args.kwargs['timeout'], 240)

    def test_every_surface_gets_every_edge_without_automatic_pass_or_na(self):
        """A discovered dialog cannot silently omit lifecycle, accessibility or input qualification."""
        result = inventory()
        self.assertEqual(set(EDGE_CHECKS), set(EDGE_DIMENSIONS))
        self.assertEqual(result['screen_edge_check_count'], len(result['screen_catalog']) * len(EDGE_DIMENSIONS))
        for screen in result['screen_catalog']:
            with self.subTest(surface=screen['symbol']):
                self.assertEqual([edge['dimension'] for edge in screen['edge_plan']], list(EDGE_DIMENSIONS))
                for edge in screen['edge_plan']:
                    self.assertTrue(edge['required_check'])
                    self.assertEqual(edge['status'], 'unexecuted')
                    self.assertEqual(edge['evidence'], [])
                    self.assertIsNone(edge['na_reason'])
        text = markdown_inventory(result)
        for dimension in EDGE_DIMENSIONS:
            self.assertEqual(text.count(f'- [ ] **{dimension}**'), len(result['screen_catalog']))

    def test_runtime_flow_commands_reject_yaml_sets_and_unsupported_shapes(self):
        """Valid YAML sets and aliases are invalid Maestro commands; inspect original tokens too."""
        def no_aliases(text):
            # PyYAML expands aliases before shape validation; Maestro rejects their command token.
            self.assertFalse(any(isinstance(token, (yaml.tokens.AnchorToken, yaml.tokens.AliasToken))
                                 for token in yaml.scan(text)))

        def validate(commands):
            self.assertIsInstance(commands, list)
            for command in commands:
                if isinstance(command, str):
                    self.assertIn(command, ('hideKeyboard', 'back', 'eraseText', 'stopApp'))
                    continue
                self.assertIsInstance(command, dict)
                self.assertEqual(len(command), 1)
                name, args = next(iter(command.items()))
                self.assertNotIn(name, ('hideKeyboard', 'back'))
                if name in ('retry', 'repeat') or (
                        name == 'runFlow' and isinstance(args, dict) and 'commands' in args):
                    validate(args['commands'])
        for path in (runtime.ROOT / '.maestro').rglob('*.yaml'):
            with self.subTest(flow=str(path.relative_to(runtime.ROOT))):
                no_aliases(path.read_text())
                documents = list(yaml.safe_load_all(path.read_text()))
                if path.name != 'config.yaml':
                    self.assertEqual(len(documents), 2)
                    validate(documents[1])
        for invalid in ([{'hideKeyboard'}], [{'back': None}], [{'tapOn': 'X', 'hideKeyboard': None}]):
            with self.subTest(invalid=invalid), self.assertRaises(AssertionError):
                validate(invalid)
        for text in ('- &repeat\n  assertVisible: Settings\n- *repeat\n',
                     '- assertVisible: &name Settings\n- assertVisible: *name\n'):
            with self.subTest(unsupported_yaml=text), self.assertRaises(AssertionError):
                no_aliases(text)
        no_aliases('- inputText: "literal &repeat and *repeat"\n')

    def test_saved_folder_rule_checks_return_from_the_reopened_title_viewport(self):
        """Reopen parks at the title, so the Match any proof must move toward the lower rules."""
        path = runtime.ROOT / '.maestro/runtime/folder-rules-match-any-reopen.yaml'
        commands = list(yaml.safe_load_all(path.read_text()))[1]
        reopen = commands.index({'runFlow': '../fixtures/save-and-reopen-smart-folder.yaml'})
        self.assertEqual(commands[reopen + 1], {
            'scrollUntilVisible': {'element': {'id': 'folder.match.'}, 'direction': 'DOWN'}})
        keyword = runtime.ROOT / '.maestro/runtime/folder-rules-title-save-reopen.yaml'
        commands = list(yaml.safe_load_all(keyword.read_text()))[1]
        typing = commands.index({'inputText': 'Maestro'})
        self.assertEqual(commands[typing - 1], {'assertVisible': {'focused': True}})
        self.assertEqual(commands[typing + 1], {
            'assertVisible': {'focused': True, 'text': '^Maestro$'}})

    def test_share_account_sheet_dismissal_distinguishes_the_persistent_open_button(self):
        """The opener's accessible label survives dismissal and cannot certify an absent sheet."""
        for path in (runtime.ROOT / '.maestro/runtime').glob('inbound-share-account-*.yaml'):
            commands = list(yaml.safe_load_all(path.read_text()))[1]
            self.assertNotIn({'assertNotVisible': '^Choose sending account$'}, commands)
            self.assertIn({'assertNotVisible':
                           '^Choose which signed-in account will own these shared drafts\\.$'}, commands)
            case = runtime.CASES[path.stem]
            self.assertEqual(case['fixture'], 'share-text')
            self.assertEqual(case['postcondition'], 'share-request-cancelled')

    def test_long_press_uses_a_command_not_an_unsupported_tap_property(self):
        """Regress the actual CLI parse failure that prevented all contextual-menu journeys."""
        # https://docs.maestro.dev/reference/commands-available/longpresson
        long_presses = 0
        for path in (runtime.ROOT / '.maestro').rglob('*.yaml'):
            self.assertNotRegex(path.read_text(), r'\blongPress\s*:')
            for document in yaml.safe_load_all(path.read_text()):
                if isinstance(document, list):
                    long_presses += sum(isinstance(command, dict) and 'longPressOn' in command for command in document)
        self.assertGreater(long_presses, 0)

    def test_observed_popup_children_replace_unexported_container_tags(self):
        """Real hosted trees expose Camera and emoji grid, while these parent tags are absent."""
        for path in (runtime.ROOT / '.maestro/runtime').glob('*.yaml'):
            self.assertNotRegex(path.read_text(), r'(?m)id: "?conversation\.attachment\.menu"?\s*$')
            self.assertNotRegex(path.read_text(), r'(?m)id: "?emoji\.picker"?\s*$', path.name)
        emoji = (runtime.ROOT / '.maestro/runtime/conversation-emoji-cancel.yaml').read_text()
        self.assertIn('emoji.picker.grid', emoji)

    def test_send_hides_keyboard_suggestions_before_selecting_app_button(self):
        """Gboard's suggestion send must not receive the tap meant for the app's Send control."""
        _, commands = list(yaml.safe_load_all((runtime.ROOT / '.maestro/runtime/conversation-send.yaml').read_text()))
        send = commands.index({'tapOn': 'Send'})
        self.assertEqual(commands[send - 1], 'hideKeyboard')
        self.assertEqual(runtime.CASES['conversation-send']['postcondition'], 'send')

    def test_cli_parser_output_survives_a_failure_without_junit(self):
        """Keep the actual parser diagnostic in the failed case artifact instead of only the job log."""
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            def parser_failure(arguments, **options):
                """Model a CLI rejection before any UI result can be produced."""
                options['stdout'].write('Unknown Property: longPress\n')
                self.assertIs(options['stderr'], subprocess.STDOUT)
                self.assertEqual(options['timeout'], 120)
                return subprocess.CompletedProcess(arguments, 1)
            with patch.object(runtime.subprocess, 'run', side_effect=parser_failure):
                self.assertEqual(runtime.run_ui('conversation-selection-cancel', directory).returncode, 1)
            self.assertIn('Unknown Property', (directory / 'maestro-output.txt').read_text())
            self.assertFalse((directory / 'junit.xml').exists())

    def test_native_host_never_claims_the_maestro_ui_connection(self):
        """An indirect safety probe must not compete with the driver's single accessibility owner."""
        root = runtime.ROOT / 'app/src/androidTest/java/dev/ipf/whitenoise/android/maestro'
        for path in root.glob('*.kt'):
            with self.subTest(source=path.name):
                self.assertNotRegex(path.read_text(), r'\.\s*uiAutomation|\bgetUiAutomation\s*\(|\bUiDevice\b')

    def test_rotation_commands_use_supported_pinned_cli_orientations(self):
        """Reject flow parse failures before requesting an emulator; include every offline and runtime flow."""
        # https://docs.maestro.dev/reference/commands-available/setorientation
        allowed = {'PORTRAIT', 'LANDSCAPE_LEFT', 'LANDSCAPE_RIGHT', 'UPSIDE_DOWN'}
        rotations = 0
        for path in (runtime.ROOT / '.maestro').rglob('*.yaml'):
            documents = list(yaml.safe_load_all(path.read_text()))
            if len(documents) != 2:
                continue
            for command in documents[1]:
                if 'setOrientation' in command:
                    rotations += 1
                    with self.subTest(flow=path.name):
                        self.assertIn(command['setOrientation'], allowed)
        self.assertGreater(rotations, 0)

    def test_screen_inventory_keeps_partial_and_unexecuted_requirements_visible(self):
        """Screen discovery cannot transform same-ID flow links into complete or executed coverage."""
        result = inventory()
        self.assertTrue(result['screen_catalog'])
        self.assertTrue(result['unmapped_requirement_ids'])
        self.assertFalse(result['full_release_coverage'])
        for screen in result['screen_catalog']:
            self.assertTrue(screen['manual_ids'])
            self.assertFalse(screen['execution_verified'])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'app/src/main/java/dev/ipf/whitenoise/android/ui/new/NewScreen.kt'
            path.parent.mkdir(parents=True)
            path.write_text('@Composable internal fun NewScreen() {}')
            with self.assertRaisesRegex(ValueError, 'no maintained source'):
                screen_catalog(root, {}, {})

    def test_named_edges_are_preserved_without_inheriting_parent_ui_proof(self):
        """A parent journey cannot certify a migration/dictation recovery subcase it did not run."""
        result = inventory()
        self.assertGreater(result['named_edge_case_count'], 0)
        for edge in result['named_edge_cases']:
            self.assertIn(edge, result['requirements'][edge['parent']]['named_edge_cases'])
            self.assertFalse(edge['full_release_proof'])
            self.assertIn(edge['requirement'], load_guide(runtime.ROOT))
        for guide in ('- **BAD-001 missing subcase:** Action → Expected: Outcome',
                      '- **INT-010 repeat:** First\n- **INT-010 repeat:** Second'):
            with self.subTest(guide=guide), self.assertRaises(ValueError):
                named_edges(guide, {'INT-010': 'existing'})

    def test_screen_discovery_handles_generics_receivers_and_ignores_non_code(self):
        """Real generic dialogs must be catalogued; commented, quoted and non-Compose names are not screens."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = 'app/src/main/java/dev/ipf/whitenoise/android/ui/Choices.kt'
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text('@Composable fun <T> ColumnScope.GenericDialog(value: T) {}\n'
                            '// @Composable fun PhantomScreen() {}\n'
                            'val example = "@Composable fun QuotedSheet() {}"\n'
                            'fun FormatDialog() {}\n')
            screens = screen_catalog(root, {'ui': [{'source': source, 'test_ids': ['INT-001']}]}, {})
            self.assertEqual([screen['symbol'] for screen in screens], ['ColumnScope.GenericDialog'])
            self.assertEqual(len(screens[0]['edge_plan']), len(EDGE_DIMENSIONS))

    def test_dialogs_outside_ui_package_require_the_same_maintained_edge_plan(self):
        """Speech/platform dialogs cannot disappear because their owner is outside the UI directory."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = 'app/src/main/java/dev/ipf/whitenoise/android/audio/tts/Trust.kt'
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text('@Composable fun EngineTrustDialog() {}')
            with self.assertRaisesRegex(ValueError, 'no maintained source'):
                screen_catalog(root, {}, {})
            screens = screen_catalog(root, {'audio': [{'source': source, 'test_ids': ['TTS-001']}]}, {})
            self.assertEqual([screen['symbol'] for screen in screens], ['EngineTrustDialog'])
            self.assertEqual(len(screens[0]['edge_plan']), len(EDGE_DIMENSIONS))
            self.assertFalse(screens[0]['execution_verified'])
        actual = {screen['symbol']: screen for screen in inventory()['screen_catalog']}
        self.assertIn('TtsTrustWarningDialog', actual)
        self.assertEqual(actual['TtsTrustWarningDialog']['source'],
                         'app/src/main/java/dev/ipf/whitenoise/android/audio/tts/TtsTrustWarning.kt')

    def test_campaign_plan_covers_all_requirements_and_original_flow_filenames(self):
        """Every permanent point receives layer prerequisites and every journey links to its actual source."""
        result = inventory()
        text = markdown_inventory(result, 'a' * 40)
        for test_id, requirement in result['requirements'].items():
            self.assertTrue(requirement['campaign_plan']['prerequisites'])
            self.assertIn(requirement['requirement'], text)
            self.assertFalse(requirement['full_release_proof'])
        for case in result['cases'].values():
            self.assertTrue((runtime.ROOT / case['flow']).is_file())
            self.assertIn(case['flow'], text)
        for screen in result['screen_catalog']:
            self.assertIn(f"[{screen['symbol']}]", text)
        for dimension in EDGE_DIMENSIONS:
            self.assertIn('- [ ] ' + dimension, text)
        self.assertIn('activity-recreation', text)
        self.assertIn('process-death', text)
        self.assertNotIn('[x]', text)
        with self.assertRaises(ValueError):
            markdown_inventory(result, '../master')

    def test_viewer_picker_and_loading_overlay_keep_their_own_edge_plans(self):
        """Media, search and transient account surfaces must not disappear merely because of their names."""
        result = inventory()
        by_name = {screen['symbol']: screen for screen in result['screen_catalog']}
        for name in ('MessageFullScreenView', 'AvatarFullScreenViewer', 'GlobalSearchDatePicker',
                     'QuickAccountSwitchTransitionOverlay', 'ComposerEmojiPickerPane'):
            with self.subTest(surface=name):
                self.assertIn(name, by_name)
                self.assertTrue(by_name[name]['manual_ids'])
                self.assertFalse(by_name[name]['execution_verified'])
                self.assertEqual({edge['dimension'] for edge in by_name[name]['edge_plan']}, set(EDGE_DIMENSIONS))

    def test_nested_menus_panels_and_modal_content_keep_distinct_edge_plans(self):
        """A terminal Screen/Sheet match must not hide menus or named dialog and picker content."""
        catalog = {screen['symbol']: screen for screen in inventory()['screen_catalog']}
        for name in ('ChatContextMenu', 'GlobalSearchFilterMenu', 'ComposerAttachmentMenu',
                     'MessageActionMenu', 'KeyboardSafePopup', 'MessageDeleteDialogContent',
                     'AddAccountSheetContent', 'ForwardMessagePickerContent', 'QrScannerSheetContent',
                     'ShareChatPickerFullScreenContent', 'SmartFolderRulePanel', 'KeyPackagesScreenForAccount'):
            with self.subTest(surface=name):
                self.assertIn(name, catalog)
                self.assertTrue(catalog[name]['manual_ids'])
                self.assertFalse(catalog[name]['execution_verified'])
                self.assertEqual(len(catalog[name]['edge_plan']), len(EDGE_DIMENSIONS))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = 'app/src/main/java/dev/ipf/whitenoise/android/ui/Nested.kt'
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text('@Composable fun PrivateSheetModal() {}\n'
                            '@Composable fun ToolbarPopup() {}\n'
                            '@Composable fun MenuItem() {}\n'
                            '@Composable fun PopupBackHandler() {}\n'
                            '@Composable fun PickerScrollSync() {}\n')
            with self.assertRaisesRegex(ValueError, 'no maintained source'):
                screen_catalog(root, {}, {})
            screens = screen_catalog(root, {'ui': [{'source': source, 'test_ids': ['INT-001']}]}, {})
            self.assertEqual([screen['symbol'] for screen in screens], ['PrivateSheetModal', 'ToolbarPopup'])
            self.assertTrue(all(len(screen['edge_plan']) == len(EDGE_DIMENSIONS) for screen in screens))

    def test_new_requirement_family_requires_an_explicit_campaign_plan(self):
        """New families cannot silently inherit a generic or obsolete layer assignment."""
        for fault in ('missing', 'extra', 'unknown-layer', 'no-prerequisite', 'non-string-layer'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / 'config').mkdir()
                plan = {'schema': 1, 'families': {'ONB': {'layers': ['offline-ui'], 'prerequisites': 'Fresh install'}}}
                if fault == 'missing':
                    plan['families'] = {}
                elif fault == 'extra':
                    plan['families']['OLD'] = plan['families']['ONB']
                elif fault == 'unknown-layer':
                    plan['families']['ONB']['layers'] = ['made-up']
                elif fault == 'non-string-layer':
                    plan['families']['ONB']['layers'] = [None]
                else:
                    plan['families']['ONB']['prerequisites'] = ''
                (root / 'config/test-requirement-layers.json').write_text(json.dumps(plan))
                with self.assertRaises(ValueError):
                    family_plans(root, {'ONB-001': 'existing'})

    def test_same_file_companion_reference_does_not_certify_a_private_dialog(self):
        """A parent-render test can help find coverage but cannot become proof of its nested dialog."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = 'app/src/main/java/dev/ipf/whitenoise/android/ui/Nested.kt'
            path = root / source
            path.parent.mkdir(parents=True)
            path.write_text('@Composable fun ParentContent() {}\n@Composable private fun HiddenDialog() {}')
            test = root / 'app/src/test/NestedTest.kt'
            test.parent.mkdir(parents=True)
            test.write_text('fun render() { ParentContent() }')
            screens = screen_catalog(root, {'ui': [{'source': source, 'test_ids': ['INT-001']}]}, {}, True)
            self.assertEqual(len(screens), 1)
            self.assertFalse(screens[0]['execution_verified'])
            self.assertEqual(screens[0]['companion_test_source_references'], [
                {'source': 'app/src/test/NestedTest.kt', 'direct_surface_reference': False}])

    def test_retry_reuses_only_an_explicit_same_source_producer(self):
        """A UI-only retry can use its original pair while rejecting stale and future identities."""
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'app.apk').write_bytes(b'app')
            (directory / 'test.apk').write_bytes(b'test')
            pair(directory, 'stage', env)
            retry = {**env, 'GITHUB_RUN_ATTEMPT': '2', 'MAESTRO_PAIR_PRODUCER_ATTEMPT': '1'}
            self.assertEqual(pair(directory, 'verify', retry)['run_attempt'], '1')
            for producer in ('', '0', '-1', '3', 'invalid', '1000000000'):
                with self.subTest(producer=producer), self.assertRaises(ValueError):
                    pair(directory, 'verify', {**retry, 'MAESTRO_PAIR_PRODUCER_ATTEMPT': producer})
            for key, value in [('GITHUB_SHA', 'b' * 40), ('GITHUB_RUN_ID', '2')]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    pair(directory, 'verify', {**retry, key: value})

    def test_conversation_checks_reject_chat_list_preview_as_route_proof(self):
        """Regress the observed reactor Back false pass on identical preview text."""
        guard = list(yaml.safe_load_all(
            (runtime.ROOT / '.maestro/fixtures/assert-fixture-conversation.yaml').read_text(),
        ))[1]
        self.assertIn({'assertVisible': {'id': 'composer-pill-surface'}}, guard)
        self.assertIn({'assertNotVisible': {'id': 'chats.scope.chats'}}, guard)
        self.assertIn({'assertVisible': 'Generated fixture message'}, guard)
        history = list(yaml.safe_load_all(
            (runtime.ROOT / '.maestro/fixtures/assert-fixture-history.yaml').read_text(),
        ))[1]
        self.assertIn({'assertNotVisible': {'id': 'chats.scope.chats'}}, history)
        self.assertIn({'assertVisible': 'Generated fixture message'}, history)
        self.assertNotIn({'assertVisible': {'id': 'composer-pill-surface'}}, history)
        for name in runtime.CASES:
            flow = runtime.ROOT / '.maestro/runtime' / (name + '.yaml')
            commands = list(yaml.safe_load_all(flow.read_text()))[1]
            with self.subTest(case=name):
                self.assertNotIn({'assertVisible': 'Generated fixture message'}, commands)

    def test_account_guard_journeys_never_execute_terminal_account_actions(self):
        """Keep cancellation evidence from turning into a sign-out or wipe campaign."""
        def inspect(flow, seen):
            if flow in seen:
                return
            seen.add(flow)
            commands = list(yaml.safe_load_all(flow.read_text()))[1]
            for command in commands:
                if not isinstance(command, dict):
                    continue
                tap = command.get('tapOn')
                text = tap.get('text') if isinstance(tap, dict) else tap
                self.assertNotEqual(text, 'Wipe', flow)
                if text == 'Sign Out':
                    self.assertEqual(flow.name, 'show-signout-sheet.yaml')
                    self.assertEqual(commands[0], {'assertVisible': 'Settings'})
                subflow = command.get('runFlow')
                if isinstance(subflow, str):
                    inspect((flow.parent / subflow).resolve(), seen)
        guards = {name: case for name, case in runtime.CASES.items() if case['suite'] == 'account-guards'}
        self.assertEqual(len(guards), 9)
        for name, case in guards.items():
            with self.subTest(case=name):
                self.assertEqual(case['postcondition'], 'accounts-retained')
                if name.startswith('accounts-wipe-') and name != 'accounts-wipe-sheet-back':
                    flow = (runtime.ROOT / '.maestro/runtime' / (name + '.yaml')).read_text()
                    self.assertNotIn('containsChild:', flow)
                    self.assertIn('id: "profile_keys.wipe_confirm"\n    enabled:', flow)
                inspect(runtime.ROOT / '.maestro/runtime' / (name + '.yaml'), set())

    def test_backup_checks_use_button_state_and_verify_both_cleared_inputs(self):
        """Reject the captured false label-state proof and unintended Back on an unfocused dialog."""
        for name, case in runtime.CASES.items():
            if case['suite'] != 'keys':
                continue
            flow = (runtime.ROOT / '.maestro/runtime' / (name + '.yaml')).read_text()
            with self.subTest(case=name):
                self.assertNotIn('- assertVisible: "Encrypted Private Key"\n- hideKeyboard', flow)
                self.assertNotRegex(flow, r'(?m)^    text: "(?:View backup|Export)"\n    enabled:')
                self.assertNotIn('containsChild:', flow)
                for tag in ('view_backup', 'export_file'):
                    self.assertIn('id: "profile_keys.' + tag + '"\n    enabled:', flow)
                self.assertNotIn('- tapOn: "View backup"', flow)
                self.assertNotIn('- tapOn: "Export"', flow)
                if name in ('keys-empty', 'keys-cancel-clears', 'keys-back-clears', 'keys-rotation-cancel'):
                    for field in ('export_password', 'export_confirmation'):
                        self.assertIn('- assertVisible:\n    id: "profile_keys.' + field + '"', flow)
                        self.assertIn('- assertNotVisible:\n    id: "profile_keys.' + field + '"\n    text: ".+"', flow)

    def test_backup_correction_proves_complete_replacement_before_matching_buttons(self):
        """Reject cursor-relative clearing that left st2! behind the corrected confirmation."""
        _, commands = list(yaml.safe_load_all(
            (runtime.ROOT / '.maestro/runtime/keys-correct-mismatch.yaml').read_text()))
        expected = [('export_password', 'Maestro-test1!'),
                    ('export_confirmation', 'Maestro-test2!'),
                    ('export_confirmation', 'Maestro-test1!')]
        inputs = [(index, command['inputText']) for index, command in enumerate(commands)
                  if isinstance(command, dict) and 'inputText' in command]
        self.assertEqual(len(inputs), len(expected))
        for (index, value), (field, wanted) in zip(inputs, expected):
            tag = 'profile_keys.' + field
            self.assertEqual(value, wanted)
            self.assertEqual(commands[index - 1], {'assertVisible': {'id': tag, 'focused': True}})
            self.assertEqual(commands[index + 1], {'assertVisible': {'id': tag, 'text': '^' + wanted + '$'}})
            self.assertEqual(commands[index + 2], 'hideKeyboard')
        erase = next(index for index, command in enumerate(commands)
                     if isinstance(command, dict) and 'eraseText' in command)
        confirmation = 'profile_keys.export_confirmation'
        self.assertIn({'longPressOn': {'id': confirmation}}, commands[inputs[1][0]:erase])
        self.assertEqual(commands[erase - 1], {'assertVisible': {'id': confirmation, 'focused': True}})
        self.assertEqual(commands[erase + 1], {'assertNotVisible': {'id': confirmation, 'text': '.+'}})
        self.assertLess(inputs[1][0], erase)
        self.assertLess(erase, inputs[2][0])
        for tag in ('view_backup', 'export_file'):
            disabled = {'assertVisible': {'id': 'profile_keys.' + tag, 'enabled': False}}
            enabled = {'assertVisible': {'id': 'profile_keys.' + tag, 'enabled': True}}
            self.assertIn(disabled, commands[inputs[1][0]:erase])
            self.assertIn(enabled, commands[inputs[2][0]:])
        self.assertIn({'tapOn': 'Cancel'}, commands)
        self.assertNotIn({'tapOn': {'id': 'profile_keys.export_file'}}, commands)

    def test_folder_cancel_return_restores_viewport_before_untouched_name_assertion(self):
        """The returned LazyColumn stays at filters; an off-screen name is not a failed dismissal."""
        cases = {name: case for name, case in runtime.CASES.items()
                 if case['suite'] == 'smart-folders' and name != 'smart-folders-draft-rotation-discard'}
        self.assertEqual(len(cases), 8)
        returned = {'runFlow': '../fixtures/return-from-smart-folder-filter.yaml'}
        for name, case in cases.items():
            with self.subTest(case=name):
                _, commands = list(yaml.safe_load_all(
                    (runtime.ROOT / '.maestro/runtime' / (name + '.yaml')).read_text()))
                self.assertEqual(case['postcondition'], 'folder-absent')
                self.assertEqual(commands.count(returned), 1)
                # Preserve initial form discovery; the later return must restore its viewport.
                self.assertEqual(commands.count({'assertVisible': {'id': 'folder.name'}}), 1)
                self.assertEqual(commands[-3:], [returned, 'back', {'assertVisible': {'id': 'chat-folders-content'}}])
        _, commands = list(yaml.safe_load_all(
            (runtime.ROOT / '.maestro/fixtures/return-from-smart-folder-filter.yaml').read_text()))
        self.assertEqual(commands[0], {'assertVisible': {'id': 'chat-folder-edit-content'}})
        for tag in ('folder.addField.PARTICIPANTS', 'folder.conditionDone'):
            self.assertIn({'assertNotVisible': {'id': tag}}, commands[:3])
        scroll = next(index for index, command in enumerate(commands) if 'scrollUntilVisible' in command)
        self.assertEqual(commands[scroll]['scrollUntilVisible'], {'element': {'id': 'folder.name'}, 'direction': 'UP'})
        self.assertEqual(commands[scroll + 1], {'assertVisible': {'id': 'folder.name'}})
        self.assertEqual(commands[scroll + 2], {'assertNotVisible': {'id': 'folder.name', 'text': '.+'}})
        self.assertEqual(commands[scroll + 3], {'assertVisible': {'id': 'folder.save', 'enabled': False}})

    def test_folder_rotation_proves_open_dialog_before_changing_orientation(self):
        """Prove the initial dialog, then handle its observed reappearance on return to portrait."""
        helper = '../fixtures/dismiss-restored-folder-condition.yaml'
        _, dismissal = list(yaml.safe_load_all((runtime.ROOT / '.maestro/fixtures' / Path(helper).name).read_text()))
        retained = dismissal[0]['runFlow']
        self.assertEqual(retained['when'], {'visible': {'id': 'folder.conditionDone'}})
        self.assertIn({'assertVisible': {'id': 'folder.conditionDone', 'enabled': True}}, retained['commands'])
        self.assertIn({'assertVisible': {'id': 'folder.mode'}}, retained['commands'])
        self.assertEqual(retained['commands'][-1], {'tapOn': 'Cancel'})
        self.assertEqual(dismissal[1:], [
            {'assertNotVisible': {'id': 'folder.mode'}},
            {'assertNotVisible': {'id': 'folder.conditionDone'}},
        ])
        for name in ('smart-folders-condition-rotation', 'smart-folders-draft-rotation-discard'):
            with self.subTest(case=name):
                _, commands = list(yaml.safe_load_all((runtime.ROOT / '.maestro/runtime' / f'{name}.yaml').read_text()))
                selected = commands.index({'tapOn': {'id': 'folder.addField.UNREAD'}})
                rotation = commands.index({'setOrientation': 'LANDSCAPE_LEFT'})
                self.assertEqual(commands[selected + 1:rotation], [
                    {'assertVisible': {'id': 'folder.conditionDone', 'enabled': True}},
                    {'assertVisible': {'id': 'folder.mode'}},
                    {'assertNotVisible': {'id': 'folder.addField.PARTICIPANTS'}},
                    {'takeScreenshot': 'smart-folder-condition-before-rotation'},
                ])
                self.assertEqual(commands[rotation + 1], {'takeScreenshot': 'smart-folder-condition-after-rotation'})
                self.assertEqual(commands[rotation + 2], {'runFlow': helper})
                portrait = commands.index({'setOrientation': 'PORTRAIT'})
                self.assertEqual(commands[portrait + 1], {'runFlow': helper})
                self.assertEqual(commands[portrait + 2], {'assertVisible': {'id': 'chat-folder-edit-content'}})
                self.assertIn({'assertNotVisible': {'id': 'folder.group.'}}, commands[portrait:])
                self.assertEqual(runtime.CASES[name]['postcondition'], 'folder-absent')

    def test_private_contact_editing_proves_recipient_focus_and_dismisses_each_ime(self):
        """Reject the captured notes-in-nickname failure and keyboard suggestion Save match."""
        paths = [runtime.ROOT / '.maestro/fixtures/save-external-private-details.yaml']
        paths += [runtime.ROOT / f'.maestro/runtime/profiles-private-{name}.yaml'
                  for name in ('cancel', 'clear', 'boundary', 'rotation', 'account-isolation')]
        edits = 0
        for path in paths:
            _, commands = list(yaml.safe_load_all(path.read_text()))
            for index, command in enumerate(commands):
                if not isinstance(command, dict) and command != 'eraseText':
                    continue
                tap = command.get('tapOn') if isinstance(command, dict) else None
                tap_text = tap.get('text') if isinstance(tap, dict) else tap
                self.assertNotIn(tap_text, ('Save', 'Cancel'), path.name)
                if not isinstance(command, dict) or not {'inputText', 'eraseText'} & command.keys():
                    continue
                edits += 1
                focus = commands[index - 1].get('assertVisible', {})
                tag = focus.get('id')
                with self.subTest(flow=path.name, field=tag):
                    self.assertIn(tag, ('person_profile.nickname', 'person_profile.notes'))
                    self.assertIs(focus.get('focused'), True)
                    if 'inputText' in command:
                        self.assertEqual(commands[index - 2], {'tapOn': {'id': tag}})
                        self.assertEqual(commands[index + 1], {'assertVisible': {'id': tag, 'text': command['inputText']}})
                    else:
                        self.assertEqual(command['eraseText'], 1)
                        self.assertEqual(commands[index - 2], {'tapOn': '(?i)select all'})
                        self.assertEqual(commands[index - 4], {'longPressOn': {'id': tag}})
                        self.assertEqual(commands[index + 1], {'assertNotVisible': {'id': tag, 'text': '.+'}})
                    self.assertEqual(commands[index + 2], 'hideKeyboard')
        self.assertEqual(edits, 11)

    def test_private_clear_selects_both_complete_values_before_backspacing(self):
        """A tap placed the cursor before ve; backspacing a count cannot remove that trailing suffix."""
        path = runtime.ROOT / '.maestro/runtime/profiles-private-clear.yaml'
        _, commands = list(yaml.safe_load_all(path.read_text()))
        selected = []
        for index, command in enumerate(commands):
            if not isinstance(command, dict) or 'eraseText' not in command:
                self.assertNotEqual(command, 'eraseText')
                continue
            tag = commands[index - 4]['longPressOn']['id']
            selected.append(tag)
            self.assertEqual(commands[index - 3], {'runFlow': {
                'when': {'notVisible': '(?i)select all'}, 'commands': [{'tapOn': 'More options'}]}})
            self.assertEqual(commands[index - 2], {'tapOn': '(?i)select all'})
            self.assertEqual(commands[index - 1], {'assertVisible': {'id': tag, 'focused': True}})
            self.assertEqual(command, {'eraseText': 1})
            self.assertEqual(commands[index + 1], {'assertNotVisible': {'id': tag, 'text': '.+'}})
        self.assertEqual(selected, ['person_profile.nickname', 'person_profile.notes'])

    def test_private_boundary_dismisses_only_the_focused_editors_keyboard(self):
        """A second hideKeyboard sent Back after the IME closed, dismissing the unsaved dialog."""
        path = runtime.ROOT / '.maestro/runtime/profiles-private-boundary.yaml'
        _, commands = list(yaml.safe_load_all(path.read_text()))
        self.assertEqual(commands.count('hideKeyboard'), 1)
        index = next(i for i, command in enumerate(commands) if isinstance(command, dict) and 'inputText' in command)
        self.assertEqual(commands[index], {'inputText': 'M' * 81})
        self.assertEqual(commands[index + 1], {'assertVisible': {'id': 'person_profile.nickname', 'text': 'M' * 81}})
        self.assertEqual(commands[index + 2], 'hideKeyboard')
        self.assertEqual(commands[index + 3]['scrollUntilVisible']['element'], {'id': 'person_profile.private_save'})
        self.assertIn({'assertVisible': 'M' * 80}, commands)
        self.assertIn({'assertNotVisible': 'M' * 81}, commands)

    def test_private_contact_public_name_anchor_matches_captured_parenthetical_label(self):
        """The actual profile-name label includes parentheses; a literal-free regex cannot find it."""
        paths = [runtime.ROOT / '.maestro/fixtures/private-dialog-landscape-scroll.yaml']
        paths += [runtime.ROOT / f'.maestro/runtime/profiles-private-{name}.yaml'
                  for name in ('clear', 'account-isolation')]
        for path in paths:
            _, commands = list(yaml.safe_load_all(path.read_text()))
            def anchors(items):
                for item in items:
                    if not isinstance(item, dict):
                        continue
                    for value in item.values():
                        if isinstance(value, str) and value.startswith('Name '):
                            yield value
                        elif isinstance(value, dict):
                            yield from anchors([value])
                        elif isinstance(value, list):
                            yield from anchors(value)
            found = list(anchors(commands))
            self.assertTrue(found, path.name)
            for pattern in found:
                with self.subTest(flow=path.name, pattern=pattern):
                    self.assertIsNotNone(re.fullmatch(pattern, 'Name (from profile): Maestro Dave'))
                    self.assertIsNone(re.fullmatch(pattern, 'Notes'))

    def test_optional_pair_cache_cannot_restore_other_jobs_or_bypass_build_validation(self):
        """Guard the observed foreign release-cache stall without loosening the actual producer gates."""
        workflow = yaml.safe_load((runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text())
        build = workflow['jobs']['maestro-runtime-build']
        setup = next(step for step in build['steps'] if step.get('uses', '').startswith('gradle/actions/setup-gradle@'))
        self.assertTrue(setup['with']['gradle-home-cache-strict-match'])
        self.assertFalse(setup['with']['cache-read-only'])
        self.assertTrue(setup['with'].get('validate-wrappers', True))
        self.assertEqual(build['timeout-minutes'], 30)
        self.assertFalse(build.get('continue-on-error', False))
        self.assertTrue(all(not step.get('continue-on-error', False) for step in build['steps']))
        compile_step = next(step for step in build['steps'] if step.get('id') == 'fixture')
        self.assertIn(':app:assembleDevZapstoreDebugAndroidTest', compile_step['run'])
        self.assertIn('maestro_runtime_pair.py stage', compile_step['run'])
        self.assertEqual(workflow['jobs']['maestro-runtime']['strategy']['max-parallel'], 2)

    def test_every_main_and_settings_route_has_named_cases(self):
        """Reject silent coverage drift in top-level and nested navigation destinations."""
        path = runtime.ROOT / 'config/maestro-screen-coverage.json'
        mapping = json.loads(path.read_text())
        source = (runtime.ROOT / mapping['source']).read_text()
        for enum, key in [('MainSection', 'main_routes'), ('SettingsDetail', 'settings_routes')]:
            body = re.search(r'internal enum class ' + enum + r'\s*\{([^}]+)\}', source).group(1)
            routes = set(re.findall(r'^\s*(\w+),?\s*$', body, re.MULTILINE))
            self.assertEqual(routes, set(mapping[key]))
            for route, cases in mapping[key].items():
                with self.subTest(enum=enum, route=route):
                    self.assertTrue(cases)
                    self.assertTrue(set(cases) <= set(runtime.CASES))

    def test_foreign_or_partial_fixture_receipts_are_rejected(self):
        """Reject stale generations, false flags and incomplete fixture receipts."""
        generation = 'a' * 32
        for flag in ('ready', 'verified', 'closed'):
            self.assertTrue(runtime.receipt(json.dumps({'generation': generation, flag: True}), generation, flag)[flag])
            for value in ({'generation': 'b' * 32, flag: True}, {'generation': generation},
                          {'generation': generation, flag: False}, {'generation': generation, flag: 'true'}):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    runtime.receipt(json.dumps(value), generation, flag)

    def test_executor_rejects_missing_false_or_untyped_native_state_proof(self):
        """Successful UI and teardown cannot certify absent account or real OS-credential proof."""
        generation = 'a' * 32
        name = 'navigation-settings-back'
        requirements = (
            ('account-action-signed-out', 'accountActionVerified', 'verified', 'account action'),
            ('account-action-wiped', 'accountActionVerified', 'verified', 'account action'),
            ('app-lock-unavailable', 'appLockFixtureNoCredential', 'ready', 'app-lock prerequisite'),
            ('app-lock-unavailable', 'appLockVerified', 'verified', 'app-lock state'),
        )
        for postcondition, field, phase, message in requirements:
            for proof in (None, False, 'true', 1, True):
                with self.subTest(postcondition=postcondition, proof=proof), tempfile.TemporaryDirectory() as temporary:
                    process = Mock(returncode=0)
                    process.poll.side_effect = [None, 0, 0]

                    def start(*args, **kwargs):
                        kwargs['stdout'].write('OK (1 test)\n')
                        kwargs['stdout'].flush()
                        return process

                    def command(arguments):
                        if arguments[-2:] == ['clear', runtime.PACKAGE]:
                            return 'Success'
                        path = arguments[-1]
                        if path.endswith('/ready.json'):
                            record = {'generation': generation, 'ready': True, 'accounts': 3,
                                      'fixture': 'basic', 'uiObserver': 'maestro',
                                      'appLockFixtureNoCredential': True}
                            if phase == 'ready':
                                record.pop(field)
                                if proof is not None:
                                    record[field] = proof
                            return json.dumps(record)
                        if path.endswith('/closed.json'):
                            return json.dumps({'generation': generation, 'closed': True})
                        if path.endswith('/verified.json'):
                            record = {'generation': generation, 'verified': True, 'appLockVerified': True}
                            if phase == 'verified':
                                record.pop(field, None)
                                if proof is not None:
                                    record[field] = proof
                            return json.dumps(record)
                        return ''

                    def ui(case, directory):
                        (directory / 'junit.xml').write_text(
                            f'<testsuite><testcase name="{case}" status="SUCCESS" time="1"/></testsuite>')
                        return Mock(returncode=0)

                    with patch.dict(runtime.CASES, {name: {**runtime.CASES[name], 'postcondition': postcondition}}), \
                         patch.object(runtime.uuid, 'uuid4', return_value=Mock(hex=generation)), \
                         patch.object(runtime, 'command', side_effect=command), \
                         patch.object(runtime.subprocess, 'Popen', side_effect=start), \
                         patch.object(runtime, 'run_ui', side_effect=ui):
                        result = runtime.run_case(name, Path(temporary))
                    self.assertTrue(result['cleanup_safe'])
                    self.assertEqual(result['passed'], proof is True)
                    if proof is not True:
                        self.assertIn(message, result['failure' if phase == 'ready' else 'cleanup_failure'])

    def test_missing_duplicate_skipped_wrong_and_failed_ui_results_are_rejected(self):
        """Require exactly the selected named assertion without skipped or failure children."""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'junit.xml'
            for count, name, status, problem in ((0, 'case', 'SUCCESS', None), (2, 'case', 'SUCCESS', None),
                                                (1, 'other', 'SUCCESS', None), (1, 'case', None, None),
                                                (1, 'case', 'SUCCESS', 'skipped'), (1, 'case', 'SUCCESS', 'failure')):
                root = ET.Element('testsuite')
                for _ in range(count):
                    case = ET.SubElement(root, 'testcase', name=name)
                    if status:
                        case.set('status', status)
                    if problem:
                        ET.SubElement(case, problem)
                ET.ElementTree(root).write(path)
                with self.subTest(count=count, name=name, status=status, problem=problem), self.assertRaises(ValueError):
                    runtime.ui_result(path, 'case')

    def test_invalid_durations_cannot_be_success_evidence(self):
        """Reject invalid numeric evidence even when the UI status claims success."""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'junit.xml'
            for value in ('NaN', 'Infinity', '-1', 'invalid'):
                path.write_text(f'<testsuite><testcase name="case" status="SUCCESS" time="{value}"/></testsuite>')
                with self.subTest(value=value), self.assertRaises(ValueError):
                    runtime.ui_result(path, 'case')
            path.write_text('<testsuite><testcase name="case" status="SUCCESS" time="1.25"/></testsuite>')
            self.assertEqual(runtime.ui_result(path, 'case'), 1.25)

    def test_unsafe_teardown_names_every_remaining_unexecuted_case(self):
        """Stop safely and keep every unexecuted case visible after uncertified teardown."""
        self.campaign_result(cleanup_safe=False, expected_calls=1)

    def test_clean_ui_failure_does_not_hide_later_cases(self):
        """Continue after safe teardown while preserving the failed overall verdict."""
        self.campaign_result(cleanup_safe=True, expected_calls=4)

    def campaign_result(self, cleanup_safe, expected_calls):
        """Exercise failure continuation and inspect the persisted complete case ledger."""
        selected = runtime.case_selection('navigation', 1)
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary) / 'report'
            def execute(name, _reports):
                """Model one case outcome to isolate campaign bookkeeping from emulator execution."""
                return {'case': name, 'passed': name != selected[0], 'cleanup_safe': cleanup_safe}
            with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), \
                 patch('sys.argv', ['runtime', '--suite', 'navigation', '--reports', str(reports)]), \
                 patch.object(runtime, 'command', return_value='1'), \
                 patch.object(runtime, 'run_case', side_effect=execute) as runner, \
                 self.assertRaises(SystemExit) as exited:
                runtime.main()
            self.assertEqual(exited.exception.code, 1)
            self.assertEqual(runner.call_count, expected_calls)
            summary = json.loads((reports / 'results.json').read_text())
            self.assertFalse(summary['evidence_complete'])
            self.assertEqual([result['case'] for result in summary['results']], selected)
            self.assertEqual(sum(bool(result.get('not_run')) for result in summary['results']), len(selected) - expected_calls)

    def test_pair_rejects_stale_source_wrong_run_and_changed_apk(self):
        """Prevent mismatched source revisions and substituted APKs from sharing evidence."""
        env = {'GITHUB_ACTIONS': 'true', 'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '1', 'GITHUB_RUN_ATTEMPT': '1'}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / 'app.apk').write_bytes(b'app')
            (directory / 'test.apk').write_bytes(b'test')
            pair(directory, 'stage', env)
            pair(directory, 'verify', env)
            for key, value in [('GITHUB_SHA', 'b' * 40), ('GITHUB_RUN_ID', '2'), ('GITHUB_RUN_ATTEMPT', '2')]:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    pair(directory, 'verify', {**env, key: value})
            (directory / 'test.apk').write_bytes(b'changed')
            with self.assertRaises(ValueError):
                pair(directory, 'verify', env)

    def test_failed_fixture_data_reset_never_launches_a_host(self):
        """Prevent a fresh-generation claim when Android did not clear isolated global stores."""
        with tempfile.TemporaryDirectory() as temporary:
            with patch.object(runtime, 'command', return_value='Failed'), \
                 patch.object(runtime.subprocess, 'Popen') as start, self.assertRaises(ValueError):
                runtime.run_case('navigation-settings-back', Path(temporary))
            start.assert_not_called()

    def test_failed_notification_reset_never_launches_a_host(self):
        """A preceding permission decision must not silently leak into the next generation."""
        for reset_step in (1, 2):
            with self.subTest(reset_step=reset_step), tempfile.TemporaryDirectory() as temporary:
                results = ['Success'] + [''] * (reset_step - 1)
                results.append(subprocess.CalledProcessError(1, 'permission reset'))
                with patch.object(runtime, 'command', side_effect=results), \
                     patch.object(runtime.subprocess, 'Popen') as start, self.assertRaises(subprocess.CalledProcessError):
                    runtime.run_case('navigation-settings-back', Path(temporary))
                start.assert_not_called()

    def test_runtime_cases_never_kill_or_replace_the_instrumentation_host(self):
        """Keep UI flows inside their native host lifetime and permanent acceptance scope."""
        ids = definitions(load_guide(runtime.ROOT))
        for name, case in runtime.CASES.items():
            with self.subTest(case=name):
                text = (runtime.ROOT / '.maestro/runtime' / f'{name}.yaml').read_text()
                header, commands = list(yaml.safe_load_all(text))
                self.assertEqual(header['appId'], runtime.PACKAGE)
                self.assertEqual(header['name'], name)
                self.assertTrue(commands)
                self.assertNotIn('launchApp', text)
                self.assertNotIn('stopApp', text)
                self.assertNotIn('clearState', text)
                self.assertNotIn('point:', text)
                self.assertNotIn('openLink:', text)
                self.assertTrue(case['assertions'])
                self.assertTrue(set(case['manual_ids']) <= set(ids), case['manual_ids'])

    def test_switch_assertions_use_checked_accessibility_state(self):
        """Regression: selected is not Android switch state and can prevent actual teardown."""
        paths = [('.maestro/fixtures/assert-no-credential-privacy.yaml', [False]),
                 ('.maestro/runtime/accounts-signout-keep-invitations.yaml', [True, False]),
                 ('.maestro/runtime/accounts-signout-warm-resume.yaml', [True, False])]
        for path, expected in paths:
            _, commands = list(yaml.safe_load_all((runtime.ROOT / path).read_text()))
            states = []
            for command in commands:
                node = command.get('assertVisible') if isinstance(command, dict) else None
                if isinstance(node, dict) and (node.get('id') == 'profile_keys.signout_invitation_keys'
                                              or node.get('text') == 'Require device authentication'):
                    self.assertNotIn('selected', node)
                    self.assertIs(type(node.get('checked')), bool)
                    states.append(node['checked'])
            self.assertEqual(states, expected)

    def test_settings_entry_uses_real_multi_identity_selector(self):
        """Regress the hosted tree where the avatar says Switch Profile rather than Open settings."""
        helper = (runtime.ROOT / '.maestro/fixtures/open-settings.yaml').read_text()
        self.assertIn('Switch Profile, Maestro (Alice|Bob|Carol)', helper)
        self.assertIn('- tapOn: "Settings"', helper)
        for path in (runtime.ROOT / '.maestro/runtime').glob('*.yaml'):
            self.assertNotIn('Open settings.*', path.read_text(), path.name)

    def test_emulator_interruption_guard_is_limited_to_the_external_launcher(self):
        """Never hide a tested-app crash behind the disposable launcher's known boot interruption."""
        path = runtime.ROOT / '.maestro/fixtures/emulator-interruption.yaml'
        _, commands = list(yaml.safe_load_all(path.read_text()))
        self.assertEqual(commands, [{'runFlow': {'when': {'visible': "Pixel Launcher isn't responding"},
                                                'commands': [{'tapOn': 'Close app'}]}}])
        for title in ("Maestro Test Lab isn't responding", "White Noise isn't responding", 'App keeps stopping'):
            self.assertIsNone(re.fullmatch(commands[0]['runFlow']['when']['visible'], title))

    def test_every_runtime_subflow_exists_and_preserves_the_live_host(self):
        """Shared navigation helpers cannot silently refer to absent flows or reset the fixture."""
        for path in (runtime.ROOT / '.maestro/runtime').glob('*.yaml'):
            _, commands = list(yaml.safe_load_all(path.read_text()))
            for item in commands:
                if not isinstance(item, dict) or not isinstance(item.get('runFlow'), str):
                    continue
                reference = item['runFlow']
                with self.subTest(flow=path.name, reference=reference):
                    target = (path.parent / reference).resolve()
                    self.assertEqual(target.parent, runtime.ROOT / '.maestro/fixtures')
                    header, _ = list(yaml.safe_load_all(target.read_text()))
                    self.assertEqual(header['appId'], runtime.PACKAGE)
                    if target.name == 'runtime-warm-resume.yaml':
                        _, resume = list(yaml.safe_load_all(target.read_text()))
                        self.assertEqual(resume, [
                            {'pressKey': 'Home'},
                            {'launchApp': {'stopApp': False, 'clearState': False, 'permissions': {'all': 'deny'}}},
                        ])
                        continue
                    for forbidden in ('launchApp', 'stopApp', 'clearState', 'point:', 'openLink:'):
                        self.assertNotIn(forbidden, target.read_text())

    def test_unknown_slices_are_rejected_before_building(self):
        """Reject invalid selection; logical suites may grow while execution shards stay bounded."""
        self.assertEqual(set(selection('runtime-all')), set(runtime.SUITES))
        self.assertEqual(selection('runtime-settings'), ['settings'])
        for value in ('runtime-../other', 'all', 'runtime-settings\n', 'runtime-'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                selection(value)
        for name in runtime.SUITES:
            self.assertGreater(sum(case['suite'] == name for case in runtime.CASES.values()), 0)

    def test_partitions_cover_every_logical_case_once(self):
        """Bound each execution shard without dropping or duplicating any selected journey."""
        expected = list(runtime.CASES)
        actual = []
        for item in matrix_selection('runtime-all'):
            cases = runtime.case_selection(item['slice'], item['partition'])
            self.assertTrue(1 <= len(cases) <= 4)
            actual.extend(cases)
        self.assertCountEqual(actual, expected)
        self.assertEqual(len(actual), len(set(actual)))
        for part in (0, -1, 99):
            with self.subTest(partition=part), self.assertRaises(ValueError):
                runtime.case_selection('navigation', part)

    def test_full_partition_admits_every_case_at_its_reserved_ceiling(self):
        """Worst-case reserved durations must leave enough time to admit the entire selected partition."""
        selected = runtime.case_selection('navigation', 1)
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary) / 'report'
            ticks = [0] + [index * runtime.CASE_RESERVE_SECONDS for index in range(len(selected))]
            with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), \
                 patch('sys.argv', ['runtime', '--suite', 'navigation', '--reports', str(reports)]), \
                 patch.object(runtime, 'command', return_value='1'), \
                 patch.object(runtime.time, 'monotonic', side_effect=ticks), \
                 patch.object(runtime, 'run_case', side_effect=lambda name, _: {
                     'case': name, 'passed': True, 'cleanup_safe': True}) as runner:
                runtime.main()
            self.assertEqual(runner.call_count, len(selected))
            summary = json.loads((reports / 'results.json').read_text())
            self.assertTrue(summary['evidence_complete'])
            self.assertEqual([case['case'] for case in summary['results']], selected)

    def test_campaign_budget_refuses_a_case_without_a_cleanup_window(self):
        """Fail with explicit unexecuted cases before a CI timeout can interrupt teardown."""
        with tempfile.TemporaryDirectory() as temporary:
            reports = Path(temporary) / 'report'
            with patch.dict('os.environ', {'GITHUB_ACTIONS': 'true'}), \
                 patch('sys.argv', ['runtime', '--suite', 'navigation', '--reports', str(reports)]), \
                 patch.object(runtime, 'command', return_value='1'), \
                 patch.object(runtime.time, 'monotonic', side_effect=[0, runtime.CAMPAIGN_SECONDS]), \
                 patch.object(runtime, 'run_case') as runner, self.assertRaises(SystemExit):
                runtime.main()
            runner.assert_not_called()
            summary = json.loads((reports / 'results.json').read_text())
            self.assertFalse(summary['evidence_complete'])
            self.assertEqual(len(summary['results']), 4)
            self.assertTrue(all(result['not_run'] for result in summary['results']))
            self.assertTrue(all('budget' in result['failure'] for result in summary['results']))

    def test_fixture_code_and_builds_remain_opt_in(self):
        """Keep native fixture builds manual and fixture code outside production APK source."""
        workflow = yaml.safe_load((runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text())
        options = workflow.get('on', workflow.get(True))['workflow_dispatch']['inputs']['maestro_suite']['options']
        self.assertTrue({'runtime-' + suite for suite in runtime.SUITES}.issubset(set(options)))
        jobs = workflow['jobs']
        self.assertIn("github.event_name == 'workflow_dispatch'", jobs['maestro-runtime-build']['if'])
        self.assertIn("startsWith(inputs.maestro_suite, 'runtime-')", jobs['maestro-runtime-build']['if'])
        self.assertEqual(jobs['maestro-runtime']['needs'], 'maestro-runtime-build')
        self.assertNotIn('gradlew', json.dumps(jobs['maestro-runtime']))
        self.assertEqual(jobs['maestro-runtime']['strategy']['max-parallel'], 2)
        self.assertGreater(jobs['maestro-runtime']['timeout-minutes'] * 60,
                           runtime.CAMPAIGN_SECONDS + 300 + runtime.CASE_RESERVE_SECONDS)
        self.assertEqual(jobs['maestro-runtime']['env']['MAESTRO_PAIR_PRODUCER_ATTEMPT'],
                         '${{ needs.maestro-runtime-build.outputs.pair_attempt }}')
        download = next(step for step in jobs['maestro-runtime']['steps'] if 'download-artifact' in step.get('uses', ''))
        self.assertEqual(download['with']['name'], '${{ needs.maestro-runtime-build.outputs.pair_artifact }}')
        self.assertIn('include', jobs['maestro-runtime']['strategy']['matrix'])
        emulator = next(step for step in jobs['maestro-runtime']['steps'] if 'android-emulator-runner' in step.get('uses', ''))
        self.assertEqual(emulator['with']['emulator-boot-timeout'], 300)
        fixture_build = (runtime.ROOT / 'scripts/maestro-runtime.init.gradle').read_text()
        self.assertIn("'ENABLE_PERFORMANCE_TEST_SELECTORS', 'true'", fixture_build)
        for path in (runtime.ROOT / 'app/src/main').rglob('*MaestroFixture*'):
            self.fail(f'Fixture must remain outside app APK: {path}')

class CredentialControlTest(unittest.TestCase):
    """Never certify mocked unlocks, stale OS probes or a credential left behind by failed setup."""
    generation = 'a' * 32
    environment = {'GITHUB_ACTIONS': 'true', 'MAESTRO_ANDROID_API': '34',
                   'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '123'}

    def row(self, stage, secure=False):
        return {'schema': 1, 'generation': self.generation, 'stage': stage, 'package': runtime.PACKAGE,
                'user': 0, 'sdk': 34, 'qemu': True, 'noBiometricAlternative': True,
                'secure': secure, 'credentialAvailable': secure}

    def output(self, row):
        return 'INSTRUMENTATION_STATUS: maestroCredential=' + json.dumps(row) + '\nOK (1 test)\n'

    def trace(self):
        return '\n'.join('10-08 12:00:00.001  456  789 I WNAppUnlock: activity=10 session=' + s
                         for s in ['1 event=prompt-launched', '1 event=prompt-terminated',
                                   '2 event=prompt-launched', '2 event=prompt-succeeded'])

    def evidence(self, rotated=False):
        cancelled = {'cover': True, 'secure': True, 'cancelled': True, 'evaluating': False,
                     'activeSession': None, 'latestSession': 1, 'required': True,
                     'available': True, 'orientation': 1, 'delay': 'immediately',
                     'storedDelay': None, 'lifecycle': 'RESUMED'}
        final = {**cancelled, 'cover': False, 'cancelled': False, 'latestSession': 2}
        rows = [cancelled]
        if rotated:
            rows.extend([{**cancelled, 'orientation': 2}, dict(cancelled)])
        rows.append(final)
        return {'cancelledSecure': True, 'rotatedCover': rotated, 'cancelledSession': 1,
                'acceptedSession': 2, 'acceptedState': True, 'disabledWarmReturn': False,
                'delayChoicesVerified': False, 'observations': rows}

    def test_native_probe_requires_completed_exact_typed_os_observation(self):
        from scripts import maestro_credential as credential
        row = self.row('baseline')
        self.assertEqual(credential.probe_record(self.output(row), self.generation, 'baseline'), row)
        for key, value in [('schema', True), ('generation', 'b' * 32), ('stage', 'installed'),
                           ('user', 1), ('user', False), ('sdk', 36), ('qemu', 1),
                           ('noBiometricAlternative', False), ('secure', 0), ('credentialAvailable', 'false')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                credential.probe_record(self.output({**row, key: value}), self.generation, 'baseline')
        for output in ['', self.output(row).replace('OK (1 test)', 'FAILURES!!!'), self.output(row) * 2]:
            with self.subTest(output=output), self.assertRaises(ValueError):
                credential.probe_record(output, self.generation, 'baseline')

    def test_preexisting_credential_never_reaches_set_or_clear(self):
        from scripts import maestro_credential as credential
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, self.environment), \
                patch.object(credential, 'command') as command:
            owner = credential.DisposableCredential(self.generation, Path(temporary))
            with patch.object(owner, 'observe', return_value=self.row('baseline', True)):
                with self.assertRaisesRegex(ValueError, 'Pre-existing'):
                    owner.install()
                with self.assertRaisesRegex(ValueError, 'No admitted baseline'):
                    owner.restore()
            command.assert_not_called()

    def test_wrong_host_or_api_cannot_reach_native_probe(self):
        from scripts import maestro_credential as credential
        for key, value in [('GITHUB_ACTIONS', 'false'), ('MAESTRO_ANDROID_API', '36'),
                           ('GITHUB_SHA', ''), ('GITHUB_RUN_ID', '')]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as temporary, \
                    patch.dict(os.environ, {**self.environment, key: value}):
                owner = credential.DisposableCredential(self.generation, Path(temporary))
                with patch.object(owner, 'observe') as probe, self.assertRaises(ValueError):
                    owner.install()
                probe.assert_not_called()

    def test_uncertain_installation_is_read_back_and_restored_without_replay(self):
        from scripts import maestro_credential as credential
        calls = []
        def command(arguments, **options):
            calls.append(arguments)
            if 'set-pin' in arguments:
                raise subprocess.TimeoutExpired(arguments, 15)
            if 'verify' in arguments:
                return 'Lock credential verified successfully'
            return 'Lock credential cleared'
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, self.environment), \
                patch.object(credential, 'command', side_effect=command):
            owner = credential.DisposableCredential(self.generation, Path(temporary))
            with patch.object(owner, 'observe', side_effect=[self.row('baseline'), self.row('installed', True),
                                                            self.row('before-clear', True), self.row('restored')]):
                with self.assertRaisesRegex(ValueError, 'Uncertain'):
                    owner.install()
                self.assertTrue(owner.owned)
                owner.restore()
                self.assertTrue(json.loads((Path(temporary) / 'credential-restored.json').read_text())['credentialRestored'])
                with self.assertRaisesRegex(ValueError, 'cannot be replayed'):
                    owner.install()
                with self.assertRaisesRegex(ValueError, 'cannot be replayed'):
                    owner.restore()
        self.assertEqual(sum('set-pin' in call for call in calls), 1)
        self.assertEqual(sum('clear' in call for call in calls), 1)
        installation = next(call for call in calls if 'set-pin' in call)
        self.assertNotIn('--old', installation)
        self.assertTrue(all(call[:3] == ['adb', '-s', 'emulator-5554'] for call in calls))

    def test_unknown_or_foreign_credential_cannot_be_cleared(self):
        from scripts import maestro_credential as credential
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, self.environment), \
                patch.object(credential, 'command', return_value='Credential did not match') as command:
            owner = credential.DisposableCredential(self.generation, Path(temporary))
            with patch.object(owner, 'observe', side_effect=[self.row('baseline'), self.row('installed', True),
                                                            self.row('before-clear', True), self.row('installed', True)]):
                with self.assertRaisesRegex(ValueError, 'does not belong'):
                    owner.install()
                with self.assertRaisesRegex(ValueError, 'does not belong'):
                    owner.restore()
            self.assertFalse(any('clear' in call.args[0] for call in command.call_args_list))
            self.assertFalse((Path(temporary) / 'credential-restored.json').exists())

    def test_verified_pin_can_be_restored_even_when_app_authentication_is_unavailable(self):
        from scripts import maestro_credential as credential
        unavailable = {**self.row('installed', True), 'credentialAvailable': False}
        with tempfile.TemporaryDirectory() as temporary, patch.dict(os.environ, self.environment), \
                patch.object(credential, 'command', return_value='Lock credential verified successfully') as command:
            owner = credential.DisposableCredential(self.generation, Path(temporary))
            with patch.object(owner, 'observe', side_effect=[self.row('baseline'), unavailable,
                                                            self.row('before-clear', True), self.row('restored')]):
                with self.assertRaisesRegex(ValueError, 'unavailable to real app'):
                    owner.install()
                self.assertTrue(owner.owned)
                owner.restore()
            self.assertEqual(sum('clear' in call.args[0] for call in command.call_args_list), 1)

    def test_uncertain_clear_requires_actual_native_restoration_and_cannot_retry(self):
        from scripts import maestro_credential as credential
        for still_secure in (False, True):
            with self.subTest(still_secure=still_secure), tempfile.TemporaryDirectory() as temporary, \
                    patch.object(credential, 'command', side_effect=subprocess.TimeoutExpired('clear', 15)) as command:
                owner = credential.DisposableCredential(self.generation, Path(temporary))
                owner.admitted = owner.attempted = owner.owned = True
                with patch.object(owner, 'observe', side_effect=[self.row('before-clear', True),
                                                                self.row('restored', still_secure)]):
                    if still_secure:
                        with self.assertRaisesRegex(ValueError, 'not independently certified'):
                            owner.restore()
                    else:
                        owner.restore()
                    with self.assertRaisesRegex(ValueError, 'cannot be replayed'):
                        owner.restore()
                self.assertEqual(command.call_count, 1)
                self.assertEqual((Path(temporary) / 'credential-restored.json').exists(), not still_secure)

    def test_credential_cleanup_covers_early_fixture_failure_and_invalidates_unsafe_success(self):
        from scripts import maestro_credential as credential
        for early_failure, restoration_failure in [(True, False), (False, True), (False, False)]:
            with self.subTest(early_failure=early_failure, restoration_failure=restoration_failure), \
                    tempfile.TemporaryDirectory() as temporary:
                owner = Mock()
                if restoration_failure:
                    owner.restore.side_effect = ValueError('OS restoration unavailable')
                returned = {'case': 'navigation-settings-back', 'generation': self.generation,
                            'passed': True, 'cleanup_safe': True}
                with patch.dict(runtime.CASES, {'navigation-settings-back': {
                        **runtime.CASES['navigation-settings-back'], 'postcondition': 'app-lock-credential-retry'}}), \
                        patch.object(runtime.uuid, 'uuid4', return_value=Mock(hex=self.generation)), \
                        patch.object(runtime, 'DisposableCredential', return_value=owner), \
                        patch.object(runtime, 'run_fixture', side_effect=ValueError('Early setup failure')
                                     if early_failure else None, return_value=returned):
                    result = runtime.run_case('navigation-settings-back', Path(temporary))
                owner.install.assert_called_once()
                owner.restore.assert_called_once()
                self.assertEqual(result['passed'], not early_failure and not restoration_failure)
                self.assertEqual(result['cleanup_safe'], not early_failure and not restoration_failure)
                self.assertEqual(json.loads((Path(temporary) / 'navigation-settings-back/result.json').read_text()), result)

    def test_accepted_callback_requires_same_process_ordered_cancel_and_later_crypto_success(self):
        from scripts import maestro_credential as credential
        self.assertEqual(credential.accepted_unlock(self.trace(), 456)['acceptedSession'], 2)
        for output, pid in [(self.trace(), 457), (self.trace(), True), (self.trace(), '456'),
                            (self.trace().replace('2 event=prompt-succeeded', '1 event=prompt-succeeded'), 456),
                            (self.trace().replace('prompt-succeeded', 'stale-success-ignored'), 456),
                            (self.trace() + '\n' + self.trace(), 456),
                            ('\n'.join(reversed(self.trace().splitlines())), 456),
                            (self.trace().replace('activity=10 session=2', 'activity=20 session=2'), 456)]:
            with self.subTest(output=output, pid=pid), self.assertRaises(ValueError):
                credential.accepted_unlock(output, pid)

    def test_native_credential_evidence_rejects_generic_success_untyped_or_unsecured_cover(self):
        from scripts import maestro_credential as credential
        ready = {'appLockFixtureCredential': True, 'nativePid': 456}
        for rotated in (False, True):
            postcondition = 'app-lock-credential-rotation' if rotated else 'app-lock-credential-retry'
            verified = {'appLockVerified': True, 'credentialEvidence': self.evidence(rotated)}
            credential.credential_state(ready, verified, postcondition)
            for field, value in [('cancelledSecure', False), ('cancelledSecure', 1), ('acceptedState', 'true'),
                                  ('acceptedSession', True), ('acceptedSession', 1), ('rotatedCover', not rotated)]:
                with self.subTest(field=field, rotated=rotated), self.assertRaises(ValueError):
                    credential.credential_state(ready, {**verified, 'credentialEvidence': {
                        **verified['credentialEvidence'], field: value}}, postcondition)
            for field, value in [('secure', False), ('secure', 1), ('activeSession', 1), ('cancelled', False)]:
                changed = self.evidence(rotated)
                changed['observations'] = [{**row, field: value} for row in changed['observations']]
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    credential.credential_state(ready, {**verified, 'credentialEvidence': changed}, postcondition)
            with self.assertRaises(ValueError):
                credential.credential_state({**ready, 'nativePid': '456'}, verified, postcondition)

    def test_complete_credential_chain_rejects_forged_restoration_foreign_callback_and_symlink(self):
        from scripts import maestro_credential as credential
        with tempfile.TemporaryDirectory() as temporary:
            leaf = Path(temporary)
            for stage, secure in [('baseline', False), ('installed', True), ('restored', False)]:
                row = self.row(stage, secure)
                (leaf / f'credential-probe-{stage}.txt').write_text(self.output(row))
                (leaf / f'credential-{stage}.json').write_text(json.dumps(row))
            restored = {**self.row('restored'), 'credentialRestored': True}
            (leaf / 'credential-restored.json').write_text(json.dumps(restored))
            (leaf / 'credential-ownership.txt').write_text('Lock credential verified successfully')
            (leaf / 'app-unlock-trace.txt').write_text(self.trace())
            (leaf / 'credential-accepted.json').write_text(json.dumps({
                'generation': self.generation, **credential.accepted_unlock(self.trace(), 456)}))
            ready = {'appLockFixtureCredential': True, 'nativePid': 456}
            verified = {'appLockVerified': True, 'credentialEvidence': self.evidence()}
            credential.qualify_credential(leaf, self.generation, ready, verified, 'app-lock-credential-retry')
            for field, value in [('generation', 'b' * 32), ('credentialRestored', 1),
                                  ('secure', True), ('user', True)]:
                (leaf / 'credential-restored.json').write_text(json.dumps({**restored, field: value}))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    credential.qualify_credential(leaf, self.generation, ready, verified, 'app-lock-credential-retry')
            (leaf / 'credential-restored.json').write_text(json.dumps(restored))
            trace = leaf / 'app-unlock-trace.txt'
            saved = leaf / 'saved-trace.txt'
            trace.rename(saved)
            trace.symlink_to(saved)
            with self.assertRaisesRegex(ValueError, 'regular'):
                credential.qualify_credential(leaf, self.generation, ready, verified, 'app-lock-credential-retry')

    def test_unsupported_pin_platform_is_rejected_before_the_fixture_build(self):
        from scripts.maestro_runtime_selection import validate_credential_platform
        for suite in ('runtime-all', 'runtime-app-lock'):
            validate_credential_platform(suite, '34')
            for api in ('33', '36', '37.0'):
                with self.subTest(suite=suite, api=api), self.assertRaisesRegex(ValueError, 'require API34'):
                    validate_credential_platform(suite, api)
        for api in ('33', '34', '36', '37.0'):
            validate_credential_platform('runtime-navigation', api)
        workflow = (runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text()
        self.assertIn('maestro_runtime_selection "$SELECTED" "$ANDROID_API"', workflow)

    def test_warm_disabling_needs_real_second_pin_and_ordered_disabled_lifecycle(self):
        from scripts import maestro_credential as credential
        ready = {'appLockFixtureCredential': True, 'nativePid': 456}
        evidence = self.evidence()
        final = {**evidence['observations'][-1], 'latestSession': 3, 'required': False}
        evidence.update(acceptedSession=3, disabledWarmReturn=True)
        evidence['observations'].extend([{**final, 'lifecycle': 'CREATED'}, final])
        verified = {'appLockVerified': True, 'credentialEvidence': evidence}
        credential.credential_state(ready, verified, 'app-lock-credential-warm-disabled')
        trace = self.trace() + '\n' + '\n'.join([
            '10-08 12:00:01.001  456  789 I WNAppUnlock: activity=10 session=3 event=prompt-launched',
            '10-08 12:00:02.001  456  789 I WNAppUnlock: activity=10 session=3 event=prompt-succeeded'])
        self.assertEqual(credential.accepted_unlock(trace, 456, 2)['acceptedSessions'], [2, 3])
        for output, count in [(trace, 1), (self.trace(), 2), (trace, True),
                              (trace.replace('3 event=prompt-launched', '2 event=prompt-launched'), 2)]:
            with self.subTest(output=output, count=count), self.assertRaises(ValueError):
                credential.accepted_unlock(output, 456, count)
        interleaved = trace.splitlines()
        interleaved[3], interleaved[4] = interleaved[4], interleaved[3]
        with self.assertRaisesRegex(ValueError, 'preceding session'):
            credential.accepted_unlock('\n'.join(interleaved), 456, 2)
        for key, value in [('disabledWarmReturn', False), ('disabledWarmReturn', 1), ('acceptedSession', 2)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                credential.credential_state(ready, {**verified, 'credentialEvidence': {
                    **evidence, key: value}}, 'app-lock-credential-warm-disabled')
        for deleted in (-1, -2):
            altered = json.loads(json.dumps(evidence))
            altered['observations'].pop(deleted)
            with self.subTest(deleted=deleted), self.assertRaises(ValueError):
                credential.credential_state(ready, {**verified, 'credentialEvidence': altered},
                                            'app-lock-credential-warm-disabled')
        altered = json.loads(json.dumps(evidence))
        altered['observations'][-2]['cover'] = True
        with self.assertRaisesRegex(ValueError, 'new lock/session'):
            credential.credential_state(ready, {**verified, 'credentialEvidence': altered},
                                        'app-lock-credential-warm-disabled')

    def test_delay_picker_requires_all_explicit_persisted_choices_in_order(self):
        from scripts import maestro_credential as credential
        ready = {'appLockFixtureCredential': True, 'nativePid': 456}
        evidence = self.evidence()
        final = evidence['observations'][-1]
        evidence.update(delayChoicesVerified=True)
        evidence['observations'].extend([{**final, 'delay': value, 'storedDelay': value}
                                        for value in ('immediately', '1m', '5m', '15m')])
        verified = {'appLockVerified': True, 'credentialEvidence': evidence}
        credential.credential_state(ready, verified, 'app-lock-credential-delay')
        for removed in range(2, 6):
            altered = json.loads(json.dumps(evidence))
            altered['observations'].pop(removed)
            with self.subTest(removed=removed), self.assertRaises(ValueError):
                credential.credential_state(ready, {**verified, 'credentialEvidence': altered},
                                            'app-lock-credential-delay')
        for index, key, value in [(2, 'storedDelay', None), (3, 'storedDelay', '5m'),
                                  (-1, 'storedDelay', None), (-1, 'delay', '1m'),
                                  (-1, 'lifecycle', True)]:
            altered = json.loads(json.dumps(evidence))
            altered['observations'][index][key] = value
            with self.subTest(index=index, key=key), self.assertRaises(ValueError):
                credential.credential_state(ready, {**verified, 'credentialEvidence': altered},
                                            'app-lock-credential-delay')
        with self.assertRaises(ValueError):
            credential.credential_state(ready, {**verified, 'credentialEvidence': {
                **evidence, 'delayChoicesVerified': 1}}, 'app-lock-credential-delay')

    def test_app_lock_extended_flows_reuse_real_pin_controls_without_state_or_clock_injection(self):
        root = runtime.ROOT / '.maestro'
        warm = (root / 'runtime/app-lock-credential-warm-resume-disable.yaml').read_text()
        self.assertEqual(warm.count('runFlow: ../fixtures/runtime-warm-resume.yaml'), 2)
        self.assertIn('runFlow: ../fixtures/enter-device-pin.yaml', warm)
        self.assertEqual(runtime.CASES['app-lock-credential-warm-resume-disable']['manual_ids'],
                         ['SEC-002', 'SEC-004', 'NAV-010'])
        delay = (root / 'runtime/app-lock-credential-delay-picker-return.yaml').read_text()
        for label in ('Immediately', 'After 1 minute', 'After 5 minutes', 'After 15 minutes'):
            self.assertIn(label, delay)
        self.assertIn('LANDSCAPE_LEFT', delay)
        self.assertIn('PORTRAIT', delay)
        self.assertEqual(delay.count('- tapOn: Cancel'), 2)
        commands = list(yaml.safe_load_all(delay))[1]
        self.assertTrue(any('assertNotVisible' in command and command['assertNotVisible'] == 'Cancel'
                            for command in commands if isinstance(command, dict)))
        for text in (warm, delay):
            for forbidden in ('clearState:', 'runScript:', 'evalScript:', 'stopApp:', 'setAppLockDelay',
                              'credentialAvailableOverride', 'markAppUnlockSucceeded'):
                if forbidden == 'stopApp:':
                    self.assertNotIn('stopApp: true', text)
                else:
                    self.assertNotIn(forbidden, text)

    def test_restoration_commands_share_a_bounded_deadline(self):
        from scripts import maestro_credential as credential
        with tempfile.TemporaryDirectory() as temporary, patch.object(credential.time, 'monotonic', return_value=61), \
                patch.object(credential, 'command') as command:
            owner = credential.DisposableCredential(self.generation, Path(temporary))
            owner.restore_deadline = 60
            with self.assertRaisesRegex(TimeoutError, 'deadline'):
                owner.execute(['adb'])
            command.assert_not_called()


if __name__ == '__main__':
    unittest.main()
