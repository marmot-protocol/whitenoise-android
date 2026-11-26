# MarmotKit 0.20.0 nightly of 26 November 2026 on Android

This testing pin includes MDK `bea7b400` and the subsequent fixes through
`ca52a047`. Use a formal MarmotKit release for store builds. [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.20.0-nightly.20261126)

The Android archive records source and builder
`ca52a04718628f08ed76b777df9c444594074182`, Android API 26 and four ABIs.
Its SHA-256 matches the published checksum,
`cbe27323602571466cc3054b677a876c66cd296dea8400a99ff23762b47d4148`.

The manifest omits Cargo features. The publishing workflow sets both telemetry
features, and the builder passes them to Cargo. All four Android ABI jobs
record both inputs as enabled. [Publishing run](https://github.com/marmot-protocol/mdk/actions/runs/45730310274),
[Workflow](https://github.com/marmot-protocol/mdk/blob/ca52a04718628f08ed76b777df9c444594074182/.github/workflows/bindings.yaml),
[Builder](https://github.com/marmot-protocol/mdk/blob/ca52a04718628f08ed76b777df9c444594074182/crates/marmot-uniffi/kotlin-bindings.sh)

## API changes

- Native queued and engine-owned send statuses now include the event ID.
  Android preserves that ID on recovery. A send refused because the group
  gained members is terminal. [MDK #3719](https://github.com/marmot-protocol/mdk/pull/3719)
- KeyPackage prewarming returns a summary. Relay health adds ten types and a
  change event. The current UI continues to use its existing relay settings.
  The archive exposes 495 public types, 493 UniFFI checksums and five helper
  declarations.

## Upgrade

The account database moves from schema 0218 in 0.19.0 to 0226. The first seven
migrations are listed in the [25 November pin record](marmotkit-nightly-20261125.md).
Migration 0226 preserves the pending send ledger and adds the terminal refused
state. The new schema prevents older runtimes from reopening migrated data.
Recheck populated upgrade and cold restart in both audit modes before handing
off a build that can replace an existing install. [INT-010](manual-release-testing/cases/INT-010.md)

The prior nightly missed a live message when the relay silently ignored history
comparison. The live delivery fix in this bundle addresses a separate lock
stall. Full recovery qualification remains required for this pin.
[MDK #3643](https://github.com/marmot-protocol/mdk/issues/3643),
[MDK #3760](https://github.com/marmot-protocol/mdk/pull/3760)
