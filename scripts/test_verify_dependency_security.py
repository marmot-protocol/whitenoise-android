import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts.verify_dependency_security import InvalidEvidence, below, unsafe_prerelease, verify
from scripts import verify_dependency_security as validator


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
        for version in ("7.0.0-alpha", "7.0.0-alpha01", "7.0.0-alpha03", "7.0.0-alpha3-SNAPSHOT",
                        "7.0.0-alpha04-SNAPSHOT", "7.0.0-alpha04-rc1"):
            self.assertTrue(unsafe_prerelease(version, "7.0.0-alpha04"))
        for version in ("6.4.5", "7.0.0-alpha04", "7.0.0-alpha05", "7.0.0-RC01", "7.0.0"):
            self.assertFalse(unsafe_prerelease(version, "7.0.0-alpha04"))
        wire = "com.squareup.wire:wire-runtime"
        for version in ("7.0.0-alpha03", "7.0.0-alpha04-SNAPSHOT", "7.0.0-alpha04-rc1"):
            self.report["components"] = [{"group": "com.squareup.wire", "name": "wire-runtime", "version": version}]
            with self.assertRaises(InvalidEvidence):
                verify(self.report, {wire: "6.4.5"}, prereleases={wire: "7.0.0-alpha04"})

    def test_inventory_does_not_bypass_security_floor_verification(self):
        """A usable inventory is not permission to select a vulnerable dependency."""
        self.report["artifacts"] = []
        self.report["components"][0]["version"] = "2.3.32"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); reports = root / "reports"; reports.mkdir()
            (reports / "selected.json").write_text(json.dumps(self.report))
            policy = root / "policy.json"
            policy.write_text(json.dumps({"schema": 1, "minimum_versions": self.policy}))
            output = reports / "inventory" / "selected.json"
            with mock.patch("sys.argv", ["verify", str(reports), "--policy", str(policy), "--inventory", str(output)]), \
                 mock.patch.object(validator.subprocess, "check_output", return_value="a" * 40):
                self.assertEqual(validator.main(), 1)
            self.assertFalse(output.exists())

    def test_inventory_cli_success_retains_verified_plugin_selection(self):
        """Exercise CI's successful export seam after selected-version validation."""
        self.report["artifact_coverage"] = "resolved_configuration"
        self.report["artifacts"] = [{"coordinate": "org.freemarker:freemarker:2.3.35", "name": "freemarker.jar",
                                     "sha256": "b" * 64, "bytes": 12}]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); reports = root / "reports"; reports.mkdir()
            (reports / "selected.json").write_text(json.dumps(self.report))
            policy = root / "policy.json"
            policy.write_text(json.dumps({"schema": 1, "minimum_versions": self.policy}))
            output = reports / "inventory" / "selected.json"
            with mock.patch("sys.argv", ["verify", str(reports), "--policy", str(policy), "--inventory", str(output)]), \
                 mock.patch.object(validator.subprocess, "check_output", return_value="a" * 40):
                self.assertEqual(validator.main(), 0)
            selected = json.loads(output.read_text())
            self.assertEqual(selected["source_sha"], "a" * 40)
            self.assertEqual(selected["tooling_test"][0]["components"][0]["coordinate"], "org.freemarker:freemarker:2.3.35")

    def test_inventory_only_errors_do_not_fail_floor_only_cli(self):
        """Malformed optional observations fail export independently of security-floor enforcement."""
        self.report.update(artifacts=[], artifact_coverage='resolved_configuration')
        for mutate in [lambda r: r['components'][0].update(licenses='invalid'),
                       lambda r: r['artifacts'].append({'coordinate': 'unknown:module:1'}),
                       lambda r: r.update(artifact_coverage='partial')]:
            candidate = copy.deepcopy(self.report)
            mutate(candidate)
            with self.subTest(candidate=candidate), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary); reports = root / 'reports'; reports.mkdir()
                (reports / 'selected.json').write_text(json.dumps(candidate))
                policy = root / 'policy.json'
                policy.write_text(json.dumps({'schema': 1, 'minimum_versions': self.policy}))
                output = reports / 'inventory' / 'selected.json'
                argv = ['verify', str(reports), '--policy', str(policy)]
                with mock.patch.object(validator.subprocess, 'check_output', return_value='a' * 40):
                    with mock.patch('sys.argv', argv):
                        self.assertEqual(validator.main(), 0)
                    with mock.patch('sys.argv', [*argv, '--inventory', str(output)]):
                        self.assertEqual(validator.main(), 1)
                self.assertFalse(output.exists())

    def test_does_not_treat_fixed_prerelease_as_stable_fix(self):
        self.assertTrue(below("2.3.35-rc1", "2.3.35"))
        self.assertTrue(below("2.3.9", "2.3.35"))
        self.assertFalse(below("2.3.35.0", "2.3.35"))
        for selected in ("latest.release", ""):
            with self.assertRaises(InvalidEvidence):
                below(selected, "2.3.35")


if __name__ == "__main__":
    unittest.main()
