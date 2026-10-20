# MarmotKit nightly 20261020 evaluation

This candidate evaluates the published nightly for [Android #3370](https://github.com/marmot-protocol/whitenoise-android/issues/3370).
It stays draft and is not approved for merging, production adoption or release.
Follow [the update guide](updating-marmotkit.md) and [CI request policy](ci-request-policy.md).

## Published artifact

| Field | Verified value |
| --- | --- |
| Release | [`marmotkit-v0.14.0-nightly.20261020`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0-nightly.20261020) |
| ZIP | `marmotkit-android-0.14.0-nightly.20261020.zip` |
| SHA-256 | `a0befdc55e21ecd65c5b61af797ec36fb570e0c76e92a44a964b0dd892f5daf3` |
| Source and builder | `67cd40d8add455f5ba2c0f080f6f2ef6c5504029` |
| Manifest name / version | `marmotkit-android` / `0.14.0-nightly.20261020` |
| Workspace version / minimum Android API | `0.14.0-dev` / `26` |
| Archive root | `marmotkit-android-0.14.0-nightly.20261020` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Cargo.lock SHA-256 | `1e2a50dc324a018638af81999a57cf9ab4140b1682ec902a363ae7ae1eb91c51` |
| Toolchain | Rust/Cargo `1.97.1`; NDK `27.2.12479018` |

The calculated ZIP digest matches both the published sibling `.sha256` and GitHub's
asset digest. The embedded source, builder, release identity and Cargo.lock digest
match the exact upstream source. Android's existing validator accepts the nine
payload files: three Kotlin sources, four JNI libraries, `manifest.json` and
`android-elf.json`. It verifies each ELF's architecture, digest, required exports
and load-segment alignment, including the 64-bit 16 KiB alignment requirement.
The manifest retains the existing five-entry `contents` contract; the two helper
Kotlin files and ELF report are validated separately by the archive contract.

**Feature provenance limitation:** the embedded Android manifest has no `features`
field. Do not describe features as manifest-verified. The exact
[builder workflow](https://github.com/marmot-protocol/mdk/blob/67cd40d8add455f5ba2c0f080f6f2ef6c5504029/.github/workflows/bindings.yaml#L45-L52)
sets `OTLP_EXPORT=1` and `PRODUCT_ANALYTICS_EXPORT=1`; the
[publishing run](https://github.com/marmot-protocol/mdk/actions/runs/39392013440)
confirms those inputs. Its build script selects `otlp-export,product-analytics-export`,
and assembly verifies per-ABI provenance before packaging. The existing Android
selection is retained on that evidence. `native-provenance.json` correctly continues
to report the manifest feature status as `unverified`; the consumer validator is
not weakened or taught to invent metadata.

## Comparison and compatibility

The baseline is Android master `1b4225cfecada4706f59395e1410d2916d3380d6`, which
pins MDK `88d1683e13a1dd60ba102d06c7b5a8acb3635590`, workspace `0.13.0`, ZIP
`c77e9e58bc4b1e26c1f5dfa44309f2ee4971cd2725e30052d333b13d83640865`.
Its generated signature comes from
[successful master CI run 39403638014](https://github.com/marmot-protocol/whitenoise-android/actions/runs/39403638014).
The baseline ZIP was also checksum-verified before comparing nested records,
enums and errors, which signature counts alone cannot describe.

- Counts advance from **371 / 362 / 5** to **384 / 371 / 5**
  (public types / UniFFI checksums / helper declarations). Preparation with the old
  counts fails closed at those exact observed counts; the new counts follow review.
- Eight records are added: `AttachmentHandoffFfi`, `AttachmentStorageUsageFfi`,
  `ChatAttachmentUsageFfi`, `ChatStoragePageFfi`, `MarmotDeploymentFfi`,
  `OpenRankingProviderFfi`, `RetainedAttachmentSummaryFfi` and `RetainedAttachmentsPageFfi`.
  `AttachmentMigrationStateFfi` and two cursor classes with their interfaces account
  for the other five types. No top-level type is removed.
- Nine APIs are added: the neutral and White Noise deployment presets; attachment
  usage/by-chat/largest queries; open/release handoff; and remove-by-age/remove-by-chat.
  This evaluation does not add Manage storage UI or adopt the handoff APIs.
- Every real `Marmot` constructor now needs a deployment, either as its final argument
  or as the required `MarmotOptions.deployment`. Production and instrumentation callers
  use MDK's `marmotDeploymentWhiteNoise()`. It preserves `com.marmot.whitenoise` as the
  keychain service and White Noise's service configuration. Existing cursor policy,
  `HOST_MANAGED` attachments, relay policy and host adapters remain in place.
- All six constructor checksums change. `uploadMedia` and `uploadMediaFiles` also
  change checksums, but their Kotlin parameters and return types are unchanged;
  the upstream method changes include documentation in UniFFI metadata.
- Nested error `MarmotKitException.StorageFull` is new. Native uploads now stage
  into encrypted account storage before uploading. Android maps a send refusal to
  free-space guidance and preserves the pending attachment for retry.
- `RetentionSweepStatusFfi.DEFERRED_SCAN_EXHAUSTED` is removed. Android has no
  reference to it; this still requires matching generated Kotlin and JNI bytes.
- `MarmotAndroid.kt` changes only its constructor example; `Keyring.kt` is identical.
  No generated binding is hand-edited or checked into Android.

The non-compiling Python preparation entry point is used locally with the published
ZIP override. Hosted `:app:stageMarmotKitApiSignature` must produce the matching
`app/build/reports/marmotkit/marmotkit-api-signature.txt`; compilation and tests run
only through hosted CI. Preserve the old/new signatures and their complete diff
with the evaluation evidence.

## Native changes that need qualification

The source range contains:

- [MDK #2352](https://github.com/marmot-protocol/mdk/pull/2352): retain consumed
  KeyPackage private keys for 24 hours after replacement; test delayed Welcome delivery.
- [MDK #2353](https://github.com/marmot-protocol/mdk/pull/2353),
  [#2380](https://github.com/marmot-protocol/mdk/pull/2380) and
  [#2384](https://github.com/marmot-protocol/mdk/pull/2384): checkpointed file downloads,
  outgoing media staging and streaming uploads. Check interruption, cancellation,
  low disk space, source retention, retry, forwarding and exact retained bytes.
- [MDK #2398](https://github.com/marmot-protocol/mdk/pull/2398),
  [#2410](https://github.com/marmot-protocol/mdk/pull/2410) and
  [#2424](https://github.com/marmot-protocol/mdk/pull/2424): handoff leases and storage
  management APIs, present but not adopted by this Android change.
- [MDK #2467](https://github.com/marmot-protocol/mdk/pull/2467): background migration
  of legacy retained SQL attachment bodies into the media store.
- [MDK #2469](https://github.com/marmot-protocol/mdk/pull/2469): retention sweep
  correction for unread messages. Test cursor ties, epoch/timestamp ordering and
  expiry captured before retention-policy changes.
- [MDK #2406](https://github.com/marmot-protocol/mdk/pull/2406),
  [#2408](https://github.com/marmot-protocol/mdk/pull/2408) and
  [#2493](https://github.com/marmot-protocol/mdk/pull/2493): explicit host deployment.
  Preserve identity access, media/image endpoints, discovery and recovery configuration.

Account migration **0113** is the only newly numbered account migration in this
range. It rebuilds `media_files` to allow staged state, adds
`outgoing_attachment_uploads.file_id`, and updates foreign keys/triggers. Earlier
migration files move under `base/migrations`; moved files are not new migrations.
The subsequent background legacy-body migration is separate from schema startup.
It runs after readiness in bounded batches, can pause for the disk reserve, and
can resume after interruption. `HOST_MANAGED` does not suppress this migration.

Before adopting the candidate, use copied synthetic fixtures to verify pending,
ready and lost media, pending outgoing uploads and legacy SQL-backed bodies:
retained digests, upload promotion, migration completion, interruption between
file publication and SQL deletion, disk-reserve pause/resume, and cold restart.
Corrupt legacy bodies can become unavailable under MDK's corruption handling;
record that outcome separately. Keep the original fixture copy.

Recovery blockers [MDK #2375](https://github.com/marmot-protocol/mdk/issues/2375)
(one sender beyond 100 messages) and [MDK #2381](https://github.com/marmot-protocol/mdk/issues/2381)
(offline longer than two minutes behind a relay's 500-event clamp) were still
open when inspected, and their fixes were not found in this source range.
Preserve their ignored tests. Run the active `MarmotKitRecoveryQualificationTest`
cases with comparison refusal and silence; do not claim full offline catch-up.

## CI and emulator evidence

The draft PR records actual run links, exact source SHAs, artifact digests and
outcomes. A queued job or a draft-skipped lane is not passing evidence.

- Draft core CI: stage API signature, compile, Zapstore full unit/golden suite,
  lint, ktlint, detekt and tooling contracts. Focused regressions include
  `SendFailureMessageTest`, `ConversationMediaSendReconciliationIntegrationTest`,
  `MarmotClientRootGateTest` and `GroupChangeAdmissionCoverageTest`.
- Explicit hosted campaigns while retaining draft status: full Android core CI,
  x86_64 native adoption acceptance, production/API-36 and staging/API-37 ART
  verification, and independent arm64 reproduction with release lint.
- Request one exact-head preview through the trusted default-branch workflow.
  Retain the isolated channel for a disposable emulator. Do not give a human the
  APK until the two required smokes pass on those exact signed bytes.
- Smoke 1: in-place upgrade over a populated fixture using the current master
  runtime, retaining account secrets and synthetic history. Smoke 2: fully stop
  and cold-start the same candidate with the same retained data. Both must reach
  usable chats without bootstrap or migration errors.
- A clean isolated install does not substitute for either upgrade smoke or
  background migration qualification. Record device/API, package identity,
  baseline lock, candidate source, APK digest and observed account/history state.
  `INT-010` and `MED-035` contain the permanent manual cases.

No physical-device access is authorized by this evaluation. Never uninstall,
clear storage, alter migration rows or downgrade an existing physical install.
Protocol, persistence, migration and recovery behavior stay owned by MDK.

## Retention and exit

Upstream deletes nightly releases and tags after 30 days. This 20 October 2026
nightly can become eligible around **19 November 2026**; do not rely on an exact
cleanup hour. Before expiry retain the original ZIP, sibling checksum, manifest,
ELF report, release metadata, builder provenance references, both API signatures,
their diff, and exact tested APKs with source/signing/digest evidence. CI artifacts
may expire earlier (some instrumented artifacts retain only seven days).

A warm content-addressed cache is not durable artifact retention. A fresh machine
or cold CI cache will fail once the pinned release URL disappears. A preserved
original ZIP can use the documented artifact override and still receives all
validation; do not silently change this pin to a mirror or a rebuilt archive.
Future continued testing needs an explicitly reviewed, published replacement.

Production adoption requires a formal release and separate qualification.
Ending the experiment means leaving migrated fixtures isolated or moving them
forward to a qualified runtime. Reverting the source pin does not make a database
downgrade safe, and a secret-key export is not a database/history backup.
