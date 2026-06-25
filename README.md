# White Noise Android

Android client for White Noise, backed by the Dark Matter Marmot bindings.

## Project Shape

The app is a Kotlin/Jetpack Compose Android app backed by the Dark Matter Marmot bindings. Dark Matter owns protocol data and stores it in SQLite. The Android app should render that data, manage Android platform behavior, and keep UI lifecycle state.

The Android app should not become a second database for Dark Matter data. If a screen is slow because a query or projection is expensive, prefer improving the Dark Matter API or SQLite-backed projection over adding an Android cache.

## Common Commands

```bash
just test                  # unit tests
just lint                  # ktlint check (read-only)
just format                # ktlint format (rewrites in place)
just debug                 # build dev debug APKs
just install-debug         # install dev debug on connected device
just run-debug             # install + launch dev debug
just apk-production        # signed production arm64-v8a APK (fast)
just apk-staging           # signed staging arm64-v8a APK (fast)
just release               # signed production + staging APKs, rebuilds Marmot bindings
just release-fast          # signed production + staging APKs, reuses checked-in bindings
just install-production    # install production arm64-v8a APK on connected device
just install-staging       # install staging arm64-v8a APK on connected device
just keystore-gen          # one-time release keystore generation
just keystore-fingerprint  # print SHA-256 of release keystore
```

Direct Gradle equivalents:

```bash
./gradlew :app:testDevDebugUnitTest
./gradlew :app:assembleDevDebug
./gradlew :app:installDevDebug
```

The supported build matrix is intentionally small:

- dev debug: `dev.ipf.whitenoise.android.dev`
- staging release: `dev.ipf.whitenoise.android.staging`
- production release: `dev.ipf.whitenoise.android`

Dev registers `whitenoise-dev://` deep links, staging registers `whitenoise-staging://`, and production registers `whitenoise://`. Gradle disables production/staging debug builds and the dev release build so each bundle ID maps to exactly one intended notification environment.

## Continuous Integration

