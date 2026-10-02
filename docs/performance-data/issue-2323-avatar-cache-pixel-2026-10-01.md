# Avatar cache scroll measurement (#2323)

## Fixture and provenance

- Device: stock Pixel 9 Pro XL, Android 17 (`CP3A.260905.009`), 360 dpi, 256 MiB Java heap class. The issue requested GrapheneOS; the user explicitly selected the available Pixel instead.
- Source base: Android `17a7e766` plus measurement commit `d778d01b2c91a9f705c96bfafd66ed3f1da9b3b1`. The installed isolated preview app APK SHA-256 is `a8c6a1603b65922aec04ba269ca0a2772967b45d5c3bb70bbdb5080b772cb247`; instrumentation APK SHA-256 is `96f092eeea653de02a358333c6c29a1aa1cf27c169524ba5b196ccc0952ec0e5`.
- The fixture reuses one synthetic 512 × 512 ARGB_8888 PNG under 48 distinct avatar keys through `Avatar` and the encrypted-group compatibility loader. Each decoded image is approximately 1 MiB. Each cache has a 16 MiB byte budget. The row avatar is 52 dp. No personal identities, images, URLs or account data enter the fixture.
- One cold downward traversal (24 interior swipes), one reverse traversal (5 swipes), and one repeated downward traversal (24 swipes). The test samples Android `Window` frame metrics and the process memory after each pass. The reverse requires fewer swipes because list movement per gesture is asymmetric; frame distributions should not be compared as matched-duration experiments.
- The test APK was installed in place into the isolated `dev.ipf.whitenoise.android.preview.pr2323` package. The device's existing White Noise packages and data were untouched. The exact-head test passed on the physical Pixel. One preceding run reached the third pass but failed with an Espresso idle timeout; the full rerun passed, so fixture idling is a known source of noise.

## Measurements

| Pass | Profile hit / miss / eviction / fetch / decode | Group hit / miss / eviction / fetch / decode | Frames, median / p95 / max | PSS KiB | Native heap bytes |
| --- | --- | --- | --- | ---: | ---: |
| Cold down | 34 / 68 / 32 / 34 / 34 | 34 / 102 / 32 / 34 / 34 | 456, 6 / 10 / 154 ms | 402,167 | 50,685,392 |
| Reverse up | 34 / 64 / 32 / 32 / 32 | 36 / 96 / 32 / 32 / 32 | 19, 34 / 116 / 123 ms | 478,795 | 119,671,632 |
| Repeat down | 33 / 64 / 32 / 32 / 32 | 34 / 96 / 32 / 32 / 32 | 454, 8 / 17 / 114 ms | 365,735 | 61,439,376 |

In-flight deduplication was zero in each pass. Sequential fixture loads do not exercise concurrent requests. The extra group misses include its first-frame `peek` and later `load` probes; a miss count is a cache lookup count, not a unique image count. The `fetch` counter is an Android-to-MDK adapter invocation in the production loader, replaced here with a synthetic byte supplier. It is **not** an HTTP request count. Native durable selected avatars use a different MDK-backed read path and are not represented by this fixture.

## Decision

The 16 MiB presentation caches demonstrably evict 512 px images and invoke their adapters again during this worst-case scroll. This alone does not establish network traffic or battery cost: MDK can satisfy reads locally, and a synthetic debug build is not a representative chat-list workload. The short reverse pass had high frame time and transient process memory, but it also rendered a different number of frames over a different duration; the result needs a release-like, matched traversal before attributing jank to cache policy.

Keep the current cache and decode bounds pending a second experiment on selected native avatars and real chat-list presentation. A global reduction from 512 px would also affect the 152 dp person-profile avatar and notification icons; a list-specific tier would need account-clear and stale-result coverage and a measured before/after comparison. The debug-only counters and deterministic fixture in this change make that decision testable without adding an Android-owned persistent cache.

## Reproduce

Build the isolated preview app and test APK with `PR_NUMBER=2323 PR_PREVIEW_CHANNEL=isolated -Pwhitenoise.previewE2eTestBuild=true`, install both with `adb -s <serial> install -r`, then run `adb -s <serial> shell am instrument -w -r -e class dev.ipf.whitenoise.android.core.AvatarCacheScrollDeviceTest dev.ipf.whitenoise.android.preview.pr2323.test/androidx.test.runner.AndroidJUnitRunner`. Read the identifier-free summaries with `adb -s <serial> logcat -d -s WNAvatarBench:I`. Do not run AGP's default connected-test teardown on a personal device.

## October 2 follow-up: actual Dev list and matched synthetic controls

The October 1 fixture above used a fixed index threshold and included gestures after reaching the list boundary. Its cache counts remain evidence of eviction, but its frame and elapsed-time values are superseded by the boundary-checked runs below.

The user selected the available stock Pixel 9 Pro XL in place of GrapheneOS and authorized an aggregate-only probe of the existing Dev chat list. The Pixel ran Android 17 `CP3A.260905.009` at 360 dpi, with a 256 MiB heap class. Source was `b79fb71443aaa0cef5c57c6705aaa614d920dcd8` plus the test-only changes in this PR. The real-list Dev APK (`ba064de6b6bc08481d21b3f2bef489f9733388c5d38942d3c71433ed344a4dac`) retained the installed package, version, signing certificate, and native library. A private backup was taken before its in-place install; no app was uninstalled or cleared. The real-list test APK was `3a50b34f5b07c77bb4cdc2c778bcb55ecb6eebb885b9eede1ebc774e6dff15d6`. The opt-in test passed once. Its five swipes per direction did not assert a list endpoint, so these are observed-window measurements, not proof of full-list coverage.

