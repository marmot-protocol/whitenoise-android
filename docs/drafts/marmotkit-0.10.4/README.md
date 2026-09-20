# MarmotKit 0.10.4 — waiting Android upgrade draft

Prepared 2026-09-20 for an implementation agent. Read this before changing the
runtime pin. This is an execution assessment, not a second product backlog.

## Status and publication boundary

- Local preparation only. No upgrade PR has been opened or branch pushed.
- Worktree: `/Users/user/Workspace/marmot-protocol/wn-marmotkit-0.10.4`.
- Branch: `build/marmotkit-0.10.4`.
- Preparation base: `cf4d1a147855829182221297cf68d5093f153f2a`, the committed local
  rebase of #2691 onto master containing #2688 and #2684. It is NOT #2691's final
  reviewed or merged head. Published #2691 was `f1fc27041` during this assessment.
- The #2691 worktree has active uncommitted review fixes. None were copied or
  changed. This draft must inherit their final merged result before publication.
- The production lock remains 0.10.3. `MARMOT_VERSION.candidate` is an inert,
  independently verifiable 0.10.4 candidate, not the active Gradle input.
- User instruction: keep the next PR unpublished until the user merges #2691.
  This document does not establish a background merge watcher.

Related authoritative work: [#2686](https://github.com/marmot-protocol/whitenoise-android/issues/2686)
for uncontrolled/repeated downloads, [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045)
for native transfer progress/cancellation, [#2058](https://github.com/marmot-protocol/whitenoise-android/issues/2058)
for complete attachment history, and [#2493](https://github.com/marmot-protocol/whitenoise-android/issues/2493)
for durable-send recovery. The first three were open and In Progress in Project 7
at inspection; #2686 was assigned to mubarakcoded. Do not close them merely for a
version bump, or create duplicate issues for their existing outcomes.

## Verified release and API delta

Sources: [release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.10.4),
[upgrade guide](https://github.com/marmot-protocol/mdk/blob/fcc85edd8dbd07c8293c899ee52230f72c54c897/docs/integration/0.10.4.md),
[attachment contract](https://github.com/marmot-protocol/mdk/blob/fcc85edd8dbd07c8293c899ee52230f72c54c897/crates/marmot-uniffi/ATTACHMENT-ACCESS.md),
[local-send contract](https://github.com/marmot-protocol/mdk/blob/fcc85edd8dbd07c8293c899ee52230f72c54c897/crates/marmot-uniffi/LOCAL-SENDS.md),
and [chat-row contract](https://github.com/marmot-protocol/mdk/blob/fcc85edd8dbd07c8293c899ee52230f72c54c897/crates/marmot-uniffi/CHAT-LIST-ROWS.md).

| Input | Verified value |
| --- | --- |
| Release | `marmotkit-v0.10.4`, published 2026-09-20 14:29:02 UTC |
| Source and builder SHA | `fcc85edd8dbd07c8293c899ee52230f72c54c897` |
| Android asset | `marmotkit-android-0.10.4.zip` |
| Archive SHA-256 | `dcbb4e00c703cce2cf773c860f1f1d46b18ac49e81af5a5089902b8a31e0c16e` |
| Archive root / workspace | `marmotkit-android-0.10.4` / `0.10.4` |
| Android API / JNI | 26 / arm64-v8a, armeabi-v7a, x86, x86_64 |
| Signature counts | 320 → 326 public types; 314 → 322 checksums; 5 → 5 helper declarations |
| Database | 86 → 89: acquisition history/budgets, draft descriptor index, durable submissions |

The downloaded ZIP hash matches both its sibling checksum and GitHub's asset
digest. The embedded manifest matches the source, builder, tag, version, API and
four JNI ABIs. API counts came from the repository's signature parser, which
correctly rejected the old counts before the candidate was updated.

The full repository preparation validator subsequently passed (exit 0) using the
candidate lock, local archive and an isolated temporary cache. This includes
manifest/layout checks, ABI/ELF export checks and generated API signature checks.
Generated binding SHA-256:
`ac4c4daed9991998f3bd5fc2a6b6efd2231b7eaf0f633b39eb48c3b1e7427d12`.
No Android consumer build, unit suite, device install or database migration was
run for 0.10.4. Artifact validation is not application qualification.

To reproduce from this worktree (temporary artifacts may be cleaned by the OS):

```sh
upgrade_tmp=$(mktemp -d /private/tmp/wn-marmotkit-0104.XXXXXX)
gh release download marmotkit-v0.10.4 --repo marmot-protocol/mdk \
  --pattern 'marmotkit-android-0.10.4.zip*' --dir "$upgrade_tmp"
shasum -a 256 "$upgrade_tmp/marmotkit-android-0.10.4.zip"
python3 scripts/prepare_marmotkit_artifact.py \
  --lock docs/drafts/marmotkit-0.10.4/MARMOT_VERSION.candidate \
  --cache-root "$upgrade_tmp/validated" \
  --artifact "$upgrade_tmp/marmotkit-android-0.10.4.zip" --offline
```

Eight new runtime methods, with no old interface methods removed in the generated
Kotlin comparison:

- Attachment permission/demand: `beginAttachmentPermissionUpdate`,
  `setAttachmentAutomaticPermission`, `requestAutomaticAttachment`.
- Durable sends: `sendTextWithClientToken`, `replyToMessageWithClientToken`,
  `sendMessageDraftWithClientToken`, `uploadMediaWithClientToken`, `localSendStatus`.

Six additional public types: `AttachmentAcquisitionModeFfi`,
`AttachmentAutomaticPermissionFfi`, `AutomaticAttachmentRequestFfi`,
`LocalSendAcceptanceFfi`, `MediaUploadSubmissionFfi`, `LocalSendStatusFfi`.
Changed existing shapes must also be adopted:

- `MarmotOptions.attachmentAcquisitionMode` is optional; omission retains native
  automatic mode, NOT host-managed permission.
- `ChatListMessagePreviewFfi` requires nullable `retentionSeconds` and
  `retentionExpiresAt` constructor arguments.
- `TimelineMessageRecordFfi` requires nullable `clientToken`, including records
  produced through prepared conversation windows.
- Transfer states add `PREVIOUSLY_ACQUIRED_UNAVAILABLE`, `COMPLETED_UNRETAINED`,
  `RETRY_EXHAUSTED`. Update exhaustive mappings and terminal waiting loops.
- Errors add `AttachmentModeRequired` and `AttachmentAccountSignedOut`. Treat
  these as configuration/lifecycle failures, not corrupt-media/network retries.

At the preparation base, 24 app-source/test files construct chat message previews
and 27 construct timeline records (overlap possible). These are search counts,
not a compiler result or a final changed-file estimate. Read the generated Kotlin
comments before changing fakes; nullable does not mean optional constructor input.

## Proposed single-PR scope

Default recommendation pending the user's scope reply: one cohesive upgrade PR
with compatibility, host-managed downloads, and required preview expiry. Use
reviewable commits rather than additional PRs. Durable-send adoption is optional
upstream and can remain on the supported old APIs for faster shipment; do not
silently claim those five methods are consumed. If the user selects full send
adoption, include the send checkpoint below in the same PR and its acceptance gate.

This release removes the missing host-permission prerequisite from #2691. It does
not automatically complete #2691's native history UI, retry/remove UI, or leave
action work. Reconcile those items against the final merged PR, then explicitly
record what this PR actually covers. Agent-control/Hermes and Apple packaging
changes are not Android work.

### 1. Compatibility and safe database upgrade

Apply the verified candidate as one atomic pin only when starting implementation.
Regenerate/stage the API signature using the repository artifact preparation path;
never copy new Kotlin onto old JNI libraries or regenerate bindings from mutable
MDK master. Update affected constructor fakes and enum/error mappings deliberately.
Keep meaningful KDoc coverage and existing controller/app-state line ceilings;
new state/coordinator code belongs in same-package extension files.

Before first 0.10.4 open, preserve a consistent private pre-upgrade account backup
including database/WAL and required key material via the approved recovery process.
Migration 87 preserves existing jobs and deadlines; new budgets start at zero and
old jobs are not automatically opted in merely by migration. Migration 88 indexes
draft descriptors; migration 89 adds durable submissions. Downgrade is unsupported:
never delete/relabel migration rows or try an older APK against the upgraded store.
Use disposable fixtures to test refusal; recovery requires a consistent pre-upgrade
backup/export or a compatible newer runtime, not an isolated old database file.

### 2. One host-managed automatic-download owner

Read `MarmotClient`, `MarmotAttachmentAcquisitionPolicy`, the Android auto-download
matrix, WorkManager entry points, native transfer helpers, and notification-driven
enqueue paths. Construct with `newWithConfiguration` and `MarmotOptions` containing
`HOST_MANAGED`, preserving the client label, advancing cursor, relay safety and
platform secret-store defaults. Keep `MarmotAndroid.initialize` before construction.

Host-managed runtimes start denied for every account, including newly created or
imported identities. Use one ordered policy coordinator, not permission state per
bubble. Revoke with `beginAttachmentPermissionUpdate` in network/preference event
order, evaluate the current matrix, then apply the captured single-use generation.
On false, discard stale work; never mint a replacement generation inside the stale
callback. Re-evaluate on reconstruction, sign-in, account changes, validated network
changes, and settings changes. Never persist generations or Wi-Fi approval.

Important cutover: #2688/#2691 persist `automatic=false`. Host permission alone will
not override that durable policy. Deliberately reconcile that containment value
with the host-managed owner while preserving explicit user pause/cancel/removal and
quota/reserve/limit values. Enable only inside the deny-first host-managed runtime;
prove there is no uncontrolled startup window. Do not turn on native automatic mode
and do not equate “one allowed type” with approval for every type.

Route eligible automatic demand through `requestAutomaticAttachment`. Repeated
foreground or restored-worker demand must be idempotent (`newlyQueued=false` is
normal). WorkManager may wake the runtime and observe, not run its own HTTP download
or another network-retry loop. Migrate/cancel existing work across package replacement
without changing stable cancellation identity. Use exact source-event identity and
original attachment index from authoritative history/timeline; never guess display
ID equals source ID. Recover missing source IDs before admission.

Read native local bytes, then eligible authenticated legacy local bytes, before
requesting any transfer. Retain usable old cache entries; no wipe/refetch to seed a
new store, no direct native SQL writes, no new durable Android protocol ledger.
No public verified-byte import method was added among the eight new calls. With
HostManaged there is no automatic discovery backfill, so the host can withhold
demand for legacy-cache hits. Prove this with an in-place upgrade byte counter.
Native history cannot reconstruct acquisitions made only in the old Android cache;
do not promise indefinite retention or zero reacquisition after those legacy bytes
are already gone. Treat this as an explicit migration limitation.

Terminal states must not become “Remote, therefore auto-download again.” Preserve
reason-specific explicit recovery for previously acquired/unavailable, completed
but unretained, retry exhausted, cancelled and removed. Local misses and reopening
chats must never silently fall back to `downloadMedia`. A deliberate user retry may
start a new cycle; navigation or auto-policy callbacks are not user retry intent.

The native opt-in bound is four acquisition attempts and 64 network attempts per
source/request cycle. Permission interruption refunds the interrupted acquisition
claim, not network attempts. Restarts/recompositions do not reset the budget.
Pure explicit jobs are not opted in by default. Verified-body publication may
finish after network revocation; explicit cancel/remove, expiry and replacement
still fence publication. Do not mis-test successful local publication as a fresh
network download. Subscription close stops observation only; native cancel is a
separate operation. Keep cleanup cancellation-safe and all native handles scoped.

### 3. Required disappearing-message preview expiry

Consume the supplied pinned `retentionExpiresAt` only for selected message previews.
Hide expired message text/attachment summaries when Unix seconds reach the deadline;
do not wait for pruning or a list event. Re-evaluate on resume and schedule visible
expiry with a lifecycle-owned timer. Avoid a timer/poll per offscreen chat.

Do not hide a separately selected draft/invitation, recompute deadlines using current
group settings, choose an older message locally, reorder/remove the row, or change
unread state. Unknown duration, zero duration, finite expiry and overflow/no finite
expiry are distinct. Ensure equality/memoization sees retention changes. Add a
localized empty/expired presentation consistent with current UI and tracked snapshots.

### 4. Optional durable-send checkpoint (scope decision pending)

Use one opaque 1–128 UTF-8-byte token per logical submission. Insert optimistic UI
before draft persistence/FFI, reconcile only the exact echoed `clientToken`, and let
MDK own queued payloads. Do not add a host protocol database, match identical text/
timestamps, or log tokens/content. Acceptance is neither relay publication nor peer
delivery. Remote/legacy rows have no token and retain their supported handling.

Same accepted token plus same request resolves the original identity; changed
payload or rejected/identity-collision attempts require deliberate new submission
handling. Query `localSendStatus` after an interrupted outcome before resubmission.
Engine-owned delivery recovery uses existing exact-event convergence, not semantic
resend. Draft admission consumes only the selected revision; never clear a newer
draft when transport later completes. Admission cancellation does not cancel owned
work. Handle the 256 queued/16 MiB per-account admission bounds honestly.

Media uploads happen before admission and are not idempotent or restart-resumable
blob operations. Query the original token before uploading again; rejected media
may require new references and a new token. Do not claim these APIs alone implement
all of #2493's online attention budget, durable timing, or user recovery requirements.

## Acceptance gates and named tests

1. Artifact: ZIP checksum, embedded provenance, all JNI architectures/exports,
   generated Kotlin and public signature counts agree. Run the existing preparation
   script tests; then stage the signature and compile both Android flavors.
2. Compatibility: update `MarmotWindowTestFakes`, constructor fixtures and transfer
   enum tests. Preserve every final #2691 regression, including old-cache-first,
   cancellation, work identity, native-cache presentation and handle cleanup.
3. Policy: extend `AttachmentPolicyStartupOrderingTest` and
   `MediaAutoDownloadMatrixTest`; add a focused host-managed coordinator test for
   ordered revoke/grant, stale completion, runtime replacement, sign-out/sign-in,
   new/imported/external-signer accounts and all denied/allowed type-network pairs.
4. Bytes/upgrade: extend `AttachmentDownloadWorkerClassTest`,
   `AttachmentDownloadIntentStoreTest`, and native transfer/local-access tests.
   Count actual request/body bytes for cache hits, repeated demand, process restart,
   missing retained bytes, quota/reserve pressure, receipt-before-publication
   interruption, all terminal states, permission flaps and deliberate retry.
   Include notification work and pre-upgrade queued workers, not only open-chat UI.
5. Presentation: extend `ChatListProjectionEqualityTest`; add
   `ChatRowRetentionExpiryTest` for exact deadline, no list event, resume, disabled,
   unknown, overflow and draft/invitation independence. Add deterministic Roborazzi
   before/at-expiry baselines, including relevant RTL/dark states.
6. If checkpoint 4 is selected, test text/reply/revisioned-draft/media, interruption,
   same-token retry, rejection, three identical rapid sends, restart, account/group
   removal and exact optimistic settlement. Measure optimistic display and local
   acceptance separately from transport completion.
7. Device: use the Android CLI skill and a disposable emulator, install old then new
   APK in place with synthetic retained media/drafts/queued work. Measure reopen
   network bytes and first-local-frame behavior after restart and package replacement.
   No uninstall or clear-data on personal devices. Before any Pixel dev install read
   `/Users/user/Workspace/marmot-protocol/PIXEL_DEV_MIGRATION_RECOVERY.md`; take a fresh
   private backup and do not reinterpret that older workaround as 0.10.4 downgrade
   permission. Physical instrumentation requires the leave-APKs-installed property.
8. Update manual release test IDs/inventory and run both guide validators. Run the
   White Noise completion skill, focused tests and required flavor screenshot checks;
   final native/runtime/release CI must reference the final artifact/head. Do not
   claim device, full matrix, or incident closure from artifact preparation alone.

The historic 1 TB report remains unattributed without installed-artifact and measured
traffic evidence. Engine budgets are limits, not measurements or proof of that cause.

## Resume after #2691 merges

1. Verify the live #2691 merge commit, latest master, clean/dirty state and review fixes.
2. Start the delivery branch from updated master and transplant only this upgrade's
   commits/files. If rebasing this branch, use its recorded preparation base as the
   exclusion boundary (`git rebase --onto origin/master cf4d1a147855829182221297cf68d5093f153f2a`
   after fetching master and committing
   intended local changes). Do not replay all pre-squash #2691 commits onto master.
3. Re-read changed ownership/constructor/policy code and rerun the API consumer scan.
   Update this draft's decisions/evidence and resolve the optional-send scope reply.
4. Implement and verify the selected checkpoints as one PR. Recheck the Project 7
   issues and exact-head review threads; close only outcomes actually delivered.
5. Only then push/open the PR, attach it to the task and Project 7 as In Progress,
   publish exact-head visual evidence and verify rendered image links. Do not mark
   ready until actionable CodeRabbit threads and required tracked baselines are resolved.

See [PR body draft](PR-DRAFT.md). For a new implementation agent:

> Read this README and PR-DRAFT.md, then verify the live #2691 state. Work only in
> wn-marmotkit-0.10.4, preserve other worktrees, and keep the PR unpublished until
> #2691 is merged. Use the Android CLI and applicable skills. Adopt exact native
> contracts, preserve user data, and report verification and remaining scope honestly.
