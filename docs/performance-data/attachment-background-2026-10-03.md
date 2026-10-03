# Actual Android background attachment continuation — 2026-10-03

A genuine generated-peer 4 MiB attachment is received through the shipping
Android resolver and scheduler. Hold its native ciphertext at 2 MiB, confirm the
actual foreground WorkManager service on API30 or user-initiated JobService on
API36, put a real Activity behind Home, and release a separately paced suffix.
After 30 seconds the Activity must remain stopped, both the transfer and scheduler
must remain active, and the independent HTTP ledger must contain bytes written at
least 25 seconds after background entry. Then require exact plaintext and retired
interactive intent. This is functional qualification, not a performance baseline.

| API / flavor | Actual execution | Behind Home (ms) | Body bytes behind Home | Overall (ms) | Java / native sampled peak (MiB) |
| --- | --- | ---: | ---: | ---: | ---: |
| 36 / Play | user-initiated-job | 30433 | 1933312 | 36241.2 | 22.38 / 101.13 |
| 36 / Zapstore | user-initiated-job | 30407 | 1933312 | 36088.2 | 19.74 / 101.08 |
| 30 / Play | foreground-work | 30830 | 1933312 | 36147.8 | 21.60 / 108.38 |
| 30 / Zapstore | foreground-work | 30798 | 1933312 | 36182.4 | 21.89 / 108.81 |

Each case passed with one upload, one GET, zero HEAD and 4,194,320 ciphertext
bytes in each direction. The remaining suffix completes after the background
interval without another owner or request. Native atomic verification and exact
readback pass. These tests do not drive full chat Open/Save or manual MED flows.

## Provenance and preserved attempts

Production/test source: base `09b64a499128e319b7225d017bc5b65bf769aae3`
(tree identical to fixture PR head `42d3b79acd5fe32ab40576b2a2ae25f699a2345b`)
plus the frozen overlay whose source-manifest SHA-256 is
`568c2fb8b52a90ee7d81d08a3732c058287d35cd7298a7e175c4b36e7379b81a`.
APK labels identify that base plus overlay; these are not later-head rebuilt APKs.
MDK pin `122bd90ffac60bb6311346e228d0f609a18521ee`; arm64 native SHA-256
`073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

| APK | SHA-256 |
| --- | --- |
| Play-app | `5f8d5871176d9f7cb7c5166bacf9b50e38489633bdafbed2651f3b18667fa9e9` |
| Play-test | `47c6c930b7238de6adca34d11ec98e060dcce28e2466c58a757a0a29bf26737e` |
| Zapstore-app | `ba718cc5eb9b818d9aa35d37a37b65e00f636e3c224c4e5062fb91e97718bced` |
| Zapstore-test | `0d73e2187c92136eb67c3beb7de420462df1c5f7b55d6f16f8dd6b738d0c8e5e` |

Durable private roots `planning/wn-five-closure-20261003/platform-background-attempt1`
and `platform-background-attempt2` retain original source overlays, APKs, ledgers,
raw instrumentation, failed style checks and all results. Their non-build evidence
manifests contain 85 and 43 verified SHA-256 entries; APK digests are separate.
The first cohort completed while behind Home but releasing its hold also bypassed
pacing: it **does not qualify sustained active transfer for 30 seconds**. A host
regression now prevents that shortcut. The second cohort is the table above.
The first build's Android-test formatting failure and subsequent passing build
are preserved; no failures are deleted or relabelled.

## Remaining closure work

The subsequent [screen-lock cohort](attachment-screen-lock-2026-10-03.md)
qualifies sustained keyguard continuation and repeats these background cases with
the final fixture APKs. Actual scheduler-stop/process-death resume, ordinary
automatic-job interruption and network-policy/cancel/backlog integration still
require separate evidence. Full MED-009/MED-017/MED-024/MED-025, representative physical
performance, image/video/album lifecycle and actual APK installation remain open.
No physical device was touched. Existing 1 KiB performance budgets are unchanged;
4 MiB sampled observations here do not qualify throughput or bounded memory.
