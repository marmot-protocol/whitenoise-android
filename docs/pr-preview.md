# PR Preview APKs

By default, each internal PR preview request publishes one ARM64 APK, `dev.ipf.whitenoise.android.preview`, the persistent Preview White Noise installation. Every PR uses version code `2000000000`, so builds can replace one another while Android retains the preview app's data and Keystore entries.

Set `include_isolated=true` when dispatching `android-pr-apk.yml` to also get a signed, side-by-side preview under `dev.ipf.whitenoise.android.preview.pr<PR>`. The trusted publisher signs both candidates and adds both download links to the PR description.

The preview uses the `preview` environment and `play` distribution. Preview builds therefore omit Zapstore self-update behavior, production telemetry credentials, and production/staging package identities. The launcher label and purple preview icon distinguish them; Settings/About includes the PR number and head SHA in the version name.

For a reviewed candidate, follow the [pinned dispatch command](ci-request-policy.md#request-preview-apks-when-needed).

## Security boundary

`android-pr-apk.yml` runs PR-controlled Gradle code without repository secrets and uploads unsigned, one-day candidate artifacts. It also uploads a separate one-day optimizer-diagnostic artifact for each built channel containing the checksummed R8 mapping and provenance bound to the same head and unsigned APK digest; the privileged publisher never downloads or processes those diagnostics. `android-pr-preview-publish.yml` runs only the copy stored on the default branch after that workflow succeeds. It confirms the PR is still open at the same internal-repository head SHA, validates checksums, provenance, ZIP shape, a 67,108,848-byte raw APK ceiling, a 512 MiB expanded-size ceiling, package ID, version, PR/SHA version name, and ARM64 contents, then signs the APK as untrusted data without executing it. It verifies the dedicated certificate fingerprint before publishing the APKs to the [Android PR previews release](https://github.com/marmot-protocol/whitenoise-android/releases/tag/android-pr-previews) and updating the PR description with download links.

Preview builds start on manual dispatch. Closing or merging a PR removes its APKs from the release, so its download links expire. To restore previews for a reopened PR, dispatch a build for its current head. The publisher accepts replacements at capacity and reserves 50 of GitHub’s 1,000 asset slots for concurrent uploads.

Configure a dedicated preview-only PKCS#12 key through these Actions secrets:

- `PR_PREVIEW_KEYSTORE_BASE64`
- `PR_PREVIEW_KEYSTORE_PASSWORD`
- `PR_PREVIEW_KEY_ALIAS`
- `PR_PREVIEW_KEY_PASSWORD`
- `PR_PREVIEW_CERT_SHA256` (certificate SHA-256 fingerprint, either raw hex or
  the `SHA256: AA:BB:...` format printed by `keytool`)

Never reuse a production, staging, or developer debug key. Back up the preview key securely: replacement requires uninstalling the persistent preview and loses its local data.

The persistent package deliberately lets code from one internal PR replace code from another while retaining the preview app's accounts, history, and Android Keystore entries. Treat it as an untrusted test environment: never use production identities or sensitive conversations there. Use a disposable device or emulator for authentication, storage, migration, or destructive changes.

## Physical-device acceptance

CI cannot prove Android installer behavior. Before relying on the persistent lane, use the ordinary downloaded-APK installer on a physical phone:

1. Install preview A, create an account and group, and send a message.
2. Install preview B over A and confirm the account, Android Keystore identity, group, history, permissions, and current PR/SHA remain correct.
3. Install A over B and confirm the equal-version update is accepted.
4. Confirm production and staging are untouched.
