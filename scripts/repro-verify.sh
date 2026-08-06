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

repro_verify_locate_apksigner() {
  local android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local override="${REPRO_VERIFY_APKSIGNER_PATH:-}"
  local -a candidates=()
  local candidate version_dir

  if [[ -n "$override" ]]; then
    if [[ ! -x "$override" ]]; then
      printf 'error: REPRO_VERIFY_APKSIGNER_PATH is not executable: %s\n' "$override" >&2
      return 1
    fi
    printf '%s\n' "$override"
    return
  fi
  if [[ -z "$android_sdk" || ! -d "$android_sdk/build-tools" ]]; then
    printf 'error: Android SDK build-tools not found; set ANDROID_HOME or ANDROID_SDK_ROOT\n' >&2
    return 1
  fi
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    candidates+=("$candidate")
  done < <(find "$android_sdk/build-tools" -mindepth 2 -maxdepth 2 -type f -name apksigner -printf '%h\n' 2>/dev/null | sort -V)
  if ((${#candidates[@]} == 0)); then
    printf 'error: apksigner not found under %s/build-tools\n' "$android_sdk" >&2
    return 1
  fi
  version_dir="${candidates[${#candidates[@]} - 1]}"
  candidate="$version_dir/apksigner"
  if [[ ! -x "$candidate" ]]; then
    printf 'error: apksigner is not executable: %s\n' "$candidate" >&2
    return 1
  fi
  printf '%s\n' "$candidate"
}

repro_verify_assert_unsigned_apk() {
  local apk="$1"
  local java_home="${JAVA_HOME:-}"
  local apksigner output status=0

  if ! apksigner="$(repro_verify_locate_apksigner)"; then
    return 1
  fi
  if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]]; then
    printf 'error: JAVA_HOME must point to the verifier JDK for apksigner\n' >&2
    return 1
  fi
  output="$(PATH="$java_home/bin:$PATH" "$apksigner" verify "$apk" 2>&1)" || status=$?
  if [[ $status -eq 0 ]]; then
    printf 'error: APK is signed (apksigner verify succeeded): %s\n' "$apk" >&2
    return 1
  fi
  if [[ $status -eq 1 && "$output" == DOES\ NOT\ VERIFY* ]]; then
    return 0
  fi
  printf 'error: apksigner verify failed unexpectedly (exit %s): %s\n' "$status" "$output" >&2
  return 1
}

repro_verify_canonical_path() {
  local path="$1"
  if command -v realpath >/dev/null 2>&1; then
    realpath -- "$path"
    return
  fi
  readlink -f -- "$path"
}

repro_verify_prepare_gradle_jvm_pin() {
  local gradle_user_home="$1"
  local java_home="$2"
  local props="$gradle_user_home/gradle.properties"

  if [[ -z "$java_home" || ! -x "$java_home/bin/java" ]]; then
    printf 'error: JAVA_HOME must point to the verifier JDK\n' >&2
    return 1
  fi
  mkdir -p "$gradle_user_home"
  cat >"$props" <<EOF
org.gradle.java.installations.paths=$java_home
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
EOF
}

repro_verify_report_value() {
  local report_path="$1"
  local key="$2"
  local line value

  while IFS= read -r line; do
    if [[ "$line" == "$key="* ]]; then
      value="${line#*=}"
      value="${value%"${value##*[![:space:]]}"}"
      printf '%s\n' "$value"
      return 0
    fi
  done <"$report_path"
  return 1
}

repro_verify_write_configured_jvm_report() {
  local java_home="$1"
  local report_path="$2"
  local settings key line prefix value

  if ! settings="$("$java_home/bin/java" -XshowSettings:properties -version 2>&1)"; then
    printf 'error: could not inspect configured JAVA_HOME: %s\n' "$java_home" >&2
    return 1
  fi
  mkdir -p "$(dirname -- "$report_path")"
  : >"$report_path"
  for key in java.home java.vendor java.version java.runtime.version; do
    value=""
    prefix="$key = "
    while IFS= read -r line; do
      line="${line#"${line%%[![:space:]]*}"}"
      if [[ "$line" == "$prefix"* ]]; then
        value="${line#"$prefix"}"
        value="${value%"${value##*[![:space:]]}"}"
        break
      fi
    done <<<"$settings"
    if [[ -z "$value" ]]; then
      printf 'error: configured JVM report has no %s\n' "$key" >&2
      return 1
    fi
    printf '%s=%s\n' "$key" "$value" >>"$report_path"
  done
}

repro_verify_write_jvm_report_init_script() {
  local init_script="$1"

  mkdir -p "$(dirname -- "$init_script")"
  cat >"$init_script" <<'EOF'
def reportPath = System.getProperty('repro.verify.jvm.report')
if (reportPath != null) {
    def report = new File(reportPath)
    report.parentFile.mkdirs()
    report.text = """java.home=${System.getProperty('java.home')}
java.vendor=${System.getProperty('java.vendor')}
java.version=${System.getProperty('java.version')}
java.runtime.version=${System.getProperty('java.runtime.version')}
"""
}
EOF
}

repro_verify_assert_build_jvm() {
  local report_path="$1"
  local expected_report="$2"
  local build_label="$3"
  local key expected actual

  if [[ ! -f "$report_path" ]]; then
    printf 'error: %s build JVM report missing: %s\n' "$build_label" "$report_path" >&2
    return 1
  fi
  if [[ ! -f "$expected_report" ]]; then
    printf 'error: configured JVM report missing: %s\n' "$expected_report" >&2
    return 1
  fi
  for key in java.home java.vendor java.version java.runtime.version; do
    if ! expected="$(repro_verify_report_value "$expected_report" "$key")"; then
      printf 'error: configured JVM report has no %s: %s\n' "$key" "$expected_report" >&2
      return 1
    fi
    if ! actual="$(repro_verify_report_value "$report_path" "$key")"; then
      printf 'error: %s build JVM report has no %s: %s\n' "$build_label" "$key" "$report_path" >&2
      return 1
    fi
    if [[ "$key" == java.home ]]; then
      if ! expected="$(repro_verify_canonical_path "$expected")" ||
        ! actual="$(repro_verify_canonical_path "$actual")"; then
        printf 'error: %s build JVM java.home is not a valid path\n' "$build_label" >&2
        return 1
      fi
    fi
    if [[ "$actual" != "$expected" ]]; then
      printf 'error: %s build JVM %s mismatch (expected %s, got %s)\n' \
        "$build_label" "$key" "$expected" "$actual" >&2
      return 1
    fi
  done
}

repro_verify_record_build_environment() {
  local evidence_dir="$1"
  local commit_sha="$2"
  local configured_jvm_report="$3"
  local jvm_report1="$4"
  local jvm_report2="$5"
  local source_tree="$6"
  local android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local report="$evidence_dir/repro-build-environment.txt"
  local apksigner

  mkdir -p "$evidence_dir"
  {
    printf 'source_sha=%s\n' "$commit_sha"
    if [[ -n "${RUNNER_OS:-}" ]]; then printf 'runner_os=%s\n' "$RUNNER_OS"; fi
    if [[ -n "${RUNNER_ARCH:-}" ]]; then printf 'runner_arch=%s\n' "$RUNNER_ARCH"; fi
    printf 'configured_java_home=%s\n' "$JAVA_HOME"
    printf 'gradle_no_daemon=true\n'
    printf 'gradle_state_isolated_per_tree=true\n'
    printf 'gradle_java_installations_paths=%s\n' "$JAVA_HOME"
    printf 'gradle_java_installations_auto_detect=false\n'
    printf 'gradle_java_installations_auto_download=false\n'
    "$JAVA_HOME/bin/java" -version
    printf '\n[configured jvm]\n'
    cat -- "$configured_jvm_report"
    printf '\n[tree1 build jvm]\n'
    cat -- "$jvm_report1"
    printf '\n[tree2 build jvm]\n'
    cat -- "$jvm_report2"
    sha256sum -- "$source_tree/gradle/wrapper/gradle-wrapper.jar" \
      "$source_tree/gradle/wrapper/gradle-wrapper.properties"
    if [[ -n "$android_sdk" && -d "$android_sdk" ]]; then
      find "$android_sdk/platforms" "$android_sdk/build-tools" \
        -maxdepth 2 -type f -name source.properties -print -exec sha256sum '{}' ';'
      find "$android_sdk/platforms" \
        -maxdepth 2 -type f -name android.jar -print -exec sha256sum '{}' ';'
    fi
    if apksigner="$(repro_verify_locate_apksigner)"; then
      printf 'apksigner=%s\n' "$apksigner"
    fi
  } >"$report" 2>&1
}

repro_verify_locate_apk() {
  local tree="$1"
  local root match
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

  for root in "${roots[@]}"; do
    matches=()
    while IFS= read -r -d '' match; do
      matches+=("$match")
    done < <(find "$root" -maxdepth 1 -type f -name '*arm64-v8a*release*.apk' -print0)
    ((${#matches[@]} == 0)) || break
  done

  if ((${#matches[@]} != 1)); then
    printf 'error: expected one unsigned arm64 APK, found %d\n' "${#matches[@]}" >&2
    ((${#matches[@]} == 0)) || printf ' - %s\n' "${matches[@]}" >&2
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
  local jvm_report="$4"
  local init_script="$5"
  local configured_jvm_report="$6"
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
  if [[ ! -f "$init_script" ]]; then
    printf 'error: JVM report init script missing: %s\n' "$init_script" >&2
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
      --init-script "$init_script" \
      -Drepro.verify.jvm.report="$jvm_report" \
      --no-daemon -q
  )
  repro_verify_assert_build_jvm "$jvm_report" "$configured_jvm_report" "$(basename -- "$tree")"
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
  local commit_sha work_dir tree tree1 tree2 gradle_user_home1 gradle_user_home2
  local jvm_init configured_jvm_report
  local jvm_report1 jvm_report2
  local apk1 apk2 digest evidence_apk

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

  if [[ "$(uname -s)" != Linux ]]; then
    printf 'error: reproducibility verification requires Linux with GNU coreutils/findutils\n' >&2
    return 1
  fi
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
  gradle_user_home1="$work_dir/gradle-user-home-tree1"
  gradle_user_home2="$work_dir/gradle-user-home-tree2"
  jvm_init="$work_dir/repro-jvm-report.init.gradle"
  configured_jvm_report="$work_dir/configured-jvm.properties"
  jvm_report1="$work_dir/build1-jvm.properties"
  jvm_report2="$work_dir/build2-jvm.properties"
  repro_verify_prepare_gradle_jvm_pin "$gradle_user_home1" "${JAVA_HOME:-}"
  repro_verify_prepare_gradle_jvm_pin "$gradle_user_home2" "${JAVA_HOME:-}"
  repro_verify_write_configured_jvm_report "$JAVA_HOME" "$configured_jvm_report"
  repro_verify_write_jvm_report_init_script "$jvm_init"
  printf 'repro-verify: source %s (%s)\n' "$git_ref" "$commit_sha" >&2

  repro_verify_clone "$REPO_ROOT" "$tree1"
  repro_verify_clone "$REPO_ROOT" "$tree2"
  for tree in "$tree1" "$tree2"; do
    git -C "$tree" checkout --quiet "$commit_sha"
    repro_verify_sanitize_tree "$tree"
  done

  printf 'repro-verify: building tree1\n' >&2
  repro_verify_build "$tree1" "$commit_sha" "$gradle_user_home1" "$jvm_report1" "$jvm_init" "$configured_jvm_report"
  printf 'repro-verify: building tree2\n' >&2
  repro_verify_build "$tree2" "$commit_sha" "$gradle_user_home2" "$jvm_report2" "$jvm_init" "$configured_jvm_report"
  if ! cmp -s "$jvm_report1" "$jvm_report2"; then
    printf 'error: build JVM reports differ between trees\n' >&2
    return 1
  fi

  apk1="$(repro_verify_locate_apk "$tree1")"
  apk2="$(repro_verify_locate_apk "$tree2")"
  repro_verify_assert_unsigned_apk "$apk1"
  repro_verify_assert_unsigned_apk "$apk2"
  if ! cmp -s "$apk1" "$apk2"; then
    repro_verify_diagnose_mismatch "$apk1" "$apk2"
    return 1
  fi

  digest="$(repro_verify_sha256 "$apk1")"
  printf 'repro-verify: OK (byte-identical unsigned production arm64-v8a APK)\n'
  printf 'sha256=%s\n' "$digest"
  if [[ -n "$evidence_dir" ]]; then
    repro_verify_record_build_environment "$evidence_dir" "$commit_sha" \
      "$configured_jvm_report" "$jvm_report1" "$jvm_report2" "$tree1"
    evidence_apk="$(repro_verify_write_evidence "$apk1" "$commit_sha" "$evidence_dir")"
    printf 'evidence_apk=%s\n' "$evidence_apk"
  fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  repro_verify_main "$@"
fi
