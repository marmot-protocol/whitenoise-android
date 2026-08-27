#!/usr/bin/env bash

set -euo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
RELEASE_SCRIPT_UNDER_TEST="${RELEASE_SCRIPT_UNDER_TEST:-$REPO_DIR/scripts/release.sh}"
FIXTURE_DIR="$(mktemp -d)"
trap 'rm -rf "$FIXTURE_DIR"' EXIT

mkdir -p "$FIXTURE_DIR/repo/scripts" "$FIXTURE_DIR/bin"
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
printf 'E: manifest (line=2)\n'
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
chmod +x "$FIXTURE_DIR/bin/java" "$FIXTURE_DIR/bin/aapt" "$FIXTURE_DIR/repo/gradlew"

export RELEASE_TEST_AAPT_LOG="$FIXTURE_DIR/aapt.log"
export RELEASE_TEST_GRADLE_LOG="$FIXTURE_DIR/gradle.log"
export WHITENOISE_PRODUCTION_KEYSTORE_PATH="$FIXTURE_DIR/release.p12"
export WHITENOISE_PRODUCTION_KEY_ALIAS="release"
export WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD="password"
export WHITENOISE_PRODUCTION_KEY_PASSWORD="password"

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

: > "$RELEASE_TEST_GRADLE_LOG"
rm -f "$selected_apk"
{
  cd "$FIXTURE_DIR/repo"
  PATH="$FIXTURE_DIR/bin:$PATH" ./scripts/release.sh --abi universal
} >/dev/null
if grep -Fq -- '-I' "$RELEASE_TEST_GRADLE_LOG"; then
  echo 'error: default release build unexpectedly received a Gradle init script' >&2
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
