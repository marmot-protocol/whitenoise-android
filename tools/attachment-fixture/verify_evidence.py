"""Download the immutable evidence archive anonymously and verify all original member bytes."""

import argparse
import base64
import hashlib
import io
from pathlib import Path, PurePosixPath
import re
import urllib.request
import zipfile


def verify_archive(encoded, expected_zip_sha256, expected_members):
    """Fail before extraction on archive corruption, unsafe paths or incomplete member coverage."""
    blob = base64.b64decode(b"".join(encoded.split()), validate=True)
    if hashlib.sha256(blob).hexdigest() != expected_zip_sha256:
        raise ValueError("ZIP SHA-256 mismatch")
    with zipfile.ZipFile(io.BytesIO(blob)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or set(names) != set(expected_members):
            raise ValueError("archive member set differs from manifest")
        result = {}
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name:
                raise ValueError("unsafe archive member path")
            if archive.getinfo(name).file_size > 10 * 1024**2:
                raise ValueError("unexpected evidence member size")
            data = archive.read(name)
            if hashlib.sha256(data).hexdigest() != expected_members[name]:
                raise ValueError("member SHA-256 mismatch: " + name)
            result[name] = data
    return result


def extract_verified(verified, root):
    """Create a fresh private root; existing roots/symlinks cannot redirect verified members."""
    root.mkdir(mode=0o700, parents=True)
    for name, data in verified.items():
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open("xb") as output:
            output.write(data)


def main():
    """Read the single checked-in manifest, use no credentials, and optionally extract verified files."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--extract", type=Path)
    args = parser.parse_args()
    manifest = args.manifest.read_text()
    url = re.search(r"^Download: <(https://gist.githubusercontent.com/[^>]+)>$", manifest, re.M).group(1)
    digest = re.search(r"^ZIP SHA-256: `([0-9a-f]{64})`", manifest, re.M).group(1)
    members = dict(re.findall(r"^\| `([^`]+)` \|[^\n]+\| `([0-9a-f]{64})` \|$", manifest, re.M))
    if not members:
        raise ValueError("manifest has no members")
    with urllib.request.urlopen(url, timeout=30) as response:
        if response.status != 200:
            raise ValueError("archive download failed")
        encoded = response.read(5 * 1024**2 + 1)
    if len(encoded) > 5 * 1024**2:
        raise ValueError("unexpected archive download size")
    verified = verify_archive(encoded, digest, members)
    if args.extract:
        extract_verified(verified, args.extract)
    print(f"Anonymous HTTP 200; ZIP and all {len(verified)} member SHA-256 checks pass")


if __name__ == "__main__":
    main()
