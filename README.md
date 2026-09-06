# White Noise Android

Android client for White Noise, backed by the Marmot bindings.

## Project Shape

The app is a Kotlin/Jetpack Compose Android app backed by the Marmot bindings. The native protocol layer owns protocol data and stores it in SQLite. The Android app should render that data, manage Android platform behavior, and keep UI lifecycle state.

The Android app should not become a second database for White Noise protocol data. If a screen is slow because a query or projection is expensive, prefer improving the native API or SQLite-backed projection over adding an Android cache.

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
just release               # signed production + staging APKs with pinned MarmotKit
just release-fast          # backward-compatible alias for just release
just install-production    # install production arm64-v8a APK on connected device
just install-staging       # install staging arm64-v8a APK on connected device
just keystore-gen          # one-time release keystore generation
just keystore-fingerprint         # print SHA-256 of production release keystore
just keystore-fingerprint staging # print SHA-256 of staging release keystore
```

Direct Gradle equivalents (Zapstore debug is the default local dev flavor):

```bash
./gradlew :app:testDevZapstoreDebugUnitTest :app:testDevPlayDebugUnitTest
./gradlew :app:assembleDevZapstoreDebug
./gradlew :app:installDevZapstoreDebug
```

Release-like startup and group-flow measurements, Baseline Profile generation,
profile packaging verification, and Compose compiler report commands are
documented in [docs/performance.md](docs/performance.md). Run them on a dedicated
physical device with an authenticated dev test account. The state-preserving
runner replaces the dev package in place with the same debug key and avoids the
connected-test teardown that otherwise removes local fixture data.

The app also has a `distribution` flavor dimension, orthogonal to the
environment above: **`zapstore`** enables verified direct-APK self-updates (the
Zapstore manifest permissions and the installer implementation in the `zapstore`
source set); **`play`** omits that installer machinery entirely and shows no
in-app update UI at all — the distributing store (e.g. Google Play) owns
updates, and Play policy forbids sending users off-store to update. Every build combines one
environment with one distribution and a build type — e.g.
`assembleDevZapstoreDebug`, `assembleProductionPlayRelease`.

The supported build matrix is intentionally small:

- dev debug: `dev.ipf.whitenoise.android.dev`
- staging release: `dev.ipf.whitenoise.android.staging`
- production release: `dev.ipf.whitenoise.android`

Dev registers `whitenoise-dev://` deep links, staging registers `whitenoise-staging://`, and production registers `whitenoise://`. Gradle disables production/staging debug builds and the dev release build so each bundle ID maps to exactly one intended notification environment.

## Continuous Integration

