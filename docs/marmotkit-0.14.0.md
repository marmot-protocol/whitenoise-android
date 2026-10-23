# Official MarmotKit 0.14.0 adoption

This record replaces the October 23 nightly pin with the official release for
Android task #3421. It authorizes neither merging nor an Android production
release. Phone testing remains paused.

## Verified artifact

- Release: [`marmotkit-v0.14.0`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0), published 23 October 2026.
- Asset: `marmotkit-android-0.14.0.zip`, 95,822,131 bytes.
- ZIP SHA-256: `a4f99bd844e3839c3598751ca5b08bc4f6b7298435a22739a426e445248e9839`;
  independently calculated and matching the sibling checksum and GitHub digest.
- Source/builder: `3fb4b4759ac676b0c21c4c3527074866c9fa4c89`; the annotated release
  tag peels to this commit. The embedded manifest identifies `marmotkit-android`,
  artifact/workspace version `0.14.0`, Android API 26 and the exact-source
  Cargo.lock SHA-256 `8286bc2731edeec804cd1fae2e40eefad3bb87a068d3aff15d94a4e5e14bc9cc`.
- Archive root: `marmotkit-android-0.14.0`. The unchanged Android validator checks
  the complete layout, three generated Kotlin files and all four ABI libraries:
  arm64-v8a, armeabi-v7a, x86 and x86_64, including ELF metadata/alignment.
- Manifest feature enumeration is absent and remains **unverified by the
  manifest**. Exact-source publishing inputs select `OTLP_EXPORT=1` and
  `PRODUCT_ANALYTICS_EXPORT=1`; `kotlin-bindings.sh` maps these to the unchanged
  `otlp-export,product-analytics-export` lock selection. This is separate builder
  evidence, not an invented manifest field.

The inspected Android master baseline is
`dc4cce3574392bd1fb1dc28d57061eadaa59909b`, pinning October 23 source
`d274dd228a3ac8d624073d8f5835e06c7cd7b30d`. The hosted pair records its actual
master commit and rejects any different native baseline before building.

## API and consumer compatibility

The complete generated Kotlin diff adds `OnboardingFindingContextFfi`,
`OnboardingCheckPurposeFfi`, `OnboardingCheckOutcomeFfi` and the
`OnboardingIssueFfi.InspectionCompleted` value. `OnboardingFindingFfi` gains six
required fields: context, purpose, outcome, declaredCapability, actions and
observedRevision. Existing function checksums and Android/keyring helper bytes
are unchanged. No exception variant is added and no generated binding is edited.

Counts change from 385/373/5 to **388 public types / 373 checksums / 5 helpers**.
The reviewed static signature SHA-256 is
`e45730fdb3591a1f947ed5689a7d4d3b08f1f6445ca6e11574260a3afc2d2637`;
hosted API staging must produce exactly these bytes.

Android renders each finding's native context, check purpose and outcome without
combining observations that share an endpoint. Legacy all-unknown findings gain
no invented provenance. Inspection completion does not assert relay write support.
The endpoint-specific Remove shortcut requires the matching finding's Edit grant
as well as the current step's grant, including the native reread. Full step-level
actions remain available independently; Android does not infer relay capabilities
or recreate MDK's validation/recovery policy. Test fixtures supply all new fields
explicitly through a test-only helper, with unknown/empty legacy defaults.

## Native changes and migration boundary

The [13-commit delta](https://github.com/marmot-protocol/mdk/compare/d274dd228a3ac8d624073d8f5835e06c7cd7b30d...3fb4b4759ac676b0c21c4c3527074866c9fa4c89)
includes owed subscription activation/reconnect handling (#2572), sends surviving
subscription-refresh failure (#2567), contextual onboarding findings (#2574),
affected-family group-sync repacking (#2583), cached timeline modifier lookups
(#2585), telemetry counters (#2584), and ctutils 0.4.5 (#2576). The published
Cargo.lock contains that patched version. These fixes remain MDK-owned and do
not by themselves close Android relay, invitation, performance or storage issues.

This pair introduces no new storage migration or SQLCipher key-format change.
The baseline already includes migration 0116 and raw-key presentation adopted
in #3409. Qualification proves compatibility with those populated databases,
not a second rekey or first-time 0116 migration. Older-release upgrades retain
all previous migration, backup, free-space and downgrade constraints. Never
rewrite migration rows, clear data or downgrade to make qualification pass.

## Qualification

Exact-source upstream [CI 39963766580](https://github.com/marmot-protocol/mdk/actions/runs/39963766580)
passed the full merge-group matrix. The release [bindings build](https://github.com/marmot-protocol/mdk/actions/runs/39968967320)
also passed. Retain Android results separately; publication alone is not Android
runtime evidence.

Required Android checks run through hosted CI: API staging, core compilation,
unit/golden/coverage/lint suites, packaged native smoke, production/staging ART
and independent arm64 reproduction. The disposable `.mdkqualification` pair
uses one signer and the strictly reviewed October 23 native baseline. It seeds
two accounts, history, retained media/avatar bytes, pending upload, explicit
removal and salt receipts. Require usable normal MainActivity after upgrade and
full process-stop/cold restart, unchanged original bytes with acquisition blocked,
Forever/removal/salt retention, no key-migration markers, pending promotion and
another restart, corrupt/lost-media recovery, and seven replay cases in both
comparison-refusal and silence modes, with no skips.

Current official-release Android qualification is pending. Record actual exact-head
run/artifact results in the PR; historical nightly, skipped or cancelled runs do
not satisfy it. Required screenshot baselines depict the new diagnostic text in
light, AMOLED and large-text RTL states. Reviewers must inspect generated images
and actionable review/docstring findings before readiness. No physical-device
access or human APK handoff is included; no preview build is requested for this
hosted qualification alone.

## Retention

The official asset is an immutable formal release, not a 30-day nightly. Retain
its verified bytes and tested APK/native/signer provenance. The historical
October 23 baseline nightly still expires around **22 November 2026**; preserve
its verified archive and qualification APKs beyond that date so the recorded
upgrade pair remains reproducible. Preserve prior recovery and phone artifacts.
