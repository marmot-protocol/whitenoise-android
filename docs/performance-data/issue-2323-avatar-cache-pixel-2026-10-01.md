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
