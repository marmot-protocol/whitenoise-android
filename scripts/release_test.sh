#!/usr/bin/env bash

set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_SCRIPT_UNDER_TEST="${RELEASE_SCRIPT_UNDER_TEST:-$REPO_DIR/scripts/release.sh}"
FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

mkdir -p "$FIXTURE_DIR/repo/app" "$FIXTURE_DIR/repo/scripts" "$FIXTURE_DIR/bin"
cp "$RELEASE_SCRIPT_UNDER_TEST" "$FIXTURE_DIR/repo/scripts/release.sh"
chmod +x "$FIXTURE_DIR/repo/scripts/release.sh"

touch "$FIXTURE_DIR/release.p12"
touch "$FIXTURE_DIR/compact.gradle"

cat > "$FIXTURE_DIR/bin/java" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF

cat > "$FIXTURE_DIR/bin/aapt" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$RELEASE_TEST_AAPT_LOG"
case "$*" in
  "dump xmltree "*)
    printf 'E: manifest (line=2)\n'
    ;;
  "dump resources "*)
    if [[ "${RELEASE_TEST_INCLUDE_FIREBASE_RESOURCES:-true}" == "true" ]]; then
      printf 'resource 0x7f120001 dev.ipf.whitenoise.android:string/google_app_id\n'
      printf 'resource 0x7f120002 dev.ipf.whitenoise.android:string/gcm_defaultSenderId\n'
    fi
    ;;
esac
EOF

cat > "$FIXTURE_DIR/bin/unzip" <<'EOF'
#!/usr/bin/env bash
if [[ "${RELEASE_TEST_INCLUDE_PUSH_CONFIG:-true}" == "true" ]]; then
  printf '%s\n' "$WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX"
  printf '%s\n' "$WHITENOISE_PRODUCTION_PUSH_RELAY_HINT"
fi
EOF

cat > "$FIXTURE_DIR/bin/strings" <<'EOF'
#!/usr/bin/env bash
cat
EOF

cat > "$FIXTURE_DIR/repo/gradlew" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$RELEASE_TEST_GRADLE_LOG"
mkdir -p \
  app/build/outputs/apk/productionZapstore/release
touch \
  app/build/outputs/apk/productionZapstore/release/app-production-zapstore-universal-release.apk
EOF
chmod +x \
  "$FIXTURE_DIR/bin/java" \
  "$FIXTURE_DIR/bin/aapt" \
  "$FIXTURE_DIR/bin/unzip" \
  "$FIXTURE_DIR/bin/strings" \
  "$FIXTURE_DIR/repo/gradlew"

export RELEASE_TEST_AAPT_LOG="$FIXTURE_DIR/aapt.log"
export RELEASE_TEST_GRADLE_LOG="$FIXTURE_DIR/gradle.log"
export RELEASE_TEST_INCLUDE_FIREBASE_RESOURCES="true"
export RELEASE_TEST_INCLUDE_PUSH_CONFIG="true"
export WHITENOISE_PRODUCTION_KEYSTORE_PATH="$FIXTURE_DIR/release.p12"
export WHITENOISE_PRODUCTION_KEY_ALIAS="release"
export WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD="password"
export WHITENOISE_PRODUCTION_KEY_PASSWORD="password"

: > "$RELEASE_TEST_AAPT_LOG"
: > "$RELEASE_TEST_GRADLE_LOG"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e

if (( status == 0 )); then
  printf 'error: production release succeeded without google-services.json\n%s\n' "$output" >&2
  exit 1
fi
if [[ "$output" != *"google-services.json"* || "$output" != *"dev.ipf.whitenoise.android"* ]]; then
  printf 'error: missing Firebase config failure was not actionable\n%s\n' "$output" >&2
  exit 1
fi
if [[ -s "$RELEASE_TEST_GRADLE_LOG" ]]; then
  echo 'error: Gradle ran before the missing Firebase config was rejected' >&2
  exit 1
fi

cat > "$FIXTURE_DIR/repo/app/google-services.json" <<'EOF'
{
  "client": [
    {
      "client_info": {
        "android_client_info": {
          "package_name": "dev.ipf.whitenoise.android.dev"
        }
      }
    }
  ]
}
EOF

set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e

if (( status == 0 )); then
  printf 'error: production release accepted google-services.json without the production client\n%s\n' "$output" >&2
  exit 1
fi
if [[ "$output" != *"google-services.json"* || "$output" != *"dev.ipf.whitenoise.android"* ]]; then
  printf 'error: mismatched Firebase client failure was not actionable\n%s\n' "$output" >&2
  exit 1
fi
if [[ -s "$RELEASE_TEST_GRADLE_LOG" ]]; then
  echo 'error: Gradle ran before the mismatched Firebase config was rejected' >&2
  exit 1
fi

cat > "$FIXTURE_DIR/repo/app/google-services.json" <<'EOF'
{
  "client": [
    {
      "client_info": {
        "android_client_info": {
          "package_name": "dev.ipf.whitenoise.android"
        }
      }
    }
  ]
}
EOF

set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e

if (( status == 0 )); then
  printf 'error: production release succeeded without a MIP-05 push server public key\n%s\n' "$output" >&2
  exit 1
fi
if [[ "$output" != *"WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX"* ]]; then
  printf 'error: missing production push-key failure was not actionable\n%s\n' "$output" >&2
  exit 1
fi
if [[ -s "$RELEASE_TEST_GRADLE_LOG" ]]; then
  echo 'error: Gradle ran before the missing production push key was rejected' >&2
  exit 1
