#!/usr/bin/env python3
"""Verify hosted fixture bytes and record signature-only preparation for in-place installs.

Preparation requires an existing development keystore and the preserved installed
Dev APK. It compares signer identities and every non-signature ZIP entry. It does
not build code, generate keys, install packages, or modify app data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

OUTPUTS = {
    "app-devZapstoreDebug.apk": "dev.ipf.whitenoise.android.dev",
    "app-devZapstoreBenchmarkRelease.apk": "dev.ipf.whitenoise.android.dev",
    "benchmark-devZapstoreBenchmarkRelease.apk": "dev.ipf.whitenoise.android.benchmark",
}
SIGNATURE_ENTRY = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", re.I)


def digest(path):
    """Hash artifact bytes incrementally without retaining a native APK in memory."""
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else stream_digest(handle)


def stream_digest(handle):
    """Provide the same bounded hashing path on the repository's older host Python."""
    value = hashlib.sha256()
    for block in iter(lambda: handle.read(1024 * 1024), b""):
        value.update(block)
    return value.hexdigest()


def payload_digest(path):
    """Bind all compiled entries while excluding only recognized APK v1 signatures."""
    value = hashlib.sha256()
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        if len({e.filename for e in entries}) != len(entries):
            raise ValueError("duplicate_zip_entry")
        for entry in sorted(entries, key=lambda item: item.filename):
            if SIGNATURE_ENTRY.fullmatch(entry.filename):
                continue
            value.update(entry.filename.encode("utf-8") + b"\0")
            value.update(str(entry.file_size).encode("ascii") + b"\0")
            with archive.open(entry) as handle:
                value.update(bytes.fromhex(stream_digest(handle)))
    return value.hexdigest()


def read_manifest(directory, source_sha):
    """Require the expected CI candidate, exact package set and every downloaded hash."""
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("invalid_source")
    path = directory / "manifest.json"
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 16384:
        raise ValueError("invalid_manifest")
    manifest = json.loads(path.read_text())
    if type(manifest.get("schema")) is not int or manifest.get("schema") != 1 or manifest.get("source_sha") != source_sha:
        raise ValueError("stale_source")
    if manifest.get("requires_disposable_profile") is not True:
        raise ValueError("missing_profile_gate")
    if not re.fullmatch(r"[0-9a-f]{40}", manifest.get("mdk_sha", "")):
        raise ValueError("invalid_mdk_provenance")
    outputs = manifest.get("outputs")
    if not isinstance(outputs, list) or len(outputs) != len(OUTPUTS):
        raise ValueError("invalid_output_set")
    names = []
    for output in outputs:
        name = output.get("file")
        if name not in OUTPUTS or output.get("package") != OUTPUTS[name]:
            raise ValueError("unexpected_package")
        names.append(name)
        apk = directory / name
        if apk.is_symlink() or not apk.is_file() or digest(apk) != output.get("sha256"):
            raise ValueError("artifact_hash_mismatch")
    if set(names) != set(OUTPUTS) or len(set(names)) != len(names):
        raise ValueError("invalid_output_set")
    return manifest


def verify(directory, source_sha):
    """Verify either original hosted bytes or their recorded signature-only descendants."""
    manifest = read_manifest(directory, source_sha)
    signing = manifest.get("local_signing")
    if signing is not None:
        original = directory / "original"
        before = read_manifest(original, source_sha)
        if digest(original / "manifest.json") != signing.get("original_manifest_sha256"):
            raise ValueError("original_manifest_mismatch")
        if before["mdk_sha"] != manifest["mdk_sha"]:
            raise ValueError("mdk_provenance_mismatch")
        for name in OUTPUTS:
            if payload_digest(original / name) != payload_digest(directory / name):
                raise ValueError("compiled_payload_changed")
    return manifest


def certificate(apksigner, apk):
    """Read only the verified signer digest, without copying certificate names into the report."""
    output = subprocess.run([str(apksigner), "verify", "--print-certs", str(apk)],
                            check=True, capture_output=True, text=True).stdout
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", output, re.M)
    if len(fingerprints) != 1:
        raise ValueError("ambiguous_signer")
    return fingerprints[0]


def native_runtime(apk):
    """Hash the installed arm64 MDK runtime, refusing absent or ambiguous native entries."""
    name = "lib/arm64-v8a/libmarmot_uniffi.so"
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == name]
        if len(entries) != 1:
            raise ValueError("missing_native_runtime")
        with archive.open(entries[0]) as handle:
            return stream_digest(handle)


