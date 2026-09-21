# Android push recovery qualification — 2026-09-20

Implementation source: isolated branch `fix/fcm-durable-recovery`, based on refreshed
master `b3bf1acc3ec681fea1459cf392656208187cc3dd`. `scope-selection.json` records this
same final qualification base. The original checkout and native
dependency pin are unchanged. This report covers the Android implementation and does
not establish a fix for the reported 2026.9.17 open-chat symptom.

## Implemented behavior

- Every callback persists a monotonically increasing generation before dispatch. Received
  priority selects the foreground-service or durable route; deleted-message callbacks use
  the same recovery coordinator.
- Wake acceptance requires a foreground activity or an acknowledged live service-instance
  lease. A saved preference cannot impersonate a process owner. Losing the last owner
  transfers any unresolved obligation to durable work without cancelling the serialized
  native catch-up lane.
- Rejected foreground-service starts, bootstrap failures, and owner teardown preserve the
  pending generation and reconcile it through unique durable work. The queue is bounded to
  one running item plus one successor and never uses periodic work.
- The worker includes lazy runtime construction, store access, eligibility evaluation, and
  recovery in one finite retry boundary. Cancellation and fatal errors propagate. Transient
  failures return retry against the same work item, cap at four runs, and retain the pending
  generation after exhaustion.
- Durable attempt claims, completion, deferral, rollback, and acknowledgement run on the
  injected storage dispatcher and are awaited. The coordinator rechecks the account,
  runtime, network, and lifecycle identity after every awaited bookkeeping step.
- Native success is provisional until completion bookkeeping returns. The same identity
  fence spans acknowledgement. If identity changes during either storage suspension, the
  old catch-up cannot publish readiness or clear the current obligation. If acknowledgement
  cleared an old marker before detecting the change, a new durable generation restores it.
- The native lane remains one running operation and one successor. A four-attempt persistent
  episode budget uses bounded backoff. Exhaustion does not create a timer or idle wakeup;
  only an existing app, account, network, unlock, or callback edge can reconcile pending work.
- Notification delivery is represented as one mutually exclusive user choice: brief push
  wake or persistent local connection. Cutover enables the replacement owner before
  disabling the previous path, waits for a real service acknowledgement, and rolls back
  if ownership cannot be established. Existing notification rendering preferences remain
  unchanged.
- The settings screen reports notification permission and current battery policy and opens
  system settings only on user action. Status refreshes on resume. The implementation does
  not request a battery-optimization exemption and adds no polling.
- Opt-in diagnostics assign an anonymous correlation to every callback and emit bounded
  received, scheduled, started, native-finished, posted/suppressed, and incomplete phases.
  Explicit copy exports at most the current or most recent bounded session. No payload,
  token, account, conversation, sender, message content, disk sink, upload, or telemetry
  timer was added.
- The worker adds no manual wake lock. The existing service wake lock remains bounded with
  `finally` cleanup. No Android-owned unread or message cache was added.

## Review findings resolved

1. **Live lifecycle ownership.** Foreground wake acceptance now requires a concrete activity
   or acknowledged service instance. Owner loss schedules the unresolved generation.
2. **Cold initialization retry boundary.** Worker initialization, storage, eligibility, and
   execution share the bounded exception boundary while cancellation and fatal errors still
   propagate.
3. **Storage off the main thread.** All durable recovery transactions execute on and await
   the storage dispatcher, with lifecycle and attempt-budget fences rechecked afterward.
4. **Completion and acknowledgement generation fence.** Completion revalidates the executed
   recovery identity inside the storage dispatcher before choosing success bookkeeping, then
   checks it again after the awaited write. Acknowledgement checks the identity both before and
   after its storage suspension, restores a durable obligation if it became stale after clearing,
   and publishes startup readiness only after acknowledgement succeeds.
5. **Local cutover settlement.** Once any account's native disable commits, cancellation or
   cleanup failure keeps the acknowledged persistent owner durable. Multi-account partial
   failure keeps that owner active, and a newer delivery intent alone owns final convergence.
6. **Push cutover settlement.** Cancellation after the durable preference commit restores the
   previous persistent preference. If service stop begins, failure or cancellation re-establishes
   and acknowledges a live persistent owner before native settings can roll back.
7. **Account activation reconciliation.** A real account switch restores local notification
   rendering first, resolves the current device mode, and reconciles transport asynchronously.
   Account, runtime, switch, and delivery-intent generations fence every suspended step.
8. **Startup and same-account restoration.** Foreground entry and same-account activation use
   the production activation path to restore rendering before transport reconciliation. The
   plan reads every authoritative signed-in account and aborts on an unreadable account rather
   than inferring disabled state. Background-only startup performs no new transport work; the
   first foreground edge performs legacy convergence.
