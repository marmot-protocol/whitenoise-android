"""Keep an expected failing control from hiding missing tests or unrelated failures."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location('control', Path(__file__).with_name('verify_compose_backport_control.py'))
control = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(control)


class ControlEvidenceTest(unittest.TestCase):
    """Exercise the control's failure classifier with actual JUnit XML structures."""

    def check_report(self, mutation=None, returncode=1):
        """Build a complete synthetic report and apply one adversarial change before verification."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            suite = ET.Element('testsuite')
            cases = {}
            for name in control.SHARED | control.BUBBLES | {control.IDENTITY}:
                cases[name] = ET.SubElement(suite, 'testcase', name=name, classname=control.EXPECTED_CLASSES[name])
            for name in ('baselineQueryDuringReusedItemMeasurementDoesNotPlaceChildrenForReal',
                         'shippedKeyReactivatesEditedBubbleWithoutCrashing'):
                ET.SubElement(cases[name], 'failure').text = (
                    'java.lang.IllegalArgumentException: LayoutNode 863 not found in RectList')
            if mutation:
                mutation(suite, cases)
            ET.ElementTree(suite).write(root / 'TEST-control.xml')
            old_output = control.OUTPUT
            try:
                control.OUTPUT = root
                control.verify_results(root, returncode)
            finally:
                control.OUTPUT = old_output

    def test_accepts_only_expected_failure_family_with_identity(self):
        """An original-runtime pass plus required RectList failures qualifies the control."""
        self.check_report()

    def test_rejects_missing_identity_case(self):
        """A red test task cannot qualify if runtime identity was never exercised."""
        with self.assertRaises(AssertionError):
            self.check_report(lambda suite, cases: suite.remove(cases[control.IDENTITY]))

    def test_rejects_wrong_test_class(self):
        """A different test with the same method name cannot stand in for the selected regression."""
        with self.assertRaises(AssertionError):
            self.check_report(lambda suite, cases: cases[control.IDENTITY].set('classname', 'unrelated.Test'))

    def test_rejects_missing_marker_as_a_substitute_for_rectlist(self):
        """A classpath marker failure is not proof that the behavior regressed."""
        def replace_failure(suite, cases):
            """Replace the required behavioral exception with an identity assertion failure."""
            case = cases['baselineQueryDuringReusedItemMeasurementDoesNotPlaceChildrenForReal']
            case.find('failure').text = 'AssertionError: missing backport marker'
        with self.assertRaises(AssertionError):
            self.check_report(replace_failure)

    def test_rejects_skipped_or_unrelated_failure(self):
        """Skipping coverage or crashing for another reason must fail qualification."""
        for kind in ('skipped', 'error'):
            with self.subTest(kind=kind), self.assertRaises(AssertionError):
                self.check_report(lambda suite, cases: ET.SubElement(cases[control.IDENTITY], kind))

    def test_rejects_successful_task_with_failure_xml(self):
        """Stale failure XML cannot qualify a successful control invocation."""
        with self.assertRaises(AssertionError):
            self.check_report(returncode=0)


if __name__ == '__main__':
    unittest.main()
