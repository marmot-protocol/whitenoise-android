# MarmotKit November 18 nightly evaluation

Testing-only adoption of `0.18.0-nightly.20261118`, replacing the
[official 0.17.0 release](marmotkit-0.17.0.md). Replace it with the formal
`marmotkit-v0.18.0` release once MDK publishes one. This record does not
authorize production distribution.

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.18.0-nightly.20261118):
  `marmotkit-android-0.18.0-nightly.20261118.zip`.
- Source and workflow builder: `0ad3c61088e1acac58e0fcf1a9f1142bae5d997a`,
  57 MDK commits after `4df811aea99269b437a6023be9446a5141e93cbe`.
- Independently calculated ZIP SHA-256, matching its published sibling checksum:
  `a6bb504753ab911fa4313ab00aa82c04f23fa4fb0ddf966eaf11742ea6ee22b1`.
- Embedded manifest: matching source, builder, version and tag, workspace
  `0.18.0-dev`, Android API 26 and four ABIs with ELF validation.
- Generated API signature SHA-256:
  `68bbeefafeb24af9b29cddd621d03bc79f5aeba7f0d640d8c53e0e2762b63c51`, with 480
  public types, 490 UniFFI checksums and five helper declarations. The previous
  bundle had 470 types and 479 checksums.

The manifest does not enumerate Cargo features, so feature selection remains
**unverified by the manifest**. Builder-side evidence comes from successful
[publishing run 44259310127](https://github.com/marmot-protocol/mdk/actions/runs/44259310127),
its exact-builder [workflow](https://github.com/marmot-protocol/mdk/blob/0ad3c61088e1acac58e0fcf1a9f1142bae5d997a/.github/workflows/bindings.yaml)
and [Kotlin builder](https://github.com/marmot-protocol/mdk/blob/0ad3c61088e1acac58e0fcf1a9f1142bae5d997a/crates/marmot-uniffi/kotlin-bindings.sh).
Every Android job shows `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1`,
which map to `otlp-export,product-analytics-export`.

## Device groups are on in every library

[MDK #3519](https://github.com/marmot-protocol/mdk/pull/3519) opens every
constructor with device groups on and removes the separate development
library. An account on this runtime can receive a link request from another
installation, and a nonlast admin's Leave steps down first. Android therefore
ships the linked-device screens in the same change: the build switch in
`MultiDeviceExperiment.kt` is on, and the manual cases `ACC-025`, `GRP-043` and
`SET-059` to `SET-066` cover linking, the roster, device management, sign-in
review, history and the group setting.

## Consumer changes

- `AppMessageRecordFfi` gains `invalidated`. Android builds such records only
  for its own optimistic and display rows, which are never tombstones, so each
  passes `false`.
- `MarmotKitError.NoUsableRelay` replaces `Publish` for a group send none of
  whose relays MDK may use. Android classifies it wherever it classified
  `Publish`, so the same failure reads as before.
- `MarmotKitError.GroupCommitPending` refuses a group change while this
  device's previous commit is unconfirmed. Android reports it as a delivery
  wait in diagnostics and as a refusal that happened before the membership
  commit. The stuck-commit detail from `groupPendingCommit` is not shown yet.
- Account setup offers `OnboardingActionFfi.RemoveUnusableRelays` as its own
  action, which opens MDK's repair preview like the recommended-relay action.
  The typed `removal_reason` is not rendered yet.
- A repair proposal with `inconclusive` set shows a warning above the preview
  that a source did not answer and that saving is at the user's own risk.

Not adopted in this change: `groupChangesNotDelivered` and its event,
`attachment_removals`, file-backed draft attachments, direct chats founded
with a disappearing-message duration, `unreactFromMessageMatching` and the
sign-in notice's `client` label. None changes existing behavior.

## Upgrade

The disposable hosted upgrade campaign starts from 0.17.0 at `4df811ae`.
Account schema advances from 207 to 212 (migrations 0208 to 0212) and
downgrade is unsupported. The campaign exercises populated upgrade, cold
reopen, the original pending upload and both recovery relay modes. No
qualification source differs between the two runtimes, so the baseline overlay
directory is empty. See [INT-010](manual-release-testing/cases/INT-010.md).
