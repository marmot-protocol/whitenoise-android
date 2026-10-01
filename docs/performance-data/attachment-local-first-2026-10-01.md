# Canonical local-before-network comparison — 2026-10-01

Refs #2779/#2785/#2909. Behavior draft #2980 depends on [foundation #2978](https://github.com/marmot-protocol/whitenoise-android/pull/2978), which must land first. Both target master for the required CI matrix. See the [baseline report](attachment-fixture-baseline-2026-10-01.md) for common fixture method, predeclared ceilings, host-only evidence and deferred native/device outcomes, and the single [archive manifest](attachment-evidence-manifest-2026-10-01.md) for original raw sessions, checks and failures.

## Behavior and comparison method

The resolver now checks canonical native retained bytes before Android acquisition admission. Previously an Android cache miss waited behind three unrelated held downloads before that lookup. The saturated-gate negative control fails on unchanged baseline source with the declared five-second deadlock guard; the candidate passes while all three permits remain held, with zero explicit demand/new producer calls. The baseline overlay was restored. This proves admission independence at the Android/native boundary, not a production five-second timing claim or HTTP throughput gain. Caller-owned native leases and true-miss controls remain unchanged.

Each packaged-native session has one exact 1 KiB cold received read, ten reads with Android caches empty, then one native database reopen read under a 503 acquisition endpoint. The primary sequence is candidate1, baseline4, candidate2, baseline5, candidate3, baseline6 within each flavor. Foundation baseline1–3 calibrates budgets and is not pooled. API30 arm64: Play emulator-5554 / universal APK; Zapstore emulator-5556 (`wn_2779_fixture_api30`, Pixel 2, 2 GiB RAM, 14 GiB data) / arm64 APK. Each source within a flavor uses the same emulator/packaging, loopback owned adb mappings, no shaping, fresh private generated identities and an independent durable host ledger. Other builds were active. APK replacement was in place; personal-device apps/data untouched.

Measured baseline source `4510a677cf1932657fc318866a516dc1e5b46271`; candidate source/test `8909bf418870cad7793a876545e4c288d35e15f0`. Both use MarmotKit 0.11.0 / MDK `946e0547485c9a2c393c2048ec3a968fd50fb441`, library archive SHA-256 `5ab8bf5f7723ed2c07d15a07c5691d05eb8a37354882020c0b69b952d6418923`. Rebase/doc updates preserve the measured production resolver blob `35a1770cd3d73594862b2146d1f1901bfe25f9c04713efc950d54b458e2e634b`; subsequent foundation probe wrapping only changes formatting. Archived per-flavor provenance retains measured probe digest and exact APK digests below.

| Flavor / source | App APK SHA-256 | Instrumentation APK SHA-256 |
| --- | --- | --- |
| Play / baseline | `788a7263272d8904940696c4945185b0125f468dc5d2446d9457f596d81d51b6` | `626fc61f0d9ea5ecf1489e8b53a1510e0caf234f64274727e9eda77d1a90af42` |
| Play / candidate | `283819d6222b16e00335e6279375091d3752251aa98179cbb803ca50605ac346` | `4b64d23225121807610a6f8031e2a8b989bccf4f029f19fe7f354d61d7c18e8f` |
| Zapstore / baseline | `e42221b5320453da3fc7e85689d502d6b2fa68b96c615305f9ef97265d4821f1` | `680ea29531aca24a26b369ed7664099d4fb51a212c16bfe7a2e613b933a36566` |
| Zapstore / candidate | `b3636867346492859690433fb2a1abed03e077763bd93d8d5c0adf72cc449a80` | `680ea29531aca24a26b369ed7664099d4fb51a212c16bfe7a2e613b933a36566` |

Two `INSTALL_FAILED_INSUFFICIENT_STORAGE` attempts stopped Zapstore in-place replacement on emulator-5554. Three partial sessions (universal candidate/baseline and arm64 candidate) remain archived, excluded from primary results even though their HTTP assertions passed. Recovery created the new owned emulator without clearing/uninstalling any app. The archive also preserves the baseline-only saturated regression timeout.

