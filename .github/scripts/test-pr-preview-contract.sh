#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-pr-preview-publish.yml
build=.github/workflows/android-pr-apk.yml
gradle=app/build.gradle.kts
publisher=.github/scripts/publish-pr-apk-to-github-release.sh

reject() {
  local pattern=$1 file=$2
  if grep -Fq -- "$pattern" "$file"; then
    printf 'Forbidden preview-workflow pattern found in %s: %s\n' "$file" "$pattern" >&2
    exit 1
  fi
}

# Guard the security and update-in-place contracts against accidental edits.
grep -Fq 'workflow_run:' "$workflow"
grep -Fq "head_repository.full_name == github.repository" "$workflow"
grep -Fq 'PR_PREVIEW_KEYSTORE_BASE64: ${{ secrets.PR_PREVIEW_KEYSTORE_BASE64 }}' "$workflow"
reject 'PR_PREVIEW_KEYSTORE' "$build"
grep -Fq 'create("preview")' "$gradle"
grep -Fq 'applicationIdSuffix = ".preview"' "$gradle"
grep -Fq 'applicationIdSuffix = ".preview.pr$previewIdentity"' "$gradle"
grep -Fq 'val prPreviewVersionCode = 2_000_000_000' "$gradle"
grep -Fq 'manifestPlaceholders["appName"] = "White Noise PR"' "$gradle"
grep -Fq 'manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_preview"' "$gradle"
grep -Fq 'assemblePreviewPlayRelease' "$build"
grep -Fq 'Configure transport-safe preview packaging' "$build"
grep -Fq 'packaging.jniLibs.useLegacyPackaging = true' "$build"
grep -Fq -- '-I "$PR_PREVIEW_INIT_SCRIPT"' "$build"
# The ABI injection alone makes AGP stamp android:testOnly="true", which would
# limit both preview channels to ADB installs. Keep the explicit override.
grep -Fq -- '-Pandroid.injected.testOnly=false' "$build"
reject '-Pandroid.injected.testOnly=true' "$build"
grep -Fq 'include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")' "$gradle"
grep -Fq 'create("play")' "$gradle"
grep -Fq 'buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")' "$gradle"
grep -Fq 'Verify current PR head' "$workflow"
grep -Fq 'Recheck PR head before description update' "$workflow"
grep -Fq 'Recheck PR head before upload' "$workflow"
grep -Fq 'Test trusted publisher contract' "$workflow"
grep -Fq '.github/scripts/test-pr-preview-validation.sh' "$workflow"
grep -Fq 'pulls/${stable_pr}' "$workflow"
grep -Fq 'workflow_run.head_repository.full_name' "$workflow"
grep -Fq 'workflow_dispatch:' "$build"
grep -Fq 'Resolve exact current internal PR head' "$build"
grep -Fq "github.event_name == 'workflow_dispatch' ||" "$build"
grep -Fq 'github.event.pull_request.head.repo.full_name == github.repository' "$build"
grep -Fq 'Resolve exact candidate provenance and current PR' "$workflow"
grep -Fq 'stable_pr=$(provenance_value "$stable" pr_number)' "$workflow"
grep -Fq '[[ "$stable_pr" == "$isolated_pr"' "$workflow"
reject 'branches: [master]' "$build"
grep -Fq 'Update PR description with preview links' "$workflow"
grep -Fq '.github/scripts/update-pr-preview-links.js' "$workflow"
grep -Fq 'stable app identity; keeps app data when switching PRs' .github/scripts/update-pr-preview-links.js
grep -Fq 'github.event.repository.default_branch' "$workflow"
reject 'github.event.workflow_run.repository.default_branch' "$workflow"
# workflow_run.head_sha may gate pull_request runs, but must not override the
# candidate-provenance HEAD_SHA in the description step used by manual backfills.
grep -Fq 'WORKFLOW_HEAD_SHA: ${{ github.event.workflow_run.head_sha }}' "$workflow"
if grep -Eq '^[[:space:]]+HEAD_SHA: \$\{\{ github\.event\.workflow_run\.head_sha \}\}$' "$workflow"; then
  printf 'Description step must use the validated candidate HEAD_SHA.\n' >&2
  exit 1
