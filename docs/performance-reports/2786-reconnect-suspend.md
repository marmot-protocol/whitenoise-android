# Local notification recovery across CPU suspend

Issue: [#2786](https://github.com/marmot-protocol/whitenoise-android/issues/2786).

## Reproduced baseline

On 25 October 2026, release `2026.10.14` (22), source
`6c7cc4bf70ab0de7eae29e13c5ed75e0d6cd3e09`, reproduced delayed Local
notifications on an unplugged Pixel 9a running GrapheneOS Android 17. The
synthetic profile used Optimized battery policy without Google Play services.
The [published evidence](https://github.com/marmot-protocol/whitenoise-android/issues/2786#issuecomment-6394805379)
records six valid, fixed 60-second screen-off captures:

| Condition | Complete five-message notifications inside window |
| --- | ---: |
| Reconnect, then release the setup CPU hold | 1 / 2 |
| Observe a fresh delivery probe, then release the hold | 2 / 2 |
| Observe the probe, retain the diagnostic CPU hold | 2 / 2 |

The failed row had no notification enqueue in the window. Its process and
foreground service survived. Perfetto clocks and independent Batterystats
endpoints agree on 70.175 seconds of suspend; the clock snapshots span 88.341
seconds. No light/deep idle duration or count increased in that failed row.
Four of five delayed bodies appeared in a later notification snapshot. The
first body's outcome is unestablished, not proof of message loss.

These observations do not identify the operation pending at suspend. The
delivery probe changes traffic and awake time, idle state differed between
rows, and production disables the phase diagnostics. They do not establish a
battery saving or qualify a permanent wake lock.

## Current-runtime phase qualification

Four further unplugged captures on the same Pixel used an isolated preview of
Android `dae2e28e1ff23fd2f42f758736f5f09b9cef93df`, based on master
`8558e6d302eacd7ad35c047886d6765790ab0c08`. The preview changed only this report;
it contained no recovery repair. Its native runtime was MarmotKit `0.14.0`, MDK
`3fb4b4759ac676b0c21c4c3527074866c9fa4c89`, whereas the reproduced release used
MarmotKit `0.13.0` / MDK `891ab349`.

The trusted [preview build](https://github.com/marmot-protocol/whitenoise-android/actions/runs/40172610629)
and [publisher](https://github.com/marmot-protocol/whitenoise-android/actions/runs/40173365481)
produced isolated APK SHA-256
`be84cee038012b45726657f424f97023e1b612a79012666ecf91bb20b87e7865`.
It was installed in the retained synthetic profile without replacing any
existing application. The new three-member synthetic group used Local delivery
and Optimized battery policy. Connected collection validation passed first;
the account, group and its five validation messages survived a cold restart.
This was an isolated-fixture investigation, not a populated shared-preview
upgrade qualification or a human APK handoff.

Each unplugged row had a fixed 60-second screen-off window with five messages
starting five seconds into the window, spaced three seconds apart. The setup
CPU hold was released before each window. Row 3 additionally observed a fresh
notification probe before releasing the hold. No ADB polling occurred during
the run, and the scheduled screen wake was after each measurement window.

| Row | Condition | Burst messages notified in window | All five visible by window + | Coordinator catch-up return | Perfetto clock suspend span |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | Reconnect, release setup hold | 5 / 5 | 18.388 s | 443 ms | 42.140 s |
| 2 | Reconnect, release setup hold | 5 / 5 | 18.679 s | 315 ms | 27.758 s |
| 3 | Probe observed, release setup hold | 5 / 5 | 21.246 s | 328 ms | 20.045 s |
| 4 | Reconnect, release setup hold | 5 / 5 | 19.543 s | 414 ms | 43.658 s |

All four collections passed their validity checks. All 21 publications were
acknowledged, including the probe; system notification events and body-bearing
notification snapshots establish the 20 burst deliveries. The same app process
and foreground service survived each row. Bounded performance logging remained
active with no dropped-event marker. Native catch-up calls themselves took
111–117 ms; the table reports the enclosing coordinator's elapsed time from
the network-restored marker, not those nested durations.

Perfetto clock spans include setup and finalization (about 87–90 seconds), so
their suspend totals are not 60-second-window totals. Independent Batterystats
elapsed-minus-uptime deltas agree within 1 ms. Coarse Batterystats history places
the coordinator return before the first post-release sleep segment in each row;
the closest gap is 28 ms, which is not a precise kernel boundary. Deep-idle
counters increased in all four rows, unlike the earlier failing release row.

The earlier wake-only delay did **not** reproduce in this current-runtime run.
No pending Android recovery step was identified, and no behavioral fix is
justified by these four successes. They do not prove that the intermittent
failure is gone or attribute its absence to MDK: the Android revision, native
version, synthetic account state and diagnostics differ from the failing
release. A further failing capture with usable phase evidence is needed before
choosing a repair. No comparative battery saving is claimed.

Restoration verified Owner active, the retained test profile stopped, the
temporary shell wake lock released, normal network settings restored and
notification permission retained. The original release APK and all 23 original
Owner White Noise APKs were unchanged. No application was uninstalled and no
application data was cleared or restored.

## Interpretation limits and next reproduction

Use an isolated, trusted preview built from the current master runtime. Keep
the existing release, personal profiles and their data intact. Record the
source, APK digest, native pin and synthetic fixture separately. Enable the
existing bounded **Performance logs** session under Developer diagnostics.
Validate its unsaturated `WNPerf` output while connected before arming an
unplugged capture.

Correlate reconnect phases across `network_restored`,
`recovery_attempt`, `notification_receiver_ready`, `account_catch_up_start`,
`account_catch_up_ready` and system notification events. The current serialized
sync logs omit operation IDs. Coordinator samples have an attempt field;
nested native catch-up samples do not. This campaign had one observed recovery
attempt per row, but overlapping generations cannot be joined reliably from
phase names alone. Local delivery also does not emit the FCM-only candidate/post
diagnostics. Preserve raw logs privately; publish only timing values and closed
phase/result categories.

The pinned native source imposes another limit. Its
[`CatchUp` command](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-app/src/runtime/account_worker.rs#L4891)
uses [`SyncMode::Drain`](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-app/src/client/sync.rs#L73).
The paced worker separately serves recovery debt. The
[live-input drain](https://github.com/marmot-protocol/mdk/blob/3fb4b4759ac676b0c21c4c3527074866c9fa4c89/crates/marmot-app/src/client/sync.rs#L3311)
can finish when input is quiet; it does not establish that every relay replay
or background recovery job has completed. Android currently emits
`account_subscription_activated`, `current_replay_complete` and
`durable_ingest_ready` together at that one return. These are host-derived
labels, not independently observed native milestones, and must not be used as
proof of full native settlement. This contract limitation does not establish
that native work caused the earlier delayed burst.

- A start without its completion across suspend can identify pending receiver,
  catch-up or notification work. It does not by itself locate a native subphase.
- If recovery completed before suspend, do not claim a reconnect wake lease
  repairs later incoming-message wake delivery.
- If work stalls with CPU and connectivity available, investigate the Android
  handoff and native contract before assigning ownership.
- If the current runtime does not reproduce, report that limit rather than
  inventing a pending step or applying a speculative repair.

The candidate Android boundary, only if supported by that evidence, is bounded
CPU wake ownership for admitted active recovery and its required notification
handoff. It must coalesce repeated starts and release on completion, failure,
cancellation and expiry. It must not hold the CPU awake indefinitely or across
unbounded retry backoff, nor promise arbitrary later Local delivery.

## Completion scope

This investigation is a slice of #2786, not its closing condition. The full
disabled/native-push/keep-connected matrix, cost budgets and any qualified
battery reduction remain separate acceptance work. An implementation must add
meaningful lifecycle/ownership regression tests, pass exact-head hosted checks,
and qualify the changed behavior on a retained fixture without uninstalling or
clearing application data.
