import copy
import unittest

from scripts.verify_dependency_security import InvalidEvidence, below, unsafe_prerelease, verify


class SelectedSecurityEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.policy = {"org.freemarker:freemarker": "2.3.35"}
        self.report = {"schema": 1, "source_sha": "a" * 40, "project": ":", "scope": "plugin", "configuration": "classpath",
                       "components": [{"group": "org.freemarker", "name": "freemarker", "version": "2.3.35"}],
                       "violations": [], "unresolved": []}

    def test_rejects_vulnerable_selected_version_despite_empty_violation_field(self):
        self.report["components"][0]["version"] = "2.3.32"
        with self.assertRaises(InvalidEvidence):
            verify(self.report, self.policy)

    def test_rejects_unresolved_modules_and_incomplete_reports(self):
        for key, value in [("unresolved", ["missing:module:1"]), ("schema", 0), ("components", None)]:
            report = copy.deepcopy(self.report)
            report[key] = value
            with self.assertRaises(InvalidEvidence):
                verify(report, self.policy)

    def test_accepts_fixed_and_newer_versions_without_requiring_absent_libraries(self):
        self.assertEqual(verify(self.report, self.policy), 1)
        self.report["components"][0]["version"] = "2.4.0"
        self.assertEqual(verify(self.report, self.policy), 1)
        self.report["components"] = []
        self.assertEqual(verify(self.report, self.policy), 0)

    def test_rejects_stale_revision_and_missing_source_identity(self):
        with self.assertRaises(InvalidEvidence):
            verify(self.report, self.policy, "b" * 40)
        self.report.pop("source_sha")
        with self.assertRaises(InvalidEvidence):
            verify(self.report, self.policy)

    def test_wire_prerelease_advisory_boundary_is_independent_of_stable_floor(self):
        for version in ("7.0.0-alpha", "7.0.0-alpha01", "7.0.0-alpha03", "7.0.0-alpha3-SNAPSHOT"):
            self.assertTrue(unsafe_prerelease(version, "7.0.0-alpha04"))
        for version in ("6.4.5", "7.0.0-alpha04", "7.0.0-alpha05", "7.0.0-RC01", "7.0.0"):
            self.assertFalse(unsafe_prerelease(version, "7.0.0-alpha04"))
        wire = "com.squareup.wire:wire-runtime"
        self.report["components"] = [{"group": "com.squareup.wire", "name": "wire-runtime", "version": "7.0.0-alpha03"}]
        with self.assertRaises(InvalidEvidence):
            verify(self.report, {wire: "6.4.5"}, prereleases={wire: "7.0.0-alpha04"})

    def test_does_not_treat_fixed_prerelease_as_stable_fix(self):
        self.assertTrue(below("2.3.35-rc1", "2.3.35"))
        self.assertTrue(below("2.3.9", "2.3.35"))
        self.assertFalse(below("2.3.35.0", "2.3.35"))
        for selected in ("latest.release", ""):
            with self.assertRaises(InvalidEvidence):
                below(selected, "2.3.35")


if __name__ == "__main__":
    unittest.main()
