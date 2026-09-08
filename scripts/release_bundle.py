#!/usr/bin/env python3
"""Verify and retrieve an approved CI release bundle; never build or publish to stores."""
from __future__ import annotations

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

ROOT = Path(__file__).resolve().parent.parent
REPOSITORY = "marmot-protocol/whitenoise-android"
BUILD_WORKFLOW = ".github/workflows/android-production-release.yml"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def command(*args):
    return subprocess.check_output(args, cwd=ROOT)


def api(path):
    return json.loads(command("gh", "api", f"repos/{REPOSITORY}/{path}"))


def properties():
    return dict(line.split("=", 1) for line in
                (ROOT / "config/android-release.properties").read_text().splitlines()
                if line and not line.startswith("#"))


def validate_version(version):
    require(re.fullmatch(r"20\d{2}\.\d{1,2}\.\d{1,2}", version), "Invalid release version")


def check_tag(source, version):
    validate_version(version)
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Invalid source commit")
    tag = f"refs/tags/android-v{version}"
    refs = command("git", "ls-remote", "origin", tag, tag + "^{}").decode().splitlines()
    targets = dict(line.split()[::-1] for line in refs)
    actual = targets.get(tag + "^{}", targets.get(tag))
    require(actual is None or actual == source, "Release tag already points to a different source commit; choose a new version")


def validate_run(run, run_id):
    require(str(run["id"]) == run_id, "Build run ID mismatch")
    require(run["repository"]["full_name"] == REPOSITORY and
            run["head_repository"]["full_name"] == REPOSITORY, "Build must belong to this repository")
    require(run["path"] == BUILD_WORKFLOW, "Run is not the production build workflow")
    require(run["event"] == "workflow_dispatch" and run["head_branch"] == "master",
            "Only manually prepared master builds may be distributed")
    require(run["status"] == "completed" and run["conclusion"] == "success", "Build run must have completed successfully")
    require(re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]), "Invalid build source")


def verify_bundle(directory, expected_digest, version, source, run_id, attempt, policy):
    validate_version(version)
    require(re.fullmatch(r"[0-9a-f]{40}", source), "Invalid source commit")
    require(re.fullmatch(r"[0-9a-f]{64}", expected_digest), "Provide the reviewed manifest SHA-256")
    manifest_path = directory / "release-manifest.json"
    require(manifest_path.is_file() and not manifest_path.is_symlink(), "Missing regular manifest file")
    require(sha256(manifest_path) == expected_digest, "Manifest does not match the reviewed SHA-256")
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get("schemaVersion") == 2, "Unsupported release manifest")
    require(manifest.get("sourceCommit") == source and manifest.get("versionName") == version, "Release source/version mismatch")
    require(manifest.get("buildRunId") == run_id and manifest.get("buildRunAttempt") == str(attempt), "Build run/attempt mismatch")
    require(manifest.get("worktreeDirty") is False and
            manifest.get("productionRuntimeConfigurationComplete") is True and
            manifest.get("missingProductionRuntimeConfiguration") == [], "Rehearsal or incomplete bundle cannot be distributed")
    for field, key in (("applicationId", "APPLICATION_ID"), ("appSigningCertificateSha256", "APP_SIGNING_SHA256"),
                       ("playUploadCertificateSha256", "PLAY_UPLOAD_SHA256"), ("zspVersion", "ZSP_VERSION")):
        require(manifest.get(field) == policy[key], f"Release policy mismatch: {field}")
    require(type(manifest.get("versionCode")) is int and manifest["versionCode"] > 0, "Invalid version code")
    expected_files = {f"whitenoise-android-{version}-arm64-v8a.apk", f"whitenoise-android-{version}-play.aab",
                      f"mapping-{version}.txt", "release-notes-en-US.txt", f"store-assets-{version}.zip"}
    require(set(manifest["files"]) == expected_files, "Release payload file set is incorrect")
    require({p.name for p in directory.iterdir()} == expected_files | {"release-manifest.json", "checksums-sha256.txt"},
            "Unexpected or missing bundle files")
    for name, info in manifest["files"].items():
        path = directory / name
        require(path.is_file() and not path.is_symlink(), "Bundle payload must be regular files")
        require(path.stat().st_size > 0 and path.stat().st_size == info["bytes"] and sha256(path) == info["sha256"],
                f"Artifact digest/size mismatch: {name}")
    checksums = directory / "checksums-sha256.txt"
    require(checksums.is_file() and not checksums.is_symlink(), "Invalid checksum file")
    expected_checksums = {f"{sha256(directory / name)}  ./{name}" for name in expected_files | {"release-manifest.json"}}
    require(set(checksums.read_text().splitlines()) == expected_checksums, "Checksum inventory mismatch")
    return manifest


def extract_flat_archive(archive, directory):
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        require(len(names) == len(set(names)), "Duplicate artifact ZIP entries")
        require(all(re.fullmatch(r"[A-Za-z0-9_.-]+", name) and name not in (".", "..") for name in names),
                "Artifact ZIP must contain only top-level files")
        # Explicit writes never restore ZIP symlinks or external paths.
        for name in names:
            with z.open(name) as incoming, (directory / name).open("wb") as outgoing:
                shutil.copyfileobj(incoming, outgoing)


