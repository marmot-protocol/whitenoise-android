"""Verify that a production MarmotKit pin came from the selected staging RC."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

if __package__:
    from . import check_marmotkit_release_channel as channel
    from . import prepare_marmotkit_artifact as artifact
    from .sync_marmotkit_rc import RC_VERSION, parse_asset
else:
    import check_marmotkit_release_channel as channel
    import prepare_marmotkit_artifact as artifact
    from sync_marmotkit_rc import RC_VERSION, parse_asset


ROOT = Path(__file__).resolve().parents[1]
PROMOTION = channel.LOCK.with_name("STABLE_PROMOTION.json")
FULL_SHA = re.compile(r"[0-9a-f]{40}")
STAGING_TAG = re.compile(r"staging/[A-Za-z0-9][A-Za-z0-9._-]*")
MDK_REPO = "repos/marmot-protocol/mdk"
# The deployed pre-rollout pin predates signed promotion records.
LEGACY_PIN = {
    "artifact-id": "0.19.0",
    "mdk-sha": "3fce95465cdea1edc8456dbd1c6691d1c8dc1f2e",
    "artifact-sha256": "9f4dd11478265a24a98f1f0c40768b7e8eba8f1fbd4fe7b129b2f09198e3a8e4",
}


def git(root: Path, *args: str) -> str:
    """Read the release checkout's existing Git history and tags."""
    return subprocess.check_output(["git", "-C", str(root), *args], text=True)


def mdk_json(path: str) -> dict:
    """Read JSON from the official MDK repository with the workflow's read token."""
    media_type = "application/vnd.github.raw+json" if path.startswith("contents/") else "application/vnd.github+json"
    content = subprocess.check_output(
        ["gh", "api", "--hostname", "github.com", "-H", f"Accept: {media_type}", f"{MDK_REPO}/{path}"],
        timeout=30,
    )
    return artifact.read_json_object(content, path)


def read_staging_pin(root: Path, selection: dict) -> dict[str, str]:
    """Resolve the named staging tag to the recorded commit and read its RC lock."""
    tag = selection.get("staging_tag", "")
    commit = selection.get("staging_commit", "")
    if not isinstance(tag, str) or not STAGING_TAG.fullmatch(tag):
        raise artifact.PreparationError("staging_tag must name a staging/ Git tag")
    if not isinstance(commit, str) or not FULL_SHA.fullmatch(commit):
        raise artifact.PreparationError("staging_commit must be a full Git SHA")
    if git(root, "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}").strip() != commit:
        raise artifact.PreparationError("staging tag no longer points to the recorded commit")
    try:
        git(root, "merge-base", "--is-ancestor", commit, "refs/remotes/origin/develop")
    except subprocess.CalledProcessError as error:
        raise artifact.PreparationError("staging commit must be in origin/develop history") from error
    pin = artifact.parse_lock_text(git(root, "show", f"{commit}:{channel.LOCK.as_posix()}"), channel.LOCK)
    if not RC_VERSION.fullmatch(pin["artifact-id"]):
        raise artifact.PreparationError("the staging tag must pin a published SemVer release candidate")
    return pin


def read_stable_metadata(commit: str) -> dict:
    """Read a verified, immutable Stable record from MDK's rc-meta history."""
    if not isinstance(commit, str) or not FULL_SHA.fullmatch(commit):
        raise artifact.PreparationError("mdk_meta_commit must be a full Git SHA")
    signed = mdk_json(f"commits/{commit}")
    verification = signed.get("commit", {}).get("verification", {})
    if signed.get("sha") != commit or verification.get("verified") is not True:
        raise artifact.PreparationError("MDK's promotion metadata commit must have a verified signature")
    history = mdk_json(f"compare/{commit}...rc-meta?per_page=1")
    if history.get("merge_base_commit", {}).get("sha") != commit:
        raise artifact.PreparationError("MDK's promotion metadata commit must belong to rc-meta history")
    return mdk_json(f"contents/stable/latest.json?ref={commit}")


def verify_promotion(staging: dict[str, str], stable: dict[str, str], metadata: dict) -> None:
    """Match the tested RC, Stable source and Android artifact to the published promotion."""
    if metadata.get("schema") != 2 or metadata.get("channel") != "stable":
        raise artifact.PreparationError("expected schema-2 Stable rc-meta")
    if metadata.get("promoted_from_rc") != staging["artifact-id"] or metadata.get("source_sha") != staging["mdk-sha"]:
        raise artifact.PreparationError("Stable MarmotKit was promoted from a different RC than the staging tag")
    if metadata.get("version") != stable["artifact-id"] or metadata.get("release_sha") != stable["mdk-sha"]:
        raise artifact.PreparationError("Stable pin does not match the published release commit")
    # The signed record is emitted after MDK's metadata-only diff gate. Check that
    # the release commit it names also has the tested source as its sole parent.
    source, release = staging["mdk-sha"], stable["mdk-sha"]
    if release != source:
        commit = mdk_json(f"commits/{release}")
        parents = [parent.get("sha") for parent in commit.get("parents", [])]
        if commit.get("sha") != release or parents != [source]:
            raise artifact.PreparationError("the Stable release commit is not directly based on the tested RC")
    track = metadata.get("tracks", {}).get("marmotkit", {})
    if track.get("tag") != stable["artifact-tag"] or not isinstance(track.get("assets"), list):
        raise artifact.PreparationError("Stable metadata does not contain the pinned MarmotKit track")
    bundle = parse_asset(track["assets"], stable["artifact-tag"], f"{stable['archive-root']}.zip",
                         artifact.MAX_ARCHIVE_DOWNLOAD_BYTES)
    if bundle.sha256 != stable["artifact-sha256"]:
        raise artifact.PreparationError("Stable Android artifact SHA-256 disagrees with the pin")


def check_production_pin(root: Path, *, if_stable: bool = False) -> None:
    """Enforce staging provenance on production pins introduced after the rollout."""
    lock = root / channel.LOCK
    if if_stable and not channel.STABLE_VERSION.fullmatch(artifact.parse_properties(lock)["artifact-id"]):
        return
    stable = channel.check_stable_pin(lock)
    promotion = root / PROMOTION
    if not promotion.exists() and all(stable[key] == value for key, value in LEGACY_PIN.items()):
        return
    if not promotion.is_file() or promotion.is_symlink():
        raise artifact.PreparationError(f"record the staging tag and MDK promotion in {PROMOTION}")
    selection = artifact.read_json_object(promotion.read_bytes(), "Stable promotion selection")
    staging = read_staging_pin(root, selection)
    metadata = read_stable_metadata(selection.get("mdk_meta_commit", ""))
    verify_promotion(staging, stable, metadata)
    print(f"Stable MarmotKit {stable['artifact-id']} matches {selection['staging_tag']} ({staging['artifact-id']})")


def main() -> int:
    """Fail production validation when the pin or its promotion evidence is invalid."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--if-stable", action="store_true", help="Validate Stable pins on develop")
    args = parser.parse_args()
    try:
        check_production_pin(ROOT, if_stable=args.if_stable)
    except (artifact.PreparationError, OSError, UnicodeError, subprocess.SubprocessError,
            AttributeError, TypeError) as error:
        print(f"Stable MarmotKit promotion check failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
