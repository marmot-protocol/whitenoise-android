"""Regression coverage for packaged native libraries and runtime page-size proof."""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("page_sizes", ROOT / "scripts/verify_android_page_sizes.py")
PAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PAGE)


class PackagedAlignmentTests(unittest.TestCase):
    """Exercise the final package rather than trusting the SDK archive's metadata."""

    def setUp(self):
        """Give each package an isolated output directory."""
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def package(self, names):
        """Build a minimal archive with independently named native dependencies."""
        path = self.root / "candidate.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name in names:
                archive.writestr(name, b"native fixture")
        return path

    def test_checks_every_64bit_dependency_and_both_abis(self):
        path = self.package(["lib/arm64-v8a/libmarmot_uniffi.so", "lib/x86_64/libother.so",
                             "lib/armeabi-v7a/libother.so"])
        with patch.object(PAGE, "command", return_value="LOAD off 0x0 align 2**14\nLOAD off 0x4000 align 2**16") as calls:
            result = PAGE.check_libraries(path, "objdump", {"arm64-v8a", "x86_64"})
        self.assertEqual(len(result), 2)
        self.assertEqual(calls.call_count, 2)
        self.assertEqual(result[1]["loadAlignments"], [16384, 65536])

    def test_rejects_4kb_dependency_even_when_marmot_is_aligned(self):
        path = self.package(["base/lib/arm64-v8a/libmarmot_uniffi.so", "base/lib/arm64-v8a/libother.so"])
        with patch.object(PAGE, "command", side_effect=["LOAD off 0 align 2**14", "LOAD off 0 align 2**12"]):
            with self.assertRaisesRegex(ValueError, "libother.so.*16 KB"):
                PAGE.check_libraries(path, "objdump", {"arm64-v8a"})

    def test_rejects_missing_abi_missing_load_or_unparseable_load(self):
        path = self.package(["lib/arm64-v8a/libother.so"])
        for output in ["", "LOAD off 0 align unknown", "LOAD off 0 align 2**14\nLOAD off 4"]:
            with self.subTest(output=output), patch.object(PAGE, "command", return_value=output):
                with self.assertRaises(ValueError):
                    PAGE.check_libraries(path, "objdump", {"arm64-v8a"})
        with self.assertRaisesRegex(ValueError, "Missing.*x86_64"):
            PAGE.check_libraries(path, "objdump", {"arm64-v8a", "x86_64"})

    def test_requires_bundle_16kb_alignment_not_an_unrelated_matching_string(self):
        for config in [{}, {"optimizations": {"uncompressNativeLibraries": {"alignment": "PAGE_ALIGNMENT_4K"}}},
                       {"comment": "PAGE_ALIGNMENT_16K"}]:
            with self.subTest(config=config), patch.object(PAGE, "command", return_value=json.dumps(config)):
                with self.assertRaisesRegex(ValueError, "PAGE_ALIGNMENT_16K"):
                    PAGE.check_bundle_config(Path("candidate.aab"), Path("bundletool.jar"))

    def test_zipalign_failure_is_not_reported_as_success(self):
        with patch.object(PAGE, "command", side_effect=subprocess.CalledProcessError(1, ["zipalign"])):
            with self.assertRaises(subprocess.CalledProcessError):
                PAGE.check_zip_alignment(Path("candidate.apk"), "zipalign")

    def test_complete_verification_checks_generated_apk_and_preserves_inputs(self):
        apk = self.package(["lib/arm64-v8a/libmarmot_uniffi.so"])
        aab = self.root / "candidate.aab"
        with zipfile.ZipFile(aab, "w") as archive:
            for abi in ["arm64-v8a", "x86_64"]:
                archive.writestr(f"base/lib/{abi}/libmarmot_uniffi.so", b"native fixture")
        tool = self.root / "bundletool.jar"
        tool.write_bytes(b"fixture")
        before = [PAGE.sha256(path) for path in [apk, aab]]

        def tools(*args):
            """Simulate official tool outputs, including a real generated APK ZIP."""
            if "config" in args:
                return json.dumps({"optimizations": {"uncompressNativeLibraries": {"alignment": "PAGE_ALIGNMENT_16K"}}})
            if "build-apks" in args:
                output = Path(next(arg.split("=", 1)[1] for arg in args if str(arg).startswith("--output=")))
                generated = self.root / "universal.apk"
                with zipfile.ZipFile(generated, "w") as archive:
                    for abi in ["arm64-v8a", "x86_64"]:
                        archive.writestr(f"lib/{abi}/libmarmot_uniffi.so", b"native fixture")
                with zipfile.ZipFile(output, "w") as archive:
                    archive.write(generated, "universal.apk")
            return "LOAD off 0 align 2**14"

        with patch.object(PAGE, "command", side_effect=tools) as calls:
            report = PAGE.verify(apk, aab, tool, "objdump", "zipalign")
        self.assertEqual(before, [PAGE.sha256(path) for path in [apk, aab]])
        self.assertEqual(report["generatedApkZipAlignment"], "verified")
        self.assertEqual(len(report["generatedApkLibraries"]), 2)
        self.assertEqual(sum(call.args[0] == "zipalign" for call in calls.call_args_list), 2)

    def test_no_success_receipt_is_written_after_failed_verification(self):
        report = self.root / "receipt.json"
        arguments = ["verify", "--apk", "a.apk", "--aab", "a.aab", "--bundletool", "tool.jar",
                     "--llvm-objdump", "objdump", "--zipalign", "zipalign", "--report", str(report)]
        with patch("sys.argv", arguments), patch.object(PAGE, "verify", side_effect=ValueError("bad alignment")):
            with self.assertRaises(SystemExit) as error:
                PAGE.main()
        self.assertEqual(error.exception.code, 1)
        self.assertFalse(report.exists())


