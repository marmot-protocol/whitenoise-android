#!/usr/bin/env python3
"""Verify final APK/AAB 16 KB packaging without altering the release candidate."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


LIBRARY = re.compile(r"(?:[^/]+/)?lib/(arm64-v8a|x86_64)/[^/]+\.so$")


def command(*args):
    """Run an SDK tool with a bounded deadline and preserve failure diagnostics."""
    return subprocess.check_output([str(arg) for arg in args], text=True, stderr=subprocess.STDOUT, timeout=180)


def sha256(path):
    """Hash large artifacts incrementally rather than retaining them in memory."""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def check_libraries(path, objdump, required_abis):
    """Check every packaged 64-bit dependency, including libraries outside MarmotKit."""
    with zipfile.ZipFile(path) as archive, tempfile.TemporaryDirectory() as directory:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate package ZIP entries")
        libraries = [(name, LIBRARY.fullmatch(name)) for name in sorted(names) if LIBRARY.fullmatch(name)]
        present = {match[1] for _, match in libraries}
        missing = required_abis - present
        if missing:
            raise ValueError("Missing native ABI(s): " + ", ".join(sorted(missing)))
        records = []
        for index, (name, match) in enumerate(libraries):
            # Never extract an archive-controlled pathname.
            library = Path(directory) / (str(index) + ".so")
            with archive.open(name) as source, library.open("wb") as target:
                shutil.copyfileobj(source, target)
            output = command(objdump, "-p", library)
            loads = [line for line in output.splitlines() if re.match(r"\s*LOAD\s", line)]
            alignments = []
            for line in loads:
                alignment = re.search(r"\balign 2\*\*(\d+)\s*$", line)
                if alignment is None or not 14 <= int(alignment[1]) <= 63:
                    raise ValueError(f"{name}: PT_LOAD does not satisfy 16 KB alignment: {line.strip()}")
                alignments.append(1 << int(alignment[1]))
            if not alignments:
                raise ValueError(f"{name}: no readable PT_LOAD segments")
            records.append({"path": name, "abi": match[1], "sha256": sha256(library),
                            "loadAlignments": alignments})
        return records


def check_bundle_config(aab, bundletool):
    """Require bundletool's actual native packaging policy to request 16 KB alignment."""
    config = json.loads(command("java", "-jar", bundletool, "dump", "config", f"--bundle={aab}"))
    alignment = config.get("optimizations", {}).get("uncompressNativeLibraries", {}).get("alignment")
    if alignment != "PAGE_ALIGNMENT_16K":
        raise ValueError("AAB must request PAGE_ALIGNMENT_16K")
    return alignment


def check_zip_alignment(apk, zipalign):
    """Ask Android Build Tools to verify native entry offsets without rewriting the APK."""
    command(zipalign, "-c", "-P", "16", "-v", "4", apk)


def verify(apk, aab, bundletool, objdump, zipalign):
    """Check direct packaging and a disposable bundletool-generated universal APK."""
    report = {"schemaVersion": 1, "requiredPageSizeBytes": 16384,
              "apkSha256": sha256(apk), "aabSha256": sha256(aab),
              "bundletoolSha256": sha256(bundletool)}
    report["directApkLibraries"] = check_libraries(apk, objdump, {"arm64-v8a"})
    report["bundleLibraries"] = check_libraries(aab, objdump, {"arm64-v8a", "x86_64"})
    report["bundleAlignment"] = check_bundle_config(aab, bundletool)
    check_zip_alignment(apk, zipalign)
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        keystore = root / "verification.jks"
        # Signing only enables bundletool's packaging rehearsal. Never load a production key.
        command("keytool", "-genkeypair", "-keystore", keystore, "-storepass", "android",
                "-alias", "verification", "-keypass", "android", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-dname", "CN=Disposable 16 KB packaging verification")
        apks = root / "verification.apks"
        command("java", "-jar", bundletool, "build-apks", f"--bundle={aab}", f"--output={apks}",
                "--mode=universal", f"--ks={keystore}", "--ks-key-alias=verification",
                "--ks-pass=pass:android", "--key-pass=pass:android")
        generated = root / "universal.apk"
        with zipfile.ZipFile(apks) as archive, archive.open("universal.apk") as source, generated.open("wb") as target:
            shutil.copyfileobj(source, target)
        report["generatedApkLibraries"] = check_libraries(generated, objdump, {"arm64-v8a", "x86_64"})
        check_zip_alignment(generated, zipalign)
        report["generatedApkSha256"] = sha256(generated)
    report["directApkZipAlignment"] = "verified"
    report["generatedApkZipAlignment"] = "verified"
    report["generatedApkSigning"] = "disposable verification key"
    return report


def main():
    """Write a fresh receipt only after all final-artifact checks succeed."""
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("apk", "aab", "bundletool", "llvm-objdump", "zipalign", "report"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    if args.report.exists():
        parser.error("report already exists; choose a fresh evidence path")
    try:
        report = verify(args.apk, args.aab, args.bundletool, args.llvm_objdump, args.zipalign)
        args.report.parent.mkdir(parents=True, exist_ok=True)
        with args.report.open("x") as output:
            output.write(json.dumps(report, indent=2, sort_keys=True) + "\n")
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        detail = getattr(error, "output", None) or str(error)
        parser.exit(1, f"16 KB verification failed: {detail}\n")
    print(f"16 KB packaging verified; receipt: {args.report}")


if __name__ == "__main__":
    main()
