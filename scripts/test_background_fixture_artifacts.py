"""Reject stale, mixed or payload-modified fixture artifacts before device installation."""

import json
import tempfile
import unittest
import zipfile
from unittest.mock import patch
from pathlib import Path

from scripts.background_fixture_artifacts import OUTPUTS, digest, payload_digest, verify, verify_install, native_runtime

SOURCE = "a" * 40


class BackgroundFixtureArtifactsTest(unittest.TestCase):
    """Exercise artifact identity with actual archive bytes rather than assumed hashes."""

    def setUp(self):
        """Create a private, complete hosted-artifact fixture with stable compiled payloads."""
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.manifest = {
            "schema": 1, "source_sha": SOURCE, "mdk_sha": "b" * 40,
            "requires_disposable_profile": True, "outputs": [],
        }
        for name, package in OUTPUTS.items():
            self.archive(self.directory / name, b"compiled")
            self.manifest["outputs"].append({"file": name, "package": package,
                                            "sha256": digest(self.directory / name)})
        self.save()

    def archive(self, path, content, signer=b"signature"):
        """Write compiled code and a separately mutable signing entry into a real ZIP."""
        with zipfile.ZipFile(path, "w") as output:
            output.writestr("classes.dex", content)
            output.writestr("lib/arm64-v8a/libmarmot_uniffi.so", b"native-runtime")
            output.writestr("META-INF/CERT.RSA", signer)

    def save(self):
        """Persist the input manifest independently of the verifier under test."""
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest))

    def test_complete_exact_source_artifacts_verify(self):
        """Accept all three recorded output identities at the expected source revision."""
        self.assertEqual(SOURCE, verify(self.directory, SOURCE)["source_sha"])

    def test_old_head_cannot_be_relabelled_as_current(self):
        """Reject reuse of an otherwise intact fixture from a different commit."""
        with self.assertRaises(ValueError):
            verify(self.directory, "c" * 40)

    def test_one_tampered_apk_invalidates_whole_set(self):
        """Require the test APK and target APKs to retain every recorded byte hash."""
        (self.directory / next(iter(OUTPUTS))).write_bytes(b"replacement")
        with self.assertRaises(ValueError):
            verify(self.directory, SOURCE)

    def test_duplicate_output_cannot_hide_missing_test_apk(self):
        """Reject duplicate app records even if every listed file hash is valid."""
        self.manifest["outputs"][-1] = dict(self.manifest["outputs"][0])
        self.save()
        with self.assertRaises(ValueError):
            verify(self.directory, SOURCE)

    def test_path_traversal_and_production_package_are_rejected(self):
        """Keep unrelated files and production app identities outside fixture preparation."""
        for key, value in (("file", "../outside.apk"), ("package", "dev.ipf.whitenoise.android")):
            with self.subTest(key=key):
                old = self.manifest["outputs"][0][key]
                self.manifest["outputs"][0][key] = value
                self.save()
                with self.assertRaises(ValueError):
                    verify(self.directory, SOURCE)
                self.manifest["outputs"][0][key] = old

    def test_signature_change_preserves_compiled_payload_digest(self):
        """Permit development signing without treating a signature as compiled application code."""
        before, after = self.directory / "before.apk", self.directory / "after.apk"
        self.archive(before, b"compiled", signer=b"ci-key")
        self.archive(after, b"compiled", signer=b"developer-key")
        self.assertNotEqual(digest(before), digest(after))
        self.assertEqual(payload_digest(before), payload_digest(after))

    def test_rewriting_dex_or_resources_is_not_signature_only(self):
        """Reject a repackaged implementation even if it can be signed by the same developer."""
        before, after = self.directory / "before.apk", self.directory / "after.apk"
        self.archive(before, b"compiled")
        self.archive(after, b"rewritten")
        self.assertNotEqual(payload_digest(before), payload_digest(after))

    def test_alias_to_unrecorded_file_is_rejected(self):
        """Reject symlink indirection instead of trusting a mutable external artifact path."""
        name = next(iter(OUTPUTS))
        apk = self.directory / name
        moved = self.directory / "elsewhere.apk"
        apk.rename(moved)
        apk.symlink_to(moved)
        with self.assertRaises(ValueError):
            verify(self.directory, SOURCE)

    def test_unrecorded_original_cannot_be_installed(self):
        """Require signing preparation bound to the precise preserved Dev code."""
        original = self.directory / next(iter(OUTPUTS))
        with self.assertRaises(ValueError):
            verify_install(self.directory, SOURCE, original, original, Path("signer"), Path("aapt"))

    def test_concurrent_dev_update_aborts_before_signer_or_device_access(self):
        """Refuse a stale restoration target when another task changed installed Dev code."""
        original = self.directory / next(iter(OUTPUTS))
        installed = self.directory / "installed.apk"
        self.archive(installed, b"concurrent-update")
        with patch("scripts.background_fixture_artifacts.certificate") as signer:
            with self.assertRaises(ValueError):
                verify_install(self.directory, SOURCE, original, installed, Path("signer"), Path("aapt"))
            signer.assert_not_called()

    def test_native_runtime_is_bound_to_bytes_not_declared_revision(self):
        """Different native runtime bytes cannot inherit a manifest's compatibility claim."""
        changed = self.directory / "changed-native.apk"
        with zipfile.ZipFile(changed, "w") as output:
            output.writestr("lib/arm64-v8a/libmarmot_uniffi.so", b"incompatible-schema")
        original = self.directory / next(iter(OUTPUTS))
        self.assertNotEqual(native_runtime(original), native_runtime(changed))

    def test_boolean_is_not_schema_version(self):
        """Reject JSON true where an integer provenance version is required."""
        self.manifest["schema"] = True
        self.save()
        with self.assertRaises(ValueError):
            verify(self.directory, SOURCE)


if __name__ == "__main__":
    unittest.main()