9. **Permission-loss pause.** Foreground entry, activation, registration sync, fallback commit,
   and service acknowledgement all require current notification permission. Denial drains only
   required cleanup and cannot register, change delivery mode, or start a persistent service.
   Regrant restores rendering before reconciling the retained mode.
10. **Device-wide Local reconciliation.** A real account switch can no longer short-circuit on
    active-account rendering alone. It confirms the complete account/runtime invariant and,
    when any account remains native-enabled, establishes and acknowledges Local before disabling
    native delivery for every signed-in account.
11. **Legacy no-transport migration.** Rendering-on/no-transport and rendering-off/all-off states
    both converge to a supported mode on a foregrounded cold start. Rendering is restored before
    the persistent owner is acknowledged, and permission denial continues to pause the migration.
12. **Activation invariant after rendering repair.** Activation restores notification rendering,
    revalidates the account/runtime/switch owner, and only then reads the device-wide delivery
    invariant. An account that began rendering-off/all-off can no longer reuse stale state to skip
    push registration or persistent-owner convergence.
13. **Atomic wake admission.** Recording a new wake generation and reopening an eligible exhausted
    attempt budget now use one durable transaction. A successful generation write can no longer be
    followed by a failed admission write that returns without an owner or durable dispatch. Tests
    prove both the single-commit success path and the shared commit-failure result.
14. **Qualification provenance.** The report now records the full exact refreshed base revision,
    and the source/test patch hash is recomputed from that revision after the final rebase.

Repository regressions cover the external review reproductions directly. They hold the
completion and acknowledgement dispatches, advance the real network generation, and prove
the stale operation cannot clear the marker. Cancellation tests cover the same settlement
windows. Transport tests hold every durable commit, cleanup, service-stop, and account-switch
boundary and prove one acknowledged path survives or the newer intent owns convergence.
Additional tests prove preference-only ownership is rejected, owner loss schedules durable
recovery, cold initialization failures consume the finite worker budget, durable writes do
not run on the main dispatcher, and the attempt claim is restored across a closed lifecycle
fence. Lifecycle regressions exercise foreground entry before bootstrap, same-account
activation, rendering-on/no-transport and all-off legacy combinations, an already-rendering
account switch with conflicting native delivery, permission denial during foreground and
activation paths, cleanup-only behavior, permission regrant ordering, and rendering repair that
changes the delivery invariant during activation.

## Automated validation

The broad checks below were completed before the latest admission-diagnostic and localization
review fixes. Per maintainer direction, only directly related tests were rerun locally for those
final changes; the hosted matrix is responsible for the full current-head gate.

| Check | Result |
| --- | --- |
| Change-aware completion gate, `--visual none` before the latest review fixes | PASS; post-rebase run 2m 16s; current-head rerun delegated to hosted CI |
| Both debug variants and instrumentation compilation | PASS |
| Formatting, static analysis, alternate-variant Android lint | PASS |
| Final review-focused primary-variant tests | 114 passed, 0 failures/errors/skips; 11 suites |
| Latest admission/store regressions | 44 passed, 0 failures/errors/skips; 2 suites |
| Alternate-variant focused tests | 241 recovery-focused tests plus the 21-test affected ordering class passed separately; 0 failures/errors/skips |
| Post-rebase CI regression set | PASS; 45 tests in each debug variant, 0 failures/errors/skips |
| Full alternate variant | PASS; 9,169 tests, 0 failures/errors, 1 skipped; 5m 41s |
| Locale resource parity | PASS; every translated resource set matches the default key set |
| Visual baseline verification | PASS |
| Manual inventory | 273 active IDs, 0 retired IDs |
| Manual-guide validator | PASS |
| Diff whitespace integrity | PASS |
| Local touched-function documentation audit | 273/316, 86.4% |

The focused matrix covers priority routing, rejected service starts, deleted callbacks,
owner acceptance and loss, scheduling database states, marker reopening, retry exhaustion,
generation coalescing, native-lane serialization, worker cancellation, completion and
acknowledgement races, delivery-mode cutover and rollback, battery-policy projection,
diagnostic correlation/privacy, and updated settings behavior.

After the final 2026-09-21 rebase, the exact CI regression set covers recovery tracing,
suspend-then-publish ownership guards, the state-source growth ratchet, denied-permission
delivery preservation, and all 29 delivery-mode recovery cases. It passed all 45 tests in
both debug variants. A full primary-variant run executed 9,122 tests and exposed one test-only
ordering race: the assertion observed the native setting before the later registration callback.
The maintained test now awaits both ordered effects and its full class passes in both variants.
The full primary suite was not repeated after that test-only correction.

