#!/usr/bin/env python3
"""Hermetic regression tests for MarmotKit artifact preparation."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import stat
import struct
import tempfile
import unittest
import warnings
import zipfile
from argparse import Namespace
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).with_name("prepare_marmotkit_artifact.py")
SPEC = importlib.util.spec_from_file_location("prepare_marmotkit_artifact", SCRIPT)
assert SPEC and SPEC.loader
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)

SOURCE_SHA = "3fc4eb83974eb64ecb298856b0db70cc3055af57"
ROOT = "marmotkit-android-0.9.12"


def fake_elf(
    elf_class: int,
    machine: int,
    *,
    include_exports: bool = True,
    export_binding: int = 1,
    export_section: int = 3,
    trailing_data: bytes = b"",
) -> bytes:
    symbols = PREPARE.REQUIRED_ELF_EXPORTS if include_exports else ()
    strings = bytearray(b"\0")
    name_offsets = []
    for symbol in symbols:
        name_offsets.append(len(strings))
        strings.extend(symbol + b"\0")

    if elf_class == 1:
        header_format = "<16sHHIIIIIHHHHHH"
        section_format = "<IIIIIIIIII"
        symbol_format = "<IIIBBH"
    else:
        header_format = "<16sHHIQQQIHHHHHH"
        section_format = "<IIQQQQIIQQ"
        symbol_format = "<IBBHQQ"
    header_size = struct.calcsize(header_format)
    section_size = struct.calcsize(section_format)
    symbol_size = struct.calcsize(symbol_format)

    data = bytearray(header_size)
    string_offset = len(data)
    data.extend(strings)
    while len(data) % 8:
        data.append(0)
    symbol_offset = len(data)
    data.extend(b"\0" * symbol_size)
    for name_offset in name_offsets:
        if elf_class == 1:
            data.extend(struct.pack(symbol_format, name_offset, 0, 0, export_binding << 4, 0, export_section))
        else:
            data.extend(struct.pack(symbol_format, name_offset, export_binding << 4, 0, export_section, 0, 0))
    text_offset = len(data)
    data.append(0xC3)
    while len(data) % 8:
        data.append(0)
    section_offset = len(data)

    sections = [
        (0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        (0, 3, 0, 0, string_offset, len(strings), 0, 0, 1, 0),
        (0, 11, 0, 0, symbol_offset, symbol_size * (len(symbols) + 1), 1, 1, 8, symbol_size),
        (0, 1, 0x6, 0, text_offset, 1, 0, 0, 1, 0),
    ]
    for section in sections:
        data.extend(struct.pack(section_format, *section))
    data.extend(trailing_data)

    ident = b"\x7fELF" + bytes((elf_class, 1, 1)) + b"\0" * 9
    header = (
        ident,
        3,
        machine,
        1,
        0,
        0,
        section_offset,
        0,
        header_size,
        0,
        0,
        section_size,
        len(sections),
        0,
    )
    data[:header_size] = struct.pack(header_format, *header)
    return bytes(data)


def manifest(source_sha: str = SOURCE_SHA) -> dict[str, object]:
    return {
        "name": "marmotkit-android",
        "version": "0.9.12",
        "tag": "marmotkit-v0.9.12",
        "source_sha": source_sha,
        "workspace_version": "0.9.12",
        "cargo_lock_sha256": "a" * 64,
        "rustc": "rustc 1.89.0",
        "cargo": "cargo 1.89.0",
        "android_ndk_home": "/opt/android/ndk/27.2.12479018",
        "android_api": "26",
        "contents": list(PREPARE.MANIFEST_CONTENTS),
    }


class MarmotKitArtifactPreparationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.archive = self.root / "artifact.zip"
        self.lock = self.root / "MARMOT_VERSION"
        self.cache = self.root / "cache"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write_archive(
        self,
        *,
        manifest_value: dict[str, object] | None = None,
        omitted: set[str] | None = None,
        extra: str | None = None,
        wrong_abi: str | None = None,
        missing_exports_abi: str | None = None,
        duplicate: str | None = None,
        raw_entry: str | None = None,
        symlink_entry: str | None = None,
        manifest_bytes: bytes | None = None,
    ) -> None:
        omitted = omitted or set()
        entries: dict[str, bytes] = {
            relative: b"package fixture\n" for relative in PREPARE.KOTLIN_FILES
        }
        entries["kotlin/dev/ipf/marmotkit/marmot_uniffi.kt"] = (
            b"package fixture\n"
            b"public interface FixtureApi {}\n"
            b"fun uniffi_marmot_uniffi_checksum_method_fixture_call(): Short\n"
            b"if (lib.uniffi_marmot_uniffi_checksum_method_fixture_call() != 4242.toShort()) {}\n"
        )
        entries["kotlin/dev/ipf/marmotkit/MarmotAndroid.kt"] = (
            b"package fixture\n"
            b"object MarmotAndroid {\n"
            b"    fun initialize(context: Any) {}\n"
            b"}\n"
        )
        entries["kotlin/io/crates/keyring/Keyring.kt"] = (
            b"package fixture\n"
            b"class Keyring {\n"
            b"    companion object {\n"
            b"        external fun initializeNdkContext(context: Any)\n"
            b"    }\n"
            b"}\n"
        )
        for abi, (elf_class, machine) in PREPARE.ABI_MACHINES.items():
            if abi == wrong_abi:
                machine = 3 if machine != 3 else 183
            entries[f"jniLibs/{abi}/libmarmot_uniffi.so"] = fake_elf(
                elf_class,
                machine,
                include_exports=abi != missing_exports_abi,
            )
        entries["manifest.json"] = manifest_bytes or json.dumps(manifest_value or manifest()).encode()
        with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
            for relative, data in entries.items():
                if relative not in omitted:
                    output.writestr(f"{ROOT}/{relative}", data)
            if extra:
                output.writestr(f"{ROOT}/{extra}", b"unexpected")
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    output.writestr(f"{ROOT}/{duplicate}", entries[duplicate])
            if raw_entry:
                output.writestr(raw_entry, b"unsafe")
            if symlink_entry:
                info = zipfile.ZipInfo(f"{ROOT}/{symlink_entry}")
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                output.writestr(info, b"target")

    def write_lock(self, checksum: str | None = None) -> None:
        checksum = checksum or hashlib.sha256(self.archive.read_bytes()).hexdigest()
        self.lock.write_text(
            "\n".join(
                (
                    "schema=1",
                    "source=marmot-protocol/mdk",
                    f"mdk-sha={SOURCE_SHA}",
                    "mdk-short-sha=3fc4eb83",
                    "mdk-branch=master",
                    "features=otlp-export",
                    "artifact-id=0.9.12",
                    "artifact-tag=marmotkit-v0.9.12",
                    "artifact-url=https://invalid.example/artifact.zip",
                    f"artifact-sha256={checksum}",
                    f"archive-root={ROOT}",
                    "manifest-schema=mdk-android-v1",
                    "workspace-version=0.9.12",
                    "android-api=26",
                    "api-type-count=1",
                    "api-checksum-count=1",
                    "api-helper-declaration-count=5",
                ),
            )
            + "\n",
            encoding="utf-8",
        )

    def prepare(self) -> Path:
        return PREPARE.prepare(
            Namespace(
                lock=self.lock,
                cache_root=self.cache,
                artifact=self.archive,
                offline=True,
            ),
        )

    def assert_rejected(self, message: str) -> None:
        with self.assertRaisesRegex(PREPARE.PreparationError, message):
            self.prepare()

    def test_success_prepares_all_files_and_reuses_valid_cache(self) -> None:
        self.write_archive()
        self.write_lock()

        output = self.prepare()
        marker_mtime = (output / ".prepared.json").stat().st_mtime_ns
        reused = self.prepare()

        self.assertEqual(output, reused)
        self.assertEqual(marker_mtime, (output / ".prepared.json").stat().st_mtime_ns)
        for relative in PREPARE.PAYLOAD_FILES:
            self.assertTrue((output / relative).is_file(), relative)
        self.assertTrue((output / "prepared-files.sha256").is_file())
        api_signature = (output / "marmotkit-api-signature.txt").read_text(encoding="utf-8")
        self.assertIn("artifact-id=0.9.12", api_signature)
        self.assertIn("public interface FixtureApi", api_signature)
        self.assertIn("uniffi_marmot_uniffi_checksum_method_fixture_call=4242", api_signature)
        self.assertIn("MarmotAndroid.kt:object MarmotAndroid", api_signature)
        self.assertIn("MarmotAndroid.kt:fun initialize(context: Any)", api_signature)
        self.assertIn("Keyring.kt:external fun initializeNdkContext(context: Any)", api_signature)
        for relative in PREPARE.KOTLIN_FILES:
            self.assertIn(f"{relative}=", api_signature)

    def test_mutated_cache_and_marker_are_rebuilt_from_verified_archive(self) -> None:
        self.write_archive()
        self.write_lock()
        output = self.prepare()
        binding = output / "kotlin/dev/ipf/marmotkit/marmot_uniffi.kt"
        binding.write_text("attacker-controlled\n", encoding="utf-8")
        marker = output / ".prepared.json"
        metadata = json.loads(marker.read_text(encoding="utf-8"))
        metadata["files"]["kotlin/dev/ipf/marmotkit/marmot_uniffi.kt"] = hashlib.sha256(binding.read_bytes()).hexdigest()
        marker.write_text(json.dumps(metadata), encoding="utf-8")

        self.prepare()

        self.assertIn("FixtureApi", binding.read_text(encoding="utf-8"))

    def test_mutated_api_signature_and_marker_are_rebuilt(self) -> None:
        self.write_archive()
        self.write_lock()
        output = self.prepare()
        signature = output / "marmotkit-api-signature.txt"
        signature.write_text("attacker-controlled\n", encoding="utf-8")
        marker = output / ".prepared.json"
        metadata = json.loads(marker.read_text(encoding="utf-8"))
        metadata["api_signature_sha256"] = hashlib.sha256(signature.read_bytes()).hexdigest()
        marker.write_text(json.dumps(metadata), encoding="utf-8")

        self.prepare()

        self.assertIn("uniffi-api-checksums", signature.read_text(encoding="utf-8"))

    def test_verified_override_is_persisted_for_offline_reuse(self) -> None:
        self.write_archive()
        self.write_lock()
        output = self.prepare()
        self.archive.unlink()

        reused = PREPARE.prepare(
            Namespace(lock=self.lock, cache_root=self.cache, artifact=None, offline=True),
        )

        self.assertEqual(output, reused)

    def test_checksum_mismatch_fails_closed(self) -> None:
        self.write_archive()
        self.write_lock("0" * 64)
        self.assert_rejected("checksum mismatch")

    def test_provenance_mismatch_fails_closed(self) -> None:
        self.write_archive(manifest_value=manifest(source_sha="b" * 40))
        self.write_lock()
        self.assert_rejected("manifest source_sha mismatch")

    def test_unexpected_layout_fails_closed(self) -> None:
        self.write_archive(extra="kotlin/Unexpected.kt")
        self.write_lock()
        self.assert_rejected("unexpected archive entry")

    def test_missing_abi_fails_closed(self) -> None:
        self.write_archive(omitted={"jniLibs/x86_64/libmarmot_uniffi.so"})
        self.write_lock()
        self.assert_rejected("missing archive entries")

    def test_duplicate_entry_fails_closed(self) -> None:
        self.write_archive(duplicate="kotlin/dev/ipf/marmotkit/marmot_uniffi.kt")
        self.write_lock()
        self.assert_rejected("duplicate archive entry")

    def test_wrong_elf_architecture_fails_closed(self) -> None:
        self.write_archive(wrong_abi="arm64-v8a")
        self.write_lock()
        self.assert_rejected("wrong ELF architecture")

    def test_archive_entry_outside_root_fails_closed(self) -> None:
        self.write_archive(raw_entry="outside-root/payload")
        self.write_lock()
        self.assert_rejected("unsafe archive entry")

    def test_archive_parent_traversal_fails_closed(self) -> None:
        self.write_archive(raw_entry=f"{ROOT}/../payload")
        self.write_lock()
        self.assert_rejected("unsafe archive entry")

    def test_archive_backslash_path_fails_closed(self) -> None:
        self.write_archive(raw_entry=f"{ROOT}/unsafe\\payload")
        self.write_lock()
        self.assert_rejected("unsafe archive entry")

    def test_archive_symlink_fails_closed(self) -> None:
        symlink_entry = PREPARE.KOTLIN_FILES[1]
        self.write_archive(omitted={symlink_entry}, symlink_entry=symlink_entry)
        self.write_lock()
        self.assert_rejected("symbolic links are not allowed")

    def test_missing_elf_export_fails_closed(self) -> None:
        self.write_archive(missing_exports_abi="x86_64")
        self.write_lock()
        self.assert_rejected("missing expected export")

    def test_elf_names_outside_dynamic_symbol_table_are_rejected(self) -> None:
        decoy_names = b"\0".join(PREPARE.REQUIRED_ELF_EXPORTS) + b"\0"
        data = fake_elf(2, 62, include_exports=False, trailing_data=decoy_names)
        library = self.root / "libmarmot_uniffi.so"
        library.write_bytes(data)

        with self.assertRaisesRegex(PREPARE.PreparationError, "missing expected export"):
            PREPARE.validate_elf(library, "x86_64")

    def test_undefined_dynamic_symbols_are_rejected(self) -> None:
        library = self.root / "libmarmot_uniffi.so"
        library.write_bytes(fake_elf(2, 62, export_section=0))

        with self.assertRaisesRegex(PREPARE.PreparationError, "missing expected export"):
            PREPARE.validate_elf(library, "x86_64")

    def test_local_dynamic_symbols_are_rejected(self) -> None:
        library = self.root / "libmarmot_uniffi.so"
        library.write_bytes(fake_elf(2, 62, export_binding=0))

        with self.assertRaisesRegex(PREPARE.PreparationError, "missing expected export"):
            PREPARE.validate_elf(library, "x86_64")

    def test_malformed_manifest_fails_closed(self) -> None:
        self.write_archive(manifest_bytes=b"{")
        self.write_lock()
        self.assert_rejected("malformed artifact manifest")

    def test_duplicate_manifest_key_fails_closed(self) -> None:
        manifest_json = json.dumps(manifest())
        duplicate_manifest = manifest_json.replace('"name":', '"name": "duplicate", "name":', 1).encode()
        self.write_archive(manifest_bytes=duplicate_manifest)
        self.write_lock()
        self.assert_rejected("duplicate artifact manifest key")

    def test_offline_cache_miss_fails_closed(self) -> None:
        self.write_archive()
        self.write_lock()
        self.archive.unlink()

        with self.assertRaisesRegex(PREPARE.PreparationError, "not cached for offline use"):
            PREPARE.prepare(
                Namespace(lock=self.lock, cache_root=self.cache, artifact=None, offline=True),
            )

    def test_non_https_artifact_url_fails_closed(self) -> None:
        self.write_archive()
        self.write_lock()
        self.lock.write_text(
            self.lock.read_text(encoding="utf-8").replace(
                "https://invalid.example/artifact.zip",
                "file:///tmp/artifact.zip",
            ),
            encoding="utf-8",
        )
        self.assert_rejected("absolute HTTPS URL")

    def test_non_https_artifact_redirect_fails_closed(self) -> None:
        handler = PREPARE.HttpsOnlyRedirectHandler()
        request = PREPARE.urllib.request.Request("https://invalid.example/artifact.zip")

        with self.assertRaisesRegex(PREPARE.PreparationError, "redirect must use HTTPS"):
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {},
                "http://invalid.example/artifact.zip",
            )

    def test_oversized_artifact_download_fails_closed(self) -> None:
        class FakeResponse(io.BytesIO):
            status = 200
            headers = {"Content-Length": str(PREPARE.MAX_ARCHIVE_DOWNLOAD_BYTES + 1)}

            def geturl(self) -> str:
                return "https://invalid.example/artifact.zip"

        opener = mock.Mock()
        opener.open.return_value = FakeResponse(b"")
        destination = self.root / "download.zip"

        with mock.patch.object(PREPARE.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(PREPARE.PreparationError, "compressed size limit"):
                PREPARE.download("https://invalid.example/artifact.zip", destination)

        self.assertFalse(destination.exists())

    def test_streamed_artifact_download_is_bounded_without_content_length(self) -> None:
        class FakeResponse(io.BytesIO):
            status = 200
            headers: dict[str, str] = {}

            def geturl(self) -> str:
                return "https://invalid.example/artifact.zip"

        opener = mock.Mock()
        opener.open.return_value = FakeResponse(b"12345")
        destination = self.root / "download.zip"

        with (
            mock.patch.object(PREPARE.urllib.request, "build_opener", return_value=opener),
            mock.patch.object(PREPARE, "MAX_ARCHIVE_DOWNLOAD_BYTES", 4),
        ):
            with self.assertRaisesRegex(PREPARE.PreparationError, "compressed size limit"):
                PREPARE.download("https://invalid.example/artifact.zip", destination)

        self.assertFalse(destination.exists())

    def test_transient_download_failure_retries_then_succeeds(self) -> None:
        class FakeResponse(io.BytesIO):
            status = 200
            headers: dict[str, str] = {}

            def geturl(self) -> str:
                return "https://invalid.example/artifact.zip"

        opener = mock.Mock()
        opener.open.side_effect = [
            PREPARE.urllib.error.URLError("temporary failure"),
            FakeResponse(b"verified bytes"),
        ]
        destination = self.root / "download.zip"

        with (
            mock.patch.object(PREPARE.urllib.request, "build_opener", return_value=opener),
            mock.patch.object(PREPARE.time, "sleep") as sleep,
        ):
            PREPARE.download("https://invalid.example/artifact.zip", destination)

        self.assertEqual(destination.read_bytes(), b"verified bytes")
        self.assertEqual(opener.open.call_count, 2)
        sleep.assert_called_once_with(1)

    def test_non_retryable_http_failure_is_not_retried(self) -> None:
        opener = mock.Mock()
        opener.open.side_effect = PREPARE.urllib.error.HTTPError(
            "https://invalid.example/artifact.zip",
            404,
            "Not Found",
            {},
            None,
        )
        destination = self.root / "download.zip"

        with (
            mock.patch.object(PREPARE.urllib.request, "build_opener", return_value=opener),
            mock.patch.object(PREPARE.time, "sleep") as sleep,
        ):
            with self.assertRaises(PREPARE.urllib.error.HTTPError):
                PREPARE.download("https://invalid.example/artifact.zip", destination)

        self.assertEqual(opener.open.call_count, 1)
        sleep.assert_not_called()
        self.assertFalse(destination.exists())

    def test_transient_download_failure_stops_after_three_attempts(self) -> None:
        opener = mock.Mock()
        opener.open.side_effect = PREPARE.urllib.error.HTTPError(
            "https://invalid.example/artifact.zip",
            503,
            "Unavailable",
            {},
            None,
        )
        destination = self.root / "download.zip"

        with (
            mock.patch.object(PREPARE.urllib.request, "build_opener", return_value=opener),
            mock.patch.object(PREPARE.time, "sleep") as sleep,
        ):
            with self.assertRaises(PREPARE.urllib.error.HTTPError):
                PREPARE.download("https://invalid.example/artifact.zip", destination)

        self.assertEqual(opener.open.call_count, 3)
        self.assertEqual(sleep.call_args_list, [mock.call(1), mock.call(2)])
        self.assertFalse(destination.exists())

    def test_api_signature_count_drift_fails_closed(self) -> None:
        self.write_archive()
        self.write_lock()
        self.lock.write_text(
            self.lock.read_text(encoding="utf-8").replace("api-type-count=1", "api-type-count=2"),
            encoding="utf-8",
        )
        self.assert_rejected("API signature count mismatch")

    def test_unsupported_manifest_schema_fails_closed(self) -> None:
        self.write_archive()
        self.write_lock()
        self.lock.write_text(
            self.lock.read_text(encoding="utf-8").replace(
                "manifest-schema=mdk-android-v1",
                "manifest-schema=mdk-android-v2",
            ),
            encoding="utf-8",
        )
        self.assert_rejected("unsupported artifact manifest schema")


if __name__ == "__main__":
    unittest.main()
