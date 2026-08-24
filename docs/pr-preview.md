# PR test APKs

Each internal pull request publishes one ARM64 APK:

- `dev.ipf.whitenoise.android.staging` is the regular White Noise test installation. A PR APK replaces that app in place and retains its accounts, history, and Android Keystore entries. PRs inherit the normal app version code; stale PRs with an older code fail closed rather than forcing a downgrade.

PR APKs use the `preview` runtime environment and `play` distribution while retaining the regular staging package identity. They omit Zapstore self-update behavior and production telemetry credentials. The launcher label and purple preview icon identify the temporary PR code; Settings/About includes the PR number and head SHA in the version name.

## Security boundary

`android-pr-apk.yml` runs PR-controlled Gradle code without repository secrets and uploads one unsigned, one-day candidate artifact. `android-pr-preview-publish.yml` runs only the copy stored on the default branch after that workflow succeeds. It confirms the PR is still open at the same internal-repository head SHA, validates checksums, provenance, ZIP shape, a 67,108,848-byte raw APK ceiling, a 512 MiB expanded-size ceiling, the regular staging package ID, version, PR/SHA version name, and ARM64 contents, then signs the APK as untrusted data without executing it. It verifies the regular staging certificate fingerprint before publishing to Blossom and updating the PR description with the install link.

Every push to an internal PR triggers `android-pr-apk.yml` (with in-progress runs cancelled on newer commits). When that build succeeds, `android-pr-preview-publish.yml` signs, uploads, and refreshes the description block as soon as validation finishes.

The publisher reuses the regular staging signing identity through the existing Actions secrets:

- `WHITENOISE_STAGING_KEYSTORE_BASE64`
- `WHITENOISE_STAGING_KEYSTORE_PASSWORD`
- `WHITENOISE_STAGING_KEY_ALIAS`
- `WHITENOISE_STAGING_KEY_PASSWORD`
- `BLOSSOM_UPLOAD_NSEC`

Never expose the staging key to PR-controlled code. The privileged publisher only signs already-built APK bytes and never executes them.

The regular package deliberately lets PR code replace the installed staging app while retaining accounts, history, and Android Keystore entries. Treat every PR APK as untrusted test code and do not use production identities or sensitive conversations while it is installed. There is no side-by-side or isolated PR lane.

## Physical-device acceptance

CI cannot prove Android installer behavior. Before relying on this lane:

1. Install a regular staging APK, create an account and group, and send a message.
2. Install PR APK A over it and confirm the account, Android Keystore identity, group, history, permissions, and current PR/SHA remain correct.
3. Install PR APK B over A and confirm the same state is retained.
4. Install a current regular staging APK over the PR build and confirm the normal app identity and state return without uninstalling.