fi
grep -Fq 'headSha.slice(0, 12)' .github/scripts/update-pr-preview-links.js
grep -Fq 'ref: ${{ steps.resolve.outputs.head_sha }}' "$build"
grep -Fq 'restore missing preview links' "$build"
grep -Fq -- '--min-sdk-version 34' .github/scripts/sign-pr-preview-candidates.sh
grep -Fq 'cancel-in-progress: false' "$workflow"
grep -Fq 'needs: prepare' "$workflow"
grep -Fq 'group: android-pr-preview-publish-pr-${{ needs.prepare.outputs.pr_number }}' "$workflow"
reject 'group: android-pr-preview-publish-${{ github.event.workflow_run.head_repository.full_name }}' "$workflow"
grep -Fq '[[ "$stable_pr" == "$PR_NUMBER" && "$stable_sha" == "$HEAD_SHA" ]]' "$workflow"
grep -Fq 'Verify signed previews' "$workflow"
grep -Fq '.github/scripts/stage-signed-pr-preview-candidates.sh signed candidates signed-check' "$workflow"
grep -Fq 'PR_PREVIEW_CERT_SHA256: ${{ secrets.PR_PREVIEW_CERT_SHA256 }}' "$workflow"
reject 'pull_request_target:' "$build"
grep -Fq 'contents: write' "$workflow"
grep -Fq 'GH_TOKEN: ${{ github.token }}' "$workflow"
grep -Fq 'PREVIEW_CHANNEL="$channel"' "$workflow"
grep -Fq '.github/scripts/publish-pr-apk-to-github-release.sh' "$workflow"
reject 'BLOSSOM_UPLOAD_NSEC' "$workflow"
# Both the pre-checkout prepare job and the post-checkout publish job must bind
# artifact downloads explicitly to this repository. Without --repo, gh fails
# before checkout with "not a git repository" and no preview links are posted.
[[ $(grep -Fc 'gh run download "$BUILD_RUN_ID" --repo "$GITHUB_REPOSITORY" --name pr-preview-stable' "$workflow") -eq 2 ]]
[[ $(grep -Fc 'gh run download "$BUILD_RUN_ID" --repo "$GITHUB_REPOSITORY" --name pr-preview-isolated' "$workflow") -eq 2 ]]

# The public link must be a stable, per-PR APK asset and publication must fail
# closed if the final browser response regresses to an inline ZIP.
grep -Fq 'asset_name="whitenoise-pr-${PR_NUMBER}-${PREVIEW_CHANNEL}.apk"' "$publisher"
grep -Fq 'gh release upload "$release_tag" "$staged_apk"' "$publisher"
grep -Fq 'application/vnd.android.package-archive' "$publisher"
grep -Fq 'content-disposition' "$publisher"
grep -Fq 'filename=${asset_name,,}' "$publisher"
reject 'application/zip' "$publisher"

# ABI-targeted unsigned builds use intermediates; reject missing or ambiguous APKs.
selector=$(sed -n '/^          shopt -s nullglob$/,/^          apk=${apks\[0\]}$/p' "$build")
test -n "$selector"
# Repeat the cardinality assertion as the final command. Bash suppresses
# errexit for `[[ ... ]]` in some caller contexts, while the final status is
# stable whether this fixture is executed directly or from an `if` condition.
selector_test="$selector"$'\n''[[ ${#apks[@]} -eq 1 ]]'
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
for location in outputs intermediates; do
  directory="$fixture/app/build/$location/apk/previewPlay/release"
  mkdir -p "$directory"
  touch "$directory/app-arm64-v8a-release.apk"
  (cd "$fixture" && bash -ec "$selector_test")
  rm "$directory/app-arm64-v8a-release.apk"
done
if (cd "$fixture" && bash -ec "$selector_test"); then
  echo 'Missing APK unexpectedly accepted' >&2
  exit 1
fi
touch "$fixture"/app/build/{outputs,intermediates}/apk/previewPlay/release/app-arm64-v8a-release.apk
if (cd "$fixture" && bash -ec "$selector_test"); then
  echo 'Ambiguous APKs unexpectedly accepted' >&2
  exit 1
fi