class RuntimePageSizeTests(unittest.TestCase):
    """A configured emulator image is insufficient without measured page size."""

    def test_actual_16kb_passes_and_4kb_or_malformed_values_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text('#!/bin/sh\nprintf "%s\\n" "$FIXTURE_PAGE_SIZE"\n')
            adb.chmod(0o755)
            for actual, expected_status in [("16384", 0), ("4096", 1), ("", 1), ("16384 junk", 1)]:
                with self.subTest(actual=actual):
                    result = subprocess.run(["bash", str(ROOT / "scripts/verify-runtime-page-size.sh"),
                                             "16384", str(root / "page-size.txt")],
                                            env={**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"],
                                                 "FIXTURE_PAGE_SIZE": actual}, capture_output=True, text=True)
                    self.assertEqual(result.returncode, expected_status, result.stderr)

    def test_release_verifier_refuses_4kb_before_installing_on_the_emulator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text('#!/bin/sh\ncase "$*" in\n"wait-for-device") exit 0;;\n'
                           '"shell getprop ro.kernel.qemu") echo 1;;\n'
                           '"shell getconf PAGE_SIZE") echo 4096;;\n'
                           '*) echo unexpected-adb-operation >&2; exit 99;;\nesac\n')
            adb.chmod(0o755)
            apk = root / "fixture.apk"
            apk.write_bytes(b"fixture")
            result = subprocess.run(["bash", str(ROOT / "scripts/verify-release-runtime.sh"), str(apk), "fixture.app"],
                                    env={**os.environ, "CI": "true", "REQUIRED_PAGE_SIZE_BYTES": "16384",
                                         "RELEASE_VERIFY_REPORT_DIR": str(root / "reports"),
                                         "PATH": str(root) + os.pathsep + os.environ["PATH"]},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn("runtime page size is 4096", result.stderr)
            self.assertNotIn("unexpected-adb-operation", result.stderr)


if __name__ == "__main__":
    unittest.main()
