# MarmotKit November 6 nightly evaluation

Testing-only adoption of `0.15.0-nightly.20261106`, replacing the
[November 5 nightly](marmotkit-nightly-20261105.md). Experimental device groups
remain disabled. This record does not authorize production distribution.

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261106):
  `marmotkit-android-0.15.0-nightly.20261106.zip`.
- Source and workflow builder: `9a45434cfbf0adaad2c34be861b6a71ebcf148b5`,
  41 MDK commits after `5286f86cd66af12705d33e1e7bf766bc7889441b`.
- Independently calculated ZIP SHA-256, matching its published sibling checksum:
  `c4c8898372f9f8bf36a6f46b83ef5c0acc9f5658c83cebbf76d6b0587292027d`.
- Embedded manifest: matching source, builder, version and tag; workspace
  `0.15.0-dev`, Android API 26. Its Cargo.lock digest
  `718e821bfc1f07552313786479667e7f0ad457642f56742e3e9808868ad174c0`
  matches the exact-source lockfile.
- Static layout, four-ABI ELF metadata, library hashes, required exports and
  64-bit load alignment were inspected with the repository's artifact parser.
- Generated API signature SHA-256:
  `a276c50177cfe09f80822b7f42a30060e5b62bdce3daea2673f44510bcc7a4f6`.

