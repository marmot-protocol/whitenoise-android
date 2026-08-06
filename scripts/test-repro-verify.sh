#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/repro-verify.sh
source "$REPO_DIR/scripts/repro-verify.sh"

FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

tree="$FIXTURE_DIR/tree"
mkdir -p "$tree/app/build/intermediates/apk/productionZapstore/release"
touch "$tree/local.properties" "$tree/app/google-services.json"
repro_verify_sanitize_tree "$tree"
if [[ -e "$tree/local.properties" || -e "$tree/app/google-services.json" ]]; then
  echo 'error: sanitize did not remove local configuration' >&2
  exit 1
fi

cleanup_dir="$FIXTURE_DIR/cleanup-on-exit"
mkdir -p "$cleanup_dir"
# Keep repro_verify_set_cleanup_trap's EXIT replacement in a subshell so the
# parent fixture cleanup trap survives while the verifier trap still runs.
(repro_verify_set_cleanup_trap "$cleanup_dir")
if [[ -e "$cleanup_dir" ]]; then
  echo 'error: EXIT trap did not remove the verifier work directory' >&2
  exit 1
fi

fake_tree="$FIXTURE_DIR/fake-build-tree"
fake_jdk="$FIXTURE_DIR/fake-jdk"
fake_sdk="$FIXTURE_DIR/fake-android-sdk"
mkdir -p "$fake_tree" "$fake_jdk/bin" "$fake_sdk"
cat >"$fake_jdk/bin/java" <<'EOF'
#!/usr/bin/env bash
if [[ " $* " == *' -XshowSettings:properties '* ]]; then
  printf '    java.home = %s\n' "$JAVA_HOME" >&2
  printf '    java.vendor = FakeVendor\n' >&2
  printf '    java.version = 21.0.0\n' >&2
  printf '    java.runtime.version = 21.0.0+0\n' >&2
fi
exit 0
EOF
cat >"$fake_tree/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
env | sort > captured-env
printf '%s\n' "$@" > captured-args
jvm_report=""
for arg in "$@"; do
  if [[ "$arg" == -Drepro.verify.jvm.report=* ]]; then
    jvm_report="${arg#-Drepro.verify.jvm.report=}"
  fi
done
if [[ -n "$jvm_report" ]]; then
  mkdir -p "$(dirname -- "$jvm_report")"
  {
    printf 'java.home=%s\n' "$JAVA_HOME"
    printf 'java.vendor=FakeVendor\n'
    printf 'java.version=21.0.0\n'
    printf 'java.runtime.version=21.0.0+0\n'
  } >"$jvm_report"
fi
EOF
chmod +x "$fake_jdk/bin/java" "$fake_tree/gradlew"

export JAVA_HOME="$fake_jdk"
export ANDROID_HOME="$fake_sdk"
export ANDROID_SDK_ROOT="$fake_sdk"
export WHITENOISE_PRODUCTION_KEYSTORE_PATH=must-not-leak
export WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN=must-not-leak
export WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN=must-not-leak
export JAVA_TOOL_OPTIONS=must-not-leak
fake_work="$FIXTURE_DIR/fake-work"
fake_gradle_home="$FIXTURE_DIR/gradle-home"
fake_jvm_report="$fake_work/build-jvm.properties"
fake_expected_jvm_report="$fake_work/configured-jvm.properties"
fake_init="$fake_work/repro-jvm-report.init.gradle"
mkdir -p "$fake_work"
if repro_verify_prepare_gradle_jvm_pin "$FIXTURE_DIR/missing-java-home" "" 2>/dev/null; then
  echo 'error: Gradle JVM pin accepted an empty JAVA_HOME' >&2
  exit 1
fi
repro_verify_prepare_gradle_jvm_pin "$fake_gradle_home" "$fake_jdk"
repro_verify_write_configured_jvm_report "$fake_jdk" "$fake_expected_jvm_report"
repro_verify_write_jvm_report_init_script "$fake_init"
repro_verify_build "$fake_tree" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef \
  "$fake_gradle_home" "$fake_jvm_report" "$fake_init" "$fake_expected_jvm_report"
repro_verify_assert_build_jvm "$fake_jvm_report" "$fake_expected_jvm_report" tree1
unset WHITENOISE_PRODUCTION_KEYSTORE_PATH WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN
unset WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN JAVA_TOOL_OPTIONS

if grep -Eq 'must-not-leak|WHITENOISE_PRODUCTION_KEYSTORE_PATH|WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN|WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN|JAVA_TOOL_OPTIONS' "$fake_tree/captured-env"; then
  echo 'error: build inherited non-canonical environment variables' >&2
  exit 1
