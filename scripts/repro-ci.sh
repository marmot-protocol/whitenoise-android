#!/usr/bin/env bash
# CI transport for two independent builds; local verification stays in repro-verify.sh.
set -euo pipefail
# shellcheck source=scripts/repro-verify.sh
source "$(dirname "${BASH_SOURCE[0]}")/repro-verify.sh"

repro_ci_build() {
  local ref="$1" work_dir="$2" gradle_home="$3" evidence_dir="$4"
  local commit_sha tree configured jvm_report init_script apk
  commit_sha="$(git -C "$REPO_ROOT" rev-parse --verify "$ref^{commit}")"
  tree="$work_dir/tree"
  configured="$work_dir/configured-jvm.properties"
  jvm_report="$work_dir/build-jvm.properties"
  init_script="$work_dir/repro-jvm-report.init.gradle"
  mkdir -p "$work_dir"
  repro_verify_prepare_gradle_jvm_pin "$gradle_home" "${JAVA_HOME:-}"
  repro_verify_write_configured_jvm_report "$JAVA_HOME" "$configured"
  repro_verify_write_jvm_report_init_script "$init_script"
  repro_verify_clone "$REPO_ROOT" "$tree"
  git -C "$tree" checkout --quiet "$commit_sha"
  repro_verify_sanitize_tree "$tree"
  repro_verify_build "$tree" "$commit_sha" "$gradle_home" "$jvm_report" "$init_script" "$configured"
  apk="$(repro_verify_locate_apk "$tree")"
  repro_verify_assert_unsigned_apk "$apk"
  repro_verify_record_build_environment "$evidence_dir" "$commit_sha" "$configured" "$jvm_report" "" "$tree"
  repro_verify_write_evidence "$apk" "$commit_sha" "$evidence_dir"
}

repro_ci_compare() {
  local commit_sha="$1" first="$2" second="$3" evidence_dir="$4"
  local name="whitenoise-production-zapstore-arm64-v8a-release-unsigned-${commit_sha:0:7}.apk"
  local directory
  for directory in "$first" "$second"; do
    grep -Fxq "source_sha=$commit_sha" "$directory/repro-build-environment.txt"
    (cd "$directory" && sha256sum -c SHA256SUMS)
    repro_verify_assert_unsigned_apk "$directory/$name"
  done
  # Match recorded JVM, SDK and wrapper inputs before claiming cross-runner equality.
  diff -u "$first/repro-build-environment.txt" "$second/repro-build-environment.txt"
  if ! cmp -s "$first/$name" "$second/$name"; then
    repro_verify_diagnose_mismatch "$first/$name" "$second/$name"
    return 1
  fi
  repro_verify_write_evidence "$first/$name" "$commit_sha" "$evidence_dir"
  cp "$first/repro-build-environment.txt" "$evidence_dir/"
  printf 'repro-verify: OK (byte-identical independent unsigned production arm64-v8a APKs)\n'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  case "${1:-}" in
    build) shift; [[ $# == 4 ]]; repro_ci_build "$@" ;;
    compare) shift; [[ $# == 4 ]]; repro_ci_compare "$@" ;;
    *) echo 'Usage: repro-ci.sh build REF WORK_DIR GRADLE_HOME EVIDENCE_DIR | compare SHA FIRST SECOND EVIDENCE_DIR' >&2; exit 2 ;;
  esac
fi
