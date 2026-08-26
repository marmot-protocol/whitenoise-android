#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-pr-preview-publish.yml
build=.github/workflows/android-pr-apk.yml
ci=.github/workflows/android-ci.yml
gradle=app/build.gradle.kts
uploader=.github/scripts/upload-pr-apk-to-blossom.sh
fallback_uploader=.github/scripts/upload-pr-apk-with-fallback.sh

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
grep -Fq 'include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")' "$gradle"
grep -Fq 'create("play")' "$gradle"
grep -Fq 'buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")' "$gradle"
grep -Fq 'Verify current PR head' "$workflow"
grep -Fq 'Recheck PR head before description update' "$workflow"
grep -Fq 'Recheck PR head before upload' "$workflow"
grep -Fq 'Test trusted publisher contract' "$workflow"
grep -Fq '.github/scripts/test-pr-preview-validation.sh' "$workflow"
grep -Fq '.github/scripts/test-upload-pr-apk-with-fallback.sh' "$workflow"
grep -Fq '.github/scripts/test-upload-pr-apk-with-fallback.sh' "$ci"
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
# Both the pre-checkout prepare job and the post-checkout publish job must bind
# artifact downloads explicitly to this repository. Without --repo, gh fails
# before checkout with "not a git repository" and no preview links are posted.
[[ $(grep -Fc 'gh run download "$BUILD_RUN_ID" --repo "$GITHUB_REPOSITORY" --name pr-preview-stable' "$workflow") -eq 2 ]]
[[ $(grep -Fc 'gh run download "$BUILD_RUN_ID" --repo "$GITHUB_REPOSITORY" --name pr-preview-isolated' "$workflow") -eq 2 ]]

# APKs are ZIP containers, so nak's automatic MIME detection labels them as
# application/zip. Preserve the BUD-11 signer while requiring an explicit
# BUD-02 APK Content-Type on the upload request.
grep -Fq 'NOSTR_SECRET_KEY="$BLOSSOM_UPLOAD_NSEC" "$nak_bin" event' "$uploader"
grep -Fq -- '--tag "x=$apk_sha256"' "$uploader"
grep -Fq -- '--tag "server=$server_host"' "$uploader"
grep -Fq -- '--header "Content-Type: $expected_mime"' "$uploader"
grep -Fq -- '--header "X-SHA-256: $apk_sha256"' "$uploader"
reject 'blossom upload --server' "$uploader"

# A transient outage at one public Blossom origin must not suppress both PR
# install links. Keep retries bounded so both APKs can reach the fallback
# before the trusted publisher's 20-minute job timeout.
grep -Fq 'BLOSSOM_SERVERS: https://nostr.download https://blossom.primal.net' "$workflow"
grep -Fq 'BLOSSOM_UPLOAD_MAX_ATTEMPTS: 2' "$workflow"
grep -Fq 'BLOSSOM_UPLOAD_TIMEOUT_SECONDS: 90' "$workflow"
grep -Fq 'BLOSSOM_UPLOAD_BACKOFF_SECONDS: 5' "$workflow"
grep -Fq 'url=$(APK_PATH="$apk" .github/scripts/upload-pr-apk-with-fallback.sh)' "$workflow"
grep -Fq 'BLOSSOM_SERVER="$server" "$uploader"' "$fallback_uploader"
reject 'eval ' "$fallback_uploader"
