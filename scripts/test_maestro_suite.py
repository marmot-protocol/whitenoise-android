"""Guard suite budgets and reject incomplete or misleading UI execution evidence."""

import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from scripts import maestro_suite as suite


class MaestroSuiteTest(unittest.TestCase):
    def test_captured_pinned_cli_output_reconciles_nine_actual_cases(self):
        """Validate the real pinned Maestro report rather than relying solely on constructed XML."""
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            suite.prepare('offline-signin', '1', 'false', destination)
            source = suite.ROOT / 'scripts/test-fixtures/maestro-2.11.0-signin-junit.xml'
            (destination / 'junit.xml').write_bytes(source.read_bytes())
            result = suite.report(destination, 0)
            self.assertEqual(result['positive_passed'], 9)
            self.assertTrue(result['evidence_complete'])

    def test_every_allowlisted_flow_starts_with_disposable_state(self):
        """A new flow cannot accidentally retain prior identity or enable sharing."""
        for filename, _ in suite.CASES.values():
            with self.subTest(flow=filename):
                flow = (suite.ROOT / '.maestro' / filename).read_text()
                self.assertIn('clearState: true', flow)
                self.assertLess(flow.index('- stopApp'), flow.index('- setOrientation: PORTRAIT'))
                self.assertLess(flow.index('- setOrientation: PORTRAIT'), flow.index('- launchApp:'))
                self.assertIn('all: deny', flow)
                self.assertNotIn('openLink:', flow)
                self.assertNotIn('point:', flow)

    def test_signup_dismisses_name_keyboard_before_scrolling_to_about(self):
        """Regress the observed hosted failure on the small emulator viewport."""
        for key in ('signup-cancel', 'signup-offline-retry', 'signup-system-back', 'signup-warm-resume', 'signup-edit-retry', 'signup-rotation'):
            with self.subTest(flow=key):
                flow = (suite.ROOT / '.maestro' / suite.CASES[key][0]).read_text()
                after_name = flow.split('- inputText: "Maestro Offline Draft"', 1)[1]
                before_about = after_name.split('id: "onboarding.sign_up.about"', 1)[0]
                dismiss = '- back' if key == 'signup-system-back' else '- hideKeyboard'
                self.assertIn(dismiss, before_about)
                self.assertIn('- scrollUntilVisible:', before_about)
                self.assertLess(before_about.index(dismiss), before_about.index('- scrollUntilVisible:'))

    def test_checksum_case_reaches_native_validation_with_only_checksum_changed(self):
        """Keep the checksum fixture secret-shaped and invalid without using a live credential."""
        valid = 'nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsmhltgl'
        corrupted = valid[:-1] + 'q'
        alphabet = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l'
        self.assertEqual(len(corrupted), 63)
        self.assertTrue(all(char in alphabet for char in corrupted[5:]))
        self.assertEqual(valid[:-6], corrupted[:-6])
        self.assertEqual(sum(a != b for a, b in zip(valid, corrupted)), 1)
        self.assertEqual(self.checksum(valid, alphabet), 1)
        self.assertNotEqual(self.checksum(corrupted, alphabet), 1)
        flow = (suite.ROOT / '.maestro/signin-invalid.yaml').read_text()
        self.assertIn('- inputText: "' + corrupted + '"', flow)
        self.assertIn("Couldn't sign in with that key. Check it and try again.", flow)

    @staticmethod
    def checksum(value, alphabet):
        """Check only fixture integrity using the public Bech32 checksum polynomial."""
        hrp, data = value.rsplit('1', 1)
        values = [ord(char) >> 5 for char in hrp] + [0] + [ord(char) & 31 for char in hrp]
        values += [alphabet.index(char) for char in data]
        result = 1
        generators = (0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
        for entry in values:
            top = result >> 25
            result = (result & 0x1ffffff) << 5 ^ entry
            for bit, generator in enumerate(generators):
                if top >> bit & 1:
                    result ^= generator
        return result

    def test_allowlist_and_budget_before_setup(self):
        """Reject unknown suites and excessive repetitions before setup can create artifacts."""
        self.assertEqual(suite.selection('onboarding', '20', 'false')[1], 20)
        self.assertEqual(len(suite.selection('offline', '3', 'false')[0]) * 3, 18)
        for values in [('offline', '4', 'false'), ('../other', '1', 'false'),
                       ('offline', '01', 'false'), ('offline', '1', 'yes'),
                       ('onboarding', '21', 'false'), ('offline', '1\n', 'false')]:
            with self.subTest(values=values), self.assertRaises(ValueError):
                suite.selection(*values)

    def test_focused_slices_cover_all_cases_with_bounded_runtime(self):
        """Keep expanded slices disjoint and complete while preserving original suite budgets."""
        signin, signup = set(suite.SUITES['offline-signin']), set(suite.SUITES['offline-signup'])
        self.assertEqual((len(signin), len(signup)), (9, 7))
        self.assertFalse(signin & signup)
        self.assertEqual(signin | signup | set(suite.SUITES['offline-edge']), set(suite.CASES))
        self.assertEqual(len(suite.SUITES['offline-edge']), 6)
        for key in ('offline-signin', 'offline-signup'):
            self.assertEqual(suite.selection(key, '1', 'true')[1], 1)
            with self.assertRaises(ValueError):
                suite.selection(key, '2', 'false')

    def test_background_resume_retains_state_and_denies_permissions(self):
        """Preserve only intended warm-resume state with permissions explicitly denied."""
        for key in ('signin-warm-resume', 'signup-warm-resume'):
            flow = (suite.ROOT / '.maestro' / suite.CASES[key][0]).read_text()
            resume = flow.split('- pressKey: Home', 1)[1]
            self.assertIn('stopApp: false', resume)
            self.assertIn('clearState: false', resume)
            self.assertIn('all: deny', resume)
            self.assertNotIn('clearState: true', resume)

    def test_every_selected_case_is_unique_and_mapped(self):
        """Keep names and permanent checklist mappings unique across repetitions."""
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            manifest = suite.prepare('offline', '3', 'true', destination)
            flows = list((destination / 'suite').glob('*.yaml'))
            self.assertEqual(len(flows), 19)
            self.assertEqual(manifest['positive_count'], 18)
            self.assertEqual(len({case['name'] for case in manifest['cases']}), 19)
            self.assertTrue(all(case['manual_ids'] for case in manifest['cases'] if not case['negative']))
            self.assertEqual(len({flow.read_text().split('name: ', 1)[1].splitlines()[0] for flow in flows}), 19)
            self.assertIn(suite.NEGATIVE_ASSERTION, (destination / 'suite/zz-negative-control.yaml').read_text())
            with self.assertRaises(ValueError):
                suite.prepare('onboarding', '1', 'false', destination)

    def test_missing_flow_cannot_leave_partial_suite(self):
        """Reject absent flow sources before writing any partial prepared suite."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(FileNotFoundError):
                suite.prepare('offline', '1', 'false', root / 'reports', root=root)
            self.assertFalse((root / 'reports/suite').exists())

    def write_results(self, destination, *, negative=False):
        """Model executed JUnit; setup or selection alone is never a passing result."""
        manifest = suite.prepare('offline', '1', str(negative).lower(), destination)
        document = ET.Element('testsuites')
        report = ET.SubElement(document, 'testsuite')
        for case in manifest['cases']:
            result = ET.SubElement(report, 'testcase', name=case['name'], time='1.25', status='SUCCESS')
            if case['negative']:
                result.set('status', 'ERROR')
                ET.SubElement(result, 'failure').text = 'Assertion is false: ' + suite.NEGATIVE_ASSERTION
        ET.ElementTree(document).write(destination / 'junit.xml')
        return document, report

    def test_completed_positive_and_intentional_failure_have_distinct_results(self):
        """Accept only complete positives and the exact deliberate assertion as control evidence."""
        for control in (False, True):
            with self.subTest(control=control), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary)
                self.write_results(destination, negative=control)
                result = suite.report(destination, int(control))
                self.assertEqual(result['positive_passed'], 6)
                self.assertEqual(result['negative_control_verified'], control)
                self.assertTrue(result['evidence_complete'])

    def test_missing_duplicate_unexpected_skipped_failed_cases_do_not_pass(self):
        """Reject mismatched discovery and skipped or failed selected assertions."""
        for fault in ('missing', 'duplicate', 'unexpected', 'skipped', 'failed', 'non_success'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary)
                document, report = self.write_results(destination)
                case = report[0]
                if fault == 'missing':
                    report.remove(case)
                elif fault == 'duplicate':
                    report.append(ET.fromstring(ET.tostring(case)))
                elif fault == 'unexpected':
                    case.set('name', 'not-selected')
                elif fault == 'non_success':
                    case.set('status', 'ERROR')
                else:
                    ET.SubElement(case, 'skipped' if fault == 'skipped' else 'failure').text = 'fixture'
                ET.ElementTree(document).write(destination / 'junit.xml')
                with self.assertRaises(ValueError):
                    suite.report(destination, 0)

    def test_setup_failure_or_unexpected_exit_is_not_negative_proof(self):
        """Prevent setup errors and timeout exits from masquerading as a deliberate control."""
        for fault in ('driver_error', 'skipped', 'unexpected_exit', 'timeout', 'killed'):
            with self.subTest(fault=fault), tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary)
                document, report = self.write_results(destination, negative=True)
                case = report[-1]
                if fault == 'driver_error':
                    case[0].text = 'Driver could not launch'
                elif fault == 'skipped':
                    case[0].tag = 'skipped'
                ET.ElementTree(document).write(destination / 'junit.xml')
                with self.assertRaises(ValueError):
                    suite.report(destination, {'unexpected_exit': 0, 'timeout': 124, 'killed': 137}.get(fault, 1))
                saved = json.loads((destination / 'suite-results.json').read_text())
                self.assertFalse(saved['evidence_complete'])

    def test_absent_junit_is_not_success(self):
        """Require an actual UI report rather than a successful-looking command exit."""
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)
            suite.prepare('offline', '1', 'false', destination)
            with self.assertRaises(FileNotFoundError):
                suite.report(destination, 0)


if __name__ == '__main__':
    unittest.main()
