"""Hermetic source, selection and packaging checks for dependency inventories."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from scripts import dependency_inventory as inventory


SOURCE = "a" * 40


def report(configuration="productionPlayReleaseRuntimeClasspath", scope="project", project=":app"):
    """Represent a successful resolution with a real content fingerprint."""
    return {"schema": 1, "source_sha": SOURCE, "project": project, "scope": scope,
            "configuration": configuration, "components": [
                {"group": "example", "name": "library", "version": "1", "reasons": ["requested"]}],
            "artifacts": [{"coordinate": "example:library:1", "name": "library-1.jar",
                           "sha256": "b" * 64, "bytes": 12}],
            "violations": [], "unresolved": []}


class DependencyInventoryTest(unittest.TestCase):
    """Ensure incomplete evidence cannot become a shipping or delta claim."""

    def test_separates_distributions_from_tests_plugins_and_nonrelease_runtime(self):
        """Only exact app production release runtime configurations qualify."""
        reports = [report(), report("productionZapstoreReleaseRuntimeClasspath"),
                   report("productionPlayReleaseUnitTestRuntimeClasspath"),
                   report("classpath", "plugin", ":"), report("devPlayDebugRuntimeClasspath"),
                   report("productionPlayReleaseCompileClasspath")]
        result = inventory.selected_inventory(reports, SOURCE)
        self.assertEqual(set(result["release_runtime"]), {"play", "zapstore"})
        self.assertEqual(len(result["tooling_test"]), 2)
        self.assertEqual(len(result["other_configurations"]), 2)
        self.assertEqual(result["retained_maven_classes"]["status"], "unverified")

    def test_rejects_stale_source_unresolved_and_malformed_artifact_hashes(self):
        """No filename or empty violation list can override inconsistent input."""
        for mutate in (lambda r: r.update(source_sha="c" * 40),
                       lambda r: r.update(unresolved=["missing:module"]),
                       lambda r: r["artifacts"][0].update(sha256="bad"),
                       lambda r: r["artifacts"][0].update(coordinate="example:other:1")):
            candidate = report(); mutate(candidate)
            with self.assertRaises(ValueError):
                inventory.selected_inventory([candidate], SOURCE)

    def test_rejects_conflicting_observations_for_same_configuration(self):
        """Changed selection cannot be hidden by last-file-wins merging."""
        changed = report(); changed["components"][0]["version"] = "2"
        changed["artifacts"][0]["coordinate"] = "example:library:2"
        with self.assertRaises(ValueError):
            inventory.selected_inventory([report(), changed], SOURCE)

    def test_rejects_duplicate_json_keys_and_forged_inventory_classification(self):
        """Ambiguous source identities or invented purposes must not survive import."""
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "record.json"
            path.write_text('{"source_sha":"a","source_sha":"b"}')
            with self.assertRaises(ValueError):
                inventory.read_json(path)
        selected = inventory.selected_inventory([report()], SOURCE)
        selected["release_runtime"]["play"]["components"][0]["purpose"] = "unrelated"
        with self.assertRaises(ValueError):
            inventory.compare(selected, selected, SOURCE, SOURCE)

    def test_cli_rejects_stale_release_and_unsafe_version_before_reading_payloads(self):
        """Wrong-source manifests and escaping paths cannot become release records."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "release-manifest.json"
            output = root / "output.json"
            for source, version in [("b" * 40, "2026.10.15"), (SOURCE, "../outside")]:
                manifest.write_text(json.dumps({"sourceCommit": source, "versionName": version, "worktreeDirty": False}))
                with mock.patch.object(inventory.subprocess, "check_output", return_value=SOURCE + "\n"), \
                     mock.patch("sys.argv", ["inventory", "release", "--bundle", str(root), "--output", str(output)]):
                    self.assertEqual(inventory.main(), 1)
                self.assertFalse(output.exists())

    def test_release_cli_exports_both_distributions_and_rejects_changed_payload(self):
        """Exercise the actual release command with the current lock and synthetic packages."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); reports = root / "reports"; reports.mkdir()
            lock = root / "MARMOT_VERSION"
            lock.write_bytes((Path(__file__).resolve().parents[1] / "app/src/main/marmotkit/MARMOT_VERSION").read_bytes())
            properties = inventory.parse_properties(lock)
            library = b"synthetic JNI bytes"; native_sha = hashlib.sha256(library).hexdigest()
            abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            provenance = {"schema": 1, "lock_sha256": inventory.digest(lock), "source": properties["source"],
                          "source_sha": properties["mdk-sha"], "artifact_id": properties["artifact-id"],
                          "artifact_sha256": properties["artifact-sha256"],
                          "features": {"android_lock": sorted(properties["features"].split(","))},
                          "libraries": [{"abi": abi, "sha256": native_sha} for abi in abis]}
            provenance_path = root / "native.json"; provenance_path.write_text(json.dumps(provenance))
            version = "2026.10.15"; files = {}
            for distribution, suffix in [("play", "play.aab"), ("zapstore", "arm64-v8a.apk")]:
                payload = root / f"whitenoise-android-{version}-{suffix}"
                with zipfile.ZipFile(payload, "w") as archive:
                    for abi in (abis if distribution == "play" else abis[:1]):
                        prefix = "base/lib" if distribution == "play" else "lib"
                        archive.writestr(f"{prefix}/{abi}/libmarmot_uniffi.so", library)
                mapping = root / f"mapping-{version}-{distribution}.txt"; mapping.write_text("synthetic mapping")
                for path in (payload, mapping):
                    files[path.name] = {k: v for k, v in inventory.fingerprint(path).items() if k != "name"}
                (reports / f"{distribution}.json").write_text(json.dumps(report(f"production{distribution.title()}ReleaseRuntimeClasspath")))
            manifest = root / "release-manifest.json"
            manifest.write_text(json.dumps({"schemaVersion": 2, "sourceCommit": SOURCE, "versionName": version,
                                            "worktreeDirty": False, "files": files}))
            output = root / "inventory" / "release.json"
            argv = ["inventory", "release", "--bundle", str(root), "--reports", str(reports),
                    "--provenance", str(provenance_path), "--lock", str(lock), "--output", str(output)]
            with mock.patch.object(inventory.subprocess, "check_output", return_value=SOURCE + "\n"), \
                 mock.patch("sys.argv", argv):
                self.assertEqual(inventory.main(), 0)
                exported = inventory.read_json(output)
                self.assertEqual(set(exported["packaging"]), {"play", "zapstore"})
                self.assertEqual(len(exported["packaging"]["play"]["native_libraries"]), 4)
                self.assertEqual(exported["release_manifest"]["sha256"], inventory.digest(manifest))
                (root / f"whitenoise-android-{version}-play.aab").write_bytes(b"changed")
                self.assertEqual(inventory.main(), 1)

    def test_delta_requires_same_configuration_coverage_and_expected_sources(self):
        """A missing configuration is not evidence that dependencies were removed."""
        before = inventory.selected_inventory([report()], SOURCE)
        after_report = report(); after_report["source_sha"] = "c" * 40
        after_report["components"][0]["version"] = "2"
        after_report["artifacts"][0]["coordinate"] = "example:library:2"
        after = inventory.selected_inventory([after_report], "c" * 40)
        delta = inventory.compare(before, after, SOURCE, "c" * 40)
        self.assertEqual(delta["removed"][0]["coordinate"], "example:library:1")
        self.assertEqual(delta["added"][0]["coordinate"], "example:library:2")
        self.assertEqual(delta["added"][0]["supplier"], "example")
        with self.assertRaises(ValueError):
            inventory.compare(before, after, "d" * 40, "c" * 40)
        extra = inventory.selected_inventory([after_report, report("testRuntimeClasspath") | {"source_sha": "c" * 40}], "c" * 40)
        with self.assertRaises(ValueError):
            inventory.compare(before, extra, SOURCE, "c" * 40)

    def test_delta_ignores_cache_only_licence_metadata_when_artifact_bytes_match(self):
        """Warm POM observations do not imply a changed JAR/AAR payload."""
        cold = report()
        warm = report()
        licence = {"name": "Example", "url": "https://example.invalid/license", "pom_sha256": "d" * 64}
        warm["components"][0]["licenses"] = [licence]
        warm["artifacts"][0]["licenses"] = [licence]
        before = inventory.selected_inventory([cold], SOURCE)
        after = inventory.selected_inventory([warm], SOURCE)
        self.assertEqual(inventory.compare(before, after, SOURCE, SOURCE)["changed_artifacts"], [])
        warm["artifacts"][0]["sha256"] = "f" * 64
        changed = inventory.selected_inventory([warm], SOURCE)
        delta = inventory.compare(before, changed, SOURCE, SOURCE)
        self.assertEqual(len(delta["changed_artifacts"]), 1)
        self.assertEqual(delta["added"], [])
        self.assertEqual(delta["removed"], [])

    def test_release_inventory_requires_both_exact_runtime_graphs_and_real_payloads(self):
        """Bind release reports to payload, native and mapping bytes without compiling."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            library = b"native fixture"
            native_sha = hashlib.sha256(library).hexdigest()
            provenance = {"schema": 1, "lock_sha256": "d" * 64, "source": "marmot-protocol/mdk",
                          "source_sha": "e" * 40, "artifact_sha256": "f" * 64,
                          "artifact_id": "1", "features": {"android_lock": ["feature"]},
                          "libraries": [{"abi": abi, "sha256": native_sha}
                                        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")]}
            for distribution, suffix, entry in [("zapstore", "apk", "lib/arm64-v8a/libmarmot_uniffi.so"),
                                                  ("play", "aab", "base/lib/arm64-v8a/libmarmot_uniffi.so")]:
                with zipfile.ZipFile(root / f"{distribution}.{suffix}", "w") as z:
                    z.writestr(entry, library)
                    if distribution == "play":
                        for abi in ("armeabi-v7a", "x86", "x86_64"):
                            z.writestr(f"base/lib/{abi}/libmarmot_uniffi.so", library)
                (root / f"{distribution}.map").write_text("mapping fixture")
            selected = inventory.selected_inventory([report(), report("productionZapstoreReleaseRuntimeClasspath")], SOURCE)
            payloads = {"play": root / "play.aab", "zapstore": root / "zapstore.apk"}
            mappings = {d: root / f"{d}.map" for d in payloads}
            result = inventory.release_inventory(selected, provenance, payloads, mappings, "d" * 64)
            self.assertEqual(result["packaging"]["play"]["native_libraries"][0]["marmotkit_match"], True)
            self.assertEqual(result["retained_maven_classes"]["status"], "unverified")
            self.assertEqual(result["native"]["crate_reachability"]["status"], "unverified")
            with self.assertRaises(ValueError):
                inventory.release_inventory(selected, provenance, payloads, mappings, "1" * 64)
            missing = inventory.selected_inventory([report()], SOURCE)
            with self.assertRaises(ValueError):
                inventory.release_inventory(missing, provenance, payloads, mappings, "d" * 64)
            corrupt = copy.deepcopy(provenance); corrupt["libraries"][0]["sha256"] = "0" * 64
            with self.assertRaises(ValueError):
                inventory.release_inventory(selected, corrupt, payloads, mappings, "d" * 64)
            lock = {"source": provenance["source"], "mdk-sha": provenance["source_sha"],
                    "artifact-sha256": provenance["artifact_sha256"], "artifact-id": "1", "features": "feature"}
            inventory.release_inventory(selected, provenance, payloads, mappings, "d" * 64, lock)
            for key, value in [("mdk-sha", "0" * 40), ("artifact-sha256", "0" * 64), ("features", "other")]:
                with self.assertRaises(ValueError):
                    inventory.release_inventory(selected, provenance, payloads, mappings, "d" * 64, lock | {key: value})


if __name__ == "__main__":
    unittest.main()
