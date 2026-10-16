"""Regression contracts for the observed shared-driver reset hang and partial reporting."""

import json
import os
import signal
import sys
import time
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import yaml

from scripts import maestro_offline_batches as batches
from scripts import maestro_suite as suite


class OfflineBatchTest(unittest.TestCase):
    def test_selected_membership_order_hashes_and_lifecycle_isolation(self):
        for selected in ('onboarding', 'offline', 'offline-signin', 'offline-signup', 'offline-edge'):
            with self.subTest(suite=selected), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                expected = suite.prepare(selected, '1', 'true', root)
                plan = batches.prepare(root)
                self.assertEqual([name for batch in plan for name in batch['names']],
                                 [case['name'] for case in expected['cases']])
                self.assertTrue(all(len(batch['names']) == 1 for batch in plan))
                cases = {case['name']: case for case in expected['cases']}
                for batch in plan:
                    path = root / 'batches' / f"{batch['number']:03}"
                    config = yaml.safe_load((path / 'config.yaml').read_text())
                    self.assertEqual(config['executionOrder']['flowsOrder'], batch['names'])
                    self.assertIs(config['executionOrder']['continueOnFailure'], False)
                    for name in batch['names']:
                        case = cases[name]
                        filename = 'zz-negative-control.yaml' if case['negative'] else name + '.yaml'
                        self.assertEqual((path / filename).read_bytes(), (root / 'suite' / filename).read_bytes())
                        if case['negative'] or any(word in case['case'] for word in ('warm-resume', 'rotation')):
                            self.assertEqual(batch['names'], [name])
                self.assertEqual(plan[-1]['names'], ['negative-control'])

    def test_captured_junit_aggregates_only_real_selected_records(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suite.prepare('offline-signin', '1', 'false', root)
            plan = batches.prepare(root)
            captured = ET.parse(suite.ROOT / 'scripts/test-fixtures/maestro-2.11.0-signin-junit.xml')
            records = {case.get('name'): case for case in captured.getroot().iter('testcase')}
            for batch in plan:
                report = ET.Element('testsuite')
                for name in batch['names']:
                    report.append(records[name])
                ET.ElementTree(report).write(root / 'batches' / f"{batch['number']:03}" / 'junit.xml')
            batches.aggregate(root, plan)
            self.assertTrue(suite.report(root, 0)['evidence_complete'])
            (root / 'batches' / '001' / 'junit.xml').unlink()
            batches.aggregate(root, plan)
            with self.assertRaises(ValueError):
                suite.report(root, 0)
            saved = json.loads((root / 'suite-results.json').read_text())
            self.assertEqual(saved['positive_passed'], 9 - len(plan[0]['names']))
            self.assertFalse(saved['evidence_complete'])

    def test_missing_malformed_duplicate_unexpected_and_skipped_batch_cannot_proceed(self):
        for fault in ('missing', 'xml', 'duplicate', 'unexpected', 'skipped'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / 'junit.xml'
                root = ET.Element('testsuite')
                case = ET.SubElement(root, 'testcase', name='selected', status='SUCCESS', time='1')
                if fault == 'duplicate':
                    root.append(ET.fromstring(ET.tostring(case)))
                if fault == 'unexpected':
                    case.set('name', 'foreign')
                if fault == 'skipped':
                    ET.SubElement(case, 'skipped')
                if fault != 'missing':
                    ET.ElementTree(root).write(path)
                if fault == 'xml':
                    path.write_text('<broken')
                self.assertFalse(batches.complete_batch(path, ['selected']))

    def test_failure_timeout_and_unsafe_teardown_stop_without_replay(self):
        for fault in ('failure', 'timeout', 'unsafe', 'missing-report'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                suite.prepare('offline-signin', '1', 'false', root)
                launches = []

                def command(arguments, **kwargs):
                    if arguments[0] == 'adb':
                        self.assertEqual(arguments[:3], ['adb', '-s', 'emulator-5554'])
                        output = 'Success\n' if arguments[3:6] == ['shell', 'pm', 'clear'] else 'package:dev.mobile.maestro\n'
                        return subprocess.CompletedProcess(arguments, 0, output, '')
                    launches.append(arguments)
                    self.assertGreater(kwargs['timeout'], 0)
                    self.assertLessEqual(kwargs['timeout'], 600)
                    self.assertIn('--driver-host-port', arguments)
                    if fault == 'timeout':
                        raise subprocess.TimeoutExpired(arguments, kwargs['timeout'])
                    if fault == 'unsafe':
                        config = yaml.safe_load(Path(arguments[arguments.index('--config') + 1]).read_text())
                        report = ET.Element('testsuite')
                        for name in config['executionOrder']['flowsOrder']:
                            ET.SubElement(report, 'testcase', name=name, status='SUCCESS', time='1')
                        ET.ElementTree(report).write(Path(arguments[arguments.index('--output') + 1]))
                    return subprocess.CompletedProcess(arguments, 1 if fault == 'failure' else 0)

                with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(batches.subprocess, 'run', command):
                    result = batches.execute(root)
                self.assertEqual(len(launches), 1)
                self.assertEqual(result, 124 if fault == 'timeout' else 1 if fault == 'failure' else 2)
                saved = json.loads((root / 'batch-results.json').read_text())
                self.assertEqual(len(saved['batches']), 1)
                self.assertFalse(saved['batches'][0]['cleanup_verified'])

    def test_global_deadline_never_resets_between_sessions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suite.prepare('offline-signin', '1', 'false', root)
            with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(
                    batches.time, 'monotonic', side_effect=[10, 611]), patch.object(batches.subprocess, 'run') as run:
                self.assertEqual(batches.execute(root), 124)
            run.assert_not_called()
            saved = json.loads((root / 'suite-results.json').read_text())
            self.assertFalse(saved['evidence_complete'])
            self.assertEqual(len(saved['missing_cases']), 9)

    def test_failed_or_unconfirmed_reset_never_starts_maestro(self):
        """Regress a stuck data-clear transport without weakening the fresh-state requirement."""
        for fault in ('exit', 'unconfirmed', 'timeout'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                suite.prepare('offline-edge', '1', 'false', root)

                def reset(arguments, **kwargs):
                    self.assertEqual(arguments, ['adb', '-s', 'emulator-5554', 'shell', 'pm',
                                                 'clear', 'dev.ipf.whitenoise.android.dev'])
                    self.assertGreater(kwargs['timeout'], 0)
                    self.assertLessEqual(kwargs['timeout'], 30)
                    if fault == 'timeout':
                        raise subprocess.TimeoutExpired(arguments, kwargs['timeout'])
                    return subprocess.CompletedProcess(arguments, 1 if fault == 'exit' else 0,
                                                       'Success' if fault == 'exit' else '', '')

                with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(
                        batches.subprocess, 'run', side_effect=reset) as command:
                    self.assertEqual(batches.execute(root), 124 if fault == 'timeout' else 2)
                self.assertEqual(command.call_count, 1)
                saved = json.loads((root / 'batch-results.json').read_text())
                self.assertFalse(saved['batches'][0]['reset_verified'])
                self.assertFalse(saved['batches'][0]['cleanup_verified'])
                self.assertFalse(json.loads((root / 'suite-results.json').read_text())['evidence_complete'])

    def test_reset_cannot_extend_the_shared_ui_deadline(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suite.prepare('offline-edge', '1', 'false', root)
            with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(
                    batches.time, 'monotonic', side_effect=[0, 565, 571]), patch.object(
                    batches.subprocess, 'run', return_value=subprocess.CompletedProcess(
                        [], 0, 'Success\n', '')) as command:
                self.assertEqual(batches.execute(root), 124)
            self.assertEqual(command.call_count, 1)
            self.assertEqual(command.call_args.kwargs['timeout'], 5)
            saved = json.loads((root / 'batch-results.json').read_text())
            self.assertTrue(saved['batches'][0]['reset_verified'])
            self.assertFalse(saved['batches'][0]['cleanup_verified'])

    def test_completed_first_batch_cannot_restart_the_global_deadline(self):
        """Actual partial XML survives when the shared budget expires before the next driver session."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            suite.prepare('offline-signin', '1', 'false', root)
            launches = []

            def command(arguments, **kwargs):
                if arguments[0] == 'adb':
                    return subprocess.CompletedProcess(arguments, 0, 'Success\n' if arguments[3:6] == ['shell', 'pm', 'clear'] else '', '')
                launches.append(arguments)
                config = yaml.safe_load(Path(arguments[arguments.index('--config') + 1]).read_text())
                report = ET.Element('testsuite')
                for name in config['executionOrder']['flowsOrder']:
                    ET.SubElement(report, 'testcase', name=name, status='SUCCESS', time='1')
                ET.ElementTree(report).write(Path(arguments[arguments.index('--output') + 1]))
                return subprocess.CompletedProcess(arguments, 0)

            with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(
                    batches.time, 'monotonic', side_effect=[0, 0, 30, 100, 601]), patch.object(
                    batches.subprocess, 'run', command):
                self.assertEqual(batches.execute(root), 124)
            self.assertEqual(len(launches), 1)
            with self.assertRaises(ValueError):
                suite.report(root, 124)
            saved = json.loads((root / 'suite-results.json').read_text())
            self.assertEqual(saved['positive_passed'], 1)
            self.assertEqual(len(saved['missing_cases']), 8)

    def test_outer_sigterm_retains_completed_batch_and_timeout_status(self):
        """Real signal delivery exercises finalization and actual child cleanup, without running UI."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            driver = root / 'driver.py'
            driver.write_text("""
import json, os, signal, subprocess, sys
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET
from scripts import maestro_offline_batches as batches, maestro_suite as suite
root = Path(sys.argv[1])
suite.prepare('offline-signin', '1', 'false', root)
original = subprocess.run
launches = 0
def command(arguments, **kwargs):
    global launches
    if arguments[0] == 'adb':
        return subprocess.CompletedProcess(arguments, 0, 'Success\\n' if arguments[3:6] == ['shell', 'pm', 'clear'] else '', '')
    launches += 1
    if launches == 1:
        plan = json.loads((root / 'batch-manifest.json').read_text())['batches'][0]
        report = ET.Element('testsuite')
        for name in plan['names']:
            ET.SubElement(report, 'testcase', name=name, status='SUCCESS', time='1')
        ET.ElementTree(report).write(Path(arguments[arguments.index('--output') + 1]))
        return subprocess.CompletedProcess(arguments, 0)
    script = 'import os,time,pathlib;pathlib.Path(' + repr(str(root / 'hanging-child')) + ').write_text(str(os.getpid()));time.sleep(30)'
    return original([sys.executable, '-c', script], timeout=kwargs['timeout'], check=False)
signal.signal(signal.SIGTERM, lambda _signal, _frame: sys.exit(124))
with patch.dict(os.environ, GITHUB_ACTIONS='true'), patch.object(batches.subprocess, 'run', command):
    result = batches.execute(root)
try:
    suite.report(root, result)
except ValueError:
    pass
raise SystemExit(result)
""")
            child = subprocess.Popen([sys.executable, str(driver), str(root)], cwd=suite.ROOT,
                                     env={**os.environ, 'PYTHONPATH': str(suite.ROOT)},
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                deadline = time.monotonic() + 10
                marker = root / 'hanging-child'
                while not marker.exists() and child.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(marker.exists(), 'Second batch did not enter its real child wait')
                child.send_signal(signal.SIGTERM)
                stdout, stderr = child.communicate(timeout=5)
                self.assertEqual(child.returncode, 124, stdout + stderr)
                saved = json.loads((root / 'suite-results.json').read_text())
                self.assertEqual(saved['positive_passed'], 1)
                self.assertEqual(len(saved['missing_cases']), 8)
                self.assertFalse(saved['evidence_complete'])
                receipt = json.loads((root / 'batch-results.json').read_text())
                self.assertEqual(receipt['maestro_exit'], 124)
                self.assertEqual(len(receipt['batches']), 2)
                self.assertTrue(receipt['batches'][0]['cleanup_verified'])
                with self.assertRaises(ProcessLookupError):
                    os.kill(int(marker.read_text()), 0)
            finally:
                if child.poll() is None:
                    child.kill()
                child.communicate(timeout=5)

    def test_reporter_failure_preserves_an_original_timeout_exit(self):
        """The shell trap must not replace the observed timeout with a generic reporting error."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = (suite.ROOT / 'scripts/run-maestro-pilot.sh').read_text()
            trap = runner[runner.index('finish() {'):runner.index('trap finish EXIT')]
            script = root / 'trap.sh'
            script.write_text('#!/bin/bash\nset -euo pipefail\nreports="' + str(root) + '"\n'
                              'maestro_started=true\npython3(){ return 1; }\ntimeout(){ return 0; }\n'
                              + trap + '\ntrap finish EXIT\nexit "${1}"\n')
            for code, expected in ((124, 124), (137, 137), (1, 1), (0, 2)):
                result = subprocess.run(['bash', str(script), str(code)], capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_non_ci_environment_never_starts_a_driver_or_device_command(self):
        with patch.dict(os.environ, GITHUB_ACTIONS='false'), patch.object(batches.subprocess, 'run') as run:
            with self.assertRaises(ValueError):
                batches.execute(Path('unused'))
            run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
