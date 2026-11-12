#!/usr/bin/env bash
# Generate a new release keystore for White Noise Android and write its
# credentials to local.properties.
#
# DESTRUCTIVE: if a keystore already exists at the target path, this script
# refuses to overwrite it. Once you ship an APK signed with a keystore, you
# CANNOT change keystores without forcing every user to uninstall first.

set -euo pipefail

# The keystore and local.properties hold release signing secrets, so every file
# this script creates is readable by its owner only.
umask 077

KEYSTORE_PATH_DEFAULT="$HOME/.android/keystores/whitenoise-android-release.p12"
KEYSTORE_PATH="${1:-$KEYSTORE_PATH_DEFAULT}"

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOCAL_PROPS="$REPO_DIR/local.properties"

if [[ -e "$KEYSTORE_PATH" || -L "$KEYSTORE_PATH" ]]; then
  echo "error: keystore already exists at $KEYSTORE_PATH" >&2
  echo "Refusing to overwrite. Delete it manually if you really mean to regenerate." >&2
  exit 1
fi

if [[ -L "$LOCAL_PROPS" ]]; then
  echo "error: $LOCAL_PROPS is a symlink; refusing to write signing credentials through it." >&2
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

KEYSTORE_DIR="$(dirname "$KEYSTORE_PATH")"
mkdir -p "$KEYSTORE_DIR"

# Stage both files beside their destinations so each final step is a
# same-filesystem link or rename of an owner-only file.
STAGING_DIR="$(mktemp -d "$KEYSTORE_DIR/.whitenoise-keystore.XXXXXX")"
STAGED_KEYSTORE="$STAGING_DIR/release.p12"
PROPS_TMP=""
COMPLETED=""
cleanup() {
  # A keystore whose password never reached local.properties is unusable and
  # would block every later run, so remove it if it is still this run's file.
  if [[ -z "$COMPLETED" && "$KEYSTORE_PATH" -ef "$STAGED_KEYSTORE" ]]; then
    rm -f "$KEYSTORE_PATH"
  fi
  rm -rf "$STAGING_DIR"
  if [[ -n "$PROPS_TMP" ]]; then rm -f "$PROPS_TMP"; fi
}
trap cleanup EXIT

KEYSTORE_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)
export KEYSTORE_PASSWORD

echo "==> Generating PKCS12 keystore at $KEYSTORE_PATH"
# keytool reads the passwords from the environment so they never appear in the
# process list.
keytool -genkeypair \
  -keystore "$STAGED_KEYSTORE" \
  -storetype PKCS12 \
  -alias whitenoise \
  -keyalg RSA \
  -keysize 2048 \
  -validity 9999 \
  -storepass:env KEYSTORE_PASSWORD \
  -keypass:env KEYSTORE_PASSWORD \
  -dname "CN=White Noise Android Release, OU=Engineering, O=IPF, C=US"
chmod 600 "$STAGED_KEYSTORE"

# A hard link never replaces an existing path, so a keystore that appeared
# while keytool ran is kept.
if ! ln "$STAGED_KEYSTORE" "$KEYSTORE_PATH"; then
  echo "error: could not place keystore at $KEYSTORE_PATH" >&2
  exit 1
fi
# If a directory appeared at the destination, ln linked the keystore inside it.
# Take that link back out and stop.
if [[ ! "$KEYSTORE_PATH" -ef "$STAGED_KEYSTORE" ]]; then
  STRAY_LINK="$KEYSTORE_PATH/$(basename "$STAGED_KEYSTORE")"
  if [[ "$STRAY_LINK" -ef "$STAGED_KEYSTORE" ]]; then rm -f "$STRAY_LINK"; fi
  echo "error: $KEYSTORE_PATH is not a regular file; keystore not placed" >&2
  exit 1
fi

# Rewrite local.properties without old signing entries, keeping every other
# line, and add the new credentials. The replacement is owner-only even when
# the previous file was not.
PROPS_TMP="$(mktemp "$REPO_DIR/.local.properties.XXXXXX")"
if [[ -f "$LOCAL_PROPS" ]]; then
  # grep exits 1 when no other lines remain, which is fine; any other status is
  # a read error and stops before the existing file is replaced.
  grep -v -E "^WHITENOISE_(KEYSTORE_|KEY_)" "$LOCAL_PROPS" > "$PROPS_TMP" || [[ $? -eq 1 ]]
fi
{
  echo ""
  echo "# Android release signing (DO NOT COMMIT)"
  echo "WHITENOISE_KEYSTORE_PATH=$KEYSTORE_PATH"
  echo "WHITENOISE_KEY_ALIAS=whitenoise"
  echo "WHITENOISE_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD"
  echo "WHITENOISE_KEY_PASSWORD=$KEYSTORE_PASSWORD"
} >> "$PROPS_TMP"
chmod 600 "$PROPS_TMP"
mv -f "$PROPS_TMP" "$LOCAL_PROPS"
PROPS_TMP=""
COMPLETED=1

cat <<EOF

==> Done.

   Keystore:  $KEYSTORE_PATH
   Alias:     whitenoise
   Password:  $KEYSTORE_PASSWORD

SAVE THE PASSWORD NOW (1Password, etc.). Losing it bricks future releases.
Credentials were appended to local.properties (gitignored).
EOF
