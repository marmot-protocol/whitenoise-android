# Stock Pixel background battery observations — 2026-10-08

**Qualification failed; #2692/#2786 remain open.** Twenty actual unplugged,
screen-off resource windows cover disabled idle, Push idle, Local idle and Push
burst under optimized battery policy. Four of five bursts posted every fresh
message within the 60-second deadline; the fifth posted zero of five. These
observations do not qualify GrapheneOS, unrestricted policy or a daily battery
budget. [Numeric samples](background-battery-pixel-2026-10-08.json) retain all
rounds, including both the delivery failure and the disabled-idle energy outlier.

## Artifact and method

Device: Pixel 9 Pro XL, stock Android 17/API37, build CP3A.260905.009.
Source `b7261b3ebceb3b2537811fe09edc078ea2eebfd4` has the identical full tree to
merged #3175 at `2ee08f351c1e13592d07e5d806053b5a3396edab`.
Installed hosted benchmark target APK SHA-256:
`c98cd8311e25398680b968d3ec347548b033701b204d9e427e67be65cc11566c`.
GitHub build run: [37763884272](https://github.com/marmot-protocol/whitenoise-android/actions/runs/37763884272),
artifact 11544461206. The existing device-compatible debug certificate was applied
without changing compiled ZIP entry contents. This is an as-installed release-like
benchmark target, not an APK rebuilt from later follow-up commits.
MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`; published artifact
`a7ae4213d1e331d17148589f7190f30b0cefef20952f46b19938524577bbbb18`.
Its native library matches the preserved original Dev APK byte for byte.
After testing, follow-up #3181 is rebased onto master
`b1b6f43f0` (composer/search/onboarding changes). Those newer app changes are not
part of the tested APK and are not qualified by these measurements.

A disposable Android user and two disposable accounts isolate conversation data.
Shared package-code replacement protects other installed Dev profiles and restores
the exact original APK before enabling them. No uninstall or data clearing occurs.
Raw traces, account backups, transport identifiers and notification content remain
private; the linked export contains bounded numeric observations only.

Direct platform Perfetto captures use a configured 60,000 ms duration with
`prefer_suspend_clock_for_duration: true`. Five balanced, shuffled rounds per
scenario have real AC/USB/wireless/dock power flags false and screen off at both
endpoints. Fixture PID equality is verified at the endpoints; that alone does not
prove uninterrupted process lifetime. Visible trace spans are 59.853–60.097 s;
reported trace data loss is zero. Temperature is 33.5–33.9 °C.
There is no compilation reset or batterystats reset.

Jetpack Macrobenchmark could not produce usable traces on this secondary user:
file access and baseline-profile installation failed. Diagnostic suppressions did
not qualify that route. Earlier captures contaminated by the Android CLI helper
and a zero-byte capture with the wrong duration clock are excluded and retained.
The direct captures above provide a separate method, not successful executions of
`BackgroundIdleBenchmark` or `NetworkRecoveryBenchmark`.

## Resource observations

These are **whole-phone hardware-rail energies**, not energy attributable solely
to White Noise. CPU includes `cpu.*`; network includes AOC, modem, radio frontend
and Wi-Fi/BT; memory includes DDR and memory interface. All rails also include
other device components. One mWh equals 3,600,000 µWs. The fuel gauge moves in
2,500 µAh steps; a zero gauge delta is not zero consumption.

| Scenario, five rounds | Median CPU mWh | Median network mWh | Median memory mWh | Median all rails mWh | Median fixture CPU ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Disabled idle | 1.238 | 1.949 | 0.866 | 6.405 | 277.837 |
| Push idle | 1.344 | 2.019 | 0.898 | 6.580 | 481.310 |
| Local idle | 1.290 | 1.960 | 0.864 | 6.454 | 369.461 |
| Push burst | 1.461 | 2.025 | 0.913 | 6.815 | 3672.232 |

Disabled-idle round 2 has a large device-wide energy outlier: CPU rail
range/median is 244%, network 48.5%, memory 72.9% and all rails 88.3% across the
five rounds, exceeding the 25% repeatability screening threshold. The fixture
uses approximately 278 ms CPU in the outlier while other application UIDs and
system processes are active. No personal app was stopped to remove that outlier.
The failed fifth burst is retained; burst CPU-rail range/median is 41.2%.
The measurements therefore cannot establish a reliable saving or app-exclusive
battery cost. No daily drain extrapolation or runtime-policy change is justified.

Push-idle and disabled-idle UID snapshots show zero foreground-service time/start
increments and zero aggregate total/background partial-wake-lock increments.
Local idle has a persistent foreground service, zero new starts and zero partial
wake-lock increments. Its service snapshot intervals include setup/teardown and
are longer than the trace; they are not exact 60-second service durations.

Each of the four successful bursts starts one foreground service. Completed Android
push wake-lock trace spans are 3028.718, 3661.404, 2418.482 and 2923.183 ms, with no
incomplete span in these traces. Aggregate UID total partial-wake-lock deltas are
4719–5891 ms and include SDK/system ownership. Batterystats' two aggregate partial
fields mean total and background duration, not duration and acquisition count.
These success observations do not qualify failed recovery cleanup.

## Delivery and failed attempts

Burst posting counts by round are **5, 5, 5, 5, 0**. Android notification records
are scoped to the disposable user, matched to fresh fixture bodies and checked
for record update timestamps within the capture deadline. They prove posting,
not listener callback counts or unique native persistence. The four successful
bursts also have aggregate high-priority FCM and push-service/catch-up phases.
One coalesced callback can recover five messages.

In round 5 the sender independently shows all five bodies sent. The recipient has
no observed recovery phases or service start within the window. Android is in
natural deep idle; waking the screen alone still does not post these bodies.
After explicit foreground opening, an independent traversal of the native message
ID selectors finds every one of the 36 fixture bodies sent through this stage,
including each of the five delayed bodies once. History traversal reaches the
oldest fixture seed. This proves eventual timeline delivery after foreground
opening; it does not repair the missed background posting deadline. Native
account databases are encrypted, so this is not a raw SQL persistence count.
The delay is not localized to FCM, the gateway or native processing; callback
absence cannot be concluded solely from the available phase diagnostics.

A later autonomous five-round reconnect attempt stops at its first collector
invocation. Collector PID/error output is empty, the completion marker is absent,
and no usable reconnect trace is produced. All five sender bodies are retained as
queued attempts, not five executed reconnect measurements. USB recovery verifies
Wi-Fi enabled, airplane mode disabled and the test's shell partial wake lock
released. The worker failure does not establish an application recovery defect.

## Separate USB functional checks

Both Push and Local enter the stopped-package state after fixture force-stop.
After explicit reopen, that state clears and each posts a fresh message within
60 seconds. Push also posts a fresh message after notification permission is
revoked and restored. Permission-loss settings were captured, but truthful loss
UI and suppression behavior are not qualified by this restore-only check.
These checks run while USB powered and do not qualify unplugged energy or Doze.

Final independent native message-ID traversal after foreground opening finds
**44 of 44** distinct fixture bodies with **zero distinct duplicate IDs**, including
the five queued reconnect bodies and three additional functional-test bodies.
It reaches the oldest fixture seed. Eventual foreground delivery does not
qualify the failed background deadline or the unmeasured reconnect windows.

## Remaining acceptance

The [device matrix](../battery-device-matrix.md) still requires a dedicated
GrapheneOS fixture, optimized and unrestricted policy coverage, ordinary process
death, forced Doze, valid reconnect failure/catch-up energy captures, transition
rollback, capability loss/recovery and failed-recovery resource ownership.
`am kill` on the cached stock-Pixel Push fixture leaves its process present; this
attempt does not qualify ordinary process death. USB functional checks are
recorded separately from actual unplugged resource measurements.

Reviewed budget provenance and a comparable before/after attribution campaign are
also pending. This partial, failed observation export is not input satisfying
`scripts/background_delivery_report.py`'s five-scenario acceptance schema.
Neither green CI nor this report closes either tracker.

Method references: [Perfetto trace configuration](https://perfetto.dev/docs/reference/trace-config-proto)
and [battery/power rail data sources](https://perfetto.dev/docs/data-sources/battery-counters).
