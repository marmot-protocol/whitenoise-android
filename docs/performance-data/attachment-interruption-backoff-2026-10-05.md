# Attachment work backoff after Android interruptions

This records the policy and a scheduler-level measurement for #3081. It does not
qualify battery, bandwidth, a physical device, process death or a real quota stop.
Refs #3081 and #2779.

## Policy

- Ordinary (automatic) attachment work opts into WorkManager's backoff for system
  interruptions. After Android stops a run, the next run is delayed 30 seconds,
  then 60, 120 and so on, doubling per run up to WorkManager's five-hour ceiling.
  The delay is measured from the stop.
- Explicit work (a deliberate tap, open or save) does not opt in. A fresh explicit
  request replaces waiting or running automatic work for the same attachment, so
  it never waits behind an old delay. Explicit work already queued or running
  stays, and repeated requests coalesce on the one unique work.
- A spec persisted by the previous release has no opt-in. The first time it runs,
  the worker updates it in place by id, so the change cannot recreate work that was
  cancelled meanwhile.
- The one follow-up after a completed transient failure is a persisted flag, not
  WorkManager's run-attempt count, because every restart after a stop also
  increments that count. A terminal result, success or cancel restores it.

## Why the field loop happened

Without the opt-in, WorkManager measures the retry delay from the spec's original
enqueue time. A spec older than the five-hour ceiling is therefore due again at
once after every stop, which is how run-attempt counts reached the hundreds.

## Measurement

`AttachmentDownloadInterruptionBackoffTest` drives the real WorkManager scheduler
with a controllable clock and stops every run through the test driver with the quota
stop reason. It reads each delay from the scheduler's public work info.

| Window of one virtual hour, each run lasting 10 seconds | Runs started |
| --- | --- |
| Opt-in, runs start at 0, 40, 110, 240, 490, 980 and 1,950 seconds | 7 |
| No opt-in, spec older than the five-hour ceiling | 360 |

Delays across 13 consecutive stops are 30 s, 60 s, 120 s, up to 5 h. These counts
come from a simulated clock and scripted stops. They are not a claim about energy or
data. The `controller-automatic-resume` emulator fixture now waits out the first
30-second delay before the resumed run, within its 100-second bound.
