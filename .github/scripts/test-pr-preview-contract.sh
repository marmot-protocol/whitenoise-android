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
grep -Fq 'applicationIdSuffix = ".preview"' "$gradle"
grep -Fq 'applicationIdSuffix = ".preview.pr$prNumber"' "$gradle"
grep -Fq 'val prPreviewVersionCode = 2_000_000_000' "$gradle"
grep -Fq 'manifestPlaceholders["appName"] = "Preview $defaultAppName"' "$gradle"
grep -Fq 'manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_preview"' "$gradle"
grep -Fq 'Verify current PR head' "$workflow"
grep -Fq 'Verify signed previews' "$workflow"
! grep -Fq 'pull_request_target:' "$build"