def verify_source_metadata(directory, manifest):
    source = manifest["sourceCommit"]
    gradle = command("git", "show", f"{source}:app/build.gradle.kts").decode()
    for pattern, value in ((r'versionName = "([^"]+)"', manifest["versionName"]),
                           (r'versionCode = (\d+)', str(manifest["versionCode"])),
                           (r'applicationId = "([^"]+)"', manifest["applicationId"])):
        require(re.findall(pattern, gradle) == [value], "Bundle identity differs from its source commit")
    source_files = command("git", "ls-tree", "-r", "--name-only", source, "--",
                           "zapstore.yaml", "fastlane/metadata/android/en-US").decode().splitlines()
    with zipfile.ZipFile(directory / f"store-assets-{manifest['versionName']}.zip") as z:
        files = [i.filename for i in z.infolist() if not i.is_dir()]
        require(len(files) == len(set(files)) and set(files) == set(source_files), "Listing archive differs from the source inventory")
        for name in files:
            require(z.read(name) == command("git", "show", f"{source}:{name}"), "Listing archive differs from reviewed source")
    notes = command("git", "show", f"{source}:fastlane/metadata/android/en-US/changelogs/{manifest['versionCode']}.txt")
    require((directory / "release-notes-en-US.txt").read_bytes() == notes, "Release notes differ from source")


def fetch(args):
    require(re.fullmatch(r"[1-9][0-9]*", args.run_id), "Invalid run ID")
    run = api(f"actions/runs/{args.run_id}")
    validate_run(run, args.run_id)
    source = run["head_sha"]
    command("git", "merge-base", "--is-ancestor", source, "origin/master")
    check_tag(source, args.version)
    artifact_name = f"android-production-{source}-{args.run_id}-{run['run_attempt']}"
    artifacts = api(f"actions/runs/{args.run_id}/artifacts?per_page=100")
    matches = [a for a in artifacts["artifacts"] if a["name"] == artifact_name and not a["expired"]]
    require(len(matches) == 1, "Expected exactly one unexpired artifact for this build attempt")
    directory = args.directory.resolve()
    require(not directory.exists(), "Output directory already exists; use a fresh destination")
    directory.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=directory.parent) as temp:
        temp = Path(temp)
        archive = temp / "artifact.zip"
        with archive.open("wb") as output:
            subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{matches[0]['id']}/zip"],
                           cwd=ROOT, stdout=output, check=True)
        extracted = temp / "bundle"
        extracted.mkdir()
        extract_flat_archive(archive, extracted)
        manifest = verify_bundle(extracted, args.manifest_sha256, args.version, source,
                                 args.run_id, run["run_attempt"], properties())
        verify_source_metadata(extracted, manifest)
        shutil.move(extracted, directory)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"source={source}\nversion={args.version}\n")
    print(f"Verified {args.version}, code {manifest['versionCode']}, source {source}, build {args.run_id}/{run['run_attempt']}")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
            output.write(f"Reviewed candidate: `{args.version}` / code `{manifest['versionCode']}`\n\n"
                         f"Source: `{source}`; build: `{args.run_id}/{run['run_attempt']}`\n\n"
                         f"Manifest SHA-256: `{args.manifest_sha256}`\n")


def github_draft(args):
    # fetch has already verified this directory in the same job. Refuse edits of
    # another candidate's draft, including a previous build of the same source.
    manifest = json.loads((args.directory / "release-manifest.json").read_text())
    version, source = manifest["versionName"], manifest["sourceCommit"]
    check_tag(source, version)
    tag = f"android-v{version}"
    releases = json.loads(command("gh", "api", "--paginate", "--slurp",
                                 f"repos/{REPOSITORY}/releases?per_page=100"))
    existing = [r for page in releases for r in page if r["tag_name"] == tag]
    require(len(existing) <= 1, "Ambiguous release")
    if existing:
        release = existing[0]
        require(release["draft"], "Refusing to modify a published GitHub release")
        require(release["target_commitish"] == source, "Draft target differs from source")
        for asset in release["assets"]:
            path = args.directory / asset["name"]
            require(path.is_file() and path.parent == args.directory, "Unexpected draft asset")
            with tempfile.TemporaryFile() as stream:
                subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                                f"repos/{REPOSITORY}/releases/assets/{asset['id']}"], stdout=stream, check=True)
                stream.seek(0)
                require(hashlib.file_digest(stream, "sha256").hexdigest() == sha256(path), "Existing draft contains another candidate's bytes")
        command("gh", "release", "upload", tag, "--repo", REPOSITORY, "--clobber",
                *map(str, sorted(args.directory.iterdir())))
    else:
        command("gh", "release", "create", tag, "--repo", REPOSITORY, "--draft", "--target", source,
                "--title", f"White Noise Android {version}", "--notes-file", str(args.directory / "release-notes-en-US.txt"),
                *map(str, sorted(args.directory.iterdir())))
    print(f"GitHub draft prepared: {tag}. It has not been published.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    check = sub.add_parser("check-tag")
    check.add_argument("--source", required=True)
    check.add_argument("--version", required=True)
    download = sub.add_parser("fetch")
    download.add_argument("--run-id", required=True)
    download.add_argument("--version", required=True)
    download.add_argument("--manifest-sha256", required=True)
    download.add_argument("--directory", type=Path, default=ROOT / "build/production-release")
    draft = sub.add_parser("github-draft")
    draft.add_argument("--directory", type=Path, default=ROOT / "build/production-release")
    args = parser.parse_args()
    if args.command == "check-tag":
        check_tag(args.source, args.version)
    elif args.command == "fetch":
        fetch(args)
    else:
        github_draft(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        raise SystemExit(f"Release verification failed: {error}")
