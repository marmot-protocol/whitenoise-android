# MarmotKit November 4 nightly evaluation

This is testing-only adoption of the latest MDK nightly. Android can test fixes
that are not yet in a formal release:
- every offline-queued send is published after reconnect (mdk#2860, mdk#2762);
- uploaded media and custom reaction artwork are bound to the encryption branch
  they were uploaded under (mdk#2891);
- sends keep working after a late losing removal branch (mdk#2864);
- directory events are verified before they are forwarded or stored (mdk#2918);
- the Nostr verification cache and vulnerable Rust dependencies are patched
  (mdk#2912, mdk#2920).

It does not authorize production distribution. Replace it with the formal
`marmotkit-v0.15.0` release once MDK publishes one.

## Published artifact and baseline

The [nightly release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261104)
publishes `marmotkit-android-0.15.0-nightly.20261104.zip` from MDK source and
workflow builder `0f3fe9e02828f57a4e4c10592df05b3835fc9f39`.

- **ZIP SHA-256:** `bfef6cd3e9c6f4bfe6bfc8f8cc9faae1a020e36aa97c265a4e3c6e85266e7662`.
  It was calculated independently and matched to the sibling checksum file.
- **Embedded manifest:**
  - name `marmotkit-android`, version `0.15.0-nightly.20261104`;
  - tag, source and builder all match;
  - workspace `0.15.0-dev`, Android API 26.
  - Its Cargo.lock SHA-256 `718e821bfc1f07552313786479667e7f0ad457642f56742e3e9808868ad174c0`
    matches the exact-source file.
- **Contents:** the generated Kotlin bindings and the four ABI libraries
  (arm64-v8a, armeabi-v7a, x86 and x86_64). Android's unchanged preparation
  script validates the layout and ELF metadata.
- **Features:** the manifest has no feature list, so feature selection is
  **unverified by the manifest**. Instead:
  - The [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/41785276478)
    sets `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1` in all four Android
    ABI build jobs.
  - The exact-source
    [`kotlin-bindings.sh`](https://github.com/marmot-protocol/mdk/blob/0f3fe9e02828f57a4e4c10592df05b3835fc9f39/crates/marmot-uniffi/kotlin-bindings.sh)
    maps those to the unchanged `otlp-export,product-analytics-export` Cargo
    features.
  - `test-policy-overrides` appears in none of those jobs' logs.

  Retained excerpts from the four "Build Android input" jobs:

  ```text
  Build Android input (arm64-v8a)    2026-11-04T06:25:06.4837521Z  OTLP_EXPORT: 1
  Build Android input (arm64-v8a)    2026-11-04T06:25:06.4837978Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (armeabi-v7a)  2026-11-04T06:25:05.4254252Z  OTLP_EXPORT: 1
  Build Android input (armeabi-v7a)  2026-11-04T06:25:05.4254685Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (x86)          2026-11-04T06:25:05.3669331Z  OTLP_EXPORT: 1
  Build Android input (x86)          2026-11-04T06:25:05.3669576Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (x86_64)       2026-11-04T06:25:05.6370003Z  OTLP_EXPORT: 1
  Build Android input (x86_64)       2026-11-04T06:25:05.6370446Z  PRODUCT_ANALYTICS_EXPORT: 1
  ```
- **Baseline:** master's November 2 nightly pin, MDK
  `2854335c6e29f6bf236b1f986c62c1b9ab31114c` ([November 2 record](marmotkit-nightly-20261102.md)).
  The candidate is 56 MDK commits ahead of it.

## API and compatibility

The generated Kotlin diff against the November 2 nightly:

- **Adds:**
  - backup settings, restore and recovery-word controls: seven types and eleven
    methods, plus the `BackupRestoreSubscription` object;
  - `reactionAttachment`, which acquires custom reaction artwork;
  - `updateLinkedDeviceSettings`;
  - the `HistoryImportProvenanceFfi` type.
- **Removes:** nothing.
- **Same signature, new checksum:** `sendMediaAttachments`, `sendTaggedMedia`,
  `reactWithMedia`, `sendCustomEvent` and `renameLinkedDevice`. Their Kotlin
  signatures are unchanged and their behaviour changed.
- **Events and errors:** none added to `MarmotEventFfi` or `MarmotKitException`.
- **Counts:** they advance from 412/405/5 to **421 types / 420 checksums / 5 helpers**.
- **Staged API signature SHA-256:**
  `0c9b625d0975477c35d65dd9790b5c7e5d5730120903737698038aa497058e19`.
  Hosted `stageMarmotKitApiSignature` must produce the same bytes.

Two existing records gain fields:

| Record | New field | Default |
| --- | --- | --- |
| `TimelineMessageRecordFfi` | `historyImportProvenance` (a device or backup source for imported history) | none |
| `MediaAttachmentReferenceFfi` | `sourceEpochIdentity` (local upload branch identity) | `null` |

`sourceEpochIdentity` sits between `sourceEpoch` and `dim`, so a positional
constructor call would shift `dim` and `thumbhash` into the wrong fields.
Production code builds these records only with named arguments. Test fixtures
pass `historyImportProvenance = null` and the extra positional `null`. The
upgrade qualification's `upgradeMediaReference` uses named arguments so the
same source compiles against the baseline and the candidate bindings.

## Upload branch identity (mdk#2891)

MDK now pins media and custom reaction sends to the epoch *and branch* their
upload was encrypted under. It rejects references with no recorded provenance
at local admission, and stale uploads before sending. Existing references stay
downloadable. Hosts must pass back references returned by an upload unchanged.

Android's send paths already do:
- The composer uploads and sends the returned references in one flow.
- Forwards upload again from the decrypted source.
- Custom emoji artwork sends the references its own upload returned.

`PendingForwardRequestStore` keeps *source* references, which are used only to
download. It now persists `sourceEpochIdentity` as an optional key, so its
encoding stays field for field. Requests stored before this change still decode.

An upload that was pending before the upgrade carries no identity. The hosted
qualification's pending-upload promotion phase shows whether MDK still admits
it after the upgrade.

## Behavior that changes without Android code

- **Offline sends (mdk#2860).** Every send queued while offline is published
  after reconnect, not only the first. Startup and reopen wake the queue.
- **Late removal branches (mdk#2864).** A late losing removal branch no longer
  stops later sends, and direct application receive output is atomic (mdk#2874).
- **Interrupted recovery (mdk#2422, mdk#2882).** Orphan pending recovery is
  atomic, and recovery goals are seeded on account open and route changes
  (mdk#2867).
- **Historical attachments (mdk#2614).** They are recovered through native
  acquisition and leases.
- **Directory events (mdk#2918, mdk#2902).** They are verified before they are
  forwarded or stored, and concurrent updates keep every component.

Device linking, backups, recovery words and authenticated rejoin all stay gated
off, because this artifact does not enable `test-policy-overrides`. Android
adds no UI for them.

## Migration and retained data

MDK adds account storage migrations:

| Migration | Change |
| --- | --- |
| 0165 | Drops the unused announced KeyPackage history |
| 0167 | Reaction attachment sources |
| 0168 | Backup restore catalog and history-import provenance |
| 0169 | Sibling Welcome resends |
| 0170 | Invitation decisions |
| 0171 | Device edit settings |
| 0172 | Authenticated group rejoin |

The runner records each version separately, so 0165 still applies over a
November 2 install that already ran 0166.

The upgrade is one way: an install opened by this nightly cannot be reopened by
the November 2 runtime. Never install it over a phone that has to go back to a
master build.

The populated hosted campaign now uses master's November 2 pin as its reviewed
baseline. `scripts/build-mdk-qualification.sh` and
`scripts/stage_mdk_baseline_fixture.py` fail closed on any other baseline
runtime. Dispatch the campaign once on this branch:

```bash
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref chore/marmotkit-nightly-20261104 -f mdk_qualification=true
```

Its upgrade, cold restart, pending-upload promotion and recovery phases must all
pass before this pin counts as qualified. Physical-device evidence stays separate.