Every pull request to `master` (and every push to `master`) runs the
`.github/workflows/android-ci.yml` validation workflow. It fails the build on
Kotlin compile errors, unit-test failures, Compose screenshot regressions
(Roborazzi — see [Screenshot tests](#screenshot-tests)), ktlint violations,
new detekt findings, or Android lint regressions. Existing detekt findings are
frozen in `config/detekt/detekt-baseline.xml`. The workflow runs both the
`zapstore` and `play` dev debug variants and requires no signing secrets or
`google-services.json`.

Two security workflows run separately from the main Gradle validation so their
permissions and results stay explicit:

- `.github/workflows/codeql.yml` compiles the credential-free dev Zapstore and
  Play debug variants and scans their Java and Kotlin with CodeQL's extended
  security query suite.
  It runs for pull requests, `master` pushes, a weekly full scan, and manual
  dispatch. Only its SARIF upload receives `security-events: write`.
- `.github/workflows/dependency-submission.yml` supplies GitHub's dependency
  graph with the resolved Gradle graph after each `master` update so repository
  vulnerability alerts can evaluate direct and transitive Gradle dependencies.
  Only this submission workflow receives `contents: write`.

Both security workflows pin every third-party action to a full commit SHA,
cancel superseded work, and have bounded job timeouts. CodeQL's Gradle setup
reuses the normal dependency and build caches; the dependency-submission action
manages its own Gradle execution. Dependency submission runs only from trusted
`master` pushes because it is the sole workflow with `contents: write`.

Pushes to `master` also run `.github/workflows/android-instrumented.yml`, a
separate emulator workflow for `:app:connectedDevZapstoreDebugAndroidTest`
(Zapstore debug, exercising direct-distribution config). Master runs the full
suite; pull requests that change MarmotKit preparation run the focused packaged
native-library smoke test. It uploads Android test reports when available and
retains them for seven days.

Pushes to `master` also run `.github/workflows/android-staging-apk.yml`, which
builds and uploads a signed `arm64-v8a` staging release APK for internal
installation. That workflow is also manually runnable from GitHub Actions. It
uses the checksum-verified MarmotKit artifact pinned by the repository and requires these repository Actions
secrets:

- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`
- `WHITENOISE_OTLP_ENDPOINT`
- `WHITENOISE_STAGING_OTLP_AUTH_TOKEN`
- `WHITENOISE_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_PUSH_RELAY_HINT`

Run the same fast checks locally before pushing:

```bash
./gradlew :app:compileDevZapstoreDebugKotlin :app:compileDevPlayDebugKotlin
./gradlew :app:testDevZapstoreDebugUnitTest :app:testDevPlayDebugUnitTest  # also: just test
./gradlew :app:verifyRoborazziDevZapstoreDebug :app:verifyRoborazziDevPlayDebug  # screenshot tests
./gradlew :app:ktlintCheck             # style/format check  (also: just lint)
./gradlew :app:detekt                  # code-smell/complexity check
./gradlew :app:lintDevZapstoreDebug :app:lintDevPlayDebug   # Android lint
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

- `WhiteNoiseThemeScreenshotTest` — a representative swatch through
  `WhiteNoiseTheme` in light, dark, and AMOLED, guarding the theme color roles
  (e.g. the AMOLED true-black audit, #446/#495).
- `OnboardingContentScreenshotTest` — the onboarding entry screen, light theme.

Baseline PNGs live under `app/src/test/snapshots/` and are committed to git. CI
runs `:app:verifyRoborazziDevZapstoreDebug` and `:app:verifyRoborazziDevPlayDebug`; on a mismatch the build fails and the
diff/compare images are uploaded as workflow artifacts (`android-ci-reports`).

**Re-baseline after an intentional UI change.** When you deliberately change a
covered composable, regenerate the baselines and commit the updated PNGs:

```bash
./gradlew :app:recordRoborazziDevZapstoreDebug   # rewrite baselines under app/src/test/snapshots/
# or :app:recordRoborazziDevPlayDebug
git add app/src/test/snapshots/        # review the image diff, then commit
```

Always eyeball the regenerated PNGs before committing — that review is the point
of the check. If `verifyRoborazziDevZapstoreDebug` or `verifyRoborazziDevPlayDebug` fails on a change you did *not* intend,
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

Staging release builds use staging-only signing values:

- `WHITENOISE_STAGING_KEYSTORE_PATH`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`

Release packaging fails if signing is unconfigured. To override for an unsigned,
non-publishable local smoke or reproducibility build, set:

- `WHITENOISE_ALLOW_UNSIGNED_RELEASE=true`

Runtime configuration is also read from `local.properties` or environment variables so endpoints and tokens stay out of Git.

**Shared runtime values:**

- `WHITENOISE_OTLP_ENDPOINT` — shared by dev, staging, and production.
- `WHITENOISE_AUDIT_LOG_ENDPOINT` — shared by staging and production.
- `WHITENOISE_AUDIT_LOG_AUTH_TOKEN` — shared by staging and production, separate from OTLP auth.
- `WHITENOISE_PUSH_RELAY_HINT` — shared by staging and production (production defaults to `wss://relay.eu.whitenoise.chat`).

**Flavor-specific OTLP tokens:**

- `WHITENOISE_DEV_OTLP_AUTH_TOKEN`
- `WHITENOISE_STAGING_OTLP_AUTH_TOKEN`
- `WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN`

The token selects the telemetry tenant. There is no shared token fallback.
Legacy token aliases `OTLP_TOKEN_WHITENOISE_ANDROID_DEV`,
`OTLP_TOKEN_WHITENOISE_ANDROID_STAGING`, and `OTLP_TOKEN_WHITENOISE_ANDROID`
remain accepted for their respective flavors.

No tenant secret is read. MarmotKit requires a nonempty tenant resource attribute,
so Android supplies fixed compatibility values (`whitenoise-android`,
`whitenoise-android-staging`, and `whitenoise-android-dev`). HTTP authentication
and tenant routing use the bearer token. The separate deployment environment
attribute remains `production`, `staging`, or `development`, respectively.

**Push identities (MIP-05):**

- `WHITENOISE_STAGING_PUSH_SERVER_PUBKEY_HEX` — staging push-server identity.
- `WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX` — production push-server identity
  (`WHITENOISE_PUSH_SERVER_PUBKEY_HEX` remains a production fallback).

**Dev audit and push:**

Dev does not inherit shared Goggles credentials or staging/production push
identities. Explicit dev configuration remains available:

- `WHITENOISE_DEV_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_DEV_AUDIT_LOG_AUTH_TOKEN`
- `WHITENOISE_DEV_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_DEV_PUSH_RELAY_HINT`

Without these values dev uploads and push registration are unconfigured. Local
audit recording is separate. Dev OTLP needs its own token as well as the shared
endpoint. Preview keeps telemetry, audit, and push configuration empty.

`app/google-services.json` is optional for dev, preview, and explicitly unsigned
reproducibility builds. Signed production packaging requires the file and an
Android client for `dev.ipf.whitenoise.android`; `scripts/release.sh` also
verifies that the final APK contains `google_app_id` and
`gcm_defaultSenderId`. When present for other supported variants, the file
should also include clients for `dev.ipf.whitenoise.android.dev` and
`dev.ipf.whitenoise.android.staging`.

### Building a release

```bash
just apk-production
```

Builds the signed production `arm64-v8a` APK using the immutable MarmotKit
artifact pinned in `app/src/main/marmotkit/MARMOT_VERSION`. Gradle downloads it
once, verifies its checksum, provenance, layout, and native architectures, and
reuses the content-addressed cache afterward. The output filename is
`whitenoise-production-v8a-release-YYYY-MM-DD-<sha>.apk`. The release folder is
printed as the final line for Finder.

```bash
just apk-staging
```

Builds the signed staging `arm64-v8a` APK. The output filename is
`whitenoise-staging-v8a-release-YYYY-MM-DD-<sha>.apk`.

```bash
just release
```

Builds all signed production and staging APKs (per-ABI + universal) with the
same pinned MarmotKit artifact. No local MDK checkout or NDK binding rebuild is
required. After one successful preparation, Gradle `--offline` builds reuse the
verified artifact from the Gradle user-home cache. Set
`WHITENOISE_MARMOTKIT_CACHE_DIR` only when an explicit shared cache location is
needed.
For an air-gapped first build, provide the exact pinned ZIP with
`-Pwhitenoise.marmotkit.artifactFile=/path/to/marmotkit-android-0.9.12.zip`
or `WHITENOISE_MARMOTKIT_ARTIFACT_FILE`; the same checksum and provenance checks
still apply.
Python 3 is required for artifact preparation. Gradle selects `python3` on
Unix-like hosts and `python` on Windows; set `WHITENOISE_PYTHON` to override the
executable name or path.

Maintainers updating the pinned release should follow
[docs/updating-marmotkit.md](docs/updating-marmotkit.md). The lock, generated
API signature, and consumer/native checks must move together.

```bash
just release-fast
```

Backward-compatible alias for `just release`; both use the same cached,
checksum-verified artifact path.

### Reproducible unsigned release APK (#1261)

To verify that two isolated builds of the same commit produce identical **unsigned**
production Zapstore `arm64-v8a` release APK bytes (no signing secrets, no
`google-services.json`), see [docs/reproducible-apk-builds.md](docs/reproducible-apk-builds.md)
and run `./scripts/repro-verify.sh`. Tag/manual CI runs publish matching
artifacts plus the recorded toolchain from
[`.github/workflows/android-repro-verify.yml`](.github/workflows/android-repro-verify.yml).

## Device Testing

For local device checks, prefer:

```bash
just install-debug
```

Avoid `connectedDevZapstoreDebugAndroidTest` on Jeff's Pixel unless he asks for it, because it can uninstall the app and wipe local state.

## Performance Guidance

Keep Compose work cheap. Do not call slow binding, database, or network paths from composition or from the main thread.

Use White Noise streams and SQLite-backed projections as the fast path. If Android needs a shape that is expensive to assemble, add or improve the native projection rather than storing a duplicate copy in the Android app.

Close native subscriptions when screens or services stop using them.
