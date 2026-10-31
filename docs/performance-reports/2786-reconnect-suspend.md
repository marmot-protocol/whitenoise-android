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

Restoration verified that the retained test profile was stopped, the temporary
shell wake lock released, normal network settings restored and notification
permission retained. Existing White Noise APKs were unchanged. No application
was uninstalled and no application data was cleared or restored.

## Ten additional reconnect captures

A further run on the same unchanged isolated APK completed ten unplugged
reconnect captures, each with five messages and the same fixed 60-second
screen-off window. No readiness probe or diagnostic hold was added to the
measurement window. All 50 publications were acknowledged, and all 50 bodies
appeared in notification records updated before the scheduled screen wake.
Nine captures passed every prespecified validity check.

| Row | Qualified | All five visible by window + | Coordinator return | Nested native call | Perfetto clock suspend span |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | Yes | 18.483 s | 1,020 ms | 776 ms | 11.150 s |
| 2 | Yes | 18.378 s | 371 ms | 124 ms | 13.524 s |
| 3 | Yes | 22.367 s | 323 ms | 113 ms | 25.569 s |
| 4 | Yes | 18.914 s | 297 ms | 112 ms | 44.526 s |
| 5 | Yes | 20.957 s | 351 ms | 112 ms | 44.133 s |
| 6 | Yes | 18.760 s | 941 ms | 630 ms | 40.781 s |
| 7 | No: late collector check | 18.200 s | 356 ms | 112 ms | 46.722 s |
| 8 | Yes | 21.030 s | 505 ms | 111 ms | 33.407 s |
| 9 | Yes | 19.701 s | 384 ms | 112 ms | 23.776 s |
| 10 | Yes | 18.663 s | 395 ms | 119 ms | 26.535 s |

Row 7's initial lock-state inspection occurred 8.290 seconds after the window
started. Although its release call preceded the window by 8.893 seconds and
its notifications arrived on time, it is excluded from qualified results.
Thus this run supplies nine valid successes, not ten. With the earlier four
captures, there are 13 qualified current-runtime captures: 12 ordinary
reconnects and one probe-assisted reconnect. None reproduced the wake-only
delay.

Each repeat row retained the same process and foreground service, and recorded
one recovery attempt with completed callbacks. All ten rows contained 15
performance events, including completion evidence in the last row, without a
dropped-event marker. Coordinator returns took 297–1,020 ms; the nested native
calls took 111–776 ms. Returns preceded the message windows by 3.402–10.155
seconds. In rows 1 and 6, recovery started after the setup hold was released
and still returned before the window. No persistent stalled step was observed.

Clock spans cover 79.531–90.033 seconds including setup/finalization; suspend
totals are not fixed-window totals or app-attributed energy measurements.
Independent Batterystats elapsed-minus-uptime deltas agree within 2 ms.
Deep-idle counters increased in nine rows. Row 3 had no light/deep idle counter
increase and still delivered its complete burst before wake.

The coarse battery-history marker in row 6 places a not-running segment across
an observed callback return, starting 328 ms before it. These markers cannot
be treated as exact kernel sleep boundaries. This trace configuration has no
scheduler or kernel-suspend events, so that overlap does not locate a stalled
native operation. The native catch-up contract limits below still apply.

Restoration passed again: existing White Noise APKs were unchanged, network
settings and notification permission were preserved, and the temporary hold was
released. The retained test profile was stopped. No APK was installed or removed,
no app data was cleared or restored, and app code was unchanged. The synthetic
host sender and collection helpers were stopped after retrieval.

These additional successes do not establish that the intermittent release
failure is fixed or distinguish an Android cause from an MDK cause. The earlier
failure remains evidence against the older release, while the tested runtime,
synthetic state and diagnostics differ. There is still no supported behavioral
repair or battery-saving claim, and #2786 remains open.

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
sync logs omit operation IDs. Coordinator samples include an attempt field. Nested native catch-up samples
omit that field. This campaign had one observed recovery
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
- If the current runtime does not reproduce, report that limit. A pending-step
  diagnosis or repair requires a failing capture.

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
