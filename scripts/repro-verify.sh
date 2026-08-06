#!/usr/bin/env bash
# Verify that two path-distinct builds of the same source revision produce the
# same unsigned production Zapstore arm64 APK. See docs/reproducible-apk-builds.md.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPRO_VERIFY_WORK_DIR=""

repro_verify_usage() {
  cat <<'EOF'
Usage: scripts/repro-verify.sh [--ref <git-ref>] [--evidence-dir <dir>] [--keep]

Builds two isolated unsigned production Zapstore arm64-v8a release APKs from the
same revision (default: HEAD) and requires them to match byte-for-byte.

Options:
  --ref <git-ref>       Revision to verify (default: HEAD)
  --evidence-dir <dir>  Copy the verified APK and SHA256SUMS here
  --keep                Keep and print the temporary build directory
EOF
}

repro_verify_cleanup() {
  local work_dir="${REPRO_VERIFY_WORK_DIR:-}"
  REPRO_VERIFY_WORK_DIR=""
  [[ -z "$work_dir" ]] || rm -rf -- "$work_dir"
}

repro_verify_set_cleanup_trap() {
  REPRO_VERIFY_WORK_DIR="$1"
  trap repro_verify_cleanup EXIT
}

repro_verify_sanitize_tree() {
  rm -f -- "$1/local.properties" "$1/app/google-services.json"
}

repro_verify_sha256() {
  local digest _
  read -r digest _ < <(sha256sum -- "$1")
  printf '%s\n' "$digest"
}

