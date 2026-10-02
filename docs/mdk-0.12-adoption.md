# MDK 0.12.0 Android adoption

This checklist prepares [Android PR #2936](https://github.com/marmot-protocol/whitenoise-android/pull/2936)
for the [MDK 0.12.0 release](https://github.com/marmot-protocol/mdk/pull/2147).
The release candidate inspected on 2026-10-02 was
`0c81ca3c05e07f93c4858d385b8314f1e26e1088`, sixteen commits ahead of the
PR's published `08c43b3b5bb75e136b0434279cb9b7b5ac226fbf` snapshot.
The candidate SHA is review context only: verify the eventual tags and manifest
instead of assuming that SHA will ship.

Use the upstream [0.12.0 integration guide at the inspected candidate](https://github.com/marmot-protocol/mdk/blob/0c81ca3c05e07f93c4858d385b8314f1e26e1088/docs/integration/0.12.0.md)
and [artifact adoption procedure](updating-marmotkit.md). All boxes below record
remaining release qualification; snapshot checks do not check them off.

## Compatibility prepared before publication

- PR #2936 supplies the new nullable `reactionMessageIdHex` argument in
  `ConversationWindowHandleTest` and `ConversationWindowReactionsTest`.
- The snapshot lock already includes `GroupAppComponentFfi`, `PollVoteFfi`,
  `PollVotePageFfi`, tagged sends and media reactions. Its API counts are
  337 types, 336 checksums and five helpers. The release counts still need review.
- The guide gives empty defaults to the new `inboxRelays` parameters and
  `OnboardingOptionsFfi.inboxRelays` and `MediaUploadRequestFfi.messageTags`.
  Existing Kotlin calls retain their previous behavior. Compilation must still
  confirm the final generated signatures and exception handling.
- Custom emoji rendering, per-voter poll UI, application-owned group settings,
  and choosing separate inbox relays are independent feature adoption. They do
  not require adding those features to the dependency bump. Custom emoji rendering
  is strongly recommended upstream; record the current literal fallback during
  peer interoperability checks rather than claiming complete feature parity.

## Host attachment calls to finish with the release

- [ ] In `NativeAttachmentTransfers.kt`, route ordinary interactive demand through
  `requestExplicitAttachment`. Keep automatic demand on `requestAutomaticAttachment`.
  Joining or promoting an existing transfer must preserve native retry counts,
  backoff and deadlines. Preserve subscription ownership and cancellation.
- [ ] In `AttachmentPlaintextResolver.kt`, replace the advisory active-transfer
  promotion through `downloadAttachmentAgain` with `requestExplicitAttachment`.
- [ ] Keep deliberate recovery of terminal failures separate: the new explicit
  request does not rearm cancelled, removed, exhausted or failed sources. Retain
  a deliberate retry route where the existing UI promises recovery; do not
  replace every `downloadAttachmentAgain` call mechanically.
- [ ] Exercise native transfer observation, automatic-to-interactive promotion,
  terminal retry, cancellation and local-first reads with the affected focused
  tests. Add behavior coverage for retry-preserving promotion when changing the
  adapter. Review `NativeAttachmentTransfersTest`'s source assertion naming the
  old demand method when updating that call.

These host changes depend on a newer artifact than the snapshot currently pinned.
Do not add a call absent from that binding or edit generated Kotlin to supply it.

## Publication handoff

- [ ] Confirm `marmotkit-v0.12.0` is published with
  `marmotkit-android-0.12.0.zip` and its sibling `.sha256`. An MDK source tag alone
  is insufficient. Confirm the cohort tags peel to the same release commit.
- [ ] Download the Android archive, calculate SHA-256, compare its sibling
  checksum and inspect `marmotkit-android-0.12.0/manifest.json`. Verify source SHA,
  workspace version, tag, features, API level and all bundled ABIs.
- [ ] Update every field of `app/src/main/marmotkit/MARMOT_VERSION` atomically
  from those verified values. Keep the previous API counts until preparation
  reports the actual counts, then review the signature delta before changing them.
- [ ] Complete the host attachment changes above and run the focused gate:

  ```bash
  ./scripts/test-prepare-marmotkit-artifact.sh
  ./gradlew :app:stageMarmotKitApiSignature \
    :app:compileDevZapstoreDebugKotlin :app:compileDevPlayDebugKotlin
  ./gradlew :app:testDevPlayDebugUnitTest \
    --tests '*ConversationWindowHandleTest' \
    --tests '*ConversationWindowReactionsTest' \
    --tests '*NativeAttachmentTransfersTest' \
    --tests '*AttachmentPlaintextResolverTest'
  ./gradlew :app:ktlintCheck :app:detekt :app:lintDevZapstoreDebug
  python3 scripts/check_manual_test_guide.py
  python3 -m unittest scripts/test_check_manual_test_guide.py
  git diff --check
  ```

- [ ] Refresh the PR description with the official artifact identity, checksum,
  reviewed API delta and validation. Push to the existing PR branch and inspect
  every actionable review thread, including the 80% docstring gate.
- [ ] Require exact-head hosted compile/unit/lint, packaged-native emulator smoke,
  production/staging ART verification, arm64 reproducibility and preview packaging.
  Previous snapshot green checks or startup smoke do not validate the final release.

## Consuming-app acceptance

- [ ] Run `INT-010`, including its 0.12.0 subcase, on a backed-up disposable
  populated fixture before a personal device. Account migrations 99–101 add
  attachment deferral counts, preserve explicit attachment priority and retain
  outgoing uploads. Confirm first open and two subsequent restarts preserve data.
  Downgrade is unsupported; do not swap back to the older snapshot after migration.
- [ ] Run `MED-019` and `MED-020` with an active/backing-off transfer, a terminal
  failure, an agent-sent file, a missing blob and a genuinely sent local file.
  Repeated ordinary taps must not reset backoff; deliberate recovery remains usable.
  Reopen the sender's retained file offline after restart where retention succeeded.
- [ ] Check muted ordinary messages, direct mentions and blocked-sender mentions
  through the existing notification paths. MDK now lets direct mentions through a
  durable mute; Android permission, channel, delivery-mode and foreground behavior
  still apply. Coordinate with any separate notification-policy PR.
- [ ] Run `NCH-001`, `NCH-002` and startup/history cases on a populated account:
  create a DM/group and send while catch-up is active; verify usable state and
  recovery notices rather than inferring health from cached rows.
- [ ] Record peer custom-emoji fallback and all unperformed device/interoperability
  checks. Upstream still documents the large-catch-up epoch-loss limitation in
  [MDK #2086](https://github.com/marmot-protocol/mdk/issues/2086).

Install updates in place. Never uninstall or clear physical-device app data.
For Pixel dev installs, follow the workspace migration-recovery runbook first.
