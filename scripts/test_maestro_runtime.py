"""Regression contracts for fixture isolation, result identity and manual-only admission."""

import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import yaml

from scripts import maestro_runtime as runtime
from scripts.maestro_runtime_pair import pair
from scripts.maestro_runtime_selection import selection
from scripts.manual_test_fragments import definitions, load_guide


class RuntimeEvidenceTest(unittest.TestCase):
    def test_foreign_or_partial_fixture_receipts_are_rejected(self):
        generation = 'a' * 32
        for flag in ('ready', 'verified', 'closed'):
            self.assertTrue(runtime.receipt(json.dumps({'generation': generation, flag: True}), generation, flag)[flag])
            for value in ({'generation': 'b' * 32, flag: True}, {'generation': generation},
                          {'generation': generation, flag: False}, {'generation': generation, flag: 'true'}):
                with self.subTest(value=value), self.assertRaises(ValueError):
                    runtime.receipt(json.dumps(value), generation, flag)

    def test_missing_duplicate_skipped_wrong_and_failed_ui_results_are_rejected(self):
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

    def test_pair_rejects_stale_source_wrong_run_and_changed_apk(self):
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

    def test_runtime_cases_never_kill_or_replace_the_instrumentation_host(self):
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
        self.assertEqual(set(selection('runtime-all')), set(runtime.SUITES))
        self.assertEqual(selection('runtime-settings'), ['settings'])
        for value in ('runtime-../other', 'all', 'runtime-settings\n', 'runtime-'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                selection(value)
        for name in runtime.SUITES:
            self.assertTrue(1 <= sum(case['suite'] == name for case in runtime.CASES.values()) <= 8)

    def test_fixture_code_and_builds_remain_opt_in(self):
        workflow = yaml.safe_load((runtime.ROOT / '.github/workflows/android-instrumented.yml').read_text())
        jobs = workflow['jobs']
        self.assertIn("github.event_name == 'workflow_dispatch'", jobs['maestro-runtime-build']['if'])
        self.assertIn("startsWith(inputs.maestro_suite, 'runtime-')", jobs['maestro-runtime-build']['if'])
        self.assertEqual(jobs['maestro-runtime']['needs'], 'maestro-runtime-build')
        self.assertNotIn('gradlew', json.dumps(jobs['maestro-runtime']))
        self.assertEqual(jobs['maestro-runtime']['strategy']['max-parallel'], 2)
        for path in (runtime.ROOT / 'app/src/main').rglob('*MaestroFixture*'):
            self.fail(f'Fixture must remain outside app APK: {path}')


if __name__ == '__main__':
    unittest.main()
