# Dark Matter Android

Android client for Dark Matter.

## Project Shape

The app is a Kotlin/Jetpack Compose Android app backed by the Dark Matter Marmot bindings. Dark Matter owns protocol data and stores it in SQLite. The Android app should render that data, manage Android platform behavior, and keep UI lifecycle state.

The Android app should not become a second database for Dark Matter data. If a screen is slow because a query or projection is expensive, prefer improving the Dark Matter API or SQLite-backed projection over adding an Android cache.

## Common Commands

```bash
just test
just debug
just install-debug
just apk
just release-fast
```

Direct Gradle equivalents:

```bash
./gradlew :app:testPlayDebugUnitTest
./gradlew :app:assemblePlayDebug
./gradlew :app:installPlayDebug
```

## Product Flavors

The app builds in two product flavors on the `distribution` dimension, so the
Firebase / FCM dependencies ship only where they're used (issue #140):

- **`play`** — Google Play build. Bundles Firebase Messaging + Play Services
  Base and (when `google-services.json` is present) applies the
  google-services plugin, so MIP-05 native push works.
- **`zapstore`** — Zapstore / no-Firebase build. Ships none of the Firebase
  SDKs; the runtime push gate reports native push unavailable and the app
  falls back to local notifications over the existing foreground-stream
  transport.

The dev/release recipes (`just debug`, `just test`, `just apk`, …) default to
the `play` flavor to preserve the previous Firebase-included behavior. Use the
`*-zapstore` recipes (e.g. `just debug-zapstore`, `just test-zapstore`) for the
no-FCM build. Firebase symbols are confined to `app/src/play/`; shared code
reaches the transport only through
`dev.ipf.darkmatter.notifications.NativePush`.

## Release Builds

Release builds use signing values from `local.properties` or the matching environment variables:

- `DARKMATTER_KEYSTORE_PATH`
- `DARKMATTER_KEYSTORE_PASSWORD`
- `DARKMATTER_KEY_ALIAS`
- `DARKMATTER_KEY_PASSWORD`

Telemetry and audit-log upload runtime configuration is also read from
`local.properties` or environment variables so endpoints and tokens stay out of
Git:

- `DARKMATTER_OTLP_ENDPOINT`
- `DARKMATTER_OTLP_AUTH_TOKEN`
- `DARKMATTER_AUDIT_LOG_ENDPOINT`
- `DARKMATTER_AUDIT_LOG_AUTH_TOKEN`
- `OTLP_TOKEN_DARKMATTER_ANDROID` (fallback auth token for both telemetry and audit logs)
- `DARKMATTER_DEPLOYMENT_ENVIRONMENT` (defaults to `android-release`)

Use:

```bash
just apk
```

This builds the signed `arm64-v8a` release APK only, using the checked-in Marmot
bindings and native libraries. The output filename is
`darkmatter-v8a-release-YYYY-MM-DD.apk`. The release folder is printed as the
final line for Finder.

Use:

```bash
just release
```

Use `just release-fast` when the checked-in Marmot bindings and native libraries are already current.

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
