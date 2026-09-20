# Android push recovery qualification — 2026-09-20

Implementation source: isolated branch `fix/fcm-durable-recovery`, based on refreshed
master `6a9f2434c02d55c358c23ee99b1ff4e0f09b9f7d`. The original checkout and native
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

Final settled-source results:

| Check | Result |
| --- | --- |
| Change-aware completion gate, `--visual changed` | PASS; final review follow-up run 2m 22s |
| Both debug variants and instrumentation compilation | PASS |
| Formatting, static analysis, alternate-variant Android lint | PASS |
| Primary-variant focused tests | 244 passed, 0 failures/errors/skips; 24 suites |
| Alternate-variant focused tests | 241 recovery-focused tests plus the 21-test affected ordering class passed separately; 0 failures/errors/skips |
| Locale resource parity | PASS; every translated resource set matches the default key set |
| Visual baseline verification | PASS |
| Manual inventory | 267 active IDs, 0 retired IDs |
| Manual-guide validator tests | 33 passed |
| Diff whitespace integrity | PASS |
| Local touched-function documentation audit | 273/316, 86.4% |

The focused matrix covers priority routing, rejected service starts, deleted callbacks,
owner acceptance and loss, scheduling database states, marker reopening, retry exhaustion,
generation coalescing, native-lane serialization, worker cancellation, completion and
acknowledgement races, delivery-mode cutover and rollback, battery-policy projection,
diagnostic correlation/privacy, and updated settings behavior.

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
then moved to the fresh isolated package ID above. The rebased settled revision was installed
in place on the API-30 emulator and physical handset. Notification permission was granted only
to the qualification package for its synthetic alert test.

The rebased settled-source candidate passed the same three device tests on each target. The
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

The final review-follow-up Dev APK was built for arm64 with SHA-256
`8c4d88c7e38a92178ac2df44729b298255ba3bde97ebf2d236873c8f323ae50b`. It reports the
expected Dev package/version and signing certificate. The physical Pixel disconnected before the
required fresh backup and in-place update could begin, so this exact candidate is not yet claimed
installed. No device data was touched during the failed connection check.

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
- Issue and pull-request ownership and Project 7 `In Progress` state were verified. No merge or
  release was created.

## Source and artifacts

The source/test patch relative to the base revision is SHA-256
`858b09083eb4d80ad8492bb4e14d5340d32909f59ca7738cc02834869bf470e6`.
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
