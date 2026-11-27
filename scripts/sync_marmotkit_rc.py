#!/usr/bin/env python3
"""Prepare a reviewed MarmotKit RC pin update for Android develop."""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

if __package__:
    from . import prepare_marmotkit_artifact as artifact
else:
    import prepare_marmotkit_artifact as artifact

ROOT = Path(__file__).resolve().parent.parent
LOCK_PATH = Path("app/src/main/marmotkit/MARMOT_VERSION")
MDK_URL = "https://github.com/marmot-protocol/mdk"
RC_META_REF_URL = "https://api.github.com/repos/marmot-protocol/mdk/commits/rc-meta"
MDK_RAW_URL = "https://raw.githubusercontent.com/marmot-protocol/mdk"
NUMBER = r"(0|[1-9][0-9]*)"
VERSION = rf"{NUMBER}\.{NUMBER}\.{NUMBER}"
RC_VERSION = re.compile(rf"{VERSION}-rc\.{NUMBER}")
WORKSPACE_VERSION = re.compile(rf"{VERSION}(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")


@dataclass(frozen=True)
class ReleaseAsset:
    """Download coordinates and the byte-level contract from rc-meta."""

    url: str
    sha256: str
    size: int

    def download_verified(self, destination: Path) -> None:
        """Fetch an asset and verify its size and digest."""
        artifact.download(self.url, destination)
        if destination.stat().st_size != self.size or artifact.sha256(destination) != self.sha256:
            raise artifact.PreparationError(f"rc-meta size or SHA-256 mismatch for {destination.name}")


@dataclass(frozen=True)
class AndroidRc:
    """Android assets from a single published release candidate."""

    version: str
    source_sha: str
    tag: str
    impact: str
    workflow_run: str
    bundle: ReleaseAsset
    checksum: ReleaseAsset

    @property
    def archive_root(self) -> str:
        """Return the common archive directory and ZIP filename stem."""
        return f"marmotkit-android-{self.version}"


