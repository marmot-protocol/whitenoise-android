# Background battery device matrix

## Scope

[#2786](https://github.com/marmot-protocol/whitenoise-android/issues/2786) asks for
"a guarded GrapheneOS device matrix covering process death, Doze/idle, push
bursts, reconnect failure, successful catch-up, and keep-connected on/off," in
addition to the automated `BackgroundIdleBenchmark` and `NetworkRecoveryBenchmark`
energy baselines documented in [docs/performance.md](performance.md). This is the holistic acceptance contract; resource benchmarks and the anonymous
campaign validator cover only part of it. A row is supported only after its exact-artifact journey
passes on the named device; nothing below has been executed yet.

Use a dedicated GrapheneOS fixture for the child's GrapheneOS acceptance gate.
An explicitly authorized disposable user on a stock Pixel can qualify that
model/build, but cannot establish GrapheneOS behavior. Use an authenticated
configured dev-flavor install and disposable local conversation history.
Never reconfigure a personal account or send fixture text to a real contact;
use a disposable/self conversation.

## Matrix

Exercise both supported modes and optimized/unrestricted battery-policy states.
Capture the original permission, listener, battery, connectivity and idle state;
restore and verify each after its row, including failed rows. Keep fresh private
backups and original APKs; package code is shared across users even when data is
isolated. No uninstall, data clearing or personal-account reconfiguration.

| Scenario | Setup | Pass criteria |
|---|---|---|
| Process death | Kill the backgrounded app's process directly (`kill`, not `am force-stop`) under each delivery mode, without otherwise touching its package state, then trigger a real message delivery. | The app recovers delivery (push wake or next foreground open) without a stuck pending state or a duplicate/missed notification. |
| Force-stop recovery | Force-stop the app from Settings/`am force-stop`, then explicitly reopen it before triggering delivery. | A force-stopped app enters Android's stopped-package state, which blocks FCM delivery until the user reopens it — a separate contract from ordinary process death. Delivery resumes only after that explicit reopen, with no stuck pending state. |
| Doze / idle | Put the device into Doze (`dumpsys deviceidle force-idle`) with the app backgrounded, then exit Doze and verify delivery. | No busy-wake during Doze; delivery resumes once Doze exits or on a maintenance-window wake, matching `BackgroundIdleBenchmark`'s idle baselines. |
| Push burst | Send at least five distinct fresh disposable fixture bodies during the screen-off measured window. | All five are delivered; no duplicate notifications; wake-lock/catch-up cost is bounded, matching `pushBurstPower`'s observation window. |
| Reconnect failure | Go offline, then block the relay/network beyond the bounded recovery window before restoring connectivity. | Recovery attempts stay bounded (no retry storm); the app recovers once connectivity is genuinely restored, consistent with `NotificationNetworkRecovery`'s existing contract. |
| Successful catch-up | Go offline briefly, then restore connectivity within the bounded window. | Catch-up completes and the chat list/conversation reflect missed activity, matching `validatedNetworkRecoveryPower`'s existing automated coverage. |
| Keep-connected on/off | Select each mode in Settings, then background and lock the device. | The foreground stream runs only in Local mode; Push mode shows no persistent "keep connected" notification and still delivers via FCM. |

| Transition rollback | Make replacement registration unavailable during Local → Push; restore it and retry. Repeat the reverse transition and rapid account/mode switches. | The working path remains enabled until replacement confirmation; stale completion cannot override the newest intent; there is one active owner. |
| Permission loss/recovery | Revoke notification permission, verify factual settings state, restore permission and send fresh fixtures. | No false delivery claim or exemption prompt; the selected working path recovers and posts once. |
| Capability loss/recovery | Remove fixture push availability or Play Services support, then restore it. | Local fallback is explicit, truthful and recoverable; successful Local delivery is not counted as FCM proof. |
| Privacy-safe export | Export bounded delivery phases for on-time and delayed fixtures. | Export separates pre-callback delay, callback/catch-up/projection/posting and runtime phases; no identities, tokens, payloads, titles, relay URLs or decrypted content. |
| Resource ownership | Trace repeated push wakes, failed reconnect and completed catch-up in each policy state. | Wake locks release on success/failure, push services finish their purpose, scheduled work/retries are bounded and no overlapping owner or redundant wake remains. |

## Recording a result

For each exercised row, record: device model and OS build fingerprint,
exact White Noise artifact hash, date, and a one-line outcome. Do not mark a
row supported from a benchmark run alone — benchmark results are automated
resource evidence, not a substitute for this manual acceptance pass, and vice
versa.

Record canonical delivered/duplicate counts independently of notification-card
updates. A listener receipt proves Android posting within its window, not unique
canonical persistence or native FCM transport. Correlate aggregate phase and
service evidence without publishing fixture text, identifiers or raw logcat.
Retain content-bearing traces privately; export only bounded numeric summaries.

Report five-scenario resource evidence alongside correctness, mode rollback,
policy/permission/capability recovery and restoration. A short smoke run, green
CI, or a passed receipt-state test cannot close #2692/#2786. Review the baseline
budgets and attribution before changing runtime policy or claiming savings.
