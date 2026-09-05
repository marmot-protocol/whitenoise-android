# Android release pipeline

White Noise ships one Android application through three distribution channels:

- Zapstore distributes the signed `arm64-v8a` direct APK and its NIP-82 metadata.
- GitHub Releases distributes that exact same APK for direct download.
- Google Play receives an Android App Bundle and signs generated device APKs
  with the same app-signing key used by the direct APK.

No publication step is automatic on a merge. The production workflow is manual,
all publication inputs default to off, and the signing and publication jobs use
separate GitHub environments so they can have separate required reviewers.

## Non-negotiable identity contract

The public values in `config/android-release.properties` are release policy:

- Application and new Zapstore listing ID: `dev.ipf.whitenoise.android`.
- Direct APK and Play app-signing certificate SHA-256: `e3ca27b35a14f3e67d0fa68c270c9a330539ac89930d89e6e5c05beba8f03b7a`.
- Play upload certificate SHA-256: `b0e30845fedc49d162198ca577c9ae653e2a66ba06a6ddca2cfccdd180193086`.

The upload key signs only the AAB sent to Play. Google Play signs the generated
device APKs with the app-signing key. Direct APKs must continue to be signed
locally with that app-signing key. `scripts/prepare-production-release.sh`
rejects swapped or unexpected certificates.

All channels share one monotonically increasing `versionCode`. A destination
store reliably takes over an existing install only when it offers a higher
version code. Never create independent Zapstore, GitHub, and Play version
sequences.

The legacy Zapstore app ID `org.parres.whitenoise` is a different Android app.
It cannot update into this application and is deliberately not referenced by
the new self-updater or release metadata.

## Version and release notes

For each release:

1. Increment `versionCode` in `app/build.gradle.kts`; never reuse a code that
   has been uploaded to any Play track.
2. Set `versionName` to the release calendar version (`YYYY.M.D`).
3. Update deterministic version strings in
   `SettingsScreenScreenshotTest.kt`.
4. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` and point
   `zapstore.yaml` at that file.
5. Run `just verify-release-metadata`.

The current release is `2026.9.4`, version code `12`. Code 11 was already used
by the initial Play internal-test draft and must not be reused.

## Store listing and screenshots

Canonical English listing metadata lives under
`fastlane/metadata/android/en-US/`. ZSP understands this layout, and the same
files can later be submitted to Play without maintaining a second description.
The description is intentionally consistent with the public privacy policy at
<https://www.whitenoise.chat/privacy>.

Phone screenshots are generated from committed, deterministic Roborazzi app
renders. They contain synthetic fixture content, not developer or user data.
Run:

```bash
just store-assets
just verify-release-metadata
```

The generator requires ImageMagick 7. It writes:

- a 512x512 32-bit PNG icon;
- a 1024x500 opaque feature graphic;
- six opaque 1080x1920 phone screenshots.

Review every generated image visually before committing it. When the selected
source screen changes, first regenerate and review its Roborazzi baseline under
`app/src/test/snapshots/`, then regenerate the store assets. ZSP uploads the
icon and screenshots to Blossom during publication. The release bundle also
contains `store-assets-<version>.zip` for Play Console and archival use.

## Local release rehearsal

Production Firebase configuration and both signing identities must be present
in ignored `local.properties` or the documented environment variables. Then:

```bash
just production-release 2026.9.4
```

The command requires a clean worktree and performs these steps once:

1. Validates version, listing metadata, screenshots, and pinned ZSP checksums.
2. Builds the production Zapstore `arm64-v8a` APK.
3. Builds the four-ABI Play App Bundle with the separate upload key.
4. Verifies package, version, ABI set, APK signature, and AAB signature.
5. Writes versioned artifacts, R8 mapping, release notes, asset archive,
   SHA-256 checksums, and `release-manifest.json` to
   `build/production-release/`.

`--allow-dirty` and `--allow-incomplete-runtime` exist only for local rehearsal
while editing or before the ingest tokens have been provisioned. The manifest
records both conditions. Never publish an artifact with `worktreeDirty: true`
or `productionRuntimeConfigurationComplete: false`.

The production OTLP and audit-log tokens are intentionally stored as GitHub
Actions secrets and cannot be read back into a developer checkout. Prefer the
protected CI workflow for publishable builds. If a publishable local build is
ever necessary, inject short-lived values through the process environment; do
not copy the tokens into a committed file. The telemetry tenant defaults to
`whitenoise-android`, and the production push relay defaults to
`wss://relay.eu.whitenoise.chat`, matching the Gradle configuration.

## Notifications and telemetry are separate systems

There are three independent pieces of production configuration:

| Path | Android configuration | Server-side configuration | Purpose |
| --- | --- | --- | --- |
| Firebase client | `app/google-services.json` | Firebase Cloud Messaging API enabled | Gives the production package its Firebase app ID and sender ID so it can obtain an FCM token. |
| White Noise push | `WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX` and relay hint | The matching Transponder private key and a Firebase service account | Registers an encrypted notification subscription and sends wake-ups through FCM without exposing message content to Firebase. |
| Opt-in telemetry | OTLP and audit-log endpoints, separate ingest tokens, and tenant | OTLP collector and Goggles audit-log API | Sends diagnostic metrics and explicitly enabled audit data; it does not deliver notifications. |

