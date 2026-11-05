# MarmotKit November 5 nightly evaluation

Testing-only adoption of `0.15.0-nightly.20261105`, replacing the
[November 4 nightly](marmotkit-nightly-20261104.md). This update does not authorize
production distribution or enable experimental device groups.

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261105):
  `marmotkit-android-0.15.0-nightly.20261105.zip`.
- Source and workflow builder: `5286f86cd66af12705d33e1e7bf766bc7889441b`,
  20 MDK commits after the previous pin `0f3fe9e02828f57a4e4c10592df05b3835fc9f39`.
- Independently calculated ZIP SHA-256, matching the published sibling checksum:
  `5fb0ee01ef0f443634bbcc74e6acf1548ce7a4413e0b921291193dc3721cacbd`.
- Embedded manifest: matching source, builder, tag and version; workspace
  `0.15.0-dev`, Android API 26. Its Cargo.lock SHA-256
  `718e821bfc1f07552313786479667e7f0ad457642f56742e3e9808868ad174c0`
  matches the exact-source lockfile.
- The repository's archive preparation tool verifies layout, native ELF metadata,
  required exports and generated API for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Generated API signature SHA-256:
  `592298e1f8ffe3bb803d7d7681c96a043b1092d141de9105b0ecb26fd57d3470`.

The manifest does not enumerate Cargo features. Feature selection is therefore
**unverified by the manifest**, but verified against the successful
[publishing run](https://github.com/marmot-protocol/mdk/actions/runs/41982881940)
and its exact-source
[workflow](https://github.com/marmot-protocol/mdk/blob/5286f86cd66af12705d33e1e7bf766bc7889441b/.github/workflows/bindings.yaml)
and [builder](https://github.com/marmot-protocol/mdk/blob/5286f86cd66af12705d33e1e7bf766bc7889441b/crates/marmot-uniffi/kotlin-bindings.sh).
All four ABI jobs set `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1`.
The builder maps these to `otlp-export,product-analytics-export`; no ABI job enables
`test-policy-overrides` or passes `--dev-device-groups`. The latter is a new
explicit builder opt-in, initialized to zero for ordinary release invocations.
The normal Android constructor and deployment/keychain identity remain unchanged.

## Runtime changes

- Recovery replaces leaves atomically and preserves recovery/publication state
  across crash and compatibility restart (mdk#2683).
- Account identity proofs can be reused across account-device reopen (mdk#2703).
  This is an upstream optimization, not a measured Android catch-up improvement.
- A peer reaction settles an own send previously judged to have reached no relay
  (mdk#2899). The native timeline and chat-list projections report delivery.
- Explicit Leave becomes account-wide across linked devices; an eligible nonlast
  admin is demoted by core as part of Leave (mdk#2925). Android already obeys the
  native `canLeave` and `requiresSelfDemoteBeforeLeave` projections.
- Experimental sibling Welcome repair uses heartbeat activity (mdk#2936), and
  device-group moves/backups gain additional APIs (mdk#2948, mdk#2951, mdk#2954).
  Android adds no UI or experimental constructor for these capabilities.

## Generated API and Android compatibility

The generated Kotlin surface advances from **421 types / 420 checksums / 5
helpers** to **425 / 424 / 5**, with no removed types or methods.

Added types are `DeviceGroupMoveSiblingFfi`, `DeviceGroupMoveViewFfi`,
`DeviceGroupMoveStateFfi` and `BackgroundBackupOutcomeFfi`. Four entry points are
added: `newWithExperimentalDeviceGroups`, `dismissDeviceGroupMoveSibling`,
`runDueBackupPass` and `setRuntimeBackupBackgroundConditions`. Eleven existing
method checksums change, covering linked-device/sign-in views, backup conditions,
removal/rename and compromise warnings. `DeviceLinkSnapshotFfi` gains `moves`,
linked-device status gains `Moved`, and typed recovery errors gain
`GroupRecoveryUnsettled` and `GroupRecoverySourceMismatch`. Android does not call
the new experimental operations or exhaustively match those gated views.

`TimelineMessageRecordFfi` adds required `recipientConfirmed`. A confirmed send
can have no `sourceMessageIdHex`, and the reaction that settles it arrives as
`DELIVERY_OR_SEND_STATE_CHANGED`, not a separate reaction trigger. Android now:

- marks that projection Sent and invalidates render equality on confirmation;
- refreshes reactions on delivery-state changes;
- excludes confirmed rows from unpublished-send suppression/retry matching;
- stops pending attachment presentation while preserving native target lookup;
- treats a matching peer-confirmed notification reply as committed.

All synthetic timeline constructors explicitly default this field to false.
Regression tests cover the live controller's delivery/reaction update, including
replacement of a retained failed legacy optimistic bubble without consuming an
unrelated same-content pending send, render equality,
unpublished-send matching and notification reply deduplication.
Existing bubble layouts do not change; no new screenshot baseline is required.

## Retained-data qualification

Account migrations **0173–0176** add recipient-confirmed sends, heartbeat Welcome
repair, a pending-publication handle watermark and device-group moves. This is a
one-way upgrade: preserve a private pre-upgrade data backup and never reopen
migrated data with the November 4 runtime.

The hosted qualification pair now fails closed unless master pins the reviewed
November 4 runtime `0f3fe9e0`. Its disposable fixture retains the complete pending
upload reference, including `sourceEpochIdentity`, and checks its serialization
round trip during seeding. Unlike the historical November 2 → November 4 pair,
this baseline already has upload branch identity. Promotion must send the
original upload unchanged after upgrade and read back its exact plaintext after
restart. A deliberately stripped copy must still be refused as stale; the
campaign must not discard provenance and silently substitute a fresh upload.

Dispatch the existing hosted campaign once on this candidate branch:

```sh
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref chore/marmotkit-nightly-20261105 -f mdk_qualification=true
```

Require populated upgrade and cold restart, original pending-upload promotion and
another cold read, retained media/avatar bytes with acquisition blocked, corrupt
and lost-media recovery, and all seven replay cases with comparison refused and
ignored. Native/hosted results are distinct from testing a signed staging or
production APK. A human handoff also needs the two exact-APK retained-data smokes
in the [CI policy](ci-request-policy.md).

## Validation status

Local archive checksum, manifest/source agreement, four-ABI ELF preparation and
static generated-source comparison are verified. Hosted Kotlin/test/style,
packaging/runtime, reproduction and populated qualification results remain
pending. No APK or physical-device behavior is claimed by this record.