def verify_install(directory, source_sha, original_dev_apk, installed_dev_apk, apksigner, aapt):
    """Require exact preserved code, matching signers, package identities and native schema runtime."""
    manifest = verify(directory, source_sha)
    if digest(original_dev_apk) != digest(installed_dev_apk):
        raise ValueError("installed_dev_changed")
    signing = manifest.get("local_signing", {})
    if signing.get("preserved_dev_apk_sha256") != digest(original_dev_apk):
        raise ValueError("unbound_preserved_apk")
    signer = certificate(apksigner, original_dev_apk)
    if signing.get("certificate_sha256") != signer:
        raise ValueError("signer_receipt_mismatch")
    runtime = native_runtime(original_dev_apk)
    for name, package in OUTPUTS.items():
        apk = directory / name
        if certificate(apksigner, apk) != signer:
            raise ValueError("installed_dev_signer_mismatch")
        badging = subprocess.run([str(aapt), "dump", "badging", str(apk)],
                                 check=True, capture_output=True, text=True).stdout
        match = re.search(r"^package: name='([^']+)'", badging, re.M)
        if match is None or match.group(1) != package:
            raise ValueError("binary_package_mismatch")
        if package == OUTPUTS["app-devZapstoreDebug.apk"] and native_runtime(apk) != runtime:
            raise ValueError("installed_native_runtime_mismatch")
    return manifest


def prepare(source, destination, source_sha, original_dev_apk, keystore, apksigner):
    """Re-sign verified compiled bytes with an existing key that matches the preserved Dev APK."""
    manifest = read_manifest(source, source_sha)
    if not keystore.is_file() or not original_dev_apk.is_file() or destination.exists():
        raise ValueError("invalid_preparation_inputs")
    destination.mkdir(mode=0o700, parents=True)
    originals = destination / "original"
    originals.mkdir(mode=0o700)
    for name in ["manifest.json"] + list(OUTPUTS):
        shutil.copyfile(source / name, originals / name)
    expected_signer = certificate(apksigner, original_dev_apk)
    for output in manifest["outputs"]:
        name = output["file"]
        apk = destination / name
        subprocess.run([
            str(apksigner), "sign", "--ks", str(keystore), "--ks-key-alias", "androiddebugkey",
            "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--out", str(apk), str(originals / name),
        ], check=True, capture_output=True)
        if certificate(apksigner, apk) != expected_signer:
            raise ValueError("installed_dev_signer_mismatch")
        if payload_digest(originals / name) != payload_digest(apk):
            raise ValueError("compiled_payload_changed")
        output["sha256"] = digest(apk)
    manifest["local_signing"] = {
        "original_manifest_sha256": digest(originals / "manifest.json"),
        "certificate_sha256": expected_signer,
        "preserved_dev_apk_sha256": digest(original_dev_apk),
        "compiled_payload_unchanged": True,
    }
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return verify(destination, source_sha)


def main():
    """Expose verification and optional signing preparation; neither command touches a device."""
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="command", required=True)
    check = actions.add_parser("verify")
    check.add_argument("directory", type=Path)
    check.add_argument("source_sha")
    install = actions.add_parser("verify-install")
    install.add_argument("directory", type=Path)
    install.add_argument("source_sha")
    install.add_argument("--original-dev-apk", type=Path, required=True)
    install.add_argument("--installed-dev-apk", type=Path, required=True)
    install.add_argument("--apksigner", type=Path, required=True)
    install.add_argument("--aapt", type=Path, required=True)
    sign = actions.add_parser("prepare")
    sign.add_argument("source", type=Path)
    sign.add_argument("destination", type=Path)
    sign.add_argument("source_sha")
    sign.add_argument("--original-dev-apk", type=Path, required=True)
    sign.add_argument("--keystore", type=Path, required=True)
    sign.add_argument("--apksigner", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "verify":
            manifest = verify(args.directory, args.source_sha)
        elif args.command == "verify-install":
            manifest = verify_install(args.directory, args.source_sha, args.original_dev_apk,
                                      args.installed_dev_apk, args.apksigner, args.aapt)
        else:
            manifest = prepare(args.source, args.destination, args.source_sha,
                               args.original_dev_apk, args.keystore, args.apksigner)
    except (OSError, ValueError, TypeError, KeyError, zipfile.BadZipFile, subprocess.CalledProcessError):
        print("Fixture artifact verification failed; no device action was performed.", file=sys.stderr)
        return 1
    print(json.dumps({"source_sha": manifest["source_sha"], "mdk_sha": manifest["mdk_sha"], "verified": True}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
