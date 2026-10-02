# Sender handoff and Retry follow-up — 2026-10-02

This follows the [earlier attachment qualification](attachment-review-followup-qualification-2026-10-02.md). Pixel feedback on `5c0719897` exposed a hidden own-file card during local preparation and transient reordering as sibling sends completed. Independent review also found silent native Retry refusal outside file cards.

Own-file metadata now remains visible while its definitive cache probe runs. A pending outgoing host copy prefers already-retained native plaintext; when native retention is absent, the matching encrypted write still precedes network acquisition. Received-file first-frame behavior, cache limits and retry budgets remain unchanged. Confirmed send positions remain bridged while media siblings are Pending, then settle to MDK order after completion, failure or discard. The common controller reports refused Retry once; the file card adds no duplicate notice. A failed host-cache publication no longer throws into availability probes; the probe checks actual cache presence afterward, while observer cancellation still propagates.

## Reusable checks

- Shipping-controller tests cover grouped and overlapping separate file sends, intermediate canonical echoes and settlement after a failed sibling. Existing authoritative-ordering regressions also run.
- Shipping AppState tests use an oversized L1 miss and a deliberately paused encrypted-cache write. They check host availability, plaintext fallback and native-first access, forbidding acquisition calls.
- The production own-file Compose test keeps the filename and preparation state visible during a blocked probe; its committed Roborazzi baseline is selected in the screenshot-owner registry.
- Null-destination Retry tests require failure feedback and retain the accepted-admission/no-platform-open checks.
- A blocked publication that fails with an IO error leaves availability false without failing its observer. Cancelling an observer leaves the publication running.
- Removing the production availability wait and send-position hold makes their regression tests fail. The original source is restored byte-for-byte afterward; failed mutation logs are retained separately.

## Local results

Both flavors pass **340 focused tests** with zero failures, errors or skips. The final shared gate passes both debug compilations, Android-test compilation, ktlint, detekt, Zapstore lint and selected Roborazzi verification. All 39 host fixture tests, 71 guide/owner/required-case-checker tests and the 323-active-ID manual guide pass. The new own-file PNG is tracked with its exact screenshot owner. These are host and Compose checks; they do not qualify the new physical sender flows.

## Android qualification

The generated 32 MiB send uses the shipping Android controller and genuine MDK outgoing retention. A fixture-only encrypted host copy is deliberately held before promotion. Availability and streaming SHA-256 readback must finish through native retention while that copy remains pending, with a 10-second assertion deadline; this is a controlled local-access check, not a production SLO. The copy is then released and independently verified. This new held-host-copy Android test is compiled but has not yet run for this follow-up. The earlier received-read ABBA comparison, HTTP ledger, small-file restart/cancellation and API 36 results are evidence for the linked prior cohort, not qualification of these new changes.

## Provenance and limits

The candidate is rebased onto master `787a5205885ca91f03dd681d0b21913601071e06`. All ten replayed commits are identical by range-diff. Published MDK remains `122bd90ffac60bb6311346e228d0f609a18521ee`, with packaged arm64 native SHA-256 `073aef751994069e4923ca0a49342c2f2111423af7bdbe014e44f9d86badc56a`.

Raw source overlays and local validation logs remain private under `planning/wn-2779-large-file-qualification-20261002-v5` and `planning/wn-2779-sender-fixes-20261002`, with SHA-256 manifests. Prior APKs, ledgers and failures retain their provenance in the linked earlier cohort. Initial compilation/static checks caught missing imports, an unqualified nested test type, test-class size, return count and line length. The final shared completion gate passes after correcting an additional test import-order error. A sandboxed host-test attempt could not bind loopback sockets; all 39 host tests pass with socket access. Every failed attempt remains recorded and is not labelled flaky.

Full MED-009/017/024/025 manual flows, physical reproduction of the new sender fixes, representative large-file throughput/memory, over-64-MiB files and Android job interruption/Range remain unqualified. Numeric progress is localized; Latin size units follow the existing file formatter. No issue closures or full-plan completion are claimed.
