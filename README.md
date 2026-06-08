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
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Release Builds

Release builds use signing values from `local.properties` or the matching environment variables:

- `DARKMATTER_KEYSTORE_PATH`
- `DARKMATTER_KEYSTORE_PASSWORD`
- `DARKMATTER_KEY_ALIAS`
- `DARKMATTER_KEY_PASSWORD`

Use:

```bash
just apk
```

This builds the signed `arm64-v8a` release APK only, using the checked-in Marmot
bindings and native libraries. The output filename is
`darkmatter-v8a-release-YYYY-MM-DD.apk`.

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