Earlier unfiltered alternate-variant attempts exposed an indefinite test wait for best-effort
native cleanup. The maintained regression now pumps its test Main dispatcher while real IO
completes, under a finite timeout, instead of advancing virtual time past the native cleanup
window. The final unfiltered alternate suite passes all 9,169 tests with one existing skip. Kover
XML was not regenerated after these review fixes, so the earlier coverage receipt is not
current-source evidence.

An earlier hosted run exposed three stale source-ordering assertions after the recovery helper
boundaries changed. Their maintained contracts now locate the final helper bodies. The next
exact-head matrix revealed a deterministic circular wait in an existing notification-ordering
test: it held a notification writer at its final gate while asking a complete account switch to
settle, and account activation can itself await notification work. The test now invokes the real
cross-account cache invalidation boundary directly. This preserves the intended proof that a
cache-generation change rejects the held stale write without coupling the proof to unrelated
account-switch settlement. The exact regression and all 21 tests in its class pass in both build
variants. A new exact-head hosted matrix is required after this report is pushed.

The completion/acknowledgement settlement regressions and their cancellation variants pass
in both build variants. The current earlier-review probe bundle also passes. The supplied
settlement probe no longer reaches its data-loss assertion because the corrected stale path
returns the expected incomplete-recovery exception to an unsupervised child; its maintained
repository adaptation contains that expected failure in the child and proves the marker remains
pending. All five supplied lifecycle probes pass together with all 29 maintained delivery-mode
regressions (34 tests total), including cancellation after durable preference and native-disable commits,
service-stop restoration, partial multi-account failure, newer-intent convergence,
startup/same-account restoration, permission-loss pausing, and activation-invariant recomputation
after rendering repair.

Recorded and verified visual baselines cover push selected, local fallback when push is
unavailable, light/dark/AMOLED themes, right-to-left large text, full notification settings,
and active diagnostic export. The selector uses whole-row radio targets and exposes exactly
one selected delivery owner.

Permanent manual checks `NTF-001` through `NTF-004` were updated for single-mode delivery.
`NTF-019` through `NTF-021` retain recovery validation, and `NTF-022` through `NTF-024`
cover cutover restoration, device policy/settings access, and correlated diagnostic export.

The completion gate is the repository's fast pre-push gate, not a full release mirror.
Existing compiler deprecation warnings remain. No check was disabled and no lint or visual
baseline suppression was added.

## Device and battery qualification

The user authorized safe device checks and later explicitly requested an in-place Dev install.
The qualification candidate uses the separate package
`dev.ipf.whitenoise.android.fcmrecoveryqualification`; it cannot overwrite the personal Dev package.
Installations used `-r -t`, and instrumentation was invoked directly. No uninstall, data
clear, database edit, account import, message send, test identity, or release publication
was performed.

The first package ID was already present on both devices under a different local signing key,
so Android rejected the in-place update. Both existing packages were retained. Qualification
then moved to the fresh isolated package ID above. The 2026-09-20 candidate was installed
in place on the API-30 emulator and physical handset. Notification permission was granted only
to the qualification package for its synthetic alert test.

The 2026-09-20 settled-source candidate passed the same three device tests on each target. The
emulator completed the two recovery/scheduler checks in 0.151 seconds and the synthetic
notification-cohort check in 3.427 seconds. The physical handset completed them in 0.754 and
4.018 seconds:

- stale durable work exits without constructing the native runtime;
- API 30 never requests expedited foreground-service emulation;
- a synthetic catch-up notification cohort alerts once and a later live notification can
  alert again.

These are harness durations, not message-delivery latency. A subsequent 45-second read-only
observation found the qualification process inactive on both targets, with no active job or new
job start/stop. Each qualification UID retained identical recorded job, wake-lock, service, and
available CPU/running-time totals. No statistics were reset. This supports the no-pending-work
fast exit only; it does not measure energy impact.

Those observations apply to the recorded 2026-09-20 candidate. Device and battery qualification
was not repeated after the final 2026-09-21 master rebase, so it is not current-head evidence.

The most recent installed Dev APK was built for arm64 before the latest review fixes, with SHA-256
`b9844f73af4e3e3eb5a5fbbe96b49f446ddb623d316c3a93f7ced15e4cb5503f`. It reports package
`dev.ipf.whitenoise.android.dev`, version `2026.9.21-dev-debug`, version code 19, and arm64-v8a.
This exact candidate was installed in place on Pixel `46131FDAS003CG` with `adb install -r -t`.
The installed APK hash matches the candidate, the signing certificate and native library match
the prior installation, the original first-install timestamp and three account records remain,
and a cold launch resumed `MainActivity` without a fatal, migration, database, or startup error
in the privacy-filtered log check. No app was uninstalled and no app data was cleared. No APK was
built or installed for the latest admission-diagnostic and localization-only review revision.