The manifest does not enumerate Cargo features; feature selection remains
**unverified by the manifest**. Equivalent builder-side evidence is retained from
successful [publishing run 42176791397](https://github.com/marmot-protocol/mdk/actions/runs/42176791397),
its exact-builder [workflow](https://github.com/marmot-protocol/mdk/blob/9a45434cfbf0adaad2c34be861b6a71ebcf148b5/.github/workflows/bindings.yaml)
and [Kotlin builder](https://github.com/marmot-protocol/mdk/blob/9a45434cfbf0adaad2c34be861b6a71ebcf148b5/crates/marmot-uniffi/kotlin-bindings.sh).
Every ABI job shows `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1`. These
map to `otlp-export,product-analytics-export`; no ABI invocation supplies
`--dev-device-groups` or enables `test-policy-overrides`. The builder initializes
its experimental-device-group switch to zero. Native feature selection is
therefore builder-verified, with the normal Android constructor and deployment
identity preserved.

Feature-input excerpts from those successful jobs:

- [arm64-v8a](https://github.com/marmot-protocol/mdk/actions/runs/42176791397/job/127757267401)
- [armeabi-v7a](https://github.com/marmot-protocol/mdk/actions/runs/42176791397/job/127757267340)
- [x86](https://github.com/marmot-protocol/mdk/actions/runs/42176791397/job/127757267433)
- [x86_64](https://github.com/marmot-protocol/mdk/actions/runs/42176791397/job/127757267534)

```text
arm64-v8a  2026-11-06T06:49:29.3308873Z  OTLP_EXPORT: 1
arm64-v8a  2026-11-06T06:49:29.3309119Z  PRODUCT_ANALYTICS_EXPORT: 1
armeabi-v7a 2026-11-06T06:49:29.1819513Z OTLP_EXPORT: 1
armeabi-v7a 2026-11-06T06:49:29.1819745Z PRODUCT_ANALYTICS_EXPORT: 1
x86        2026-11-06T06:49:30.0099137Z  OTLP_EXPORT: 1
x86        2026-11-06T06:49:30.0099503Z  PRODUCT_ANALYTICS_EXPORT: 1
x86_64     2026-11-06T06:49:30.0405630Z  OTLP_EXPORT: 1
x86_64     2026-11-06T06:49:30.0406052Z  PRODUCT_ANALYTICS_EXPORT: 1
```

## Runtime changes

- Cheaper idle maintenance avoids empty expiry deletes and unnecessary projection
  checkpoints, with per-phase performance counters (mdk#2952). The upstream
  recovery-loop work remains separate; this bump does not establish a complete
  catch-up fix or measured Android battery improvement.
- Blossom upload, synthesized download and deletion URLs retain configured server
  base paths (mdk#2984). This corrects endpoint addressing without establishing
  faster attachment downloads.
- Publication retains unresolved evidence across retries and authentication
  handoff instead of replacing uncertainty with a later definite refusal
  (mdk#2998).
- Typed size refusals and coarse pending-Welcome diagnostics are available
  (mdk#2993, mdk#2999). A size observation is not proof that earlier publication
  reached no relay; Android's existing ownership/uncertainty handling is retained.
- Whole-view chat selection and filtered chat-list query APIs are added
  (mdk#2997, mdk#3000). This pin update adds no corresponding Android feature UI.
- Linked-device eligibility, settings, Welcome settlement and local repair gain
  fixes and APIs. Android continues using ordinary constructors; experimental
  device-group operations remain gated off.

## Generated API and consumer compatibility

Static generated-source comparison advances **425 types / 424 checksums / 5
helpers** to **432 / 431 / 5**, with no removed types or methods.

Added types are `ChatListConditionFfi`, `ChatListFilterNodeFfi`,
`ChatListQueryErrorFfi`, `ChatListViewQueryFfi`, `EventSizeClassFfi`,
`OversizedArtifactFfi` and `PendingWelcomeDeliveryFfi`. Seven methods are added:
`chatListIds`, `chatListQueryIds`, `openChatListQueryWindow`,
`pendingWelcomeDeliveries`, `resetDeviceGroupForRepair`, `revalidateChatListIds`
and `revalidateChatListQueryIds`.

`openChatListWindow` is the only existing method checksum that changes
(`26719` → `18511`). UniFFI includes its revised method documentation in metadata
hashing. The Rust receiver, public Kotlin signature and generated argument/result
conversions remain unchanged. The native method now delegates through an empty
filter, preserving existing unfiltered behavior. No Android caller change is
needed; consume the published native library and generated Kotlin together.

`WelcomeDeliveryPending` gains nullable `sizeClass`; the diagnostic test fixture
now supplies `null`. `UnrecognizedSignInFfi` gains `previouslyLinkedHere`; Android
has no synthetic constructors for that record. New typed exceptions include
`EventTooLarge` and `InvalidChatListQuery`; existing nonexhaustive host error
handling remains in place. No generated binding is edited or regenerated.

## Retained-data qualification

[Migration 0177](https://github.com/marmot-protocol/mdk/blob/9a45434cfbf0adaad2c34be861b6a71ebcf148b5/crates/storage-sqlite/src/base/migrations/0177_welcome_failure_size_class.rs)
adds nullable Welcome failure size diagnosis. Existing repair pointers retain
all their fields and start with unknown diagnosis. The upgrade remains one-way;
retain the baseline's private data backup and do not reopen migrated data with
an older nightly.

The populated campaign now requires current master to pin the reviewed November 5
runtime `5286f86c`. Both the shell build guard and test-only fixture overlay reject
other baselines, including the candidate and partial SHAs. Shared fixture APIs
remain compatible with that baseline. No production or generated source is
copied onto it.

The fixture preserves the complete original pending-upload reference, including
`sourceEpochIdentity`. It must send unchanged after upgrade and remain readable
after another cold restart; a deliberately stripped reference must remain stale.
Require preserved accounts, history, drafts and retained media/avatar bytes with
acquisition blocked, normal usable MainActivity, corrupt/lost media recovery and
all seven native replay cases with relay comparison refused and ignored.

```sh
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref chore/marmotkit-nightly-20261106 -f mdk_qualification=true
```

The permanent `INT-010` manual case records this upgrade pair and migration.
Hosted disposable fixtures do not establish behavior on a trusted signed preview
or a user's physical device; use the exact-APK handoff gates if that work is later
requested.

## Validation status

Archive SHA-256, embedded manifest/source/builder agreement, effective ABI feature
inputs, static ELF inspection and generated API comparison are verified. The first
candidate, `f5bf3d270fcaecd3ec0a07003a772a0462046f3d`, passed
[full two-distribution core CI](https://github.com/marmot-protocol/whitenoise-android/actions/runs/42189626546),
[all six native adoption cases](https://github.com/marmot-protocol/whitenoise-android/actions/runs/42189799394)
and [all 11 populated-upgrade phases](https://github.com/marmot-protocol/whitenoise-android/actions/runs/42189804798).
The latter used November 5 on current master `c4b96a457b3ac612199ad253ff99e80a009bc666`
as its baseline, preserved the original pending upload, and passed all seven
replay cases under both comparison modes. It does not qualify a trusted signed
preview. Pending-Welcome row preservation remains manual Android acceptance;
the hosted fixture does not explicitly seed that row.

The candidate's PR merge commit also passed
[minified production API 36 and staging API 37 ART checks](https://github.com/marmot-protocol/whitenoise-android/actions/runs/42192570945),
[unsigned reproducibility and both release-lint variants](https://github.com/marmot-protocol/whitenoise-android/actions/runs/42192571006),
and all 219 general emulator cases. Its ready-event Play suite exposed five
image-work timeouts after an SVG viewer fixture. That fixture counted decoder
entry before completion, then could retire its Compose dispatcher while holding
the shared image-work lane. Its teardown now removes the viewer and joins that
lane before closing the fixture; the animation retries and public-image handoff
also join source work before their assertions finish. This repairs the source-level isolation race;
the failed run did not retain a lock-owner trace proving the exact interleaving.

Use [PR #3609's validation record](https://github.com/marmot-protocol/whitenoise-android/pull/3609)
for the final reviewed revision and current check results; earlier passing runs
do not replace final-head validation. No APK was installed on a physical device.
