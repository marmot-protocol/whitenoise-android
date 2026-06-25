# White Noise Android — build recipes
# Run `just` with no args to list recipes.

set shell := ["bash", "-uc"]

# Use Android Studio's bundled JBR so plain `java` works.
export JAVA_HOME := env_var_or_default("JAVA_HOME", "/Applications/Android Studio.app/Contents/jbr/Contents/Home")

# Variant packages. Dev, production, and staging are separate application IDs so
# all three installs can coexist on one device. Activity stays in the Kotlin
# namespace, so launch commands need the full activity FQN, not the leading-dot
# shorthand.
DEV_PKG := "dev.ipf.whitenoise.android.dev"
PRODUCTION_PKG := "dev.ipf.whitenoise.android"
STAGING_PKG := "dev.ipf.whitenoise.android.staging"
MAIN_ACTIVITY := "dev.ipf.darkmatter.MainActivity"
PRODUCTION_APK_DIR := "app/build/outputs/apk/production/release"
STAGING_APK_DIR := "app/build/outputs/apk/staging/release"

_default:
    @just --list

# Build dev debug APK (per-ABI splits + universal).
debug:
    ./gradlew :app:assembleDevDebug
    @ls -lh app/build/outputs/apk/dev/debug/*.apk

# Install the dev debug APK on the connected device.
install-debug:
    ./gradlew :app:installDevDebug

# Launch the installed dev variant. Uses the activity FQN because
# applicationId no longer matches the namespace (dev.ipf.darkmatter), so
# `pkg/.MainActivity` shorthand doesn't resolve.
launch-debug:
    adb shell am start -n {{DEV_PKG}}/{{MAIN_ACTIVITY}}

# Build, install, and launch the dev debug variant. One-shot dev workflow.
run-debug: install-debug launch-debug

# Uninstall the dev package.
uninstall-debug:
    adb uninstall {{DEV_PKG}}

# Run unit tests.
test:
    ./gradlew :app:testDevDebugUnitTest

# Lint Kotlin sources with ktlint (read-only; fails on violations). Also runs
# as part of `./gradlew check`.
lint:
    ./gradlew ktlintCheck

# Auto-format Kotlin sources with ktlint (rewrites files in place).
format:
    ./gradlew ktlintFormat

# Build signed production and staging release APKs (per-ABI splits + universal).
# Requires signing creds in local.properties. Rebuilds the marmot bindings +
# native libs.
release:
    ./scripts/release.sh --flavor all

# Build the production arm64-v8a APK immediately using the current
# checked-in Marmot bindings + native libs, then print the release folder as
# the final line so it is easy to open in Finder.
apk: apk-production

# Build the production arm64-v8a APK.
apk-production:
    ./scripts/release.sh --skip-bindings --flavor production --abi arm64-v8a
    @printf '%s\n' "$PWD/{{PRODUCTION_APK_DIR}}"

# Build the staging arm64-v8a APK.
apk-staging:
    ./scripts/release.sh --skip-bindings --flavor staging --abi arm64-v8a
    @printf '%s\n' "$PWD/{{STAGING_APK_DIR}}"

# Same as `release` but skip the (slow) Rust rebuild — use whatever .so's
# are already checked in.
release-fast:
    ./scripts/release.sh --skip-bindings --flavor all

# Install the arm64-v8a production APK on the connected device. Useful for
# sanity-checking a release build on your own phone.
install-production:
    ./scripts/release.sh --skip-bindings --flavor production --abi arm64-v8a
    adb install -r {{PRODUCTION_APK_DIR}}/whitenoise-production-v8a-release-$(date +%F).apk

# Launch the installed production variant.
launch-production:
    adb shell am start -n {{PRODUCTION_PKG}}/{{MAIN_ACTIVITY}}

# Build, install, and launch the production variant.
run-production: install-production launch-production

# Uninstall the production variant.
uninstall-production:
    adb uninstall {{PRODUCTION_PKG}}

# Install the arm64-v8a staging APK on the connected device.
install-staging:
    ./scripts/release.sh --skip-bindings --flavor staging --abi arm64-v8a
    adb install -r {{STAGING_APK_DIR}}/whitenoise-staging-v8a-release-$(date +%F).apk

# Launch the installed staging variant.
launch-staging:
    adb shell am start -n {{STAGING_PKG}}/{{MAIN_ACTIVITY}}

# Build, install, and launch the staging variant.
run-staging: install-staging launch-staging

# Uninstall the staging variant.
uninstall-staging:
    adb uninstall {{STAGING_PKG}}

# Back-compat aliases for the default production release.
install-release: install-production
launch-release: launch-production
run-release: run-production
uninstall-release: uninstall-production

# Generate a new release keystore. ONE-TIME: refuses to overwrite an
# existing keystore. Writes credentials to local.properties.
keystore-gen:
    ./scripts/keystore-gen.sh

# Print SHA-256 fingerprint of the release keystore (useful for Play App
# Signing, FCM, etc.). Use `just keystore-fingerprint staging` for staging.
keystore-fingerprint flavor="production":
    @bash -c ' \
        set -euo pipefail; \
        flavor="$1"; \
        local_props="local.properties"; \
        prop_value() { \
            local key value; \
            for key in "$@"; do \
                if [[ -n "${!key:-}" ]]; then \
                    printf "%s\n" "${!key}"; \
                    return 0; \
                fi; \
                value=$(grep "^${key}=" "$local_props" 2>/dev/null | head -1 | cut -d= -f2- || true); \
                if [[ -n "$value" ]]; then \
                    printf "%s\n" "$value"; \
                    return 0; \
                fi; \
            done; \
            return 1; \
        }; \
        require_prop() { \
            local label="$1"; \
            shift; \
            prop_value "$@" || { \
                echo "error: missing $label signing value for $flavor" >&2; \
                exit 1; \
            }; \
        }; \
        case "$flavor" in \
            production) \
                path=$(require_prop KEYSTORE_PATH WHITENOISE_PRODUCTION_KEYSTORE_PATH WHITENOISE_KEYSTORE_PATH DARKMATTER_KEYSTORE_PATH); \
                pw=$(require_prop KEYSTORE_PASSWORD WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD WHITENOISE_KEYSTORE_PASSWORD DARKMATTER_KEYSTORE_PASSWORD); \
                key_alias=$(require_prop KEY_ALIAS WHITENOISE_PRODUCTION_KEY_ALIAS WHITENOISE_KEY_ALIAS DARKMATTER_KEY_ALIAS); \
                ;; \
            staging) \
                path=$(require_prop KEYSTORE_PATH WHITENOISE_STAGING_KEYSTORE_PATH); \
                pw=$(require_prop KEYSTORE_PASSWORD WHITENOISE_STAGING_KEYSTORE_PASSWORD); \
                key_alias=$(require_prop KEY_ALIAS WHITENOISE_STAGING_KEY_ALIAS); \
                ;; \
            *) \
                echo "error: unsupported flavor: $flavor (expected production or staging)" >&2; \
                exit 1; \
                ;; \
        esac; \
        keytool -list -v -keystore "$path" -alias "$key_alias" -storepass "$pw" | grep -E "SHA(1|256):" \
    ' bash "{{flavor}}"

# Clean all build outputs.
clean:
    ./gradlew clean