A fresh private pre-install archive is retained at
`/Users/mubarak/Workspace/marmot-protocol/pixel-fcm-recovery-20260921.X7NSnz`. The data archive
SHA-256 is `57b346e10e80a2164dd20ece8e59c0dc866e20c60acbd62142116a7d234484cb`;
the preserved pre-install APK SHA-256 is
`b40bf86cb4638968c7979408c83e1104f1e803b36b18cd69822ccabb54b26488`.

## Unperformed and deferred checks

- Live end-to-end delivery, foreground keyboard-open delivery, screen-off and idle-mode
  delivery, airplane/network transitions, and numbered-message paging are unperformed.
  They require a disposable identity, sender, configured route, and controlled workload.
- Five alternating baseline/candidate active-workload pairs and three paired two-hour
  screen-off idle sessions are unperformed. CPU, network, wake-lock, energy-per-batch,
  power-rail, median, and tail-latency distributions were not measured.
- No zero-battery-impact claim is made. The original affected release was not compared with
  this candidate under the original open-chat reproduction, so that issue is not claimed fixed.
- Authoritative already-read notification suppression remains deferred with its native
  dependency. Existing eligibility and cancellation behavior remains unchanged.
- Live Project 7 item data verified on 2026-09-21 that issue #2676 and pull request #2712 are each
  present once with `In Progress` status. No merge or release was created.

## Source and artifacts

The source/test patch relative to the base revision is SHA-256
`b064fafd09a02bd03cd9b57bc5ac49cef8783d7f7a31eb5d3b7d13a7eaf4a283`.
This hashes `git diff origin/master --binary -- app/src`. The native dependency remains unchanged.

Final qualification APK hashes:

- `candidate.apk`: `ede3e92ffa0df17dd3ef8f469f15a8e7458e3d823279a9d089fa1100c59db1ba`
- `tests.apk`: `6cd5a981854ef5b41e89a1b1eb3c9de068cefd935f490c315ca31007b02e58b0`

The candidate reports package `dev.ipf.whitenoise.android.fcmrecoveryqualification`, version name
`2026.9.15-dev-debug`, version code 15, and arm64-v8a. Private build/test receipts, exact
device output, isolated system snapshots, APKs, source patch, and documentation audit are
retained outside the repository at:

`/Users/mubarak/Workspace/marmot-protocol/fcm-recovery-qualification-20260920`

## Reproduction commands

Run from the isolated worktree:

```sh
/Users/mubarak/.codex/skills/whitenoise-android-completion-gate/scripts/run_completion_gate.sh \
  --visual changed \
  --tests '*PushWake*Test' --tests '*PushTokenStoreTest' \
  --tests '*NativePush*Test' --tests '*NotificationDeliveryModeRecoveryTest' \
  --tests '*DefaultNotificationDeliveryTest' \
  --tests '*NotificationBatteryPolicyTest' --tests '*PerformanceDiagnostic*Test' \
  --tests '*NotificationsScreenTest' --tests '*DiagnosticsContentTest' \
  --tests '*NotificationDeliverySelectorScreenshotTest' \
  --tests '*NotificationsScreenScreenshotTest' \
  --tests '*DiagnosticsScreenScreenshotTest'

./gradlew :app:testDevZapstoreDebugUnitTest \
  --tests '*PushWake*Test' --tests '*PushTokenStoreTest' \
  --tests '*NativePush*Test' --tests '*NotificationDeliveryModeRecoveryTest' \
  --tests '*DefaultNotificationDeliveryTest' \
  --tests '*NotificationBatteryPolicyTest' --tests '*PerformanceDiagnostic*Test' \
  --tests '*NotificationsScreenTest' --tests '*DiagnosticsContentTest' \
  --tests '*NotificationDeliverySelectorScreenshotTest' \
  --tests '*NotificationsScreenScreenshotTest' \
  --tests '*DiagnosticsScreenScreenshotTest' --console=plain

python3 scripts/check_manual_test_guide.py
python3 -m unittest scripts/test_check_manual_test_guide.py

./gradlew -I docs/qualification/fcm-qualification.init.gradle \
  :app:assembleDevPlayDebug :app:assembleDevPlayDebugAndroidTest \
  -Pandroid.injected.build.abi=arm64-v8a --console=plain
```

Verify the APK package ID before using only in-place installs of the qualification package:

```sh
adb -s DEVICE install -r -t candidate.apk
adb -s DEVICE install -r -t tests.apk
adb -s DEVICE shell am instrument -w -r \
  -e class dev.ipf.whitenoise.android.notifications.PushWakeRecoveryDeviceTest \
  dev.ipf.whitenoise.android.fcmrecoveryqualification.test/androidx.test.runner.AndroidJUnitRunner
```

Never run a physical-device connected-test task without
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