fi

export WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX="deadbeef"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e
if (( status == 0 )) || [[ "$output" != *"64 hexadecimal characters"* ]]; then
  printf 'error: malformed production push key was not rejected clearly\n%s\n' "$output" >&2
  exit 1
fi

export WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
export WHITENOISE_PRODUCTION_PUSH_RELAY_HINT="https://relay.example"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e
if (( status == 0 )) || [[ "$output" != *"valid wss:// URI"* ]]; then
  printf 'error: malformed production push relay hint was not rejected clearly\n%s\n' "$output" >&2
  exit 1
fi

export WHITENOISE_PRODUCTION_PUSH_RELAY_HINT="wss://relay.example:not-a-port"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e
if (( status == 0 )) || [[ "$output" != *"valid wss:// URI"* ]]; then
  printf 'error: non-numeric production push relay port was not rejected clearly\n%s\n' "$output" >&2
  exit 1
fi

export WHITENOISE_PRODUCTION_PUSH_RELAY_HINT="wss://relay.example"

set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal \
    --gradle-init-script "$FIXTURE_DIR/compact.gradle"
} 2>&1)"
status=$?
set -e

if (( status != 0 )); then
  printf 'error: universal release invocation failed with status %d\n%s\n' "$status" "$output" >&2
  exit "$status"
fi

selected_apk="$FIXTURE_DIR/repo/app/build/outputs/apk/productionZapstore/release/app-production-zapstore-universal-release.apk"

if [[ "$output" != *"==> Selected: $selected_apk"* ]]; then
  printf 'error: universal APK was not selected\n%s\n' "$output" >&2
  exit 1
fi

if ! grep -Fq -- ':app:assembleProductionZapstoreRelease' "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: Zapstore release task was not invoked' >&2
  exit 1
fi
if ! grep -Fq -- '-Pandroid.injected.testOnly=false' "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: release build did not disable testOnly' >&2
  exit 1
fi
if ! grep -Fq -- "-I $FIXTURE_DIR/compact.gradle" "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: trusted Gradle init script was not applied' >&2
  exit 1
fi
if grep -Fq -- '-Pandroid.injected.build.abi=' "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: universal build unexpectedly received a per-ABI Gradle property' >&2
  exit 1
fi
if ! grep -Fq -- "dump xmltree $selected_apk AndroidManifest.xml" "$RELEASE_TEST_AAPT_LOG"; then
  echo 'error: selected universal APK was not checked with aapt' >&2
  exit 1
fi
if ! grep -Fq -- "dump resources $selected_apk" "$RELEASE_TEST_AAPT_LOG"; then
  echo 'error: selected universal APK resources were not checked with aapt' >&2
  exit 1
fi

: > "$RELEASE_TEST_AAPT_LOG"
: > "$RELEASE_TEST_GRADLE_LOG"
rm -f "$selected_apk"
export RELEASE_TEST_INCLUDE_FIREBASE_RESOURCES="false"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e
export RELEASE_TEST_INCLUDE_FIREBASE_RESOURCES="true"

if (( status == 0 )); then
  printf 'error: production release accepted an APK without Firebase resources\n%s\n' "$output" >&2
  exit 1
fi
if [[ "$output" != *"google_app_id"* || "$output" != *"gcm_defaultSenderId"* ]]; then
  printf 'error: missing packaged Firebase resource failure was not actionable\n%s\n' "$output" >&2
  exit 1
fi
if ! grep -Fq -- "dump resources $selected_apk" "$RELEASE_TEST_AAPT_LOG"; then
  echo 'error: APK resources were not inspected before rejection' >&2
  exit 1
fi

: > "$RELEASE_TEST_AAPT_LOG"
: > "$RELEASE_TEST_GRADLE_LOG"
rm -f "$selected_apk"
export RELEASE_TEST_INCLUDE_PUSH_CONFIG="false"
set +e
output="$({
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} 2>&1)"
status=$?
set -e
export RELEASE_TEST_INCLUDE_PUSH_CONFIG="true"

if (( status == 0 )); then
  printf 'error: production release accepted an APK without its configured MIP-05 values\n%s\n' "$output" >&2
  exit 1
fi
if [[ "$output" != *"does not contain the configured MIP-05"* ]]; then
  printf 'error: missing packaged MIP-05 config failure was not actionable\n%s\n' "$output" >&2
  exit 1
fi

: > "$RELEASE_TEST_AAPT_LOG"
: > "$RELEASE_TEST_GRADLE_LOG"
rm -f "$selected_apk"
{
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh
} >/dev/null
if grep -Fq -- '-I' "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: default release build unexpectedly received a Gradle init script' >&2
  exit 1
fi
if ! grep -Fq -- "dump resources $selected_apk" "$RELEASE_TEST_AAPT_LOG"; then
  echo 'error: default release build did not verify its APK resources' >&2
  exit 1
fi

if {
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal \
    --gradle-init-script ""
} >/dev/null 2>&1; then
  echo 'error: empty Gradle init-script operand was accepted' >&2
  exit 1
fi

ln -s "$FIXTURE_DIR/compact.gradle" "$FIXTURE_DIR/compact-link.gradle"
if {
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal \
    --gradle-init-script "$FIXTURE_DIR/compact-link.gradle"
} >/dev/null 2>&1; then
  echo 'error: symlink Gradle init script was accepted' >&2
  exit 1
fi

printf 'release.sh universal ABI regression test passed\n'
