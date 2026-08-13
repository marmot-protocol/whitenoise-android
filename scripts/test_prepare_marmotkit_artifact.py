#!/usr/bin/env python3
"""Hermetic regression tests for MarmotKit artifact preparation."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
import warnings
import zipfile
from argparse import Namespace
from pathlib import Path


SCRIPT = Path(__file__).with_name("prepare_marmotkit_artifact.py")
SPEC = importlib.util.spec_from_file_location("prepare_marmotkit_artifact", SCRIPT)
assert SPEC and SPEC.loader
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)

SOURCE_SHA = "3fc4eb83974eb64ecb298856b0db70cc3055af57"
ROOT = "marmotkit-android-0.9.12"


def fake_elf(elf_class: int, machine: int) -> bytes:
    data = bytearray(64)
    data[:6] = b"\x7fELF" + bytes((elf_class, 1))
    data[18:20] = machine.to_bytes(2, "little")
    data.extend(b"uniffi_marmot_uniffi_fn_constructor_marmot_new\0")
    data.extend(b"Java_io_crates_keyring_Keyring_00024Companion_initializeNdkContext\0")
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
        duplicate: str | None = None,
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
        for abi, (elf_class, machine) in PREPARE.ABI_MACHINES.items():
            if abi == wrong_abi:
                machine = 3 if machine != 3 else 183
            entries[f"jniLibs/{abi}/libmarmot_uniffi.so"] = fake_elf(elf_class, machine)
        entries["manifest.json"] = json.dumps(manifest_value or manifest()).encode()
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
