# MarmotKit October 30 nightly evaluation

This is testing-only adoption of the latest MDK nightly. It brings in MDK fixes that
are not yet in a formal release, so Android can test them before MarmotKit 0.15.0:
attachment transfer state without the coalescing wait (mdk#2157, mdk#2311), typed
stale-epoch media errors (mdk#2158, mdk#2312) and KeyPackage deletion scoped to this
device's slot (mdk#1697). It does not authorize production distribution. Replace it
with the formal `marmotkit-v0.15.0` release once MDK publishes one.

## Published artifact and baseline

The [nightly release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261030)
publishes `marmotkit-android-0.15.0-nightly.20261030.zip` from MDK source and
workflow builder `f7178f0e997e3d6d450fb0af45a32e4780ed862c`.

- ZIP SHA-256: `17c85ab52b3953e41f9e5a3247252e6dbc03777c4d9aafa33b3b9a0e5ac2cfdd`.
  Independently calculated and matched to the sibling checksum and GitHub digest.
- Embedded manifest: `marmotkit-android`, version `0.15.0-nightly.20261030`,
  matching tag/source/builder, workspace `0.15.0-dev`, Android API 26. The manifest's
  Cargo.lock SHA-256 `77a6c664bfde2c05acb9091847345289b1aed9816fad74fb83bd4565f8e7edd1`
  matches the exact-source file.
- The archive holds the three generated Kotlin files and the four ABI libraries:
  arm64-v8a, armeabi-v7a, x86 and x86_64. Android's unchanged preparation script
  validates the layout and ELF metadata.
- Manifest features are absent, so feature selection is **unverified by the
  manifest**. The [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/41010695131)
  sets `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1` in all four Android ABI
  build jobs, and the exact-source
  [`kotlin-bindings.sh`](https://github.com/marmot-protocol/mdk/blob/f7178f0e997e3d6d450fb0af45a32e4780ed862c/crates/marmot-uniffi/kotlin-bindings.sh)
  maps them to the unchanged `otlp-export,product-analytics-export` Cargo features.
- The baseline is master's official MarmotKit 0.14.0 pin, MDK
  `3fb4b4759ac676b0c21c4c3527074866c9fa4c89` ([0.14.0 record](marmotkit-0.14.0.md)).
  The nightly is 106 MDK commits ahead of it.

## API and compatibility

The generated Kotlin diff against 0.14.0 adds three public types,
`AccountDevicesActivationFfi`, `LinkedDeviceFfi` and `LinkedDeviceStatusFfi`, and five
methods: `linkedDevices`, `renameLinkedDevice`, `keyPackageRefreshOverdue`,
`accountDevicesActivation` and `enableAccountDevices`. Nothing is removed. Counts
advance from 388/373/5 to **391 types / 378 checksums / 5 helpers**. The staged API
signature SHA-256 is `6ae8cfc66a028b474df2dee8ca00f9cd6b2cd28bfa21643bd3791c4cc868043b`;
hosted `stageMarmotKitApiSignature` must produce the same bytes.

Android changes needed to keep current behavior:

- `MarmotKitException.MediaReferenceStaleEpoch` and `MediaReferenceEpochUnsettled`
  replace the `InvalidMediaReference` text MDK used for these two cases.
  `isStaleMediaReferenceEpoch` now checks the types instead of matching
  "media reference was encrypted at epoch". Without this, custom-emoji sends and
  forwards would stop re-uploading after an epoch change. Draft sends still fall
  back to the direct send for both, diagnostics still report them as
  `INVALID_INPUT`, and the attachment download gate still treats them as terminal.
- `MarmotEventFfi.LinkedDevicesChanged` and `KeyPackageRefreshOverdueChanged` get
  content-free diagnostic lines. Android shows no linked-device UI. MDK returns an
  empty list and `false` for these in release builds until the roster spec lands.
- `GroupEventKindFfi.QueuedIntentRefused` and the new
  `MarmotKitError.AccountDevicesActivationRefused` need no exhaustive handling in
  Android today.

## Behavior that changes without Android code

- Errors are classified by what they are (mdk#2602). A busy, closed or full store
  reported by the account session now arrives as `StorageBusy`, `StorageClosed` or
  `StorageFull` instead of `Runtime`. An external-signer rejection is now
  `ExternalSignerRejected` on every path, including identity proofs for KeyPackages
  and group changes, so diagnostics report it as `CANCELLED`.
- `AttachmentTransferSubscription.next()` delivers state changes as soon as the
  engine reports them; progress-only replacements stay at four per second.
  Android's snapshot peek beside the feed still works and can be removed in a
  follow-up.
- Sign-out deletes only this device's KeyPackage slot (mdk#2654).
- Group member rows are accounts, not devices, with a `deviceCount`. Creating a
  group or inviting adds every recent device of each recipient account, up to 20.
- A convergence pass that spends its failure budget is reported through
  `AppGroupMlsStateFfi.convergenceParked`; Android does not show it yet.

## Migration and retained data

MDK adds account storage migrations 0117 to 0134, covering push-token gossip
state, account-scoped member counts, the recovery coverage ledger, device groups
and account sign-ins. The upgrade is one way: an install opened by this nightly
cannot be reopened by the 0.14.0 runtime. Never install it over a phone that has
to go back to a master build.

The populated hosted campaign now uses master's 0.14.0 pin as its reviewed
baseline: `scripts/build-mdk-qualification.sh` and
`scripts/stage_mdk_baseline_fixture.py` fail closed on any other baseline runtime.
Dispatch it once on this branch:

```bash
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref codex/marmotkit-nightly-20261030 -f mdk_qualification=true
```

Its upgrade, cold restart, pending-upload promotion and recovery phases must all
pass before this pin counts as qualified. Physical-device evidence stays separate.