fi
for expected in \
  "ANDROID_HOME=$fake_sdk" \
  "ANDROID_SDK_ROOT=$fake_sdk" \
  "CI=true" \
  "GITHUB_SHA=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" \
  "GRADLE_USER_HOME=$fake_gradle_home" \
  "HOME=$fake_tree/.repro-home" \
  "JAVA_HOME=$fake_jdk" \
  "LANG=C.UTF-8" \
  "LC_ALL=C.UTF-8" \
  "TZ=UTC" \
  "WHITENOISE_ALLOW_UNSIGNED_RELEASE=true"; do
  grep -Fxq "$expected" "$fake_tree/captured-env" || {
    echo "error: canonical build environment missing: $expected" >&2
    exit 1
  }
done
grep -Fxq ':app:assembleProductionZapstoreRelease' "$fake_tree/captured-args" || {
  echo 'error: verifier did not request the production Zapstore release task' >&2
  exit 1
}
grep -Fxq -- "--init-script" "$fake_tree/captured-args" || {
  echo 'error: verifier did not pass a JVM report init script' >&2
  exit 1
}
grep -Fxq -- "$fake_init" "$fake_tree/captured-args" || {
  echo 'error: verifier passed the wrong JVM report init script' >&2
  exit 1
}
grep -Fxq -- "-Drepro.verify.jvm.report=$fake_jvm_report" "$fake_tree/captured-args" || {
  echo 'error: verifier did not request a build JVM report' >&2
  exit 1
}
grep -Fxq "org.gradle.java.installations.paths=$fake_jdk" "$fake_gradle_home/gradle.properties" || {
  echo 'error: isolated Gradle user home did not pin java.installations.paths' >&2
  exit 1
}
grep -Fxq 'org.gradle.java.installations.auto-detect=false' "$fake_gradle_home/gradle.properties" || {
  echo 'error: isolated Gradle user home did not disable java.installations auto-detect' >&2
  exit 1
}
grep -Fxq 'org.gradle.java.installations.auto-download=false' "$fake_gradle_home/gradle.properties" || {
  echo 'error: isolated Gradle user home did not disable java.installations auto-download' >&2
  exit 1
}
[[ -f "$fake_jvm_report" ]] || {
  echo 'error: build JVM report was not written' >&2
  exit 1
}
grep -Fxq "java.home=$fake_jdk" "$fake_jvm_report" || {
  echo 'error: build JVM report does not record the configured JAVA_HOME' >&2
  exit 1
}

mismatch_report="$fake_work/build-jvm-mismatch.properties"
{
  printf 'java.home=%s\n' "$fake_jdk"
  printf 'java.vendor=WrongVendor\n'
  printf 'java.version=21.0.0\n'
  printf 'java.runtime.version=21.0.0+0\n'
} >"$mismatch_report"
if repro_verify_assert_build_jvm "$mismatch_report" "$fake_expected_jvm_report" tree1 2>/dev/null; then
  echo 'error: verifier accepted a mismatched build JVM' >&2
  exit 1
fi

home_mismatch_report="$fake_work/build-jvm-home-mismatch.properties"
{
  printf 'java.home=%s\n' "$FIXTURE_DIR/missing-jdk"
  printf 'java.vendor=FakeVendor\n'
  printf 'java.version=21.0.0\n'
  printf 'java.runtime.version=21.0.0+0\n'
} >"$home_mismatch_report"
if repro_verify_assert_build_jvm "$home_mismatch_report" "$fake_expected_jvm_report" tree1 2>/dev/null; then
  echo 'error: verifier accepted an invalid build JVM java.home' >&2
  exit 1
fi

apk="$tree/app/build/intermediates/apk/productionZapstore/release/app-production-zapstore-arm64-v8a-release-unsigned.apk"
printf 'payload-a' > "$apk"
[[ "$(repro_verify_locate_apk "$tree")" == "$apk" ]] || {
  echo 'error: verifier located the wrong APK' >&2
  exit 1
}

outputs_dir="$tree/app/build/outputs/apk/productionZapstore/release"
mkdir -p "$outputs_dir"
outputs_apk="$outputs_dir/app-production-zapstore-arm64-v8a-release-unsigned.apk"
printf 'preferred-output' >"$outputs_apk"
[[ "$(repro_verify_locate_apk "$tree")" == "$outputs_apk" ]] || {
  echo 'error: verifier did not prefer the final outputs APK' >&2
  exit 1
}
second_outputs_apk="$outputs_dir/other-arm64-v8a-release.apk"
printf 'ambiguous-output' >"$second_outputs_apk"
if repro_verify_locate_apk "$tree" 2>/dev/null; then
  echo 'error: verifier accepted ambiguous final APK outputs' >&2
  exit 1
