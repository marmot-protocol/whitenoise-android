# Attachment review follow-up qualification — 2026-10-02

This supplements the [candidate qualification report](attachment-candidate-qualification-2026-10-02.md). It covers the Media library Retry regression, duplicate Retry failure feedback, queued cancellation, readable progress, outgoing local publication and bounded native reads. It does not complete tracker #2779 or qualify the full manual flows.

This is the earlier `5c0719897` cohort. The [sender handoff and Retry follow-up](attachment-sender-followup-qualification-2026-10-02.md) records subsequent Pixel findings and qualification separately.

## Method and results

Generated 32 MiB plaintext is genuinely sent through the shipping Android controller and published by MDK. The foreground conversation projection reconciles accepted-pending sends before the confirmed card is probed. L1 rejects this payload under its unchanged 8 MiB entry limit; the test waits for the matching encrypted host publication and hashes a lease from the shipping resolver. The server ledger requires exactly one upload and one received acquisition, each **33,554,448 ciphertext bytes** including the authentication tag, zero HEAD requests and zero additional transfers during retained reads.

On the same APK, four received native lease reads alternate **256 KiB / 1 MiB / 1 MiB / 256 KiB**. Streaming SHA-256 and length must match; read counts must be **128 / 32 / 32 / 128**. The new default equals MDK's existing 1 MiB per-call bound; cache limits are unchanged. Timings and sampled process heap peaks include copying and hashing the lease. Two samples per chunk size per flavor are observational comparisons, not p95 estimates or new production budgets.

Both matching flavors passed on the owned API 30 arm64 emulator:

| Flavor | Own encrypted publication/read ms | Received cold ms | Median old / new local-read ms | Peak Java old / new MiB | Peak native old / new MiB |
| --- | --- | --- | --- | --- | --- |
| Play | 981.5 | 2719.2 | 2743.8 / 784.1 | 92.53 / 93.16 | 76.24 / 79.21 |
| Zapstore | 936.7 | 2290.2 | 2584.8 / 708.7 | 92.34 / 93.56 | 76.23 / 79.21 |

Local-read median improved by 71–73%; the largest new native sampled peak increased by about 3 MiB. Cold-read peaks reached **76.10 MiB Java / 187.69 MiB native**. Those large-payload allocations remain a measured limitation; this comparison does not establish representative large-file throughput/memory acceptance. Earlier paired runs without the outgoing host-cache assertion also passed (about 73% faster); their ledgers and measurements remain separate. Neither cohort reproduces the complete 10–20 second Pixel UI delay.

The final shared completion gate passed both-flavor compilation, Android-test compilation, ktlint, detekt, Zapstore lint and focused Roborazzi verification. All **304 focused unit/Compose tests per flavor**, 39 host fixture tests and nine required-case-checker tests passed; screenshot ownership and the manual-case guide pass. Host tests are separate from Android measurements.

Both flavors also passed two real API 36 platform tests: RTL 200% font progress with 48 dp semantics, and the actual Android external opener while cancellation cleanup is deliberately paused, requiring zero launches. Host regression tests cover null-destination native Retry, a single failure callback, cached Open after a cancelled transfer loses to completion, terminal Cancelled copy and cancellation of queued Open before acquisition.

The unchanged small-file strict budgets remain 1,500 ms cold, 150 ms retained, 32 MiB Java and 128 MiB native; held cancellation retains 5-second acknowledgement/disconnect limits and a 30-second quiet interval. All four fresh small-file runs passed; no large-file observation overrides these budgets.

| Session | Outcome | GET / upload | Response bytes | Cold / slowest retained ms, or ack / disconnect ms |
| --- | --- | --- | --- | --- |
| Play-restart | PASS | 1 / 1 | 1040 | 277.450 / 7.470 |
| Play-cancel | PASS | 2 / 1 | 2064 | 40.314 / 58.292 |
| Zapstore-restart | PASS | 1 / 1 | 1040 | 277.156 / 8.391 |
| Zapstore-cancel | PASS | 2 / 1 | 2064 | 23.664 / 30.652 |

## Provenance and failures

- Current master: `2029f58b53554629ddbaec6a6c8e43de2cb377df`.
- APK build base: `bd549bc2463589187986449032b89f2c1e2ebd0e` plus the exact source overlay recorded in the private SHA-256 manifest. The committed source must match those overlay bytes; a later documentation commit is not the literal APK build SHA.
- Published MDK: `122bd90ffac60bb6311346e228d0f609a18521ee`; packaged arm64 native SHA-256: `073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

| Current-source fixture artifact | SHA-256 |
| --- | --- |
| Play-app | `bc9b575321118daacf47c538e8148c8413a0cc165a2e529b8130ed1cb6157db0` |
| Play-test | `f6869478810b07bf037e89cbeaef5f49292cd629987045e9f3a201b1aa350e6c` |
| Zapstore-app | `fb8c5086eb55750e8c119d0bc9ceabaa0461a6328709fc0228f217281996a9f9` |
| Zapstore-test | `11181f56232c9be25cbde452117d71cb4fb5c3277c7cb866250db1804241285c` |

Raw reports, ledgers, APKs, complete source overlays and failures remain private and durable in `planning/wn-2779-large-file-qualification-20261002-v1` through `-v4`; SHA-256 manifests preserve session provenance. No public archive expansion or raw JSON deletion is claimed.

The first attempt stopped before installation because the relocated APK path was wrong. Build/static attempts exposed missing test imports/arguments, a long-line/complexity violation, and a Kotlin compiler heap limit; corrected builds pass without weakening assertions. The first outgoing host-cache attempt failed in both flavors after one successful upload and zero downloads: its diagnostic controller never started the foreground projection, so accepted-pending cache reconciliation had not occurred. The corrected test starts the shipping subscription and waits for the real confirmed projection; both passes retain exact cache/content and HTTP assertions. These failures remain recorded, not relabelled as flakes. An initial focused APK snapshot expectation still said “Opening”; the intended “Preparing attachment” state and baseline were then updated. Sandbox-only host fixture failures from denied loopback access are distinct from the passing authorized host runs.

Full MED-009/017/024/025 manual flows, TalkBack acceptance, production Pixel reproduction, large APK opening, >64 MiB files, account lifecycle invalidation, worker interruption/Range and representative throughput/memory remain pending. The [manual guide](../manual-release-testing.md) records those cases explicitly. No issue closures or full-plan completion claims apply.