## Android paired results

| Flavor / source / phase | n | Median ms | p95 ms | Max ms | Java peak bytes | Native peak bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Play / baseline / Cold | 3 | 285.82 | — | 286.00 | 9546632 | 61662992 |
| Play / baseline / Retained | 30 | 4.82 | 9.30 | 11.09 | 10788816 | 66354704 |
| Play / baseline / Native reopen | 3 | 7.32 | — | 10.53 | 13129376 | 104813872 |
| Play / candidate / Cold | 3 | 286.61 | — | 289.98 | 9425384 | 61651168 |
| Play / candidate / Retained | 30 | 3.05 | 5.74 | 6.73 | 10326960 | 61736672 |
| Play / candidate / Native reopen | 3 | 5.05 | — | 5.46 | 12601488 | 91641712 |
| Zapstore / baseline / Cold | 3 | 282.25 | — | 287.03 | 8581904 | 61436240 |
| Zapstore / baseline / Retained | 30 | 4.55 | 7.67 | 8.25 | 9862584 | 66168416 |
| Zapstore / baseline / Native reopen | 3 | 6.54 | — | 7.72 | 12289568 | 104618704 |
| Zapstore / candidate / Cold | 3 | 280.31 | — | 297.51 | 8645032 | 61413696 |
| Zapstore / candidate / Retained | 30 | 3.22 | 5.02 | 5.06 | 9579048 | 66109712 |
| Zapstore / candidate / Native reopen | 3 | 3.74 | — | 5.38 | 11972776 | 104331840 |

All 12 primary sessions pass exact bytes and predeclared ceilings: each has 1 upload, 1 GET, 0 HEAD, 1040 uploaded/downloaded ciphertext bytes and completed GET; all 11 subsequent canonical reads add zero requests/bytes. The reusable foundation checker independently passes these archived samples. Native outgoing canonical availability is false throughout.

Cold/reopen n=3 per source/flavor: no p95. Retained n=30 with within-session correlation and nearest-rank p95. Memory is 10 ms sampled absolute Java/native heap plus initial/final samples; latency includes sampler lifecycle. Socket-write counts exclude headers/error JSON/TCP retransmits/link traffic.

**Baseline already avoided repeat transfers.** This change removes unnecessary gate waiting; it demonstrates neither newly saved bandwidth nor a phase-specific cold-throughput improvement. Native database reopen is not app process restart, whole-device offline, navigation or external handoff qualification.

## Validation and landing boundaries

The measured candidate passed 69 focused tests in each distribution (resolver/native chunks/leases/cache races/saturated host/Open/APK/cancellation), using 2 GiB workers. The negative control failed as expected; no baseline fix. Both measured APK/test variants built and 12 paired emulator sessions passed. Manual guide validator and 33 tests passed; MED-009/MED-017 companion acceptance remains unchecked. Reviewed-head hosted fixture jobs passed both distributions. Follow-up rebase/gate evidence belongs to the PR; new-head CI, substantive review and meaningful 80% CodeRabbit docstring confirmation remain required. Rate-limited SUCCESS is not review evidence. Visual changes: none.

Production code, fixtures and existing regression tests are unchanged by this report compaction. Genuine outgoing retention stays blocked on [MDK #2135](https://github.com/marmot-protocol/mdk/issues/2135); join/promotion stays held on [MDK #2134](https://github.com/marmot-protocol/mdk/issues/2134) until adopted retry budgets and tapped-file priority are preserved together. Both prerequisites are now owned by `mubarakcoded`. Android #2936 remains open; Datawav #2973 is already included. Datawav ownership is preserved and Danny's MDK #2106 is untouched.

Full lifecycle/offline/device/Open/Save/installer/large native-file qualification, genuine outgoing controller retention, protected-worker behavior, shared progress and production resume remain deferred as described in the baseline. Keep drafts until all required landing gates pass. No issue closure, merge or release; full attachment reliability work remains unfinished.
