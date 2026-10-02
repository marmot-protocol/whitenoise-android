# Pixel attachment fixture qualification — 2026-10-01

Supplemental physical evidence for #2978/#2980 under tracker #2779. This qualifies small received-file access and native-runtime reopening; it does not close the broader attachment issues. Raw sessions and exact digests are in the single [evidence archive manifest](attachment-evidence-manifest-2026-10-01.md), under `pixel-2026-10-01/`.

## Method and provenance

Pixel 9 Pro XL, API 37, arm64-v8a; generated loopback relay/HTTP over owned adb reverse mappings, no shaping or network toggles. Isolated package `dev.ipf.whitenoise.android.medialatency`; only user 0 app/test APKs were installed in place. The target app was absent on both device users initially; the existing isolated instrumentation package had a fresh readable private data backup and previous APK retained. Signatures matched. No personal-package install, uninstall, clear, recovery or real-account action was issued by this test process. Direct `am instrument` avoids AGP's connected-test uninstall behavior; both diagnostic APKs remain installed. All owned reverse mappings were removed.

Measured baseline source `e8fca8e521737ba06eb527577efe3b55c9c43839`; candidate source `193bb591a35431844b76f89253135599abe1303f`. Both use unchanged MarmotKit 0.11.0 / MDK `946e0547485c9a2c393c2048ec3a968fd50fb441`, library archive SHA-256 `5ab8bf5f7723ed2c07d15a07c5691d05eb8a37354882020c0b69b952d6418923`, packaged arm64 native SHA-256 `db12b60dabd3981922132ca15b1d109dabc64da648e1365a15a9c83ea9a93dba`. Probe SHA-256 `b046d52ca58409c5afae22c7ad5875570272e68bf04b97b944826c404d127ae6`. Production code, probe, fixtures and existing regression tests are unchanged for these measurements.

| Flavor / source | App APK SHA-256 | Instrumentation APK SHA-256 |
| --- | --- | --- |
| Play / baseline | `83576384fcd05c91b6572a595c728e0da7649d511ba9e65606d8f65e80949b4b` | `dac498fae1708e9a0f16f948903c97e39713fc907d78e955137adf4cf5944582` |
| Play / candidate | `47d52039ffa76d0870179b49091c113aa65031aea6f6d58b476ad5b3cc93ba0a` | `b7fae6557bafb897e84de4cce2c1e9b4e0858c118aed94fd0ade449e65af4c11` |
| Zapstore / baseline | `e42221b5320453da3fc7e85689d502d6b2fa68b96c615305f9ef97265d4821f1` | `5d6cb5196eb2b80b27e085e5e7e41f12b0136fdae94e54d407566c436bb40dda` |
| Zapstore / candidate | `b3636867346492859690433fb2a1abed03e077763bd93d8d5c0adf72cc449a80` | `5d6cb5196eb2b80b27e085e5e7e41f12b0136fdae94e54d407566c436bb40dda` |

A separately reviewed private Pixel host driver checks the exact serial/model/API/ABI and verifies installed app/test APK SHA-256 before each session. The unchanged checked-in runner remains emulator-only. Physical budget checks reuse **only the performance ceilings** from `reference-api30-arm64`: cold 1500 ms, retained/reopen 150 ms, Java 32 MiB, native 128 MiB. This does not assert that API 37 matches the emulator profile or establish calibrated Pixel production SLOs. Every sample, exact 1/10/1 phase counts, literal success and finite metrics must pass; the independent ledger must prove complete upload/GET, one acquisition, 1040 bytes and denied later acquisition.

Per flavor, order is baseline1,candidate1,baseline2,candidate2,baseline3,candidate3; Play then Zapstore. Every APK is arm64-only. A session uses fresh generated peers to send/receive a real 1 KiB TXT, then reads exact canonical bytes through the production Android resolver. After cold completion HTTP acquisition returns 503 while continuing to count requests. Ten reads assert empty Android memory/disk caches; a final read reopens the native database at the same path. No generated message row or retained asset is substituted.

## Results

| Flavor / source / phase | n | Median ms | p95 ms | Max ms | Java peak bytes | Native peak bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Play / baseline / Cold | 3 | 576.34 | — | 586.27 | 12829408 | 64345872 |
| Play / baseline / Retained | 30 | 82.40 | 103.16 | 109.55 | 7697120 | 64542608 |
| Play / baseline / Native reopen | 3 | 103.52 | — | 104.32 | 9859808 | 107327232 |
| Play / candidate / Cold | 3 | 576.60 | — | 612.90 | 12862176 | 64386784 |
| Play / candidate / Retained | 30 | 49.76 | 61.98 | 63.61 | 6640352 | 64498128 |
| Play / candidate / Native reopen | 3 | 85.73 | — | 89.53 | 8803040 | 107476160 |
| Zapstore / baseline / Cold | 3 | 577.46 | — | 606.74 | 12890848 | 64397872 |
| Zapstore / baseline / Retained | 30 | 78.30 | 109.41 | 110.49 | 7369440 | 64634240 |
| Zapstore / baseline / Native reopen | 3 | 59.15 | — | 73.79 | 9622240 | 94688656 |
| Zapstore / candidate / Cold | 3 | 613.76 | — | 615.93 | 12825312 | 64362080 |
| Zapstore / candidate / Retained | 30 | 54.79 | 69.25 | 70.70 | 6709984 | 64492272 |
| Zapstore / candidate / Native reopen | 3 | 56.31 | — | 60.73 | 8844000 | 107461424 |

All 12 sessions pass: one upload, one GET, zero HEAD, 1040 uploaded/downloaded ciphertext bytes, complete terminal ledger events and zero additional acquisitions for all 11 local reads. Each original performance ceiling passes. Genuine outgoing canonical availability is false in all 12 sessions. No failed or partial physical measurement was omitted; all 12 attempted sessions completed.

## Limits and remaining gates

The post-run whole-device-unchanged check **failed**: before/after system package snapshots show changes to unrelated White Noise packages during the window. The device was **not exclusive**. This process's install logs and per-session APK hashes show only isolated measurement app/test installs. Whole-device unchanged state cannot be claimed, and concurrent activity limits latency comparison. Baseline already avoids repeated HTTP; neither newly saved bandwidth nor cold-throughput improvement is demonstrated.

Cold/reopen have 3 observations per source/flavor, so no p95; retained has 30 correlated samples, nearest-rank p95. Memory is absolute Java/native heap sampled every 10 ms plus initial/final samples, not allocation deltas or guaranteed maxima. Latency includes the sampler lifecycle; socket-write bytes exclude headers/error JSON/link traffic.

Physical app-process restart, whole-device offline, saturation with unrelated real downloads, navigation/Open/Save/installer handoff, large native incoming assets, actual outgoing/send-controller retention and the complete physical lifecycle matrix remain unqualified. The saturated-gate baseline negative control/candidate success is separate automated Android-boundary evidence from #2980; it was not run under real download saturation on this Pixel. MED-009/MED-017 manual outcomes stay unchecked.

Exact measured heads had green hosted CI at qualification time; substantive CodeRabbit review and meaningful 80% docstring confirmation remain outstanding. New documentation heads require their own applicable checks. MDK #2134/#2135 dependencies and Datawav/Danny ownership boundaries remain unchanged. No issue closure, merge or release.
