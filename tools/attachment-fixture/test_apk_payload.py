"""Payload builder contracts: padding stays APK-shaped and in range, signing follows alignment, no device is touched."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import apk_payload as payload

MIB = 1024 * 1024
MANIFEST = b"\x03\x00\x08\x00" + (1000).to_bytes(4, "little") + bytes(992)
SDK_ROOT = Path(os.environ.get("ANDROID_HOME", "/nonexistent"))


def synthetic_apk(path, manifest=MANIFEST, extra=()):
    """A small APK-shaped archive with a manifest, a dex entry and any extra names a test needs."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", manifest)
        archive.writestr("classes.dex", bytes(4096))
        for name in extra:
            archive.writestr(name, b"x")
    return Path(path)


class PaddedApkTest(unittest.TestCase):
    """Padding is deterministic, stored, bounded and keeps the manifest untouched."""

    def setUp(self):
        """Every test gets its own private scratch directory."""
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)

    def tearDown(self):
        """Remove generated archives."""
        self.directory.cleanup()

    def test_padded_apk_lands_near_target_and_keeps_the_manifest_in_a_stored_entry(self):
        """The result is within the entry overhead of the target, still a valid ZIP, with the pad stored uncompressed."""
        source = synthetic_apk(self.root / "source.apk")
        output = self.root / "padded.apk"
        size = payload.padded_apk(source, 3 * MIB // 2, output, minimum=MIB, maximum=2 * MIB)
        self.assertEqual(output.stat().st_size, size)
        self.assertLessEqual(abs(size - 3 * MIB // 2), payload.ENTRY_OVERHEAD)
        with zipfile.ZipFile(output) as archive:
            self.assertIsNone(archive.testzip())
            self.assertEqual(MANIFEST, archive.read("AndroidManifest.xml"))
            pad = archive.getinfo(payload.PAD_ENTRY)
            self.assertEqual(zipfile.ZIP_STORED, pad.compress_type)
            self.assertEqual(b"".join(payload.pad_bytes(pad.file_size)), archive.read(payload.PAD_ENTRY))
        self.assertEqual(0o600, output.stat().st_mode & 0o777)

    def test_padded_apk_refuses_non_apk_sources_targets_out_of_range_and_double_padding(self):
        """A ZIP without a manifest, a target outside the range, an oversize source or a padded source is refused."""
        no_manifest = self.root / "no-manifest.zip"
        with zipfile.ZipFile(no_manifest, "w") as archive:
            archive.writestr("classes.dex", bytes(16))
        source = synthetic_apk(self.root / "source.apk")
        padded = synthetic_apk(self.root / "padded.apk", extra=(payload.PAD_ENTRY,))
        for apk, target, minimum, maximum in ((no_manifest, MIB, MIB, 2 * MIB), (source, MIB - 1, MIB, 2 * MIB),
                                               (source, 2 * MIB + 1, MIB, 2 * MIB), (source, 1024, 1024, 2048),
                                               (padded, MIB, MIB, 2 * MIB), (self.root / "absent.apk", MIB, MIB, 2 * MIB)):
            with self.assertRaises(ValueError, msg=(apk.name, target)):
                payload.padded_apk(apk, target, self.root / "out.apk", minimum=minimum, maximum=maximum)
            self.assertFalse((self.root / "out.apk").exists() and (self.root / "out.apk").stat().st_size > 0)
        with self.assertRaises(ValueError):
            payload.padded_apk(source, payload.MIN_BYTES - 1, self.root / "out.apk")

    def test_pad_bytes_are_deterministic_bounded_and_exact(self):
        """Two generations agree byte for byte, chunks never exceed the chunk size and the total is exact."""
        first = list(payload.pad_bytes(payload.CHUNK * 2 + 7))
        self.assertEqual(first, list(payload.pad_bytes(payload.CHUNK * 2 + 7)))
        self.assertEqual(payload.CHUNK * 2 + 7, sum(len(chunk) for chunk in first))
        self.assertTrue(all(len(chunk) <= payload.CHUNK for chunk in first))
        self.assertEqual([], list(payload.pad_bytes(0)))

    def test_describe_reports_closed_facts_only(self):
        """The receipt carries size, digest, manifest presence and range, never a path to key material."""
        source = synthetic_apk(self.root / "source.apk")
        facts = payload.describe(source)
        self.assertEqual({"bytes", "sha256", "has_manifest", "within_payload_range"}, set(facts))
        self.assertEqual((source.stat().st_size, True, False), (facts["bytes"], facts["has_manifest"],
                                                                facts["within_payload_range"]))
        self.assertEqual(payload.sha256_of(source), facts["sha256"])


class SigningCommandTest(unittest.TestCase):
    """The host signs with the given key in the required order and never issues a device command."""

    def test_align_then_sign_then_verify_in_order_without_any_device_command(self):
        """zipalign precedes apksigner sign, verify follows, and no command names adb."""
        run = mock.Mock(return_value=mock.Mock(stdout=""))
        with tempfile.TemporaryDirectory(prefix="fixture-adb-") as directory:
            unsigned = Path(directory) / "unsigned.apk"
            unsigned.write_bytes(b"zip")
            output = Path(directory) / "signed.apk"
            output.write_bytes(b"signed")
            payload.align_and_sign(unsigned, output, "/tools", "/ks/debug.keystore", "alias", "secret", run)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(["/tools/zipalign", "/tools/apksigner", "/tools/apksigner"], [c[0] for c in commands])
        self.assertEqual(["-p", "-f", "4"], commands[0][1:4])
        self.assertEqual("sign", commands[1][1])
        self.assertIn("--min-sdk-version", commands[1])
        self.assertEqual(commands[0][-1], commands[1][-1])
        self.assertEqual(["verify", "--min-sdk-version", payload.MIN_SDK], commands[2][1:4])
        self.assertFalse(any(Path(command[0]).name in {"adb", "adb.exe"} for command in commands))

    def test_the_signing_password_never_reaches_a_command_line_or_a_failure_message(self):
        """apksigner reads the password from the environment, so a failed signing command cannot print it."""
        password = "correct-horse-battery-staple"
        seen = []

        def failing_run(command, **options):
            """Record the command and its environment, then fail the signing step like subprocess.run does."""
            seen.append((command, options.get("env")))
            if command[1] == "sign":
                raise subprocess.CalledProcessError(1, command)
            return mock.Mock(stdout="")

        with tempfile.TemporaryDirectory() as directory:
            unsigned = Path(directory) / "unsigned.apk"
            unsigned.write_bytes(b"zip")
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                payload.align_and_sign(unsigned, Path(directory) / "signed.apk", "/tools", "/ks/release.keystore",
                                       "alias", password, failing_run)
        self.assertNotIn(password, str(caught.exception))
        self.assertFalse(any(password in part for command, _ in seen for part in command))
        sign_command, sign_environment = next((c, e) for c, e in seen if c[1] == "sign")
        self.assertIn(f"env:{payload.PASSWORD_ENVIRONMENT_VARIABLE}", sign_command)
        self.assertEqual(password, sign_environment[payload.PASSWORD_ENVIRONMENT_VARIABLE])

    def test_signer_digest_requires_exactly_one_signer(self):
        """One SHA-256 digest line is returned, zero or several are refused."""
        line = "Signer #1 certificate SHA-256 digest: " + "ab" * 32
        run = mock.Mock(return_value=mock.Mock(stdout=line + "\nSigner #1 certificate SHA-1 digest: 00\n"))
        self.assertEqual("ab" * 32, payload.signer_digest("/tools/apksigner", "x.apk", run))
        self.assertIn("--print-certs", run.call_args.args[0])
        for printed in ("", line + "\n" + line.replace("#1", "#2").replace("ab" * 32, "cd" * 32)):
            run.return_value.stdout = printed
            with self.assertRaises(ValueError):
                payload.signer_digest("/tools/apksigner", "x.apk", run)

    def test_build_tools_prefers_the_newest_directory_that_has_both_binaries(self):
        """A newer directory missing apksigner is skipped and an SDK without both binaries is refused."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for version, binaries in (("35.0.0", ("zipalign", "apksigner")), ("36.1.0", ("zipalign",))):
                (root / "build-tools" / version).mkdir(parents=True)
                for binary in binaries:
                    (root / "build-tools" / version / binary).write_text("")
            self.assertEqual(root / "build-tools" / "35.0.0", payload.build_tools(root))
            shutil.rmtree(root / "build-tools" / "35.0.0")
            with self.assertRaises(ValueError):
                payload.build_tools(root)


@unittest.skipUnless(
    (SDK_ROOT / "build-tools").is_dir() and payload.DEFAULT_KEYSTORE.is_file() and shutil.which("java"),
    "needs local build-tools, a debug keystore and a JVM",
)
class LocalSigningTest(unittest.TestCase):
    """When the host has the tools, a padded synthetic archive really aligns, signs and verifies."""

    def test_synthetic_archive_signs_and_verifies_with_the_debug_key(self):
        """The signed output verifies with apksigner and reports exactly one signer."""
        try:
            tools = payload.build_tools(SDK_ROOT)
        except ValueError as error:
            self.skipTest(str(error))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = synthetic_apk(root / "source.apk")
            unsigned = root / "unsigned.apk"
            payload.padded_apk(source, MIB, unsigned, minimum=MIB // 2, maximum=2 * MIB)
            signed = root / "signed.apk"
            try:
                payload.align_and_sign(unsigned, signed, tools, payload.DEFAULT_KEYSTORE, payload.DEFAULT_ALIAS,
                                       payload.DEBUG_STORE_PASSWORD)
            except subprocess.CalledProcessError as error:
                self.fail(f"signing failed: {error.stderr}")
            self.assertEqual(64, len(payload.signer_digest(tools / "apksigner", signed)))
            self.assertTrue(payload.has_manifest(signed))


if __name__ == "__main__":
    unittest.main()
