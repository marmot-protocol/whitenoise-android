"""Build a genuinely signed 30 to 31 MiB APK payload on the host for the received-APK fixture; nothing installs it."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

PAD_ENTRY = "assets/fixture-pad.bin"
MANIFEST_ENTRY = "AndroidManifest.xml"
MIN_BYTES = 30 * 1024 * 1024
# The probe refuses anything above 31 MiB, which keeps the payload under the 32 MiB Android controller cap.
MAX_BYTES = 31 * 1024 * 1024
DEFAULT_TARGET_BYTES = MAX_BYTES - 64 * 1024
CHUNK = 64 * 1024
# A stored entry costs a local header, a central directory entry and the name twice, plus a little slack.
ENTRY_OVERHEAD = 30 + 46 + 2 * len(PAD_ENTRY) + 64
MIN_SDK = "30"
DEFAULT_KEYSTORE = Path.home() / ".android" / "debug.keystore"
DEFAULT_ALIAS = "androiddebugkey"
# The AOSP debug keystore ships with this fixed, documented password, it protects nothing.
DEBUG_STORE_PASSWORD = "android"
DIGEST_LINE = re.compile(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})")


def build_tools(sdk_root):
    """Pick the newest build-tools directory that ships both zipalign and apksigner, or refuse."""
    root = Path(sdk_root) / "build-tools"
    candidates = sorted((d for d in root.iterdir() if d.is_dir()), key=lambda d: d.name) if root.is_dir() else []
    for directory in reversed(candidates):
        if (directory / "zipalign").is_file() and (directory / "apksigner").is_file():
            return directory
    raise ValueError("no build-tools with zipalign and apksigner under the SDK root")


def pad_bytes(size):
    """Yield deterministic, incompressible-looking padding in bounded chunks without allocating it all."""
    seed = hashlib.sha256(PAD_ENTRY.encode()).digest()
    block = seed * (CHUNK // len(seed))
    for offset in range(0, size, CHUNK):
        yield block[: min(CHUNK, size - offset)]


def has_manifest(apk):
    """True when the archive is a readable ZIP carrying a non-directory AndroidManifest.xml entry."""
    try:
        with zipfile.ZipFile(apk) as archive:
            return archive.testzip() is None and any(
                info.filename == MANIFEST_ENTRY and not info.is_dir() for info in archive.infolist())
    except (OSError, zipfile.BadZipFile):
        return False


def padded_apk(source, target_bytes, output, minimum=MIN_BYTES, maximum=MAX_BYTES):
    """Copy the source APK and append one stored pad entry so the unsigned result lands near target_bytes."""
    source, output = Path(source), Path(output)
    if not minimum <= target_bytes <= maximum:
        raise ValueError("target size outside the 30 to 31 MiB payload range")
    if not has_manifest(source):
        raise ValueError("source is not an APK-shaped archive with an Android manifest")
    with zipfile.ZipFile(source) as archive:
        if any(info.filename == PAD_ENTRY for info in archive.infolist()):
            raise ValueError("source already carries the fixture pad entry")
    pad = target_bytes - source.stat().st_size - ENTRY_OVERHEAD
    if pad <= 0:
        raise ValueError("source APK is already larger than the target payload")
    shutil.copyfile(source, output)
    os.chmod(output, 0o600)
    info = zipfile.ZipInfo(PAD_ENTRY, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_STORED
    info.external_attr = 0o644 << 16
    # Append mode writes over the old signing block, which apksigner replaces anyway.
    with zipfile.ZipFile(output, "a") as archive, archive.open(info, "w") as entry:
        for chunk in pad_bytes(pad):
            entry.write(chunk)
    size = output.stat().st_size
    if not minimum <= size <= maximum or not has_manifest(output):
        raise ValueError("padded APK left the payload range or lost its manifest")
    return size


def align_and_sign(unsigned, output, tools, keystore, alias, store_password, run=subprocess.run):
    """zipalign first, then sign with the given key and verify, the order APK signature schemes require."""
    tools, output = Path(tools), Path(output)
    with tempfile.TemporaryDirectory(prefix="wn-apk-payload-") as scratch:
        aligned = Path(scratch) / "aligned.apk"
        run([str(tools / "zipalign"), "-p", "-f", "4", str(unsigned), str(aligned)], check=True,
            capture_output=True, text=True)
        run([str(tools / "apksigner"), "sign", "--ks", str(keystore), "--ks-key-alias", alias,
             "--ks-pass", f"pass:{store_password}", "--key-pass", f"pass:{store_password}",
             "--min-sdk-version", MIN_SDK, "--out", str(output), str(aligned)], check=True,
            capture_output=True, text=True)
    run([str(tools / "apksigner"), "verify", "--min-sdk-version", MIN_SDK, str(output)], check=True,
        capture_output=True, text=True)
    os.chmod(output, 0o600)


def signer_digest(apksigner, apk, run=subprocess.run):
    """The single signer certificate SHA-256 of an APK as apksigner prints it, or a refusal for anything else."""
    printed = run([str(apksigner), "verify", "--print-certs", "--min-sdk-version", MIN_SDK, str(apk)],
                  check=True, capture_output=True, text=True).stdout
    digests = DIGEST_LINE.findall(printed)
    if len(digests) != 1:
        raise ValueError("expected exactly one signer certificate")
    return digests[0]


def sha256_of(path):
    """Hex SHA-256 of a file, streamed."""
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe(apk, apksigner=None, run=subprocess.run):
    """Closed facts about a payload for the report: size, digest, manifest presence and, when asked, the signer."""
    apk = Path(apk)
    facts = {"bytes": apk.stat().st_size, "sha256": sha256_of(apk), "has_manifest": has_manifest(apk),
             "within_payload_range": MIN_BYTES <= apk.stat().st_size <= MAX_BYTES}
    if apksigner is not None:
        facts["signer_sha256"] = signer_digest(apksigner, apk, run)
    return facts


def build(source, output, target_bytes, tools, keystore, alias, store_password, run=subprocess.run):
    """Pad, align, sign and describe; the receipt never includes the key material or its password."""
    output = Path(output)
    with tempfile.TemporaryDirectory(prefix="wn-apk-payload-") as scratch:
        unsigned = Path(scratch) / "unsigned.apk"
        padded_apk(source, target_bytes, unsigned)
        align_and_sign(unsigned, output, tools, keystore, alias, store_password, run)
    receipt = describe(output, Path(tools) / "apksigner", run)
    receipt["source_sha256"] = sha256_of(source)
    if not receipt["within_payload_range"] or not receipt["has_manifest"]:
        raise ValueError("signed payload left the payload range or lost its manifest")
    return receipt


def main():
    """Require explicit source and output paths; never contact a device."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-apk", type=Path, required=True, help="The built isolated test APK to pad")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--target-bytes", type=int, default=DEFAULT_TARGET_BYTES)
    parser.add_argument("--sdk-root", default=os.environ.get("ANDROID_HOME", ""))
    parser.add_argument("--keystore", type=Path, default=DEFAULT_KEYSTORE)
    parser.add_argument("--alias", default=DEFAULT_ALIAS)
    parser.add_argument("--store-password-env", help="Environment variable holding a non-debug keystore password")
    args = parser.parse_args()
    password = os.environ[args.store_password_env] if args.store_password_env else DEBUG_STORE_PASSWORD
    receipt = build(args.source_apk, args.output, args.target_bytes, build_tools(args.sdk_root), args.keystore,
                    args.alias, password)
    print(json.dumps(receipt, indent=2))


if __name__ == "__main__":
    main()
