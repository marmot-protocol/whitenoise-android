# Media return request-count investigation (#2909)

## Pixel reproduction

- Device: stock Pixel 9 Pro XL, Android 17. Installed personal Dev app: `2026.9.30-dev-debug`, MDK diagnostic revision `946e0547`. The exact Android Git revision embedded in this installed app was not available from the diagnostic line. No app install, uninstall, data clear, or media send was performed on this package.
- With the user's authorization, inspect only an existing sent video and an existing incoming video in the chat they selected. Keep screenshots private. The incoming video initially displayed Download; its prior successful download state was unknown.
- Temporary, opt-in `WNPerf` logging was enabled. The first incoming-video tap entered `attachment_acquisition_start` and MDK `downloading`, then `verifying_plaintext`, `ready`, and `attachment_plaintext_ready` at 3,896 ms. This was a first observed acquisition, not a repeat transfer.
- After leaving and reopening the chat, the acquired video's poster and Play control were visible and it opened with one tap. No second Android `attachment_fetch` phase appeared during that return. The existing sent video also opened from the media gallery without a visible loading indicator.
- A controlled `am force-stop` followed immediately by `am start` changed the Dev app PID from `9234` to `15669`; the incoming video's poster and Play control were present on chat re-entry. The performance session ends on process restart, so these post-restart observations are visual, not a logged HTTP count.

The observed path did not reproduce the reported repeat-loading icon. An absent Android phase log is not packet-level proof of zero HTTP, and the first-frame screenshot was taken after navigation settled. No personal media content, contact identity, URL, or attachment key belongs in public evidence.

## Counted synthetic boundary

In the separate MDK test change `15e9b0031c63e3761a285ab864be9e61686d7bcb`, the loopback `attachment_worker_downloads_without_engine_group_or_screen_and_retains_through_restart` fixture counts ciphertext response bytes. For a received source with automatic or explicit demand, and a synthetically seeded sent-direction source, each run serves one body. Local retained reads after restart serve zero additional bodies. The focused test passed after rebasing onto MDK `5d7c5763`. The sent-direction fixture is not a completed outgoing upload/send and does not establish outgoing-success retention.

The Android `ConversationVideoRotationAndroidTest.locallyMaterializedVideoReturnsWithoutSourceFetch` fixture pre-materializes a hash-verified local video, leaves and re-enters the production video bubble, opens it on one tap each time, and asserts its source supplier was called once in total. It passed on the Pixel from source `bf84ecfeb1fb49e78f0994294a97391e7435b0ff` in an isolated `dev.ipf.whitenoise.android.preview.pr2909` package. The arm64 app APK SHA-256 was `8e38f9784d98f59cbb2d79593c151eecf3adf7e063e61b91fa52ad6246993f03`; the test APK was `7e30a9188d92affd7c0228818d0bf19e0e404ae35e9e1b57101707023506793a`. The supplier count is not an HTTP count.

## Decision

Keep #2909 open. Existing retained incoming media worked in this observed case. The successful outgoing upload path still publishes Android's encrypted disk cache asynchronously and can skip or fail that write; the counted MDK test does not cover a real outgoing upload. A canonical MDK retention write tied to the confirmed source, with completion/failure surfaced independently of message delivery and bounded large-file handling, remains the substantive fix. The broader image, album, account, expiry, corruption, storage-pressure, and offline matrix remains unverified.

## Reproduce

1. Run `cargo test --locked -p marmot-app --lib attachment_worker_downloads_without_engine_group_or_screen_and_retains_through_restart -- --nocapture` in the MDK worktree.
2. Build the Android isolated preview app and test APK with `PR_NUMBER=2909 PR_PREVIEW_CHANNEL=isolated -Pwhitenoise.previewE2eTestBuild=true`.
3. Install those two APKs using `adb -s <serial> install -r`, then run `adb -s <serial> shell am instrument -w -e class 'dev.ipf.whitenoise.android.ui.conversation.media.ConversationVideoRotationAndroidTest#locallyMaterializedVideoReturnsWithoutSourceFetch' dev.ipf.whitenoise.android.preview.pr2909.test/androidx.test.runner.AndroidJUnitRunner`. Do not use AGP's default connected-test teardown on a personal device.
