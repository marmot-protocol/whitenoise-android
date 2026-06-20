# Dark Matter Android

Android client for Dark Matter.

## Project Shape

The app is a Kotlin/Jetpack Compose Android app backed by the Dark Matter Marmot bindings. Dark Matter owns protocol data and stores it in SQLite. The Android app should render that data, manage Android platform behavior, and keep UI lifecycle state.

The Android app should not become a second database for Dark Matter data. If a screen is slow because a query or projection is expensive, prefer improving the Dark Matter API or SQLite-backed projection over adding an Android cache.

## Common Commands

```bash
just test                  # unit tests
just lint                  # ktlint check (read-only)
just format                # ktlint format (rewrites in place)
just debug                 # build debug APKs
just install-debug         # install debug on connected device
just run-debug             # install + launch debug
just apk                   # signed arm64-v8a release APK (fast)
just release               # signed release APKs, rebuilds Marmot bindings
just release-fast          # signed release APKs, reuses checked-in bindings
just install-release       # install release arm64-v8a on connected device
just keystore-gen          # one-time release keystore generation
just keystore-fingerprint  # print SHA-256 of release keystore
```

Direct Gradle equivalents:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

The debug variant uses an `applicationIdSuffix` of `.debug` (`dev.ipf.darkmatter.debug`), so it installs alongside the release build (`dev.ipf.darkmatter`) without collision.

## Continuous Integration

Every pull request to `master` (and every push to `master`) runs the
`.github/workflows/android-ci.yml` validation workflow. It fails the build on
Kotlin compile errors, unit-test failures, ktlint violations, or Android lint
regressions. The workflow uses the debug variant only and requires no signing
secrets or `google-services.json`.

Run the same checks locally before pushing:

```bash
./gradlew :app:compileDebugKotlin   # Kotlin compile
./gradlew :app:testDebugUnitTest    # unit tests          (also: just test)
./gradlew :app:ktlintCheck          # style/format check  (also: just lint)
./gradlew :app:lintDebug            # Android lint
```

Use `just format` (`./gradlew :app:ktlintFormat`) to auto-fix ktlint findings
before re-running the check.

## Release Builds

Release builds use signing values from `local.properties` or matching environment variables:

- `DARKMATTER_KEYSTORE_PATH`
- `DARKMATTER_KEYSTORE_PASSWORD`
- `DARKMATTER_KEY_ALIAS`
- `DARKMATTER_KEY_PASSWORD`

Release packaging fails if signing is unconfigured. To override for a local smoke build, set:

- `DARKMATTER_ALLOW_UNSIGNED_RELEASE=true`

Runtime configuration is also read from `local.properties` or environment variables so endpoints and tokens stay out of Git.

**Telemetry / audit:**

- `DARKMATTER_OTLP_ENDPOINT`
- `DARKMATTER_OTLP_AUTH_TOKEN`
- `DARKMATTER_AUDIT_LOG_ENDPOINT`
- `DARKMATTER_AUDIT_LOG_AUTH_TOKEN`
- `OTLP_TOKEN_DARKMATTER_ANDROID` (fallback auth token for OTLP only; audit logs require their own token)
- `DARKMATTER_DEPLOYMENT_ENVIRONMENT` (defaults to `production`)
- `DARKMATTER_TELEMETRY_TENANT` (defaults to `darkmatter-android`)

**Push (MIP-05):**

- `DARKMATTER_PUSH_SERVER_PUBKEY_HEX` — push-server identity pubkey
- `DARKMATTER_PUSH_RELAY_HINT` (defaults to `wss://relay.eu.whitenoise.chat`)

Unset push values mean the runtime treats push as unconfigured rather than registering against a default server.

`app/google-services.json` is optional. When present, the Firebase plugin is applied and FCM works; when absent, the app falls back to local notifications.

### Building a release

```bash
just apk
```

Builds the signed `arm64-v8a` release APK using the checked-in Marmot bindings and native libraries. The output filename is `darkmatter-v8a-release-YYYY-MM-DD.apk`. The release folder is printed as the final line for Finder.

```bash
just release
```

Builds all signed APKs (per-ABI + universal) and rebuilds the Marmot bindings. Assumes a sibling checkout of the `darkmatter` Rust workspace at `../darkmatter`; override with `DARKMATTER_MARMOT_DIR`.

```bash
just release-fast
```

Same as `just release` but reuses the checked-in Marmot bindings and native libraries.

## Device Testing

For local device checks, prefer:

```bash
just install-debug
```

Avoid `connectedDebugAndroidTest` on Jeff's Pixel unless he asks for it, because it can uninstall the app and wipe local state.

## Performance Guidance

Keep Compose work cheap. Do not call slow binding, database, or network paths from composition or from the main thread.

Use Dark Matter streams and SQLite-backed projections as the fast path. If Android needs a shape that is expensive to assemble, add or improve the Dark Matter projection rather than storing a duplicate copy in the Android app.

Close native subscriptions when screens or services stop using them.
