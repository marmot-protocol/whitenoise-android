# Packaged-native transport resume qualification

Refs #2045 and #2878 under #2779. This checks transport interruption through the
shipping Android resolver, not an Android scheduler stop, process restart or full
issue closure. No production code or existing performance ceilings change.

## Method and result

Owned API30 arm64 emulator, isolated `.medialatency` identities, generated native
peers and the genuine Android send controller. Upload 4,194,304 plaintext bytes;
hold the received 4,194,320-byte ciphertext at 2,097,152 bytes, then disconnect.
Do not issue a deliberate Retry. Require Range at the held prefix and the strong
If-Range comparison. Compatible retry returns HTTP 206 with the remainder; changed
validator returns HTTP 200 with a complete replacement. Partial ciphertext must
not yield a plaintext lease. Final bytes and three retained reads must match;
acquisition is unavailable during those retained reads.

| Flavor/scenario | Uploads / GETs / HEADs | Ciphertext written | Overall ms | Java / native peak MiB | Outcome |
| --- | --- | ---: | ---: | ---: | --- |
| Play / compatible | 1 / 2 / 0 | 4,194,320 | 28,280.51 | 27.61 / 90.26 | Pass |
| Play / changed validator | 1 / 2 / 0 | 6,291,472 | 28,328.78 | 27.90 / 87.04 | Pass |
| Zapstore / compatible | 1 / 2 / 0 | 4,194,320 | 27,554.59 | 29.10 / 90.15 | Pass |
| Zapstore / changed validator | 1 / 2 / 0 | 6,291,472 | 28,191.18 | 27.30 / 88.17 | Pass |

Each case has an independently committed interrupted-request disconnect and
replacement completion. Overall time includes waiting for native retry; it is
not uninterrupted throughput or a performance target. One sample per case and
10 ms heap sampling cannot establish a p95 or capture every allocation spike.
Representative large-file performance remains unqualified. The unchanged 1 KiB
latency/memory budgets are inapplicable to these 4 MiB functional probes.

## Failures and provenance

The first Play compatible probe failed its two-GET assertion: the incomplete
fixture served the published bare hash but returned 404 for MDK's `.bin` group
fallback. Native subsequently resumed the original locator correctly. Preserve
that failed report, ledger and error transcript. Resume mode now publishes and
serves the canonical `.bin` locator, matching the native fallback so candidates
deduplicate; default small-file upload descriptors stay unchanged. A later host
suite failed four cases because an existing factory test seam rejected the new
keyword on ordinary runs. Preserve that log; the default constructor call is
restored, and all 46 host fixture tests pass.

APK source is Android `0a0e7fc957a9883dfd2deecbd60b2641db101c90` plus the frozen
three-file instrumentation overlay. It is not an APK rebuilt with the eventual
commit's `GIT_COMMIT` label. MDK is `122bd90ffac60bb6311346e228d0f609a18521ee`;
arm64 native SHA-256 is
`073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

| Artifact | SHA-256 |
| --- | --- |
| Play app | `3a8b38cb3d55c9971fff61e33b4b142c9e3954214addb418db45be9006ce0724` |
| Play test | `cb1f258103fcbab49e6284a6c32a65e7d30f8e57cbb96477310cc14fda7ce1b4` |
| Zapstore app | `2861e640d276ba6f8fcefdbd612df20335df37d1b7d4b88389fcdd1734cf5471` |
| Zapstore test | `037623e97ecc19e1694161cf58b238fb482bfca2c533c117e1bac67e6769159c` |

Private durable root:
`planning/wn-five-closure-20261003/resume-attempt1`, including failed/passing
sessions, SQLite ledgers, APK manifests, frozen sources and gate logs. Raw
transcripts/databases are private; this report and reusable tests are the public
evidence. Follow [the remaining closure contract](../attachment-remaining-closure.md)
for UI, scheduler, lifecycle, installer and physical qualification.
