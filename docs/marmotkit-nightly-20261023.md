# MarmotKit October 23 nightly evaluation

This is testing-only adoption for Android #3408. It does not authorize production
distribution, merging, enabling automatic media deletion or resuming phone tests.

## Published artifact and baseline

The [immutable nightly release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0-nightly.20261023)
publishes `marmotkit-android-0.14.0-nightly.20261023.zip` from MDK source and
workflow builder `d274dd228a3ac8d624073d8f5835e06c7cd7b30d`.

- ZIP SHA-256: `f22805ecb7bcac8cc2c3a884a8b7a7176cf63bd057c85b64a0e3666a6f4568b9`.
  Independently calculated and matched to the sibling checksum and GitHub digest.
- Embedded manifest: `marmotkit-android`, version `0.14.0-nightly.20261023`,
  matching tag/source/builder, workspace `0.14.0-dev`, Android API 26.
  The manifest's Cargo.lock hash matches the exact-source file.
- Complete archive layout and all ELF metadata pass the unchanged Android
  validator: arm64-v8a, armeabi-v7a, x86 and x86_64. The three generated Kotlin
  files and four JNI libraries come directly from the archive.
- Manifest features are absent, so feature provenance remains explicitly
  **unverified by the manifest**. Compiled features are verified separately through
  [the publishing run](https://github.com/marmot-protocol/mdk/actions/runs/39902519328):
  all four ABI compile steps enable `OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1`,
  and the exact-source builder passes `otlp-export,product-analytics-export` to Cargo.
  Assembly checks source, builder, run ID, feature selection and each library hash;
  its downloaded input digests match the ABI uploads. Retained assembly artifact
  `12479896923` has SHA-256
  `c5ff58d0ec1143c074d327ff143eb08b4ab2d90ef076e6e01e4ad70a4148189b`.
  Its inner release ZIP is byte-identical to the independently verified published ZIP.
- The reviewed Android baseline is master
  `2f4555008c809b4ebd617fcd23ef74af850409d2`, pinning October 22 MDK
  `77f22e35499a42bc96402ce19b79ceed57ffbcc3` through #3395.

## API and compatibility

Static inspection of the complete published binding difference finds one new
enum, `AttachmentKeepMediaFfi` (Forever, OneYear, SixMonths, ThirtyDays), and two
methods, `attachmentKeepMedia` and `setAttachmentKeepMedia`.
`AttachmentTransferStateFfi` appends `CleanedBySetting`. No existing method or
checksum is removed/changed; the Android/keyring helper bytes are unchanged.
Counts advance from 384/371/5 to **385 types / 373 checksums / 5 helpers**.
The reviewed static API signature SHA-256 is
`63be26f8c5cf20fcff7505a4bdb191886c148132d1395cd3d963469a64660c7d`.
Hosted `stageMarmotKitApiSignature` must produce exactly those bytes.

Android maps setting-cleaned media to its existing not-retained presentation,
terminates observation, keeps diagnostics typed, and permits native recovery
only through deliberate download-again. Ordinary joins and durable retries do
not recreate demand. Focused transfer, demand and diagnostic tests cover this
compatibility boundary. No generated bindings are edited, no new retention UI
is exposed and no cleanup preference is enabled.

## Migration and native ownership

[MDK #2541](https://github.com/marmot-protocol/mdk/pull/2541) adds migration 0116:
the account retention preference, removal reason and acquisition keep-from time.
Existing accounts default to Forever; explicit removals keep their meaning.
Choosing a finite window is destructive to eligible local media and is outside
this Android change.

[MDK #2549](https://github.com/marmot-protocol/mdk/pull/2549) presents the same
derived 32-byte secret as a raw SQLCipher key. Existing encrypted databases need
a one-time page rekey, separately guarded by `.raw-key-migrating`; MDK retains
salts, recovery markers, WAL recovery and all cipher hardening. Android does not
open raw databases, rewrite migration rows, supply keys or implement recovery.
Do not downgrade migrated installations. Full-app performance and security
release sign-off remain outstanding upstream; a microbenchmark is not Android
startup qualification.

Other included changes reduce duplicate relay event/connection work (#2547),
combine Welcome reconciliation scans (#2548), reject incomplete onboarding repair
freshness (#2545), and narrow directory internals/log targets (#2542/#2560).
These do not complete Android battery, invitation-state, relay-repair UI or
storage-breakdown issues. The #3289 replay fixes were already in October 22.

## Qualification evidence and required results

Exact-source upstream [CI 39846624613](https://github.com/marmot-protocol/mdk/actions/runs/39846624613)
passed. Retained actual test logs show `raw_marker_forces_recovery`,
`raw_rekey_wal_crash_recovers`, `raw_rekey_and_downgrade`,
`external_raw_rekey_recovers`, `failed_rekey_keeps_recovery` and migration 0116's
`existing_rows_keep_their_meaning` passing. The upstream downgrade test uses
synthetic native data; this task performs no Android or personal-data downgrade.

The hosted Android campaign remains required on the final candidate. Its
disposable package and signer upgrade a populated October 22 baseline, with
identity/history, a 16 MiB retained attachment, cached avatars, a pending upload
and a separate explicitly removed synthetic attachment. Preserve private
baseline backup and salt digests. Require normal MainActivity usability and cold
restart with original bytes and public/blob acquisition blocked. Candidate-only
assertions verify Forever, explicit removal, preserved salts and no unfinished
key-migration marker after both upgrade and cold restart. Then require pending
promotion/restart, corrupt/lost disposable-media recovery and all seven replay
cases in both refusal and silence modes, with no skips.

The old migration-0113 SQL-body compaction and byte-offset fault hooks are
inapplicable to this file-backed baseline. Native interrupted-rekey tests and
published-Android migration results are separate evidence, as are native quarantine
injection and Android local-deletion/restart coverage.

Before readiness, retain successful exact-head hosted core/unit/golden/lint,
API staging, packaged native smoke, production/staging ART and arm64 reproduction
results, independent review and current actionable-thread/docstring status.
Record actual run links in the PR; cancelled, skipped or historical runs cannot
qualify this candidate. Phone testing stays paused. No human preview handoff is
requested; a disposable debug pair is not proof for a trusted signed preview.

## Artifact retention

Nightlies expire after 30 days, approximately **22 November 2026** for this build.
Keep the verified ZIP, sibling checksum, manifest, exact-source inputs, API
signature, tested APKs and source/native/signer provenance beyond GitHub expiry.
CI retention alone is insufficient. The dependency URL may stop resolving after
expiry, so later builds need the verified retained artifact or a newly reviewed
published release. Never rebuild the old native library and call it the tested ZIP.
