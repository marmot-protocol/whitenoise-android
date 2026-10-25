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

## Phase qualification before behavior changes

Use an isolated, trusted preview built from the current master runtime. Keep
the existing release, personal profiles and their data intact. Record the
source, APK digest, native pin and synthetic fixture separately. Enable the
existing bounded **Performance logs** session under Developer diagnostics.
Validate its unsaturated `WNPerf` output while connected before arming an
unplugged capture.

Correlate the same opaque reconnect trace across `network_restored`,
`recovery_attempt`, `notification_receiver_ready`, `account_catch_up_start`,
`account_catch_up_ready` and notification candidate/post markers. Compare
these with the fixed send window, system notification events and independent
elapsed/uptime or Perfetto-clock suspend evidence. Preserve raw logs privately;
publish only timing values and closed phase/result categories.

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