| Existing Dev list pass | Profile hit / miss / eviction / fetch / decode / dedup | Group hit / miss / eviction / fetch / decode / dedup | Frames, median / p95 / max | PSS KiB | Whole-app UID RX / TX bytes |
| --- | --- | --- | --- | ---: | ---: |
| Cold down | 34 / 45 / 0 / 7 / 7 / 0 | 0 / 0 / 0 / 0 / 0 / 0 | 366, 5 / 22 / 71 ms | 393,263 | 247,433 / 9,166 |
| Reverse up | 45 / 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 / 0 | 371, 5 / 21 / 36 ms | 366,536 | 416 / 416 |
| Repeat down | 66 / 0 / 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 / 0 / 0 | 376, 5 / 19 / 40 ms | 374,756 | 0 / 0 |

The observed account did not fill either cache; seven profile adapter reads occurred on the cold pass and none on return. No group image appeared in the measured window. UID traffic covers the whole app, including relay traffic, and cannot be attributed to avatar HTTP. No content, image, URL, account ID, or chat name was logged.

The corrected isolated fixture kept the same 48 rows and touch coordinates, but used `canScrollForward`/`canScrollBackward` to stop at actual list boundaries. Every pass started at item 0 or 34 and finished at the opposite boundary. It compared 512 px images, 256 px *source* images, and empty avatar boxes. The isolated app APK SHA-256 was `542846ca2dc8d8cfd7d3175e16c8ed673e4ee7101af8369ced9d83d4cf459dd0`; the corrected test APK was `9fc4615a7af0e447fda15498de4d7a1108ad626db9ca468d3bb826e2015ac52d`. All three tests passed on the Pixel with this APK. The 256 px source models smaller decoded pixels; it is not a production loader change or a quality comparison.

| Fixture / pass | Swipes | Profile fetch / decode / eviction | Group fetch / decode / eviction | Frames, median / p95 / max | PSS KiB |
| --- | ---: | --- | --- | --- | ---: |
| 512 px cold down | 7 | 34 / 34 / 32 | 34 / 34 / 32 | 36, 8 / 163 / 237 ms | 426,852 |
| 512 px reverse up | 7 | 31 / 31 / 31 | 31 / 31 / 31 | 40, 5 / 64 / 94 ms | 439,272 |
| 512 px repeat down | 11 | 32 / 32 / 32 | 32 / 32 / 32 | 41, 6 / 59 / 63 ms | 440,344 |
| 256 px cold down | 7 | 34 / 34 / 0 | 34 / 34 / 0 | 32, 10 / 198 / 237 ms | 298,641 |
| 256 px reverse up | 7 | 0 / 0 / 0 | 0 / 0 / 0 | 35, 8 / 110 / 132 ms | 301,904 |
| 256 px repeat down | 7 | 0 / 0 / 0 | 0 / 0 / 0 | 36, 8 / 94 / 102 ms | 306,860 |
| Empty cold down | 7 | 0 / 0 / 0 | 0 / 0 / 0 | 29, 6 / 120 / 127 ms | 236,912 |
| Empty reverse up | 7 | 0 / 0 / 0 | 0 / 0 / 0 | 32, 6 / 78 / 108 ms | 232,762 |
| Empty repeat down | 7 | 0 / 0 / 0 | 0 / 0 / 0 | 29, 6 / 83 / 88 ms | 234,888 |

The 512 px repeat needed 11 gestures in the captured run, so even the boundary-checked fixture is not a matched-duration frame experiment. Each frame percentile has only 29–41 rendered frames. A second corrected 512 px run reached both boundaries in seven swipes per pass, with repeat p95 107 ms; the profile/group return-pass adapter counts were unchanged. A prior 256 px run had zero return-pass fetches yet a repeat p95 of 77 ms. The no-avatar control also had long frame tails. These observations do not isolate a frame-time penalty caused by cache eviction. The smaller source eliminated synthetic return-pass adapter work and reduced synthetic PSS, but it does not establish an HTTP or battery saving in an actual chat list. The 512 px adapter calls used a synthetic supplier; no network request was counted.

### Updated decision for #2323

Close this measurement task when the report and reproducible probes merge. The current Pixel chat list showed no return-pass adapter work or frame regression attributable to avatars; the second downward pass also recorded zero whole-app received bytes. The over-capacity fixture proves that the 512 px presentation caches churn, but its frame results vary as much as the no-avatar control, and it does not model MDK's real selected-asset mix or network behavior. A global 256 px cap could reduce quality on larger avatar surfaces; a list/detail tier adds another ownership and invalidation path. Neither production change is justified by a demonstrated user-visible or battery cost here. Reopen a focused optimization if a real over-capacity list shows repeat MDK work together with attributable frame, memory, or transfer cost.

To reproduce the new synthetic controls, run the three `AvatarCacheScrollDeviceTest` methods `overCapacityDirectTouchScroll`, `smallerSourceDirectTouchScroll`, and `directTouchScrollWithoutAvatars` separately in the isolated package, then read `WNAvatarBench`. The real-list probe runs only with `-e allowRealAvatarProbe true` in an existing authenticated Dev install; read `WNAvatarRealProbe`. Use `adb install -r` for updates and avoid `connectedAndroidTest` on a personal device unless `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` is supplied.
