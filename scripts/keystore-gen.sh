#!/usr/bin/env bash
# Generate a new release keystore for White Noise Android and write its
# credentials to local.properties.
#
# DESTRUCTIVE: if a keystore already exists at the target path, this script
# refuses to overwrite it. Once you ship an APK signed with a keystore, you
# CANNOT change keystores without forcing every user to uninstall first.

set -euo pipefail

KEYSTORE_PATH_DEFAULT="$HOME/.android/keystores/whitenoise-android-release.p12"
KEYSTORE_PATH="${1:-$KEYSTORE_PATH_DEFAULT}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_PROPS="$REPO_DIR/local.properties"

if [[ -e "$KEYSTORE_PATH" ]]; then
  echo "error: keystore already exists at $KEYSTORE_PATH" >&2
  echo "Refusing to overwrite. Delete it manually if you really mean to regenerate." >&2
  exit 1
fi

if ! command -v keytool >/dev/null 2>&1; then
  for candidate in \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "$HOME/Library/Application Support/JetBrains/Toolbox/apps/android-studio/Contents/jbr/Contents/Home"; do
    if [[ -x "$candidate/bin/keytool" ]]; then
      export JAVA_HOME="$candidate"
      export PATH="$JAVA_HOME/bin:$PATH"
      break
    fi
  done
fi
if ! command -v keytool >/dev/null 2>&1; then
  echo "error: keytool not found. Set JAVA_HOME to Android Studio's JBR." >&2
  exit 1
fi

mkdir -p "$(dirname "$KEYSTORE_PATH")"

KEYSTORE_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)

echo "==> Generating PKCS12 keystore at $KEYSTORE_PATH"
keytool -genkeypair \
  -keystore "$KEYSTORE_PATH" \
  -storetype PKCS12 \
  -alias whitenoise \
  -keyalg RSA \
  -keysize 2048 \
  -validity 9999 \
  -storepass "$KEYSTORE_PASSWORD" \
  -keypass "$KEYSTORE_PASSWORD" \
  -dname "CN=White Noise Android Release, OU=Engineering, O=IPF, C=US"

# Write/update local.properties
touch "$LOCAL_PROPS"
grep -v -E "^(WHITENOISE|DARKMATTER)_(KEYSTORE_|KEY_)" "$LOCAL_PROPS" > "$LOCAL_PROPS.tmp" || true
mv "$LOCAL_PROPS.tmp" "$LOCAL_PROPS"
{
  echo ""
  echo "# Android release signing (DO NOT COMMIT)"
  echo "WHITENOISE_KEYSTORE_PATH=$KEYSTORE_PATH"
  echo "WHITENOISE_KEY_ALIAS=whitenoise"
  echo "WHITENOISE_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "WHITENOISE_KEY_PASSWORD=$KEYSTORE_PASSWORD"
} >> "$LOCAL_PROPS"

cat <<EOF

==> Done.

   Keystore:  $KEYSTORE_PATH
   Alias:     whitenoise
   Password:  $KEYSTORE_PASSWORD

SAVE THE PASSWORD NOW (1Password, etc.). Losing it bricks future releases.
Credentials were appended to local.properties (gitignored).
EOF
