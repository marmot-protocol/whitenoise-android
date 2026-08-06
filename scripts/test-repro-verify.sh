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
exit 0
EOF
cat >"$fake_tree/gradlew" <<'EOF'
#!/usr/bin/env bash
env | sort > captured-env
printf '%s\n' "$@" > captured-args
EOF
chmod +x "$fake_jdk/bin/java" "$fake_tree/gradlew"

export JAVA_HOME="$fake_jdk"
export ANDROID_HOME="$fake_sdk"
export ANDROID_SDK_ROOT="$fake_sdk"
export WHITENOISE_PRODUCTION_KEYSTORE_PATH=must-not-leak
export WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN=must-not-leak
export WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN=must-not-leak
export JAVA_TOOL_OPTIONS=must-not-leak
repro_verify_build "$fake_tree" deadbeefdeadbeefdeadbeefdeadbeefdeadbeef "$FIXTURE_DIR/gradle-home"
unset WHITENOISE_PRODUCTION_KEYSTORE_PATH WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN
unset WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN JAVA_TOOL_OPTIONS

if grep -Eq 'must-not-leak|WHITENOISE_PRODUCTION_KEYSTORE_PATH|WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN|WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN|JAVA_TOOL_OPTIONS' "$fake_tree/captured-env"; then
  echo 'error: build inherited non-canonical environment variables' >&2
  exit 1
fi
for expected in \
  "ANDROID_HOME=$fake_sdk" \
  "ANDROID_SDK_ROOT=$fake_sdk" \
  "GITHUB_SHA=deadbeefdeadbeefdeadbeefdeadbeefdeadbeef" \
  "GRADLE_USER_HOME=$FIXTURE_DIR/gradle-home" \
  "HOME=$fake_tree/.repro-home" \
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

apk="$tree/app/build/intermediates/apk/productionZapstore/release/app-production-zapstore-arm64-v8a-release-unsigned.apk"
printf 'payload-a' > "$apk"
[[ "$(repro_verify_locate_apk "$tree")" == "$apk" ]] || {
  echo 'error: verifier located the wrong APK' >&2
  exit 1
}

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

printf 'test-repro-verify.sh passed\n'
