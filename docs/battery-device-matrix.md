# Background battery device matrix

## Scope

[#2786](https://github.com/marmot-protocol/whitenoise-android/issues/2786) asks for
"a guarded GrapheneOS device matrix covering process death, Doze/idle, push
bursts, reconnect failure, successful catch-up, and keep-connected on/off," in
addition to the automated `BackgroundIdleBenchmark` and `NetworkRecoveryBenchmark`
energy baselines documented in [docs/performance.md](performance.md). This is a
manual-testing document, not automation — there is no CI or scripted runner for
it, consistent with how the Macrobenchmark harness itself is guarded and
physical-device-only. A row is supported only after its exact-artifact journey
passes on the named device; nothing below has been executed yet.

Use a Pixel 6a GrapheneOS fixture with an authenticated dev-flavor install and
local conversation history, matching the device already used for other
guarded White Noise Android device acceptance work (for example
[#2815](https://github.com/marmot-protocol/whitenoise-android/issues/2815)).
Never reconfigure a personal account or send fixture text to a real contact;
use a disposable/self conversation.

## Matrix

| Scenario | Setup | Pass criteria |
|---|---|---|
| Process death | Force-stop the app while backgrounded under each delivery mode (`kill`/`am force-stop`), then trigger a real message delivery. | The app recovers delivery (push wake or next foreground open) without a stuck pending state or a duplicate/missed notification. |
| Doze / idle | Put the device into Doze (`dumpsys deviceidle force-idle`) with the app backgrounded, then exit Doze and verify delivery. | No busy-wake during Doze; delivery resumes once Doze exits or on a maintenance-window wake, matching `BackgroundIdleBenchmark`'s idle baselines. |
| Push burst | Send roughly five messages in quick succession while backgrounded. | All five are delivered; no duplicate notifications; wake-lock/catch-up cost is bounded, matching `pushBurstPower`'s observation window. |
| Reconnect failure | Go offline, then block the relay/network beyond the bounded recovery window before restoring connectivity. | Recovery attempts stay bounded (no retry storm); the app recovers once connectivity is genuinely restored, consistent with `NotificationNetworkRecovery`'s existing contract. |
| Successful catch-up | Go offline briefly, then restore connectivity within the bounded window. | Catch-up completes and the chat list/conversation reflect missed activity, matching `validatedNetworkRecoveryPower`'s existing automated coverage. |
| Keep-connected on/off | Toggle Settings → Notifications → Local vs. Push while backgrounded, with the device locked. | The foreground stream runs only in Local mode; Push mode shows no persistent "keep connected" notification and still delivers via FCM. |

## Recording a result

For each exercised row, record: device model and GrapheneOS build fingerprint,
exact White Noise artifact hash, date, and a one-line outcome. Do not mark a
row supported from a benchmark run alone — benchmark results are automated
resource evidence, not a substitute for this manual acceptance pass, and vice
versa.
