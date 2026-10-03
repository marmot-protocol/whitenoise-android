# Android attachment continuation under keyguard — 2026-10-03

This extends the [actual background fixture](attachment-background-2026-10-03.md)
with verified screen-off **and Android keyguard locked** for the same 30-second
sustained transfer. A generated 4 MiB attachment uses the shipping Android sender,
resolver and actual foreground WorkManager service (API30) or user-initiated
JobService (API36). Independent host pacing keeps the download active after
30 seconds; the ledger must include body bytes written at least 25 seconds after
background entry. The Activity remains stopped; keyguard and screen-off are checked
again before final plaintext verification. Original emulator keyguard settings are
captured and restored; no PIN is set, app data cleared or physical device touched.

| API / flavor | Locked interval (ms) | Body bytes during interval | Overall ms | Java / native sampled peak MiB |
| --- | ---: | ---: | ---: | ---: |
| 36 / Play | 30982 | 1949696 | 36462.5 | 19.40 / 101.41 |
| 36 / Zapstore | 31033 | 1949696 | 36425.5 | 19.79 / 101.15 |
| 30 / Play | 31457 | 1949696 | 38851.4 | 27.27 / 99.79 |
| 30 / Zapstore | 31379 | 1949696 | 38904.9 | 21.93 / 112.96 |

All four cases pass: one upload, one GET, zero HEAD, 4,194,320 ciphertext bytes
in each direction, exact final plaintext and retired interactive intent. These
functional observations do not establish representative performance, full chat
Open/Save behavior, or actual Android scheduler-stop/process-death resume.
Existing 1 KiB performance budgets remain unchanged and inapplicable to this cohort.

## Source and artifacts

Base `09b64a499128e319b7225d017bc5b65bf769aae3` plus frozen overlay; the base
source tree is identical to fixture PR head `42d3b79acd5fe32ab40576b2a2ae25f699a2345b`.
Source-manifest SHA-256: `1b078bcdb9863124034e868857333f91603a9d5f2683bc2f16e55ad8d4f65bfa`.
These APK labels describe that base plus overlay, not the later committed PR head.
MDK `122bd90ffac60bb6311346e228d0f609a18521ee`; arm64 native SHA-256
`073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

| APK | SHA-256 |
| --- | --- |
| Play-app | `5f8d5871176d9f7cb7c5166bacf9b50e38489633bdafbed2651f3b18667fa9e9` |
| Play-test | `3d45be6ad9c8d104ba6aa3e70e06101a8b6904954907a65a83f6a17223a8d6d9` |
| Zapstore-app | `ba718cc5eb9b818d9aa35d37a37b65e00f636e3c224c4e5062fb91e97718bced` |
| Zapstore-test | `988a7daef809586b64bdee5dda884fc227dfe85e827c9d800f5b550205509dcb` |

Private durable roots `planning/wn-five-closure-20261003/platform-lock-attempt1`
and `platform-lock-attempt2` preserve APKs, source overlays, raw ledgers, every
build and instrumentation attempt, reports and checksum manifests. Initial Play
cases failed on both API levels because the AVD keyguard was disabled; the body
remained held and neither case qualified screen lock. The corrected opt-in fixture
temporarily enables keyguard only after Lab identity/emulator guards, and restores
the original setting in cancellation-safe cleanup. Earlier formatting failures and
one missing local variable during the options refactor are preserved as failed
builds, not Android runtime passes. Host checker regression ensures a failed lock
case cannot label itself lock-qualified.

Full MED-009/MED-017/MED-024/MED-025, automatic-job interruption/resume, real
Open/Save policy/backlog cases, image/video/album lifecycle, physical installer
acceptance and representative performance remain required in the
[five-issue closure contract](../attachment-remaining-closure.md). No issue closes.