def parse_rc(metadata: dict) -> AndroidRc:
    """Accept schema-2 RC metadata with uniquely named official Android assets."""
    if not isinstance(metadata, dict) or metadata.get("schema") != 2 or metadata.get("channel") != "rc":
        raise artifact.PreparationError("expected schema-2 rc-meta for the RC channel")
    version = metadata.get("version")
    match = RC_VERSION.fullmatch(version) if isinstance(version, str) else None
    if match is None or metadata.get("base_version") != ".".join(match.groups()[:3]):
        raise artifact.PreparationError("rc-meta version must be a SemVer RC matching base_version")
    source_sha = metadata.get("source_sha")
    if not isinstance(source_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise artifact.PreparationError("rc-meta source_sha must be a lowercase full Git SHA")
    impact = metadata.get("impact")
    workflow_run = metadata.get("workflow_run")
    if impact not in ("patch", "minor") or not isinstance(workflow_run, str) or not re.fullmatch(
        rf"{re.escape(MDK_URL)}/actions/runs/[1-9][0-9]*", workflow_run
    ):
        raise artifact.PreparationError("rc-meta must name the release impact and official workflow run")
    tag = f"marmotkit-v{version}"
    tracks = metadata.get("tracks")
    track = tracks.get("marmotkit") if isinstance(tracks, dict) else None
    assets = track.get("assets") if isinstance(track, dict) else None
    if not isinstance(assets, list) or track.get("tag") != tag:
        raise artifact.PreparationError(f"rc-meta must include the {tag} MarmotKit track")
    zip_name = f"marmotkit-android-{version}.zip"
    bundle = parse_asset(assets, tag, zip_name, artifact.MAX_ARCHIVE_DOWNLOAD_BYTES)
    checksum = parse_asset(assets, tag, f"{zip_name}.sha256", 4096)
    return AndroidRc(version, source_sha, tag, impact, workflow_run, bundle, checksum)


def parse_asset(assets: list, tag: str, name: str, max_size: int) -> ReleaseAsset:
    """Find one exact release asset and validate its immutable download URL."""
    matches = [entry for entry in assets if isinstance(entry, dict) and entry.get("name") == name]
    if len(matches) != 1:
        raise artifact.PreparationError(f"rc-meta needs exactly one {name} asset")
    entry = matches[0]
    url = f"{MDK_URL}/releases/download/{tag}/{name}"
    digest = entry.get("sha256")
    size = entry.get("size")
    if (
        entry.get("url") != url
        or not isinstance(digest, str)
        or not re.fullmatch(r"[0-9a-f]{64}", digest)
        or type(size) is not int
        or not 0 < size <= max_size
    ):
        raise artifact.PreparationError(f"invalid rc-meta download URL, size, or SHA-256 for {name}")
    return ReleaseAsset(url, digest, size)


def latest_rc(work: Path) -> tuple[AndroidRc, str]:
    """Resolve the metadata commit so the candidate and review links share one revision."""
    ref = work / "rc-meta-commit.json"
    artifact.download(RC_META_REF_URL, ref)
    commit = artifact.read_json_object(ref.read_bytes(), "rc-meta commit").get("sha")
    if not isinstance(commit, str) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise artifact.PreparationError("rc-meta commit must have a lowercase full Git SHA")
    metadata = work / "latest.json"
    artifact.download(f"{MDK_RAW_URL}/{commit}/rc/latest.json", metadata)
    return parse_rc(artifact.read_json_object(metadata.read_bytes(), "rc-meta")), commit


def version_order(version: str) -> tuple[int, int, int, bool, int]:
    """Order Stable releases and their RCs without accepting retired nightly pins."""
    match = re.fullmatch(rf"{VERSION}(?:-rc\.{NUMBER})?", version)
    if match is None:
        raise artifact.PreparationError(f"expected a Stable or RC base pin, got {version}")
    major, minor, patch, rc_number = match.groups()
    return int(major), int(minor), int(patch), rc_number is None, int(rc_number or 0)


def candidate_lock(
    rc: AndroidRc, current: dict[str, str], work: Path
) -> tuple[dict[str, str], bool]:
    """Verify the ZIP and derive a pin from the same native and Kotlin payload."""
    pin = {
        **current,
        "mdk-sha": rc.source_sha,
        "mdk-short-sha": rc.source_sha[:8],
        "mdk-branch": "canary",
        "artifact-id": rc.version,
        "artifact-tag": rc.tag,
        "artifact-url": rc.bundle.url,
        "artifact-sha256": rc.bundle.sha256,
        "archive-root": rc.archive_root,
    }
    archive_path = work / f"{rc.archive_root}.zip"
    checksum_path = work / f"{archive_path.name}.sha256"
    rc.checksum.download_verified(checksum_path)
    entries = checksum_path.read_text(encoding="ascii").split()
    if len(entries) != 2 or entries[0] != rc.bundle.sha256 or entries[1].removeprefix("*") != archive_path.name:
        raise artifact.PreparationError("published Android ZIP checksum disagrees with rc-meta")
    rc.bundle.download_verified(archive_path)

    kotlin_root = work / "kotlin-api"
    with zipfile.ZipFile(archive_path) as archive:
        files = artifact.safe_archive_files(archive, rc.archive_root)
        manifest = artifact.read_manifest(archive, files["manifest.json"])
        workspace_version = manifest.get("workspace_version")
        if not isinstance(workspace_version, str) or not WORKSPACE_VERSION.fullmatch(workspace_version):
            raise artifact.PreparationError("invalid workspace_version in the Android artifact manifest")
        pin["workspace-version"] = workspace_version
        has_feature_evidence = artifact.native_features(manifest, pin)["status"] == "matched_manifest"
        for relative in artifact.KOTLIN_FILES:
            target = kotlin_root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(files[relative]))
    types, helpers, checksums = artifact.read_api_declarations(kotlin_root)
    pin["api-type-count"] = str(len(types))
    pin["api-helper-declaration-count"] = str(len(helpers))
    pin["api-checksum-count"] = str(len(checksums))
    if not helpers:
        raise artifact.PreparationError("generated Kotlin contains no helper API declarations")
    artifact.extract_atomically(archive_path, work / "verified", pin)
    return pin, has_feature_evidence


def checked_branch(root: Path) -> str:
    """Require develop or a development work branch before preparing a pin."""
    branch = subprocess.run(
        ["git", "branch", "--show-current"], cwd=root, text=True, capture_output=True, check=True
    ).stdout.strip()
    if not branch or branch == "master" or subprocess.run(
        ["git", "merge-base", "--is-ancestor", "refs/remotes/origin/develop", "HEAD"],
        cwd=root, stderr=subprocess.DEVNULL,
    ).returncode:
        raise artifact.PreparationError("fetch origin develop and run on develop or a branch based on it")
    return branch