Every pull request to `master` (and every push to `master`) runs the
`.github/workflows/android-ci.yml` validation workflow. It fails the build on
Kotlin compile errors, unit-test failures, Compose screenshot regressions
(Roborazzi — see [Screenshot tests](#screenshot-tests)), ktlint violations, or
Android lint regressions. The workflow uses the dev debug variant only
and requires no signing secrets or `google-services.json`.

Pushes to `master` also run `.github/workflows/android-instrumented.yml`, a
separate emulator workflow for `:app:connectedDevDebugAndroidTest`. It is
master-only, not a pull-request gate, because emulator boot is slower and more
flake-prone than the fast JVM checks. It uploads Android test reports when
available and retains them for seven days.

Pushes to `master` also run `.github/workflows/android-staging-apk.yml`, which
builds and uploads a signed `arm64-v8a` staging release APK for internal
installation. That workflow is also manually runnable from GitHub Actions. It
uses the checked-in Marmot bindings and requires these repository Actions
secrets:

- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`
- `WHITENOISE_STAGING_OTLP_ENDPOINT`
- `WHITENOISE_STAGING_OTLP_AUTH_TOKEN`
- `WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_STAGING_TELEMETRY_TENANT`
- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_STAGING_PUSH_RELAY_HINT`

Run the same fast checks locally before pushing:

```bash
./gradlew :app:compileDevDebugKotlin   # Kotlin compile
./gradlew :app:testDevDebugUnitTest    # unit tests          (also: just test)
./gradlew :app:verifyRoborazziDevDebug # screenshot tests    (compares baselines)
./gradlew :app:ktlintCheck             # style/format check  (also: just lint)
./gradlew :app:lintDevDebug            # Android lint
```

Use `just format` (`./gradlew :app:ktlintFormat`) to auto-fix ktlint findings
before re-running the check.

Dependency freshness is automated via [Dependabot](.github/dependabot.yml),
which opens grouped pull requests **weekly** for GitHub Actions and the
hand-pinned gradle dependencies in `gradle/libs.versions.toml`. Each ecosystem
caps its own open Dependabot PRs at five (the limit is per ecosystem, not
repo-wide), and GitHub Actions bumps are grouped into a single PR. Those PRs run
through the same `android-ci.yml` validation as any other change, so a bump
cannot merge without a green build. Compose-BOM- and Firebase-BOM-managed
artifacts (and the intentional `material3` alpha pin) are excluded so Dependabot
does not fight the BOMs.

## Screenshot tests

A small [Roborazzi](https://github.com/takahirom/roborazzi) pilot guards Compose
UI against visual regressions that compile cleanly and pass unit tests but ship
a broken layout (issue #551). The tests render real composables on the JVM via
Robolectric — no emulator — so they add no device-test runtime. The pilot
covers two surfaces:

- `DarkMatterThemeScreenshotTest` — a representative swatch through
  `DarkMatterTheme` in light, dark, and AMOLED, guarding the theme color roles
  (e.g. the AMOLED true-black audit, #446/#495).
- `OnboardingContentScreenshotTest` — the onboarding entry screen, light theme.

Baseline PNGs live under `app/src/test/snapshots/` and are committed to git. CI
runs `:app:verifyRoborazziDevDebug`; on a mismatch the build fails and the
diff/compare images are uploaded as workflow artifacts (`android-ci-reports`).

**Re-baseline after an intentional UI change.** When you deliberately change a
covered composable, regenerate the baselines and commit the updated PNGs:

```bash
./gradlew :app:recordRoborazziDevDebug   # rewrite baselines under app/src/test/snapshots/
git add app/src/test/snapshots/        # review the image diff, then commit
```

Always eyeball the regenerated PNGs before committing — that review is the point
of the check. If `verifyRoborazziDevDebug` fails on a change you did *not* intend,
that is a caught regression: fix the UI, don't re-record.

## Release Builds

Production release builds use signing values from `local.properties` or matching environment variables:

- `WHITENOISE_PRODUCTION_KEYSTORE_PATH`
- `WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD`
- `WHITENOISE_PRODUCTION_KEY_ALIAS`
- `WHITENOISE_PRODUCTION_KEY_PASSWORD`

Production also accepts global signing values as fallbacks:

- `WHITENOISE_KEYSTORE_PATH`
- `WHITENOISE_KEYSTORE_PASSWORD`
- `WHITENOISE_KEY_ALIAS`
- `WHITENOISE_KEY_PASSWORD`

Legacy `DARKMATTER_KEYSTORE_*` names are still accepted as fallbacks.

Staging release builds use staging-only signing values:

- `WHITENOISE_STAGING_KEYSTORE_PATH`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`

Release packaging fails if signing is unconfigured. To override for a local smoke build, set:

- `WHITENOISE_ALLOW_UNSIGNED_RELEASE=true`

Runtime configuration is also read from `local.properties` or environment variables so endpoints and tokens stay out of Git.

**Dev telemetry / audit:**

- `WHITENOISE_DEV_OTLP_ENDPOINT`
- `WHITENOISE_DEV_OTLP_AUTH_TOKEN`
- `WHITENOISE_DEV_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_DEV_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_DEV_TELEMETRY_TENANT` (defaults to `whitenoise-rs-android-dev`)

Dev also accepts `OTLP_TOKEN_WHITENOISE_ANDROID_DEV` as an OTLP auth-token alias.

**Dev push (MIP-05):**

- `WHITENOISE_DEV_PUSH_SERVER_PUBKEY_HEX` — dev push-server identity pubkey
- `WHITENOISE_DEV_PUSH_RELAY_HINT`

**Production telemetry / audit:**

- `WHITENOISE_PRODUCTION_OTLP_ENDPOINT`
- `WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN`
- `WHITENOISE_PRODUCTION_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_PRODUCTION_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_PRODUCTION_TELEMETRY_TENANT` (defaults to `whitenoise-rs-android`)

Production also accepts `OTLP_TOKEN_WHITENOISE_ANDROID` as an OTLP auth-token alias. Legacy global `DARKMATTER_*` runtime names and `OTLP_TOKEN_DARKMATTER_ANDROID` are accepted as production fallbacks.

**Production push (MIP-05):**

- `WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX` — push-server identity pubkey
- `WHITENOISE_PRODUCTION_PUSH_RELAY_HINT` (defaults to `wss://relay.eu.whitenoise.chat`)

**Staging telemetry / audit:**

- `WHITENOISE_STAGING_OTLP_ENDPOINT`
- `WHITENOISE_STAGING_OTLP_AUTH_TOKEN`
- `WHITENOISE_STAGING_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_STAGING_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_STAGING_TELEMETRY_TENANT` (defaults to `whitenoise-rs-android-staging`)

Staging also accepts `OTLP_TOKEN_WHITENOISE_ANDROID_STAGING` as an OTLP auth-token alias.

**Staging push (MIP-05):**

- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX` — staging push-server identity pubkey
- `WHITENOISE_STAGING_PUSH_RELAY_HINT`

Unset push values mean the runtime treats push as unconfigured rather than registering against a default server.

`app/google-services.json` is optional. When present, the Firebase plugin is applied and FCM works; when absent, the app falls back to local notifications. For the supported variants it must include Android clients for `dev.ipf.whitenoise.android.dev`, `dev.ipf.whitenoise.android.staging`, and `dev.ipf.whitenoise.android`.

### Building a release

```bash
just apk-production
```

Builds the signed production `arm64-v8a` APK using the checked-in Marmot bindings and native libraries. The output filename is `whitenoise-production-v8a-release-YYYY-MM-DD.apk`. The release folder is printed as the final line for Finder.

```bash
just apk-staging
```

Builds the signed staging `arm64-v8a` APK. The output filename is `whitenoise-staging-v8a-release-YYYY-MM-DD.apk`.

```bash
just release
```

Builds all signed production and staging APKs (per-ABI + universal) and rebuilds the Marmot bindings. Assumes a sibling checkout of the `darkmatter` Rust workspace at `../darkmatter`; override with `DARKMATTER_MARMOT_DIR`.

```bash
just release-fast
```

Same as `just release` but reuses the checked-in Marmot bindings and native libraries.

## Device Testing

For local device checks, prefer:

```bash
just install-debug
```

Avoid `connectedDevDebugAndroidTest` on Jeff's Pixel unless he asks for it, because it can uninstall the app and wipe local state.

## Performance Guidance

Keep Compose work cheap. Do not call slow binding, database, or network paths from composition or from the main thread.

Use Dark Matter streams and SQLite-backed projections as the fast path. If Android needs a shape that is expensive to assemble, add or improve the Dark Matter projection rather than storing a duplicate copy in the Android app.

Close native subscriptions when screens or services stop using them.