fi
rm -f "$second_outputs_apk"

empty_tree="$FIXTURE_DIR/empty-tree"
mkdir -p "$empty_tree/app/build/outputs/apk/productionZapstore/release"
if repro_verify_locate_apk "$empty_tree" 2>/dev/null; then
  echo 'error: verifier located an APK in an empty output root' >&2
  exit 1
fi
no_root_tree="$FIXTURE_DIR/no-root-tree"
mkdir -p "$no_root_tree/app/build"
if repro_verify_locate_apk "$no_root_tree" 2>/dev/null; then
  echo 'error: verifier located an APK without a release output root' >&2
  exit 1
fi

apk_b="$FIXTURE_DIR/b.apk"
printf 'payload-b' > "$apk_b"
diag="$(repro_verify_diagnose_mismatch "$apk" "$apk_b" 2>&1)"
[[ "$diag" == *'APK mismatch:'* && "$diag" == *'First differing byte positions'* ]] || {
  echo 'error: mismatch diagnosis was incomplete' >&2
  exit 1
}

evidence="$FIXTURE_DIR/evidence"
repro_verify_write_evidence "$apk" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef "$evidence" >/dev/null
name=whitenoise-production-zapstore-arm64-v8a-release-unsigned-deadbee.apk
[[ -f "$evidence/$name" ]] || {
  echo 'error: evidence APK was not written' >&2
  exit 1
}
grep -q "$name" "$evidence/SHA256SUMS" || {
  echo 'error: SHA256SUMS is missing the evidence APK' >&2
  exit 1
}

fake_apksigner="$FIXTURE_DIR/fake-apksigner"
cat >"$fake_apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ $# -eq 2 && "$1" == verify ]] || exit 64
apk="$2"
java -version >/dev/null
case "${apk##*/}" in
  repro-signed.apk) exit 0 ;;
  repro-tool-error.apk) exit 127 ;;
  repro-bad-unsigned.apk)
    printf 'UNEXPECTED FAILURE\n' >&2
    exit 1
    ;;
  *)
    printf 'DOES NOT VERIFY\n' >&2
    exit 1
    ;;
esac
EOF
chmod +x "$fake_apksigner"

unsigned_apk="$FIXTURE_DIR/repro-unsigned.apk"
signed_apk="$FIXTURE_DIR/repro-signed.apk"
tool_error_apk="$FIXTURE_DIR/repro-tool-error.apk"
bad_unsigned_apk="$FIXTURE_DIR/repro-bad-unsigned.apk"
printf 'unsigned-payload' >"$unsigned_apk"
printf 'signed-payload' >"$signed_apk"
printf 'tool-error-payload' >"$tool_error_apk"
printf 'bad-unsigned-payload' >"$bad_unsigned_apk"

export REPRO_VERIFY_APKSIGNER_PATH="$fake_apksigner"
fake_path="$FIXTURE_DIR/fake-path"
mkdir -p "$fake_path"
ln -s "$(command -v bash)" "$fake_path/bash"
PATH="$fake_path" repro_verify_assert_unsigned_apk "$unsigned_apk" || {
  echo 'error: unsigned APK was rejected' >&2
  exit 1
}
if repro_verify_assert_unsigned_apk "$signed_apk" 2>/dev/null; then
  echo 'error: signed APK was accepted as unsigned' >&2
  exit 1
fi
if repro_verify_assert_unsigned_apk "$tool_error_apk" 2>/dev/null; then
  echo 'error: apksigner tool failure was treated as unsigned' >&2
  exit 1
fi
if repro_verify_assert_unsigned_apk "$bad_unsigned_apk" 2>/dev/null; then
  echo 'error: unexpected apksigner exit-1 output was treated as unsigned' >&2
  exit 1
fi
unset REPRO_VERIFY_APKSIGNER_PATH

missing_apksigner="$FIXTURE_DIR/missing-apksigner"
if repro_verify_assert_unsigned_apk "$unsigned_apk" 2>/dev/null; then
  echo 'error: unsigned check succeeded without apksigner' >&2
  exit 1
fi
export REPRO_VERIFY_APKSIGNER_PATH="$missing_apksigner"
if repro_verify_assert_unsigned_apk "$unsigned_apk" 2>/dev/null; then
  echo 'error: unsigned check succeeded with invalid apksigner override' >&2
  exit 1
fi
unset REPRO_VERIFY_APKSIGNER_PATH

printf 'test-repro-verify.sh passed\n'
