"""Regression contracts for fixture isolation, result identity and manual-only admission."""

import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import yaml

from scripts import maestro_runtime as runtime
from scripts.maestro_coverage import inventory, screen_catalog
from scripts.maestro_runtime_summary import campaign
from scripts.maestro_runtime_pair import pair
from scripts.maestro_runtime_selection import selection, matrix_selection
from scripts.manual_test_fragments import definitions, load_guide


class CampaignSummaryTest(unittest.TestCase):
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
            leaf = target / 'maestro-runtime-navigation-1' / runtime.case_selection('navigation', 1)[0]
            (leaf / 'junit.xml').unlink()
            result = campaign(root, 'runtime-navigation', 'a' * 40, '123', '2')
            self.assertFalse(result['evidence_complete'])
            self.assertEqual(result['passed_count'], 5)

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
            path.write_text('internal fun NewScreen() {}')
            with self.assertRaisesRegex(ValueError, 'no maintained source'):
                screen_catalog(root, {}, {})

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

    def test_every_settings_route_has_named_cases(self):
        """Reject silent coverage drift when a new Settings destination has no named journey."""
        path = runtime.ROOT / 'config/maestro-screen-coverage.json'
        mapping = json.loads(path.read_text())
        source = (runtime.ROOT / mapping['source']).read_text()
        body = re.search(r'internal enum class SettingsDetail\s*\{([^}]+)\}', source).group(1)
        routes = set(re.findall(r'^\s*(\w+),?\s*$', body, re.MULTILINE))
        self.assertEqual(routes, set(mapping['settings_routes']))
        for route, cases in mapping['settings_routes'].items():
            with self.subTest(route=route):
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

    def test_unknown_slices_are_rejected_before_building(self):
        """Reject invalid selection and enforce the per-shard case budget."""
        self.assertEqual(set(selection('runtime-all')), set(runtime.SUITES))
        self.assertEqual(selection('runtime-settings'), ['settings'])
        for value in ('runtime-../other', 'all', 'runtime-settings\n', 'runtime-'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                selection(value)
        for name in runtime.SUITES:
            self.assertTrue(1 <= sum(case['suite'] == name for case in runtime.CASES.values()) <= 8)

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


if __name__ == '__main__':
    unittest.main()
