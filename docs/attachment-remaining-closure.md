# Remaining attachment closure work

Scope: [#2045](https://github.com/marmot-protocol/whitenoise-android/issues/2045),
[#2909](https://github.com/marmot-protocol/whitenoise-android/issues/2909),
[#2781](https://github.com/marmot-protocol/whitenoise-android/issues/2781),
[#2785](https://github.com/marmot-protocol/whitenoise-android/issues/2785) and
[#2878](https://github.com/marmot-protocol/whitenoise-android/issues/2878).
Their live issue acceptance criteria are authoritative. This document records
implementation order and proof boundaries, not new or reduced closure criteria.

Starting source: Android `0a0e7fc957a9883dfd2deecbd60b2641db101c90`, MDK
`122bd90ffac60bb6311346e228d0f609a18521ee`. Earlier attachment PRs are merged.
Current source already uses user-initiated jobs for visible taps on API 34+,
foreground WorkManager on older devices, native atomic demand and genuine outgoing
retention. Validate those implementations before adding replacements.

## Delivery order

1. Extend generated-peer fixtures to expose remaining failures reproducibly.
   Distinguish native transport interruption, observer detach, Android scheduler
   stop and process death. A successful Range fixture alone qualifies only the
   transport case. Preserve every failed and partial run.
2. Complete known/unknown-length phase and cancellation UI qualification. Cover
   all transfer surfaces, concurrent Open/Save, queued/active cancellation, stale
   callbacks and navigation, localized semantics, 48 dp controls, narrow/RTL
   layouts and large fonts. Keep MED-024/MED-025 manual outcomes separate.
3. Qualify genuine sent/received images, videos and albums across chat return,
   backgrounding, Activity recreation and process restart with automatic downloads
   off and acquisition unavailable. Assert exact retained bytes, zero HTTP,
   first-frame continuity and one-tap playback. Cover presentation cache ceilings,
   held/failed host publication, account isolation, stale work, corruption and
   legitimate deletion. Do not seed fake outgoing retention.
4. Drive verified received APKs to the actual Android installer on permitted
   distributions. Cover invalid packages, permission denied/granted, disabled
   policy, no installer, cancellation/retry, process recreation and Save after an
   installation block. Separate transfer completion from platform dispatch.
5. Execute real scheduler/background/lock and process-interruption cases. Assert
   elevated interactive versus ordinary automatic execution, privacy-safe stop
   markers, compatible committed-prefix resume, incompatible-partial recovery,
   policy/pause/cancel/backlog preservation and no duplicate acquisition owners.
6. Capture representative performance baseline and numeric phase/memory budgets
   before optimization. Use small, medium and near-limit files, send/receive,
   warm/cold caches, foreground/recreated processes and verified Wi-Fi/constrained
   conditions. Count HTTP requests, body bytes, retries and failures independently;
   measure preparation, first progress, completion, verification, materialization,
   preview and dispatch plus sampled Java/native peaks. Repeat identical cohorts
   at candidate heads and require gains beyond run variability. Emulator transport
   pacing does not establish physical Wi-Fi performance or universal SLOs.

## Closure evidence

| Issue | Required remaining proof |
| --- | --- |
| #2045 | Genuine phase/unknown-length/resume/cancel integration plus all changed UI/control semantics and manual flows |
| #2909 | Sent/received image/video/album lifecycle, first frame, local playback and zero-request parity; bounded resources and valid cleanup |
| #2781 | Complete received APK verification and installer/recovery behavior, including exact-head physical-device coverage |
| #2785 | Controlled representative baseline and targets; attributed and repeated improvements with unchanged correctness and bandwidth |
| #2878 | Actual Android job interruption/background continuation and committed-prefix resume; stop diagnostics and preserved network policy |

Use owned isolated emulators wherever they can exercise the behavior. Physical
acceptance and representative performance remain mandatory where the issue says
so; keep personal app data, use isolated generated accounts and in-place installs.
Never substitute native-only probes for complete app UI or physical coverage.

Keep drafts until required changed-path tests, current-head CI, self/review work,
substantive CodeRabbit review and useful docstring coverage at least 80% pass.
UI changes require inspected tracked screenshot baselines and accessible current
PR screenshots. Reviewable PRs may use Closes only after every corresponding
criterion passes; otherwise keep Refs and list the exact missing proof. Do not
merge or release. After landing, post closure proof and read back issue, owner and
Project 7 state. Tracker #2779 and separate #1499/#2284/#2491/#2556 work remain open
until their own required scope is complete; include only verified blocking native
prerequisites in this five-issue effort.
