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

| ABI | Publishing job |
| --- | --- |
| `arm64-v8a` | [139615637705](https://github.com/marmot-protocol/mdk/actions/runs/45730310274/job/139615637705) |
| `armeabi-v7a` | [139615637716](https://github.com/marmot-protocol/mdk/actions/runs/45730310274/job/139615637716) |
| `x86` | [139615637833](https://github.com/marmot-protocol/mdk/actions/runs/45730310274/job/139615637833) |
| `x86_64` | [139615637712](https://github.com/marmot-protocol/mdk/actions/runs/45730310274/job/139615637712) |

```text
Build Android input (arm64-v8a) 2026-11-26T00:59:39.5960763Z  OTLP_EXPORT: 1
Build Android input (arm64-v8a) 2026-11-26T00:59:39.5961289Z  PRODUCT_ANALYTICS_EXPORT: 1
Build Android input (armeabi-v7a) 2026-11-26T00:58:02.2095389Z  OTLP_EXPORT: 1
Build Android input (armeabi-v7a) 2026-11-26T00:58:02.2095781Z  PRODUCT_ANALYTICS_EXPORT: 1
Build Android input (x86) 2026-11-26T01:03:11.0029940Z  OTLP_EXPORT: 1
Build Android input (x86) 2026-11-26T01:03:11.0030336Z  PRODUCT_ANALYTICS_EXPORT: 1
Build Android input (x86_64) 2026-11-26T00:59:31.7142766Z  OTLP_EXPORT: 1
Build Android input (x86_64) 2026-11-26T00:59:31.7143164Z  PRODUCT_ANALYTICS_EXPORT: 1
```

## API changes

- Native queued and engine-owned send statuses now include the event ID.
  Android preserves that ID on recovery. A send refused because the group
  gained members is terminal. [MDK #3719](https://github.com/marmot-protocol/mdk/pull/3719)
- KeyPackage prewarming returns a summary. Relay health adds ten types and a
  change event. The current UI continues to use its existing relay settings.
  The archive exposes 495 public types, 493 UniFFI checksums and five helper
  declarations.

## Consumer changes

- Android sends kind-1202 answers and controls through
  `sendCustomEventWithClientToken` and keeps the accepted event ID until the
  intended agent settles it. Session controls target the session's first event.
- Agent activity labels a row “Earlier” when MDK retained an edit but the
  first event is missing, using `agentItemFirstEventMissing`.

## Native runtime changes

- MDK switched to upstream nostr. Batched live subscriptions break only the
  routes reported lost. MDK checks request-specific evidence before marking
  history as covered.
  [MDK #3704](https://github.com/marmot-protocol/mdk/pull/3704),
  [#3713](https://github.com/marmot-protocol/mdk/pull/3713),
  [#3714](https://github.com/marmot-protocol/mdk/pull/3714)
- Invitation delivery waits for a complete KeyPackage lookup before reporting
  an incompatible package. Welcomes have a delivery deadline, and hosts can
  see when their inbox list prevents delivery.
  [MDK #3605](https://github.com/marmot-protocol/mdk/pull/3605),
  [#3708](https://github.com/marmot-protocol/mdk/pull/3708),
  [#3710](https://github.com/marmot-protocol/mdk/pull/3710)
- The roster drops a linked device that rewrites its own entry. Keyless
  removal records can reach an unlinked device.
  [MDK #3706](https://github.com/marmot-protocol/mdk/pull/3706),
  [#3709](https://github.com/marmot-protocol/mdk/pull/3709)

## Upgrade

The account database moves from schema 0218 in 0.19.0 to 0226. The first seven
migrations are listed in the [25 November pin record](marmotkit-nightly-20261125.md).
Migration 0226 preserves the pending send ledger and adds the terminal refused
state. The new schema prevents older runtimes from reopening migrated data.
Recheck populated upgrade and cold restart in both audit modes before handing
off a build that can replace an existing install. [INT-010](manual-release-testing/cases/INT-010.md)

Both audit modes preserved populated 0.19.0 account data across the upgrade
and cold restart on `78f4fd91`. The audit-declined run passed all recovery
cases. The audit-enabled run missed one live message when a relay silently
ignored history comparison after a peer deleted local state. Stable preview
qualification remains open on that case. The live delivery fix in this bundle
addresses a separate lock stall.
[Hosted qualification](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45824935648),
[MDK #3643](https://github.com/marmot-protocol/mdk/issues/3643),
[MDK #3760](https://github.com/marmot-protocol/mdk/pull/3760)
