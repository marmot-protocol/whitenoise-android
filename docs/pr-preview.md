# PR Preview APKs

Each internal pull request publishes two ARM64 APKs:

- `dev.ipf.whitenoise.android.preview` is the default, persistent Preview White Noise installation. Every PR uses version code `2000000000`, so builds can replace one another while Android retains the preview app's data and Keystore entries.
- `dev.ipf.whitenoise.android.preview.pr<PR>` is isolated for storage, authentication, migration, destructive, or side-by-side testing.

Both use the `preview` environment and `play` distribution. Preview builds therefore omit Zapstore self-update behavior, production telemetry credentials, and production/staging package identities. The launcher label and purple preview icon distinguish them; Settings/About includes the PR number and head SHA in the version name.

## Security boundary

`android-pr-apk.yml` runs PR-controlled Gradle code without repository secrets and uploads unsigned, one-day candidate artifacts. `android-pr-preview-publish.yml` runs only the copy stored on the default branch after that workflow succeeds. It confirms the PR is still open at the same internal-repository head SHA, validates checksums, provenance, ZIP shape, size, package ID, version, PR/SHA version name, and ARM64 contents, then signs the APK as untrusted data without executing it. It verifies the dedicated certificate fingerprint before publishing to Blossom and updating the PR comment.

Configure a dedicated preview-only PKCS#12 key through these Actions secrets:

- `PR_PREVIEW_KEYSTORE_BASE64`
- `PR_PREVIEW_KEYSTORE_PASSWORD`
- `PR_PREVIEW_KEY_ALIAS`
- `PR_PREVIEW_KEY_PASSWORD`
- `PR_PREVIEW_CERT_SHA256` (certificate SHA-256 fingerprint)
- `BLOSSOM_UPLOAD_NSEC`

Never reuse a production, staging, or developer debug key. Back up the preview key securely: replacement requires uninstalling the persistent preview and loses its local data.

## Physical-device acceptance

CI cannot prove Android installer behavior. Before relying on the persistent lane, use the ordinary downloaded-APK installer on a physical phone:

1. Install preview A, create an account and group, and send a message.
2. Install preview B over A and confirm the account, Android Keystore identity, group, history, permissions, and current PR/SHA remain correct.
3. Install A over B and confirm the equal-version update is accepted.
4. Confirm production and staging are untouched, and confirm an isolated preview installs alongside all three.

