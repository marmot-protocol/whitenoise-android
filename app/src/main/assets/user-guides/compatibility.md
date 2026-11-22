# Compatibility

This page lists the release and source baselines these guides were checked against. Other guides link here to keep version details in one place. For mobile apps, go to [Download](/download). For Linux packages, see [Install and set up White Noise](/docs/get-started/install/linux).

## Platform baselines

### iPhone

Requires iOS 18 or newer. Get it from the App Store; see [Download](/download).

- App Store release: version 2026.11.16, released November 18, 2026. The App Store listing does not show a build number. The newest 2026.11.16 tag is build 52, `v2026.11.16-build.52`.
- Reviewed source: version 2026.11.16, build 52, commit `c2ee45e980f71f175abba1948022163a9bc11cf1`, checked on November 17, 2026. After the build 52 tag it adds one fix for scrolling in pinned conversations and test changes; none of these change the guides.
- Current-build images: the message composer, custom emoji, stickers, dictation controls, Appearance, Data Usage and diagnostics were captured from an unchanged local Debug-Production build of that source. They show a synthetic profile and a fictional one-member group, not personal conversations.
- Earlier images: other selected screens use version 2026.11.10 (48), source `9e8e60372b2cbedfd77062342b9bc6c3821b55a7`. The older **Download Storage** image is labelled as historical: that screen was removed from the reviewed source. Each capture retains its own source and build in the image inventory.
- Capture environment: iPhone 17, iOS 27.2 Simulator, on November 17, 2026. These are not App Store binaries or physical-iPhone captures. Hold-to-dictate requires iOS 26 or later and a supported speech model; the app itself still supports iOS 18.
- Missing checks: live speech transcription, physical-device and App Store build testing, cross-device message delivery, push notifications, recovery and moderation. Notifications and diagnostic sharing were off during capture. The synthetic profile and one-member group remain in the local production app; cleanup has not been verified. The pre-existing staging app was left untouched. Remaining image coverage is tracked in [issue14](https://github.com/marmot-protocol/whitenoise-web/issues/14).

### Android

Requires Android 11 or newer. Get it from Zapstore (recommended), Google Play or GitHub; see [Download](/download).

- Release: `android-v2026.11.17`, build 27, source `045fd9be34aa05de3347a9337622c6a671a8a703`.
- Evidence: source review of the release. Screenshots from the CI preview build of [pull request 3879](https://github.com/marmot-protocol/whitenoise-android/pull/3879), source `f2e491ccc2350fcc2e5fb0a0fda0d0c70e9a2221`, Android SDK 37, synthetic test data, navigated on a device. That build predates the release by four media and picture-loading changes, chat header shortcuts, the PDF and HTML reader and a Members page fix. None of these change the screens shown.
- Missing checks: full delivery testing.

### Linux

Packages are available for x86-64, with a portable tarball for ARM64. [GitHub Releases](https://github.com/marmot-protocol/whitenoise-linux/releases) provides AppImage, Flatpak and portable tarballs. Portable tarballs require glibc 2.39 or newer.

- Latest public release: `v2026.10.6-build.1`.
- Reviewed source: `7d961e3526b534bd7283e5d409677806c5c64f2c`.
- Screenshot source: released build `acbc3bb812c4676b550b0b42451c8ec2fcc64ec8`.
- Evidence: the x86-64 portable tarball was checksum-checked, launched and navigated with SDL's dummy video driver, synthetic accounts and networking disabled. Screenshots preserve the app's rendered pixels; captions distinguish drafts from sent messages.
- Missing checks: AppImage and Flatpak installation, remote signers, full recovery, desktop notification integration and cross-client delivery. The screenshots do not establish which newer source controls are in the release.

New-chat controls differ between these baselines. The released build opens an address-and-group-name form and permits a group without another member. The reviewed source uses a contact picker, followed by **New group**, and requires a first member. The guides describe the reviewed source; screenshots are placed beside controls present in the captured release.

### Agent connector computer

The current website setup guide lists Mac and Linux. Windows support is unknown.

## What this means

- iOS and Android instructions use the reviewed sources above. iOS screenshots span two separately identified local builds. If a control differs, compare your app version with its platform baseline and update the app when a newer release is available.
- Linux instructions use the reviewed source above, which may include controls newer than the public release. The Linux app uses a password-encrypted vault and has its own backup procedure; phone recovery instructions do not apply to that vault.
- Device screenshots use synthetic test data and show how to move through the app. Message delivery between real devices was outside their scope. Additional app illustrations use the Android project’s checked-in UI reference renders at the reviewed source. They illustrate controls without claiming that those features ran on a device.
- The guides were checked against these sources and remain untested end to end on each release.
- Mixed app versions and mixed clients remain untested together as a whole.
- Linked devices are still in development. A second installation does not sync your history.
- Agent connectors run on your Mac or Linux computer, separately from the phone app.

For the pinned MDK source, Rust toolchain and focused test result, see [Build quickstart](/docs/build/quickstart).

## Find your version

On iPhone, open Settings, then General, then iPhone Storage, and select White Noise to see the version you have installed. On Android, open **Settings > Help & support > Help > About & licenses**. **Version** and **Build** show what is installed. Compare them with the release names on GitHub Releases, such as `android-v2026.11.17`. Android's system app details for White Noise also show the version.

On Linux, open **Settings > About** and compare the version with the Linux GitHub release tag.

## Report a problem

When you report a problem, include your platform and OS version, plus your app version and build. Remove other people's details from any screenshots first. Then follow [Report a problem](/docs/troubleshooting/report-problem).