def update_lock(root: Path, branch: str, original: bytes, candidate: str) -> None:
    """Replace a clean lock only if the checkout and input still match."""
    lock = root / LOCK_PATH
    with artifact.preparation_lock(root / "build" / "marmotkit-rc-sync", "pin-update"):
        status = subprocess.run(
            ["git", "status", "--porcelain", "--", str(LOCK_PATH)],
            cwd=root, text=True, capture_output=True, check=True,
        ).stdout
        if checked_branch(root) != branch or status or lock.is_symlink() or lock.read_bytes() != original:
            raise artifact.PreparationError("the checkout or MarmotKit lock changed; apply from a clean lock")
        with tempfile.TemporaryDirectory(prefix=".marmotkit-rc-", dir=lock.parent) as directory:
            pending = Path(directory) / lock.name
            pending.write_text(candidate, encoding="utf-8")
            pending.chmod(lock.stat().st_mode & 0o777)
            os.replace(pending, lock)


def write_proposal(
    directory: Path, rc: AndroidRc, metadata_commit: str, candidate: str, feature_evidence: bool,
) -> None:
    """Export a verified candidate for a draft PR without changing the checkout."""
    directory.mkdir()
    (directory / LOCK_PATH.name).write_text(candidate, encoding="utf-8")
    (directory / "proposal.json").write_text(json.dumps({
        "version": rc.version,
        "impact": rc.impact,
        "metadata_commit": metadata_commit,
        "workflow_run": rc.workflow_run,
        "manifest_features_verified": feature_evidence,
    }) + "\n", encoding="utf-8")


def sync(
    root: Path, *, apply_version: str | None, verified_features: bool, proposal_dir: Path | None,
) -> None:
    """Preview, export, or apply a verified RC candidate."""
    branch = checked_branch(root)
    lock = root / LOCK_PATH
    original = lock.read_bytes()
    current = artifact.parse_lock_text(original.decode("utf-8"), lock)
    with tempfile.TemporaryDirectory(prefix="marmotkit-rc-") as directory:
        work = Path(directory)
        rc, metadata_commit = latest_rc(work)
        if apply_version is not None and rc.version != apply_version:
            raise artifact.PreparationError(
                f"rc-meta now points to {rc.version}; preview that RC before applying"
            )
        installed = current["artifact-id"]
        if rc.version == installed:
            if (current["mdk-sha"], current["artifact-sha256"], current["artifact-tag"], current["artifact-url"]) != (
                rc.source_sha, rc.bundle.sha256, rc.tag, rc.bundle.url
            ):
                raise artifact.PreparationError(f"{installed} coordinates differ from the published RC; inspect the pin")
            print(f"MarmotKit {installed} coordinates already match rc-meta.")
            return
        if version_order(rc.version) < version_order(installed):
            raise artifact.PreparationError(f"refusing to downgrade MarmotKit from {installed} to {rc.version}")
        pin, has_feature_evidence = candidate_lock(rc, current, work)

    print(f"MarmotKit {installed} → {rc.version} (impact: {rc.impact})")
    print(f"Release run: {rc.workflow_run}")
    for name in ("signature_delta.json", "integration-notes.md"):
        print(f"{MDK_URL}/blob/{metadata_commit}/rc/{name}")
    if not has_feature_evidence:
        print(f"Verify {pin['features']} for every Android ABI in the release build logs.")
        if apply_version is not None and not verified_features:
            raise artifact.PreparationError(
                "manifest has no feature list; verify builder evidence and pass --verified-features"
            )
    candidate = "".join(f"{key}={value}\n" for key, value in pin.items())
    if apply_version is not None:
        update_lock(root, branch, original, candidate)
        print(f"Updated {LOCK_PATH}. Review the impact and run Android CI in the pin PR.")
    elif proposal_dir is not None:
        write_proposal(proposal_dir, rc, metadata_commit, candidate, has_feature_evidence)
    else:
        print("".join(difflib.unified_diff(
            original.decode("utf-8").splitlines(keepends=True), candidate.splitlines(keepends=True),
            fromfile=str(LOCK_PATH), tofile=f"{LOCK_PATH} ({rc.version})",
        )), end="")


def main() -> int:
    """Run manual sync and report rejected candidates without a traceback."""
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--apply", metavar="VERSION", help="write the pin for this exact reviewed RC")
    mode.add_argument("--proposal-dir", type=Path, help="export a candidate and metadata for a draft bump PR")
    parser.add_argument("--verified-features", action="store_true", help="confirm you verified the Android build feature flags")
    args = parser.parse_args()
    try:
        sync(ROOT, apply_version=args.apply, verified_features=args.verified_features, proposal_dir=args.proposal_dir)
    except (artifact.PreparationError, OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f"error: MarmotKit RC sync failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
