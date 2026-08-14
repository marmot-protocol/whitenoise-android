#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-pr-preview-publish.yml
build=.github/workflows/android-pr-apk.yml
gradle=app/build.gradle.kts

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
grep -Fq 'Recheck PR head before comment' "$workflow"
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
if [[ $(grep -Fc -- '--repo "$GITHUB_REPOSITORY"' "$workflow") -lt 2 ]]; then
  printf 'Preview provenance downloads must select the repository explicitly.\n' >&2
  exit 1
fi
reject 'branches: [master]' "$build"
grep -Fq 'stable app identity; keeps app data when switching PRs' "$workflow"
grep -Fq 'github.event.repository.default_branch' "$workflow"
reject 'github.event.workflow_run.repository.default_branch' "$workflow"
# workflow_run.head_sha may gate pull_request runs, but must not override the
# candidate-provenance HEAD_SHA in the comment step used by manual backfills.
grep -Fq 'WORKFLOW_HEAD_SHA: ${{ github.event.workflow_run.head_sha }}' "$workflow"
if grep -Eq '^[[:space:]]+HEAD_SHA: \$\{\{ github\.event\.workflow_run\.head_sha \}\}$' "$workflow"; then
  printf 'Comment step must use the validated candidate HEAD_SHA.\n' >&2
  exit 1
fi
grep -Fq 'process.env.HEAD_SHA.slice(0, 12)' "$workflow"
grep -Fq 'ref: ${{ steps.resolve.outputs.head_sha }}' "$build"
grep -Fq 'restore a missing comment' "$build"
grep -Fq -- '--min-sdk-version 34' .github/scripts/sign-pr-preview-candidates.sh
grep -Fq 'cancel-in-progress: true' "$workflow"
grep -Fq 'needs: prepare' "$workflow"
grep -Fq 'group: android-pr-preview-publish-pr-${{ needs.prepare.outputs.pr_number }}' "$workflow"
reject 'group: android-pr-preview-publish-${{ github.event.workflow_run.head_repository.full_name }}' "$workflow"
grep -Fq '[[ "$stable_pr" == "$PR_NUMBER" && "$stable_sha" == "$HEAD_SHA" ]]' "$workflow"
grep -Fq 'Verify signed previews' "$workflow"
grep -Fq '.github/scripts/stage-signed-pr-preview-candidates.sh signed candidates signed-check' "$workflow"
grep -Fq 'PR_PREVIEW_CERT_SHA256: ${{ secrets.PR_PREVIEW_CERT_SHA256 }}' "$workflow"
reject 'pull_request_target:' "$build"