repro_verify_locate_apk() {
  local tree="$1"
  local root
  local -a roots=() matches=()

  for root in \
    "$tree/app/build/outputs/apk/productionZapstore/release" \
    "$tree/app/build/intermediates/apk/productionZapstore/release"; do
    [[ ! -d "$root" ]] || roots+=("$root")
  done
  if ((${#roots[@]} == 0)); then
    printf 'error: no production Zapstore release output under %s/app/build\n' "$tree" >&2
    return 1
  fi

  while IFS= read -r -d '' root; do
    matches+=("$root")
  done < <(find "${roots[@]}" -maxdepth 1 -type f -name '*arm64-v8a*release*.apk' -print0)

  if ((${#matches[@]} != 1)); then
    printf 'error: expected one unsigned arm64 APK, found %d\n' "${#matches[@]}" >&2
    printf ' - %s\n' "${matches[@]}" >&2
    return 1
  fi
  printf '%s\n' "${matches[0]}"
}

repro_verify_diagnose_mismatch() {
  local apk1="$1"
  local apk2="$2"

  printf 'APK mismatch:\n' >&2
  printf '  tree1: %s bytes sha256=%s\n' "$(stat -c%s "$apk1")" "$(repro_verify_sha256 "$apk1")" >&2
  printf '  tree2: %s bytes sha256=%s\n' "$(stat -c%s "$apk2")" "$(repro_verify_sha256 "$apk2")" >&2
  printf 'First differing byte positions (decimal byte values):\n' >&2
  cmp -l "$apk1" "$apk2" 2>/dev/null | head -20 >&2 || true
  if command -v unzip >/dev/null 2>&1; then
    printf 'Zip entry-name diff:\n' >&2
    diff -u <(unzip -Z1 "$apk1") <(unzip -Z1 "$apk2") >&2 || true
  fi
}

repro_verify_clone() {
  if git clone --local --quiet "$1" "$2" 2>/dev/null; then
    return
  fi
  git clone --quiet "$1" "$2"
}

repro_verify_build() {
  local tree="$1"
  local commit_sha="$2"
  local gradle_user_home="$3"
  local android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local canonical_home="$tree/.repro-home"
  local -a clean_env

  if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
    printf 'error: JAVA_HOME must point to the verifier JDK\n' >&2
    return 1
  fi
  if [[ -z "$android_sdk" || ! -d "$android_sdk" ]]; then
    printf 'error: set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK\n' >&2
    return 1
  fi
  if [[ -n "${ANDROID_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" && "$ANDROID_HOME" != "$ANDROID_SDK_ROOT" ]]; then
    printf 'error: ANDROID_HOME and ANDROID_SDK_ROOT must identify the same SDK\n' >&2
    return 1
  fi

  mkdir -p "$canonical_home" "$gradle_user_home"
  clean_env=(
    env -i
    "ANDROID_HOME=$android_sdk"
    "ANDROID_SDK_ROOT=$android_sdk"
    "CI=true"
    "GITHUB_SHA=$commit_sha"
    "GRADLE_USER_HOME=$gradle_user_home"
    "HOME=$canonical_home"
    "JAVA_HOME=$JAVA_HOME"
    "LANG=C.UTF-8"
    "LC_ALL=C.UTF-8"
    "PATH=$PATH"
    "TZ=UTC"
    "WHITENOISE_ALLOW_UNSIGNED_RELEASE=true"
  )

  (
    cd "$tree"
    "${clean_env[@]}" ./gradlew :app:assembleProductionZapstoreRelease \
      -Pandroid.injected.build.abi=arm64-v8a \
      -Pandroid.injected.testOnly=false \
      --no-daemon -q
  )
}

repro_verify_write_evidence() {
  local apk="$1"
  local commit_sha="$2"
  local evidence_dir="$3"
  local name="whitenoise-production-zapstore-arm64-v8a-release-unsigned-${commit_sha:0:7}.apk"

  mkdir -p "$evidence_dir"
  cp -- "$apk" "$evidence_dir/$name"
  (cd "$evidence_dir" && sha256sum "$name" > SHA256SUMS)
  printf '%s\n' "$evidence_dir/$name"
}

repro_verify_main() {
  local git_ref=HEAD
  local evidence_dir=""
  local keep=false
  local commit_sha work_dir tree1 tree2 gradle_user_home apk1 apk2 digest evidence_apk

  while (($#)); do
    case "$1" in
      --ref|--evidence-dir)
        if (($# < 2)); then
          repro_verify_usage >&2
          return 2
        fi
        if [[ "$1" == --ref ]]; then git_ref="$2"; else evidence_dir="$2"; fi
        shift 2
        ;;
      --keep) keep=true; shift ;;
      --help|-h) repro_verify_usage; return ;;
      *)
        printf 'error: unknown argument: %s\n' "$1" >&2
        repro_verify_usage >&2
        return 2
        ;;
    esac
  done

  if ! commit_sha="$(git -C "$REPO_ROOT" rev-parse --verify "$git_ref^{commit}")"; then
    printf 'error: invalid git ref: %s\n' "$git_ref" >&2
    return 1
  fi

  work_dir="$(mktemp -d "${TMPDIR:-/tmp}/whitenoise-repro-verify.XXXXXX")"
  if [[ "$keep" == true ]]; then
    printf 'repro-verify: work dir %s\n' "$work_dir" >&2
  else
    repro_verify_set_cleanup_trap "$work_dir"
  fi

  tree1="$work_dir/tree1"
  tree2="$work_dir/tree2"
  gradle_user_home="$work_dir/gradle-user-home"
  printf 'repro-verify: source %s (%s)\n' "$git_ref" "$commit_sha" >&2

  repro_verify_clone "$REPO_ROOT" "$tree1"
  repro_verify_clone "$REPO_ROOT" "$tree2"
  for tree in "$tree1" "$tree2"; do
    git -C "$tree" checkout --quiet "$commit_sha"
    repro_verify_sanitize_tree "$tree"
  done

  printf 'repro-verify: building tree1\n' >&2
  repro_verify_build "$tree1" "$commit_sha" "$gradle_user_home"
  printf 'repro-verify: building tree2\n' >&2
  repro_verify_build "$tree2" "$commit_sha" "$gradle_user_home"

  apk1="$(repro_verify_locate_apk "$tree1")"
  apk2="$(repro_verify_locate_apk "$tree2")"
  if ! cmp -s "$apk1" "$apk2"; then
    repro_verify_diagnose_mismatch "$apk1" "$apk2"
    return 1
  fi

  digest="$(repro_verify_sha256 "$apk1")"
  printf 'repro-verify: OK (byte-identical unsigned production arm64-v8a APK)\n'
  printf 'sha256=%s\n' "$digest"
  if [[ -n "$evidence_dir" ]]; then
    evidence_apk="$(repro_verify_write_evidence "$apk1" "$commit_sha" "$evidence_dir")"
    printf 'evidence_apk=%s\n' "$evidence_apk"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  repro_verify_main "$@"
fi
