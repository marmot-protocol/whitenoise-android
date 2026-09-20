# Local 0.10.4 implementation checkpoint

Updated 2026-09-20. **Unpublished draft; do not merge or call release-qualified.**

Worktree: `/Users/user/Workspace/marmot-protocol/wn-marmotkit-0.10.4`.
Branch: `build/marmotkit-0.10.4`.
Stack base: `cf4d1a147855829182221297cf68d5093f153f2a` (the local #2691 rebase,
not its final reviewed head). Preparation commit: `14698e077`.
Implementation checkpoint: `47c604377`.
Last live check: #2691 remains OPEN at `8cb10a9d21773695a3e17648f1148f25a4c970bd`;
master is `2226fbc2654e38c92bfc90b8477059cf259bb94f`. The newer #2691 fixes are not
silently included in this older preparation base.
The user explicitly asked to consume the bindings now and keep the work locally;
do not push/open a PR until #2691 is merged and this branch has been rebased.
Do not change the concurrently active `wn-complete-marmotkit-adoption` checkout.

## Implemented draft surface

- The active lock pins the verified 0.10.4 archive and matching native/Kotlin/API
  signature cohort. The candidate lock in this directory remains the same input.
- `MarmotClient` constructs `HOST_MANAGED` with the existing client label,
  advancing cursor, relay restrictions, and platform secret-store defaults.
- `NativeAttachmentPermissions` consumes `beginAttachmentPermissionUpdate` and
  `setAttachmentAutomaticPermission`. Host event revisions invalidate stale
  evaluations; native generations are single-use and never reminted on false.
  Revocation runs off the UI dispatcher, before evaluating the per-account matrix.
  Reachable accounts are revoked before any grants; errors fail closed.
- Network, settings, account refresh, and user pause/resume invalidate permission.
  Signed-out accounts are excluded. Runtime reconstruction starts denied. The
  old containment flag is enabled only in this host-managed runtime; numeric
  quota/reserve/transfer policy is retained and user pauses remain a separate gate.
- Automatic misses use `requestAutomaticAttachment`; no automatic fallback to
  `downloadMedia`. Native and eligible legacy local bytes are checked first.
  New native results are not persisted into a second Android encrypted cache.
- Native source IDs are recovered from authoritative history when absent, including
  restored workers. Stable work/cancellation identity excludes the optional source
  hint. Presentation probes carry the native source ID.
- New terminal states fail without a host HTTP retry. New mode/signed-out errors
  are terminal. Explicit user recovery can create a new native cycle; subsequent
  WorkManager attempts observe existing demand rather than resetting its budget.
- Subscription lifetime encloses admission as well as waiting. Initial snapshots
  are not mistaken for post-admission outcomes. Observer cancellation closes only
  the subscription; user Cancel separately invokes native control.
- Native local materialization runs on IO with bounded chunks and closes leases
  on partial-batch failure or dispatcher cancellation. Old leases must be removed
  successfully before marking a process lease directory prepared.
- `DurableLocalSends` consumes `sendTextWithClientToken`,
  `replyToMessageWithClientToken`, `uploadMediaWithClientToken`, and
  `localSendStatus`. Existing optimistic IDs are the opaque per-logical-send tokens.
  Admission stays Pending, never falsely reports transport completion, and status
  recovery avoids re-admitting retained ownership. Upload-only preparation does
  not claim a token association and checks status before another blob operation.
- `DraftSend` consumes `sendMessageDraftWithClientToken` for matching revision-bound
  media drafts. **Non-draft/mismatched media keeps the supported legacy publication
  fallback.** This is not a claim that every send surface is now token-aware.
- Timeline equality includes `clientToken`; projection reconciliation uses exact
  tokens and does not fall back to text/time matching for token-bearing records.
- Chat preview retention is carried through the app mapping. Visible rows hide
  only expired selected-message content, rechecking the pinned finite deadline
  while started and on resume. Drafts/invitations, unknown duration, zero duration,
  and missing/overflowed expiry preserve their separate semantics.
- Manual checklist MED-019/MED-020 and new MSG-017/MSG-018 cover the cutover.
  Issue #2493 is assigned to mubarakcoded and verified In Progress in Project 7;
  adoption does not close its broader recovery/timing acceptance criteria.

## Verification ledger

- Verified artifact preparation, archive checksum, provenance, JNI layouts/exports,
  signature counts, and API staging: passed.
- Initial 0.10.4 compatibility compile: passed (Zapstore debug).
- Focused Zapstore run before the final selection guard: 53 tests across 14 suites,
  zero failures/errors. Final-source Play run: 54 tests across 14 suites, zero
  failures/errors/skips, including the attachment-only draft selection regression.
  Includes permission fencing, admission recovery, exact-token controller
  reconciliation, native observation/lease cleanup, stable work identity, policy
  ordering, record equality, source-size ceilings, drafts, and preview expiry.
- Manual guide validator: passed (264 active IDs); validator tests: 33 passed.
- Static analysis after fixes: zero findings. The focused Gradle run completed
  successfully in 2m 19s; this was not the full unit/CI matrix.
