"""Tag the exact commit of a selected signed Android staging build."""
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys

from scripts.release_bundle import REPOSITORY, api, command, require


def staging_run(run_id):
    """Accept a successful staging workflow run from this repository's develop branch."""
    require(re.fullmatch(r"[1-9][0-9]*", run_id), "Enter a numeric build run ID")
    run = api(f"actions/runs/{run_id}")
    require(str(run["id"]) == run_id, "Build run ID mismatch")
    require(run["repository"]["full_name"] == run["head_repository"]["full_name"] == REPOSITORY,
            "Build belongs to a different repository")
    require(run["path"] == ".github/workflows/android-staging-apk.yml",
            "Choose a run from Android Staging APK")
    require(run["head_branch"] == "develop" and run["event"] in {"push", "workflow_dispatch"},
            "Choose a staging run from develop")
    require(run["status"] == "completed" and run["conclusion"] == "success",
            "The staging build must have completed successfully")
    require(re.fullmatch(r"[0-9a-f]{40}", run["head_sha"]), "Invalid staging source commit")
    return run


def tag_target(ref):
    """Peel an existing tag to its commit, or return None when the ref is absent."""
    remote = f"https://github.com/{REPOSITORY}.git"
    refs = dict(line.split()[::-1] for line in
                command("git", "ls-remote", remote, ref, ref + "^{}").decode().splitlines())
    return refs.get(ref + "^{}", refs.get(ref))


def publish_staging_tag(run_id):
    """Tie one run to a create-only annotated tag and return the tag URL."""
    run = staging_run(run_id)
    source = run["head_sha"]
    command("git", "merge-base", "--is-ancestor", source, "HEAD")
    date = datetime.strptime(run["created_at"], "%Y-%m-%dT%H:%M:%SZ").strftime("%Y.%m.%d")
    number = run["run_number"]
    require(type(number) is int and number > 0, "Invalid staging build number")
    tag = f"staging/{date}-{number}"
    ref = f"refs/tags/{tag}"
    url = f"https://github.com/{REPOSITORY}/tree/{tag}"
    existing = tag_target(ref)
    if existing is not None:
        require(existing == source, f"{tag} already points to a different commit")
        return url

    name = f"whitenoise-staging-v8a-release-{number}-{source[:7]}"
    artifacts = api(f"actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    matches = [artifact for artifact in artifacts if artifact["name"] == name and not artifact["expired"]]
    require(len(matches) == 1, "Expected one unexpired signed staging APK artifact for the chosen run")
    digest = matches[0].get("digest") or ""
    require(re.fullmatch(r"sha256:[0-9a-f]{64}", digest), "Staging APK artifact has no SHA-256 digest")
    lock = command("git", "show", f"{source}:app/src/main/marmotkit/MARMOT_VERSION").decode()
    pin = dict(line.split("=", 1) for line in lock.splitlines() if "=" in line)
    require(pin.get("artifact-id") and re.fullmatch(r"[0-9a-f]{40}", pin.get("mdk-sha", "")),
            "Staging commit has an invalid MarmotKit pin")
    message = (
        f"Android staging build {number}\n\n"
        f"MarmotKit: {pin['artifact-id']}\nMDK source: {pin['mdk-sha']}\n"
        f"Build: https://github.com/{REPOSITORY}/actions/runs/{run_id}\n"
        f"Artifact: {name}\nArtifact SHA-256: {digest.removeprefix('sha256:')}\n"
    )
    tag_object = json.loads(command(
        "gh", "api", "--method", "POST", f"repos/{REPOSITORY}/git/tags",
        "-f", f"tag={tag}", "-f", f"message={message}", "-f", f"object={source}", "-f", "type=commit",
        "-f", "tagger[name]=github-actions[bot]", "-f", "tagger[email]=41898282+github-actions[bot]@users.noreply.github.com",
    ))
    try:
        command("gh", "api", "--method", "POST", f"repos/{REPOSITORY}/git/refs",
                "-f", f"ref={ref}", "-f", f"sha={tag_object['sha']}")
    except subprocess.CalledProcessError:
        # A concurrent creator may have published the same build after our read.
        if tag_target(ref) != source:
            raise
    return url


def main():
    """Report the selected build tag in the workflow summary."""
    try:
        url = publish_staging_tag(sys.argv[1])
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
    print(url)
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a") as output:
            output.write(f"Tagged the chosen [staging build]({url}).\n")


if __name__ == "__main__":
    main()
