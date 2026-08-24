#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-pr-preview-publish.yml
build=.github/workflows/android-pr-apk.yml
gradle=app/build.gradle.kts
updater=.github/scripts/update-pr-preview-links.js
uploader=.github/scripts/upload-pr-apk-to-blossom.sh

reject() {
  local pattern=$1 file=$2
  if grep -Fq -- "$pattern" "$file"; then
    printf 'Forbidden PR APK workflow pattern found in %s: %s\n' "$file" "$pattern" >&2
    exit 1
  fi
}

# PR-controlled code builds without secrets. The base-branch publisher validates
# untrusted bytes, signs them as data with the regular staging key, and publishes
# exactly one regular-app update.
grep -Fq 'workflow_run:' "$workflow"
grep -Fq "head_repository.full_name == github.repository" "$workflow"
grep -Fq 'WHITENOISE_STAGING_KEYSTORE_BASE64: ${{ secrets.WHITENOISE_STAGING_KEYSTORE_BASE64 }}' "$workflow"
reject 'WHITENOISE_STAGING_KEYSTORE' "$build"
reject 'PR_PREVIEW_KEYSTORE' "$build"
grep -Fq 'create("preview")' "$gradle"
grep -Fq 'applicationId = "dev.ipf.whitenoise.android.staging"' "$gradle"
grep -Fq "PR_PREVIEW_CHANNEL must be 'regular'" "$gradle"
reject 'applicationIdSuffix = ".preview"' "$gradle"
reject 'preview.pr$previewIdentity' "$gradle"
reject '2_000_000_000' "$gradle"
grep -Fq 'assemblePreviewPlayRelease' "$build"
grep -Fq 'include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")' "$gradle"
grep -Fq 'buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")' "$gradle"
grep -Fq 'Verify current PR head' "$workflow"
grep -Fq 'Recheck PR head before description update' "$workflow"
grep -Fq 'Recheck PR head before upload' "$workflow"
grep -Fq '.github/scripts/test-pr-preview-validation.sh' "$workflow"
grep -Fq 'Resolve trusted regular app version contract' "$workflow"
grep -Fq 'EXPECTED_VERSION_CODE=%s' "$workflow"
grep -Fq '[[ "$actual_version" == "$EXPECTED_VERSION_CODE" ]]' .github/scripts/verify-pr-preview-candidates.sh
grep -Fq 'pulls/${regular_pr}' "$workflow"
grep -Fq 'regular_pr=$(provenance_value "$regular" pr_number)' "$workflow"
grep -Fq '[[ "$regular_pr" == "$PR_NUMBER" && "$regular_sha" == "$HEAD_SHA" ]]' "$workflow"
reject 'branches: [master]' "$build"
reject 'pull_request_target:' "$build"
grep -Fq 'workflow_dispatch:' "$build"
grep -Fq 'Resolve exact current internal PR head' "$build"
grep -Fq 'github.event.pull_request.head.repo.full_name == github.repository' "$build"
grep -Fq 'Update PR description with regular-app link' "$workflow"
grep -Fq '.github/scripts/update-pr-preview-links.js' "$workflow"
grep -Fq 'updates the regular White Noise staging app' "$updater"
grep -Fq 'github.event.repository.default_branch' "$workflow"
reject 'github.event.workflow_run.repository.default_branch' "$workflow"
grep -Fq 'WORKFLOW_HEAD_SHA: ${{ github.event.workflow_run.head_sha }}' "$workflow"
if grep -Eq '^[[:space:]]+HEAD_SHA: \$\{\{ github\.event\.workflow_run\.head_sha \}\}$' "$workflow"; then
  printf 'Description step must use validated candidate HEAD_SHA.\n' >&2
  exit 1
fi
reject 'Isolated PR' "$updater"
reject 'stableUrl' "$updater"
reject 'isolatedUrl' "$updater"
grep -Fq 'headSha.slice(0, 12)' "$updater"
grep -Fq 'ref: ${{ steps.resolve.outputs.head_sha }}' "$build"
grep -Fq -- '--min-sdk-version 34' .github/scripts/sign-pr-preview-candidates.sh
grep -Fq 'cancel-in-progress: false' "$workflow"
grep -Fq 'group: android-pr-preview-publish-pr-${{ needs.prepare.outputs.pr_number }}' "$workflow"
grep -Fq '.github/scripts/stage-signed-pr-preview-candidates.sh signed candidates signed-check' "$workflow"
[[ $(grep -Fc 'gh run download "$BUILD_RUN_ID" --repo "$GITHUB_REPOSITORY" --name pr-preview-regular' "$workflow") -eq 2 ]]
reject 'pr-preview-stable' "$workflow"
reject 'pr-preview-isolated' "$workflow"

# Preserve authenticated Blossom publication and explicit APK MIME.
grep -Fq 'NOSTR_SECRET_KEY="$BLOSSOM_UPLOAD_NSEC" "$nak_bin" event' "$uploader"
grep -Fq -- '--tag "x=$apk_sha256"' "$uploader"
grep -Fq -- '--header "Content-Type: $expected_mime"' "$uploader"
grep -Fq -- '--header "X-SHA-256: $apk_sha256"' "$uploader"
reject 'blossom upload --server' "$uploader"