- Expiry screenshot recorded and visually inspected:
  `app/src/test/snapshots/chat_row_expired_selected_preview.png`. Message content
  is hidden while the row, title, timestamp, and unread count remain intact.
- Both app flavors and Android-test sources compile on the final source. Ktlint,
  detekt, and focused Play screenshot verification pass. The fast gate's initial
  online run was stopped while lint waited on Google Maven version metadata;
  its complete Gradle task set was restarted with `--offline` and **passed**:
  `BUILD SUCCESSFUL in 17m 12s`, 96 tasks (22 executed, 74 up-to-date), including
  Android lint. Remote dependency freshness was not checked in this offline run.
- Diff whitespace check: passed.
- No APK install, emulator migration, byte-count experiment, or personal-device
  operation has been performed. No app data was cleared or uninstalled.

## Required before publication

For reproducibility, the final-source local Gradle invocation used JDK 21 at
`/Users/user/.gradle/jdks/eclipse_adoptium-21-x86_64-os_x.2/jdk-21.0.7+6/Contents/Home`
and `ANDROID_HOME=/Users/user/Library/Android/sdk`, with `--offline --no-daemon
--console=plain --stacktrace`. Its tasks were the fast gate's complete selected set:

```text
:app:compileDevZapstoreDebugKotlin :app:compileDevPlayDebugKotlin
:app:ktlintCheck :app:detekt :app:lintDevZapstoreDebug
:app:compileDevPlayDebugUnitTestKotlin :app:ktlintTestSourceSetCheck
:app:compileDevZapstoreDebugAndroidTestKotlin :app:ktlintAndroidTestSourceSetCheck
:app:stageMarmotKitApiSignature :app:verifyRoborazziDevPlayDebug
```

Supply each of these as a separate quoted `--tests` filter:

```text
*NativeAttachmentPermissionsTest
*DurableLocalSendsTest
*ChatPreviewRetentionTest
*AttachmentPolicyStartupOrderingTest
*AttachmentPlaintextResolverTest
*NativeAttachmentTransfersTest
*NativeAttachmentObservationTest
*NativeAttachmentLocalAccessTest
*AttachmentDownloadWorkerTest
*TimelineRecordRenderEqualityTest
*StateSourceSizeCeilingTest
*DraftSendTest
*ConversationSendRetryIntegrationTest.callerTokenReconcilesIdenticalOptimisticMessagesWithoutHeuristics
*ChatRowsPortScreenshotTest.expiredSelectedMessagePreview
```

This selected run is not the full test suite. After rebase, extend it with the
affected parent-PR regressions below rather than assuming these filters cover them.

1. Fetch master after #2691 merges. Rebase/transplant **only the commits after the
   stack base** onto the merged master; do not replay the parent #2691 commits after
   a squash merge. Resolve conflicts against #2691's final reviewed fixes, not its
   old published head. Keep the media pager order and warm-cache behavior intact.
2. Reconcile all #2691 cache-first, cancellation, history, source identity, lease
   cleanup, and presentation tests. Legacy tests whose fake only implements
   `downloadMedia` need a contract-faithful native-owned transfer/local-asset fake
   (notably `MediaDownloadIntegrationFixture`, used by `MediaDownloadHostRegressionTest`
   and `MediaImageBubbleLatencyTest`);
   do not restore the production fallback just to keep an obsolete fake green.
3. Re-run focused adoption tests and real controller send/media regressions, then
   the fast completion gate against the final rebased tree. Do not run two Gradle
   builds against this worktree concurrently. Inspect the new expiry screenshot
   and track its PNG before using `--visual changed`.
4. Qualify fresh and in-place emulator upgrades (schemas 87–89), process death,
   signed-out/imported accounts, offline local reads, user cancel/admission races,
   network permission ordering, native quota/reserve pressure, and transfer bytes.
   Specifically prove native-only and legacy-only cache hits transfer zero media
   bytes across reopen, restart, and package replacement.
5. Test text/reply/draft acceptance races and identical rapid messages through real
   bindings. Check draft edits during upload/acceptance, rejection/collision, epoch
   invalidation, interrupted uploads, status recovery, and explicit convergence.
   A rejected token requires deliberate resubmission; do not silently mint a new
   token or re-upload after an ambiguous owned outcome. Audit the legacy non-draft
   media fallback separately rather than representing it as token-correlated.
6. Keep the full-history, progress/reason-specific UI, and broader #2493 acceptance
   boundaries from the parent adoption plan explicit. A new call site is not proof
   of a complete product flow. Resolve every actionable review thread and the
   required docstring coverage, and verify tracked visual baselines before ready.

Use the Android CLI and applicable Android/testing/completion skills for device
work. Use disposable emulators for migration testing. Never uninstall or clear
data on a physical device; back up before an in-place install. Downgrade after
schemas 87–89 is unsupported, including on the Pixel dev recovery path.

The historical 1 TB report remains unproven attribution. This draft changes
ownership and bounds future automatic acquisition; it is not evidence of the
production incident's byte source or a guarantee of indefinite cache retention.
