# Attachment 32 MiB fixture qualification — 2026-10-02

Source: master `e82cf1ec009bd3d1afecf72b48585196bcec2742` plus the two-line fixture overlay in this PR. MDK: `122bd90ffac60bb6311346e228d0f609a18521ee`; arm64 native SHA-256: `073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`. Test source SHA-256: `1435576a2271368c73e70ddd450216178c5878cff0e93fe3e709d6d6818cfe5e`.

The held outgoing copy uses the shipping 64 MiB entry cap. Its inherited generic 16 MiB cap previously rejected the generated 32 MiB file before the encrypted-write hook. Production cache configuration is unchanged.

## Method and results

Fresh isolated `.medialatency` APKs run on owned API 30 arm64 and API 36 arm64 emulators. The opt-in probe uses `allowControlledAttachmentProbe=true`, `fixtureUseAndroidSendController=true` and `fixtureCompareLargeLocalReads=true` with the loopback fixture blob server/relay. Genuine Android sending, native availability and streaming SHA-256 readback must finish while the encrypted host copy remains held; after release the host copy is independently verified. Received reads alternate 256 KiB / 1 MiB / 1 MiB / 256 KiB (ABBA), verify exact bytes and require 128 / 32 / 32 / 128 native calls.

| Flavor | Own read while host copy held (ms) | Retained 256 KiB / 1 MiB medians (ms) | Cold receive (ms) | Cold Java / native peak (MiB) |
| --- | ---: | ---: | ---: | ---: |
| Play | 894.30 | 2604.70 / 704.29 | 2652.63 | 71.97 / 190.24 |
| Zapstore | 796.44 | 2862.78 / 772.62 | 3429.91 | 87.61 / 197.96 |

Both large sessions pass: each has one upload, one GET, zero HEAD and 33,554,448 ciphertext bytes in each direction, with no repeat acquisition. The 10-second held-copy deadline is a functional assertion, not a production SLO. The medians compare local read chunk sizes, not cold download throughput.

Both flavors also pass genuine send/process restart and held HTTP cancellation (four API 30 sessions), including exact retained bytes, native cancellation acknowledgement, socket closure and 30 seconds quiet. Existing small-file latency/memory/cancellation budgets remain unchanged and all pass. Both flavors pass RTL/200% font/48 dp semantics and actual external-opener cancellation with paused cleanup (four API 36 cases). These are Android checks, distinct from the [earlier host-only follow-up evidence](attachment-sender-followup-qualification-2026-10-02.md).

## APK SHA-256

| Artifact | SHA-256 |
| --- | --- |
| Play-app | `e2f0a94a4b79dc3b765e26d75d7da80593ce1f302f80b5b257cdb63849fe6246` |
| Play-test | `a667b0b15dfff48795e3efa5ad4880926fb45280f0643c20305ef1f0b4d348f9` |
| Zapstore-app | `74fa912d63f80178657e3839276a0c59bea750a31376dc54e64d2fede018d37a` |
| Zapstore-test | `046063e87081da6be87f6dfc96fd246d2bf7b3d84563b382a9e0cba2277febd2` |

## Failures and limits

The preserved v5 cohort (source `b7bf5ff37542d99f5b32a3b078e571406b84b39a`) includes the runner's obsolete test-APK-path failure before execution and both failed 32 MiB held-copy probes. Aligning the test cache entry cap made both pass in v6 with byte-identical production APKs. This v7 cohort repeats qualification on newer master; it does not relabel the v5/v6 results. Raw reports, ledgers, APKs, source overlays and SHA-256 manifests remain in durable private evidence directories `planning/wn-2779-large-file-qualification-20261002-v5`, `...-v6` and `...-v7`; no public archive payload was expanded.

Cold native memory reaches 197.96 MiB. Representative large-file memory/throughput, physical sender visibility/ordering acceptance, full MED-009/017/024/025 manual flows, >64 MiB files and Android job interruption/Range remain unqualified. These results do not close #2779 or its children.
