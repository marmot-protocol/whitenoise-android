#!/usr/bin/env bash
set -euo pipefail

workflow=.github/workflows/android-pr-preview-publish.yml
build=.github/workflows/android-pr-apk.yml
gradle=app/build.gradle.kts

# Guard the security and update-in-place contracts against accidental edits.
grep -Fq 'workflow_run:' "$workflow"
grep -Fq "head_repository.full_name == github.repository" "$workflow"
grep -Fq 'PR_PREVIEW_KEYSTORE_BASE64: ${{ secrets.PR_PREVIEW_KEYSTORE_BASE64 }}' "$workflow"
! grep -Fq 'PR_PREVIEW_KEYSTORE' "$build"
grep -Fq 'create("preview")' "$gradle"
grep -Fq 'applicationIdSuffix = ".preview"' "$gradle"
grep -Fq 'applicationIdSuffix = ".preview.pr$previewIdentity"' "$gradle"
grep -Fq 'val prPreviewVersionCode = 2_000_000_000' "$gradle"
grep -Fq 'manifestPlaceholders["appName"] = "PR $previewIdentity Preview"' "$gradle"
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
grep -Fq 'commits/${HEAD_SHA}/pulls' "$workflow"
grep -Fq 'workflow_run.head_repository.full_name' "$workflow"
grep -Fq -- '--min-sdk-version 34' .github/scripts/sign-pr-preview-candidates.sh
grep -Fq 'cancel-in-progress: true' "$workflow"
grep -Fq 'Verify signed previews' "$workflow"
grep -Fq 'PR_PREVIEW_CERT_SHA256: ${{ secrets.PR_PREVIEW_CERT_SHA256 }}' "$workflow"
! grep -Fq 'pull_request_target:' "$build"
