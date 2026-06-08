# Dark Matter Android — build recipes
# Run `just` with no args to list recipes.

set shell := ["bash", "-uc"]

# Use Android Studio's bundled JBR so plain `java` works.
export JAVA_HOME := env_var_or_default("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")

# Variant packages. The debug variant gets an applicationIdSuffix so dev
# installs and release installs coexist on a device without a signing-cert
# collision. Activity stays in the base namespace, so launch commands need
# the full activity FQN, not the leading-dot shorthand.
DEBUG_PKG := "dev.ipf.darkmatter.debug"
RELEASE_PKG := "dev.ipf.darkmatter"
MAIN_ACTIVITY := "dev.ipf.darkmatter.MainActivity"
RELEASE_APK_DIR := "app/build/outputs/apk/release"

_default:
    @just --list

# Build debug APK (per-ABI splits + universal).
debug:
    ./gradlew :app:assembleDebug
    @ls -lh app/build/outputs/apk/debug/*.apk

# Install the debug APK on the connected device.
install-debug:
    ./gradlew :app:installDebug

# Launch the installed debug variant. Uses the activity FQN because
# applicationId (dev.ipf.darkmatter.debug) no longer matches the namespace
# (dev.ipf.darkmatter), so `pkg/.MainActivity` shorthand doesn't resolve.
launch-debug:
    adb shell am start -n {{DEBUG_PKG}}/{{MAIN_ACTIVITY}}

# Build, install, and launch the debug variant. One-shot dev workflow.
run-debug: install-debug launch-debug

# Uninstall only the debug variant. Release install (Dark Matter) is untouched.
uninstall-debug:
    adb uninstall {{DEBUG_PKG}}

# Run unit tests.
test:
    ./gradlew :app:testDebugUnitTest

# Build signed release APKs (per-ABI splits + universal). Requires signing
# creds in local.properties. Rebuilds the marmot bindings + native libs.
release:
    ./scripts/release.sh

# Build the production/release arm64-v8a APK immediately using the current
# checked-in Marmot bindings + native libs, then print the release folder as
# the final line so it is easy to open in Finder.
apk:
    ./scripts/release.sh --skip-bindings --abi arm64-v8a
    @printf '%s\n' "$PWD/{{RELEASE_APK_DIR}}"

# Same as `release` but skip the (slow) Rust rebuild — use whatever .so's
# are already checked in.
release-fast:
    ./scripts/release.sh --skip-bindings

# Install the arm64-v8a release APK on the connected device. Useful for
# sanity-checking a release build on your own phone.
install-release:
    ./scripts/release.sh --skip-bindings --abi arm64-v8a
    adb install -r {{RELEASE_APK_DIR}}/darkmatter-v8a-release-$(date +%F).apk

# Launch the installed release variant.
launch-release:
    adb shell am start -n {{RELEASE_PKG}}/{{MAIN_ACTIVITY}}

# Build, install, and launch the release variant. Mirrors run-debug.
run-release: install-release launch-release

# Uninstall only the release variant. Debug install (Dark Matter (dev)) is untouched.
uninstall-release:
    adb uninstall {{RELEASE_PKG}}

# Generate a new release keystore. ONE-TIME: refuses to overwrite an
# existing keystore. Writes credentials to local.properties.
keystore-gen:
    ./scripts/keystore-gen.sh

# Print SHA-256 fingerprint of the current release keystore (useful for
# Play App Signing, FCM, etc.).
keystore-fingerprint:
    @bash -c ' \
        path=$$(grep "^DARKMATTER_KEYSTORE_PATH=" local.properties | cut -d= -f2-); \
        pw=$$(grep "^DARKMATTER_KEYSTORE_PASSWORD=" local.properties | cut -d= -f2-); \
        alias=$$(grep "^DARKMATTER_KEY_ALIAS=" local.properties | cut -d= -f2-); \
        keytool -list -v -keystore "$$path" -alias "$$alias" -storepass "$$pw" | grep -E "SHA(1|256):" \
    '

# Clean all build outputs.
clean:
    ./gradlew clean
