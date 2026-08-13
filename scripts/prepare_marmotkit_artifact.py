#!/usr/bin/env python3
"""Download, validate, and atomically prepare the pinned MarmotKit Android bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import sys
import tempfile
import urllib.parse
import urllib.request
import zipfile
from contextlib import contextmanager
from pathlib import Path, PurePosixPath

try:
    import fcntl
except ImportError:  # Windows
    fcntl = None
    import msvcrt


KOTLIN_FILES = (
    "kotlin/dev/ipf/marmotkit/marmot_uniffi.kt",
    "kotlin/dev/ipf/marmotkit/MarmotAndroid.kt",
    "kotlin/io/crates/keyring/Keyring.kt",
)
ABI_MACHINES = {
    "arm64-v8a": (2, 183),
    "armeabi-v7a": (1, 40),
    "x86": (1, 3),
    "x86_64": (2, 62),
}
JNI_FILES = tuple(f"jniLibs/{abi}/libmarmot_uniffi.so" for abi in ABI_MACHINES)
PAYLOAD_FILES = (*KOTLIN_FILES, *JNI_FILES, "manifest.json")
MANIFEST_CONTENTS = (KOTLIN_FILES[0], *JNI_FILES)
REQUIRED_PROPERTIES = {
    "schema",
    "source",
    "mdk-sha",
    "mdk-short-sha",
    "features",
    "artifact-id",
    "artifact-tag",
    "artifact-url",
    "artifact-sha256",
    "archive-root",
    "manifest-schema",
    "workspace-version",
    "android-api",
    "api-type-count",
    "api-checksum-count",
    "api-helper-declaration-count",
}
MAX_FILE_BYTES = 200 * 1024 * 1024
MAX_ARCHIVE_UNCOMPRESSED_BYTES = 600 * 1024 * 1024
MAX_ARCHIVE_DOWNLOAD_BYTES = 256 * 1024 * 1024


class PreparationError(RuntimeError):
    """The pinned artifact failed a fail-closed preparation check."""


class HttpsOnlyRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Allow artifact redirects only when the resolved destination stays on HTTPS."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None and urllib.parse.urlsplit(redirected.full_url).scheme != "https":
            raise PreparationError(f"artifact redirect must use HTTPS: {redirected.full_url}")
        return redirected


def parse_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for line_number, raw_line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key.strip() or not value.strip():
            raise PreparationError(f"invalid lock entry at {path}:{line_number}")
        key = key.strip()
        if key in properties:
            raise PreparationError(f"duplicate lock property: {key}")
        properties[key] = value.strip()
    missing = sorted(REQUIRED_PROPERTIES - properties.keys())
    if missing:
        raise PreparationError(f"missing lock properties: {', '.join(missing)}")
    if properties["schema"] != "1":
        raise PreparationError(f"unsupported lock schema: {properties['schema']}")
    sha = properties["artifact-sha256"]
    source_sha = properties["mdk-sha"]
    if len(sha) != 64 or any(character not in "0123456789abcdef" for character in sha):
        raise PreparationError("artifact-sha256 must be a lowercase SHA-256")
    if len(source_sha) != 40 or any(character not in "0123456789abcdef" for character in source_sha):
        raise PreparationError("mdk-sha must be a lowercase full Git SHA")
    if properties["mdk-short-sha"] != source_sha[:8]:
        raise PreparationError("mdk-short-sha does not match mdk-sha")
    artifact_url = urllib.parse.urlsplit(properties["artifact-url"])
    if artifact_url.scheme != "https" or not artifact_url.netloc:
        raise PreparationError("artifact-url must be an absolute HTTPS URL")
    for key in ("api-type-count", "api-checksum-count", "api-helper-declaration-count"):
        if not properties[key].isdigit() or int(properties[key]) <= 0:
            raise PreparationError(f"{key} must be a positive integer")
    root = PurePosixPath(properties["archive-root"])
    if root.is_absolute() or len(root.parts) != 1 or root.name in {"", ".", ".."}:
        raise PreparationError("archive-root must be one safe directory name")
    return properties


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".part", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "whitenoise-android-marmotkit/1"})
        opener = urllib.request.build_opener(HttpsOnlyRedirectHandler())
        with os.fdopen(descriptor, "wb") as output, opener.open(request, timeout=60) as response:
            if response.status != 200:
                raise PreparationError(f"artifact download returned HTTP {response.status}")
            final_url = response.geturl()
            if urllib.parse.urlsplit(final_url).scheme != "https":
                raise PreparationError(f"artifact download resolved to a non-HTTPS URL: {final_url}")
            content_length = response.headers.get("Content-Length")
            if content_length is not None:
                try:
                    declared_size = int(content_length)
                except ValueError as error:
                    raise PreparationError("artifact download returned an invalid Content-Length") from error
                if declared_size < 0 or declared_size > MAX_ARCHIVE_DOWNLOAD_BYTES:
                    raise PreparationError("artifact download exceeds the compressed size limit")
            downloaded = 0
            while chunk := response.read(1024 * 1024):
                downloaded += len(chunk)
                if downloaded > MAX_ARCHIVE_DOWNLOAD_BYTES:
                    raise PreparationError("artifact download exceeds the compressed size limit")
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def copy_atomically(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{destination.name}.", suffix=".part", dir=destination.parent)
    temporary = Path(temporary_name)
    try:
        with source.open("rb") as input_file, os.fdopen(descriptor, "wb") as output:
            shutil.copyfileobj(input_file, output, length=1024 * 1024)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, destination)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def ensure_archive(
    properties: dict[str, str],
    cache_root: Path,
    supplied_artifact: Path | None,
    offline: bool,
) -> Path:
    expected_sha = properties["artifact-sha256"]
    archive = cache_root / "downloads" / f"{expected_sha}.zip"
    if supplied_artifact is not None:
        if not supplied_artifact.is_file():
            raise PreparationError(f"artifact does not exist: {supplied_artifact}")
        actual_sha = sha256(supplied_artifact)
        if actual_sha != expected_sha:
            raise PreparationError(f"artifact checksum mismatch: expected {expected_sha}, got {actual_sha}")
        if not archive.is_file() or sha256(archive) != expected_sha:
            copy_atomically(supplied_artifact, archive)
    elif not archive.is_file() or sha256(archive) != expected_sha:
        if archive.exists():
            archive.unlink()
        if offline:
            raise PreparationError(f"pinned artifact is not cached for offline use: {archive}")
        download(properties["artifact-url"], archive)
    if not archive.is_file():
        raise PreparationError(f"artifact does not exist: {archive}")
    actual_sha = sha256(archive)
    if actual_sha != expected_sha:
        raise PreparationError(f"artifact checksum mismatch: expected {expected_sha}, got {actual_sha}")
    return archive


def safe_archive_files(archive: zipfile.ZipFile, root: str) -> dict[str, zipfile.ZipInfo]:
    entries: dict[str, zipfile.ZipInfo] = {}
    total_size = 0
    prefix = f"{root}/"
    for info in archive.infolist():
        name = info.filename
        path = PurePosixPath(name)
        if "\\" in name or path.is_absolute() or ".." in path.parts or not name.startswith(prefix):
            raise PreparationError(f"unsafe archive entry: {name}")
        if info.is_dir():
            continue
        relative = name[len(prefix) :]
        if relative in entries:
            raise PreparationError(f"duplicate archive entry: {relative}")
        if relative not in PAYLOAD_FILES:
            raise PreparationError(f"unexpected archive entry: {relative}")
        if (info.external_attr >> 16) & 0o170000 == 0o120000:
            raise PreparationError(f"symbolic links are not allowed: {relative}")
        if info.file_size > MAX_FILE_BYTES:
            raise PreparationError(f"archive entry is too large: {relative}")
        total_size += info.file_size
        entries[relative] = info
    missing = sorted(set(PAYLOAD_FILES) - entries.keys())
    if missing:
        raise PreparationError(f"missing archive entries: {', '.join(missing)}")
    if total_size > MAX_ARCHIVE_UNCOMPRESSED_BYTES:
        raise PreparationError("artifact exceeds the uncompressed size limit")
    return entries


def validate_manifest(archive: zipfile.ZipFile, entry: zipfile.ZipInfo, properties: dict[str, str]) -> None:
    if properties["manifest-schema"] != "mdk-android-v1":
        raise PreparationError(f"unsupported artifact manifest schema: {properties['manifest-schema']}")

    def reject_duplicate_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise PreparationError(f"duplicate artifact manifest key: {key}")
            result[key] = value
        return result

    try:
        manifest = json.loads(archive.read(entry), object_pairs_hook=reject_duplicate_keys)
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise PreparationError(f"malformed artifact manifest: {error}") from error
    expected = {
        "name": "marmotkit-android",
        "version": properties["artifact-id"],
        "tag": properties["artifact-tag"],
        "source_sha": properties["mdk-sha"],
        "workspace_version": properties["workspace-version"],
        "android_api": properties["android-api"],
    }
    for key, value in expected.items():
        if str(manifest.get(key)) != value:
            raise PreparationError(f"manifest {key} mismatch: expected {value}, got {manifest.get(key)!r}")
    if manifest.get("contents") != list(MANIFEST_CONTENTS):
        raise PreparationError("manifest contents do not match the pinned Android contract")
    cargo_lock = manifest.get("cargo_lock_sha256", "")
    if len(cargo_lock) != 64 or any(character not in "0123456789abcdef" for character in cargo_lock):
        raise PreparationError("manifest cargo_lock_sha256 is invalid")
    for key in ("rustc", "cargo", "android_ndk_home"):
        if not isinstance(manifest.get(key), str) or not manifest[key].strip():
            raise PreparationError(f"manifest {key} is missing")


def validate_elf(path: Path, abi: str) -> None:
    expected_class, expected_machine = ABI_MACHINES[abi]
    symbols = (
        b"uniffi_marmot_uniffi_fn_constructor_marmot_new",
        b"Java_io_crates_keyring_Keyring_00024Companion_initializeNdkContext",
    )
    with path.open("rb") as library:
        header = library.read(20)
        if len(header) < 20 or header[:4] != b"\x7fELF" or header[5] != 1:
            raise PreparationError(f"{abi} library is not a little-endian ELF")
        if header[4] != expected_class or struct.unpack_from("<H", header, 18)[0] != expected_machine:
            raise PreparationError(f"{abi} library has the wrong ELF architecture")

        found: set[bytes] = set()
        overlap = b""
        overlap_size = max(map(len, symbols)) - 1
        while chunk := library.read(1024 * 1024):
            window = overlap + chunk
            found.update(symbol for symbol in symbols if symbol in window)
            overlap = window[-overlap_size:]
    for symbol in symbols:
        if symbol not in found:
            raise PreparationError(f"{abi} library is missing expected export {symbol.decode()}")


def helper_api_declarations(prepared_root: Path) -> list[str]:
    declarations: list[str] = []
    for relative in KOTLIN_FILES[1:]:
        source = (prepared_root / relative).read_text(encoding="utf-8")
        for raw_line in source.splitlines():
            line = raw_line.strip()
            match = re.match(
                r"^(?:public\s+)?((?:(?:data|enum|sealed|open|abstract)\s+)?(?:class|interface|object)\s+[A-Za-z0-9_]+)",
                line,
            )
            if match is None and line.startswith("companion object"):
                declarations.append(f"{relative}:companion object")
                continue
            if match is None:
                match = re.match(
                    r"^(?:public\s+)?((?:external\s+)?fun\s+[A-Za-z0-9_]+\s*\([^)]*\))",
                    line,
                )
            if match is not None:
                declarations.append(f"{relative}:{match.group(1)}")
    return sorted(set(declarations))


def api_signature(prepared_root: Path, properties: dict[str, str]) -> str:
    binding = prepared_root / KOTLIN_FILES[0]
    source = binding.read_text(encoding="utf-8")
    types = sorted(
        {
            f"{kind} {name}"
            for kind, name in re.findall(
                r"^(public interface|open class|data class|enum class|sealed class)\s+([A-Za-z0-9_]+)",
                source,
                re.MULTILINE,
            )
            if name not in {"FfiConverter"}
        },
    )
    helpers = helper_api_declarations(prepared_root)
    checksums = sorted(
        {
            f"{name}={value}"
            for name, value in re.findall(
                r"if \(lib\.(uniffi_marmot_uniffi_checksum_[A-Za-z0-9_]+)\(\) != ([0-9]+)\.toShort\(\)\)",
                source,
            )
        },
    )
    if not types or not checksums:
        raise PreparationError("generated Kotlin has no inspectable public API signature")
    expected_type_count = int(properties["api-type-count"])
    expected_checksum_count = int(properties["api-checksum-count"])
    expected_helper_count = int(properties["api-helper-declaration-count"])
    if (
        len(types) != expected_type_count
        or len(checksums) != expected_checksum_count
        or len(helpers) != expected_helper_count
    ):
        raise PreparationError(
            "generated Kotlin API signature count mismatch: "
            f"expected {expected_type_count} types/{expected_checksum_count} checksums/"
            f"{expected_helper_count} helper declarations, "
            f"got {len(types)} types/{len(checksums)} checksums/{len(helpers)} helper declarations",
        )
    kotlin_hashes = tuple(f"{relative}={sha256(prepared_root / relative)}" for relative in KOTLIN_FILES)
    return "\n".join(
        (
            "# Generated MarmotKit Android API signature",
            f"artifact-id={properties['artifact-id']}",
            f"source-sha={properties['mdk-sha']}",
            f"artifact-sha256={properties['artifact-sha256']}",
            "",
            "[kotlin-files]",
            *kotlin_hashes,
            "",
            "[types]",
            *types,
            "",
            "[helper-public-api]",
            *helpers,
            "",
            "[uniffi-api-checksums]",
            *checksums,
            "",
        ),
    )


def write_api_signature(prepared_root: Path, destination: Path, properties: dict[str, str]) -> None:
    destination.write_text(api_signature(prepared_root, properties), encoding="utf-8")


def archive_payload_hashes(archive_path: Path, properties: dict[str, str]) -> dict[str, str]:
    with zipfile.ZipFile(archive_path) as archive:
        entries = safe_archive_files(archive, properties["archive-root"])
        validate_manifest(archive, entries["manifest.json"], properties)
        hashes: dict[str, str] = {}
        for relative, info in entries.items():
            digest = hashlib.sha256()
            with archive.open(info) as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            hashes[relative] = digest.hexdigest()
        return hashes


def validate_prepared(output: Path, properties: dict[str, str], archive_hashes: dict[str, str]) -> bool:
    marker = output / ".prepared.json"
    try:
        metadata = json.loads(marker.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if metadata.get("artifact_sha256") != properties["artifact-sha256"]:
        return False
    api_signature_path = output / "marmotkit-api-signature.txt"
    if not api_signature_path.is_file() or sha256(api_signature_path) != metadata.get("api_signature_sha256"):
        return False
    marker_files = metadata.get("files")
    if marker_files != archive_hashes:
        return False
    try:
        for relative, expected_sha in archive_hashes.items():
            path = output / relative
            if not path.is_file() or sha256(path) != expected_sha:
                return False
        for abi in ABI_MACHINES:
            validate_elf(output / f"jniLibs/{abi}/libmarmot_uniffi.so", abi)
        if api_signature(output, properties) != api_signature_path.read_text(encoding="utf-8"):
            return False
    except PreparationError:
        return False
    return True


def extract_atomically(archive_path: Path, output: Path, properties: dict[str, str]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    try:
        with zipfile.ZipFile(archive_path) as archive:
            entries = safe_archive_files(archive, properties["archive-root"])
            validate_manifest(archive, entries["manifest.json"], properties)
            for relative, info in entries.items():
                destination = temporary / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                with archive.open(info) as source, destination.open("wb") as target:
                    shutil.copyfileobj(source, target, length=1024 * 1024)
        for abi in ABI_MACHINES:
            validate_elf(temporary / f"jniLibs/{abi}/libmarmot_uniffi.so", abi)
        write_api_signature(
            temporary,
            temporary / "marmotkit-api-signature.txt",
            properties,
        )
        api_signature_sha = sha256(temporary / "marmotkit-api-signature.txt")
        signatures = []
        file_hashes = {}
        for relative in PAYLOAD_FILES:
            path = temporary / relative
            file_hash = sha256(path)
            file_hashes[relative] = file_hash
            signatures.append(f"{file_hash}  {path.stat().st_size:>12}  {relative}")
        (temporary / "prepared-files.sha256").write_text("\n".join(signatures) + "\n", encoding="utf-8")
        (temporary / ".prepared.json").write_text(
            json.dumps(
                {
                    "schema": 1,
                    "artifact_id": properties["artifact-id"],
                    "artifact_sha256": properties["artifact-sha256"],
                    "api_signature_sha256": api_signature_sha,
                    "files": file_hashes,
                    "source_sha": properties["mdk-sha"],
                },
                sort_keys=True,
            )
            + "\n",
            encoding="utf-8",
        )
        if output.exists():
            shutil.rmtree(output)
        os.replace(temporary, output)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


@contextmanager
def preparation_lock(cache_root: Path, artifact_sha: str):
    lock_path = cache_root / "locks" / f"{artifact_sha}.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("a+b") as lock_file:
        if fcntl is not None:
            fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX)
            try:
                yield
            finally:
                fcntl.flock(lock_file.fileno(), fcntl.LOCK_UN)
        else:
            lock_file.seek(0, os.SEEK_END)
            if lock_file.tell() == 0:
                lock_file.write(b"\0")
                lock_file.flush()
            lock_file.seek(0)
            msvcrt.locking(lock_file.fileno(), msvcrt.LK_LOCK, 1)
            try:
                yield
            finally:
                lock_file.seek(0)
                msvcrt.locking(lock_file.fileno(), msvcrt.LK_UNLCK, 1)


def prepare(args: argparse.Namespace) -> Path:
    properties = parse_properties(args.lock)
    output = args.cache_root / properties["artifact-sha256"] / properties["archive-root"]
    with preparation_lock(args.cache_root, properties["artifact-sha256"]):
        archive = ensure_archive(properties, args.cache_root, args.artifact, args.offline)
        trusted_hashes = archive_payload_hashes(archive, properties)
        if validate_prepared(output, properties, trusted_hashes):
            return output
        extract_atomically(archive, output, properties)
        if not validate_prepared(output, properties, trusted_hashes):
            raise PreparationError("prepared artifact failed its final validation")
    return output


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--cache-root", type=Path, required=True)
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args()
    try:
        output = prepare(args)
    except (PreparationError, OSError, zipfile.BadZipFile) as error:
        print(f"error: MarmotKit artifact preparation failed: {error}", file=sys.stderr)
        return 1
    print(output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