The Google services file is public app configuration, not an APK signing key or
an FCM server credential. The Firebase service-account JSON belongs only on the
push server. The Android release check verifies that the Google services file
contains the production package and that the generated Firebase resource IDs
are packaged in both release artifacts.

An empty SHA-certificate table in Firebase does not prevent FCM token delivery.
SHA fingerprints become relevant when adding products such as Google sign-in,
phone authentication, or Dynamic Links. Add both Play app-signing and direct
APK fingerprints before enabling a Firebase product that requires them.

## GitHub Actions setup

The manual **Android Production Release** workflow needs three protected GitHub
environments. Configure a required reviewer for each before adding secrets.

### `android-release-signing`

Secrets:

- `ANDROID_GOOGLE_SERVICES_JSON_BASE64`
- `WHITENOISE_PRODUCTION_KEYSTORE_BASE64`
- `WHITENOISE_PRODUCTION_KEYSTORE_PASSWORD`
- `WHITENOISE_PRODUCTION_KEY_ALIAS`
- `WHITENOISE_PRODUCTION_KEY_PASSWORD`
- `WHITENOISE_PLAY_UPLOAD_KEYSTORE_BASE64`
- `WHITENOISE_PLAY_UPLOAD_KEYSTORE_PASSWORD`
- `WHITENOISE_PLAY_UPLOAD_KEY_ALIAS`
- `WHITENOISE_PLAY_UPLOAD_KEY_PASSWORD`
- `WHITENOISE_PRODUCTION_OTLP_AUTH_TOKEN`
- `WHITENOISE_PRODUCTION_AUDIT_LOG_AUTH_TOKEN`

Variables:

- `WHITENOISE_PRODUCTION_OTLP_ENDPOINT`
- `WHITENOISE_PRODUCTION_AUDIT_LOG_ENDPOINT`
- `WHITENOISE_PRODUCTION_TELEMETRY_TENANT`
- `WHITENOISE_PRODUCTION_PUSH_SERVER_PUBKEY_HEX`
- `WHITENOISE_PRODUCTION_PUSH_RELAY_HINT`

The app-signing keystore is the most sensitive Android release secret. Limit
environment access to this workflow and keep an offline recovery copy.

### `zapstore-production`

Secrets:

- `ZAPSTORE_SIGN_WITH`: preferably the complete scoped NIP-46 `bunker://` URI
  for publisher identity
  `75d737c3472471029c44876b330d2284288a42779b591a2ed4daa1c6c07efaf7`.
- `ZAPSTORE_BUNKER_CLIENT_KEY`: a stable, randomly generated 64-character hex
  client key used only to authenticate ZSP to that bunker. It is required for
  bunker signing and ignored when `ZAPSTORE_SIGN_WITH` is an `nsec`.

Variable:

- `ZAPSTORE_BUNKER_CLIENT_PUBKEY`: the corresponding public client identity,
  recorded so the remote signer can verify or pre-authorize the GitHub runner.

The workflow installs the exact ZSP release and verifies its upstream SHA-256
before running it. ZSP stores the NIP-46 client key on disk; the workflow
recreates that file on every disposable runner so a one-use bunker connection
secret remains associated with the same approved client identity. Do not reuse
an unrelated Blossom upload key without first proving it resolves to the
expected Zapstore publisher identity.

### `google-play-internal`

Secret:

- `GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`: a dedicated service account added to Play
  Console with only the permissions needed to manage testing-track releases.

The Play job is limited to the `internal` track. It does not publish to open
testing or production.

## Running a release

1. Merge the reviewed version, notes, metadata, screenshots, and pipeline
   changes to `master` with all required CI checks green.
2. Open **Actions → Android Production Release → Run workflow** on `master`.
3. Enter the exact `versionName`.
4. Leave both publication toggles off for the first run. Creating the GitHub
   draft is safe and does not expose the release publicly.
5. Download the workflow artifact and inspect `release-manifest.json`, checksums,
   screenshots, release notes, APK, and AAB.
6. Install the direct APK on an appropriate test device and exercise sign-in,
   messages, media, push notifications, and opt-in telemetry.
7. Test the cross-store path on Android 14 or newer: install the direct APK,
   then release a strictly higher code to Play internal testing and accept the
   system handoff prompt if shown.
8. For publication, run the same exact `master` commit with the desired toggles
   enabled. Approve the waiting environment jobs only after the prepared bundle
   has been reviewed.
9. Publish the GitHub draft only when its APK checksum matches the manifest and
   the Zapstore artifact. Promote Play beyond internal testing only as a
   separate, deliberate Play Console decision.

The workflow refuses Zapstore or Play publication from any branch other than
`master`. Third-party Actions are pinned to immutable commit SHAs.

## Legacy Zapstore removal

Removal of `org.parres.whitenoise` is intentionally not automated. ZSP 0.4.17
does not provide a listing-removal command, and deletion is irreversible for
discovery while still doing nothing to migrate installed application data.

First publish and verify `dev.ipf.whitenoise.android`, announce the replacement
on the old listing if the Zapstore interface permits it, and allow a migration
period. Only then use the old publisher identity to remove the legacy listing,
after separately confirming the exact deletion events and targets.
