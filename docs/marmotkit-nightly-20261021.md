# MarmotKit nightly 20261021 evaluation

This testing-only refresh of [PR #3371](https://github.com/marmot-protocol/whitenoise-android/pull/3371)
and [issue #3370](https://github.com/marmot-protocol/whitenoise-android/issues/3370)
replaces the [20 October evaluation](marmotkit-nightly-20261020.md). Keep the PR draft;
do not merge, publish a production release or access physical devices.

## Verified published artifact

| Field | Value |
| --- | --- |
| Release | [`marmotkit-v0.14.0-nightly.20261021`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0-nightly.20261021) |
| ZIP | `marmotkit-android-0.14.0-nightly.20261021.zip` |
| SHA-256 | `09ab27547a11edb1d200e0338a95f5ee92fe6636f45cfa64035d55c65cba770c` |
| Source, builder and tag commit | `8c214fee07466b0b7b63f8dfa39877cce1f63573` |
| Manifest name / version | `marmotkit-android` / `0.14.0-nightly.20261021` |
| Archive root | `marmotkit-android-0.14.0-nightly.20261021` |
| Workspace / minimum Android API | `0.14.0-dev` / `26` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Cargo.lock SHA-256 | `e12b94c089d057ab01eac79547089270c7bc5bb54d2e0b2ec6d9a9238f727464` |
| Toolchain | Rust/Cargo `1.97.1`; NDK `27.2.12479018` |

The calculated ZIP SHA-256 matches its sibling checksum and GitHub asset digest.
The checksum asset's own digest also matches GitHub. The embedded identity,
source/builder, immutable tag and exact-source Cargo.lock agree. The unchanged
Android validator accepts all nine payload files and verifies four ELF
architectures, digests, exports and segment alignment, including 64-bit 16 KiB.

The manifest still omits `features`. Its feature status stays **unverified**;
the validator is not weakened. The exact-source
[builder](https://github.com/marmot-protocol/mdk/blob/8c214fee07466b0b7b63f8dfa39877cce1f63573/.github/workflows/bindings.yaml#L45-L52)
and successful [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/39546166339)
confirm `OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1`, retaining the existing
`otlp-export,product-analytics-export` selection on separate provenance evidence.

## API and source comparison

The previous reviewed nightly is MDK `67cd40d8`, ZIP
`a0befdc55e21ecd65c5b61af797ec36fb570e0c76e92a44a964b0dd892f5daf3`.
Its hosted signature comes from [Android CI 39516172585](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39516172585).
Counts remain **384 types / 371 checksums / 5 helper declarations**. Preparation
passes with the existing counts; no count was adjusted to suppress a mismatch.

- `AttachmentMigrationStateFfi` adds `COMPACTING`. Counts alone do not reveal
  this nested enum change; the complete generated Kotlin diff was inspected.
- `watchAgentTextStream` retains its parameters/return type. Its documentation
  now names bundled public-CA roots instead of OS trust; its UniFFI checksum
  changes from `24253` to `15795`. No other checksum changes.
- The Kotlin helper files are byte-identical. No generated binding is edited.
- Production Android has no exhaustive handling of the migration enum. The
  fixture waits for both the expected legacy-byte balance and `COMPLETE` or
  `NOT_NEEDED`; transient `COMPACTING` cannot pass completion.

The inspected signature SHA-256 is
`e9be2810f149ae87aa0e8527af2b4b83e8f2792f4a84852c1c6d016aacdb9869`.
Hosted `:app:stageMarmotKitApiSignature` must reproduce it. The baseline-to-nightly
constructor/deployment and `StorageFull` compatibility changes described in the
20 October record remain in this PR; White Noise services and keychain ownership
are unchanged.

The 12-commit source range adds bounded reconciliation progress
([MDK #2490](https://github.com/marmot-protocol/mdk/pull/2490)), preserves retryable
stalled recovery routes ([#2503](https://github.com/marmot-protocol/mdk/pull/2503)),
adds database compaction ([#2496](https://github.com/marmot-protocol/mdk/pull/2496)),
and replaces the QUIC OS verifier with bundled Mozilla roots to avoid the Android
JNI initialization panic ([#2331](https://github.com/marmot-protocol/mdk/pull/2331)).
Directory extraction and Hermes test changes do not add Android host APIs.

## Migration and qualification

There is no newly numbered account migration relative to the 20 October nightly;
the current-master `88d1683e` baseline still crosses 0113 and the background
legacy-body migration. Newly created databases use incremental auto-vacuum.
Existing databases retain their mode until native compaction: at least 25% free
pages, enough volume space for the reserve plus two copies of live data, and at
most 64 MiB live data. Later maintenance/removals reclaim freed pages. Native
reads/writes can wait during compaction; usage reports the preceding snapshot.
Android must not implement VACUUM, change schema rows or invent recovery logic.

Rerun the seven-phase hosted campaign against current master: populated baseline,
in-place upgrade, cold restart, pending-upload promotion, post-promotion restart,
and recovery with comparison refusal and silence. Preserve exact accounts/history
and both payloads; do not count ignored cases as passes. The two ignored cases
remain tied to open [MDK #2375](https://github.com/marmot-protocol/mdk/issues/2375)
and [#2381](https://github.com/marmot-protocol/mdk/issues/2381).

The PR records exact Android source, hosted run outcomes, signatures and APK
digests. The successful 20 October campaign at `755d06e1f` is historical evidence,
not qualification of this pin. All compilation, tests and runtime checks run on
GitHub under [current CI policy](ci-request-policy.md).

Exact-source [MDK CI 39462029491](https://github.com/marmot-protocol/mdk/actions/runs/39462029491)
passed. Retained individual test evidence includes compaction shrink/incremental
reclaim (Rust shard 3), free-page threshold/blocked WAL truncate/closing storage
(shard 1), size/space limits/rollback retry/new incremental databases (shard 5),
transaction refusal (shard 2)
and interruption before copy (shard 4). These are upstream tests. The small
Android fixture does not establish large-database compaction, actual OS ENOSPC,
corrupt/lost-media handling or the file-publication/SQL-deletion crash window.
Do not infer compaction happened merely because migration settled.

The previous source's Simulator Nightly failed a replay-budget assertion. No
exact-`8c214fee` Simulator Nightly result was found; the new recovery fixes and
Android passes cannot by themselves clear that separate gap. Real public-broker
QUIC connectivity also needs separate qualification.

The disposable debug-signed `.mdkqualification` pair does not clear the trusted
preview handoff gate. There is still no matching populated baseline with PR3371's
isolated preview package and preview signer in this evidence. Do not distribute
the historical preview as a qualified candidate.

## Retention

This nightly can become eligible for deletion around **20 November 2026** (30
days); do not rely on a precise cleanup hour. Retain the original ZIP, checksum,
manifest, ELF report, source/builder evidence, old/new signatures and their full
diff, and exact tested APKs. Hosted qualification artifacts retain 30 days; some
other CI artifacts retain seven. A warm cache is not durable retention. The
original verified ZIP can use the documented artifact override after expiry;
never silently substitute a rebuilt archive or change the pin to another source.
Keep migrated fixtures isolated and move them forward; never downgrade them.
