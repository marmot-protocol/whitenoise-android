# Dark Matter Android — build recipes
# Run `just` with no args to list recipes.

set shell := ["bash", "-uc"]

# Use Android Studio's bundled JBR so plain `java` works.
export JAVA_HOME := env_var_or_default("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")

_default:
    @just --list

# Build debug APK (per-ABI splits + universal).
debug:
    ./gradlew :app:assembleDebug
    @ls -lh app/build/outputs/apk/debug/*.apk

# Install the debug APK on the connected device.
install-debug:
    ./gradlew :app:installDebug

# Run unit tests.
test:
    ./gradlew :app:testDebugUnitTest

# Build signed release APKs (per-ABI splits + universal). Requires signing
# creds in local.properties. Rebuilds the marmot bindings + native libs.
release:
    ./scripts/release.sh

# Same as `release` but skip the (slow) Rust rebuild — use whatever .so's
# are already checked in.
release-fast:
    ./scripts/release.sh --skip-bindings

# Install the arm64-v8a release APK on the connected device. Useful for
# sanity-checking a release build on your own phone.
install-release:
    ./scripts/release.sh --skip-bindings --abi arm64-v8a
    adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk

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
