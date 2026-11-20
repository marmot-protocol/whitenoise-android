# MarmotKit 0.18.0 on Android

Android consumes the immutable `marmotkit-v0.18.0` bundle, replacing the
[official 0.17.0 release](marmotkit-0.17.0.md).

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.18.0):
  `marmotkit-android-0.18.0.zip`.
- Source and workflow builder: `34646a577807f2b112ee031f3c164ec58aa18b64`,
  77 MDK commits after `4df811aea99269b437a6023be9446a5141e93cbe`.
- Independently calculated ZIP SHA-256, matching its published sibling checksum:
  `d757f5340ca8ea0b7384ebabb4d8fec5a55d64aa51c63c7443ffce15a5a53440`.
- Embedded manifest: matching source, builder, version and tag, workspace
  `0.18.0`, Android API 26 and four ABIs with ELF validation.
- Generated API signature SHA-256:
  `8df8901342b8b9913ca4082136d65afa3452741d38558b01c7926e8ad9d5e5e8`, with 487
  public types, 494 UniFFI checksums and five helper declarations. The 0.17.0
  bundle had 470 types and 479 checksums.

The manifest does not enumerate Cargo features, so feature selection remains
**unverified by the manifest**. Builder-side evidence comes from successful
[publishing run 44716579239](https://github.com/marmot-protocol/mdk/actions/runs/44716579239),
its exact-builder [workflow](https://github.com/marmot-protocol/mdk/blob/34646a577807f2b112ee031f3c164ec58aa18b64/.github/workflows/bindings.yaml)
and [Kotlin builder](https://github.com/marmot-protocol/mdk/blob/34646a577807f2b112ee031f3c164ec58aa18b64/crates/marmot-uniffi/kotlin-bindings.sh),
which pass `OTLP_EXPORT` and `PRODUCT_ANALYTICS_EXPORT` to Cargo as
`otlp-export,product-analytics-export`.

## Device groups are on in every library

[MDK #3519](https://github.com/marmot-protocol/mdk/pull/3519) opens every
constructor with device groups on and removes the separate development
library. An account on this runtime can receive a link request from another
installation, and a nonlast admin's Leave steps down first. Android therefore
ships the linked-device screens in the same change: the build switch in
`MultiDeviceExperiment.kt` is on, and the manual cases `ACC-025`, `GRP-043` and
`SET-060` to `SET-067` cover linking, the roster, device management, sign-in
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

- `DeviceLinkSnapshotFfi` gains `chatProgress`, how many chats a newly linked
  device is still waiting on ([MDK #3543](https://github.com/marmot-protocol/mdk/pull/3543)).
  It defaults to empty and Android does not show it yet.

- `followAvailabilityPage`, `followAvailabilityStatus`,
  `refreshFollowAvailability` and `cancelFollowAvailability` arrive with six
  `FollowAvailability` types ([MDK #3550](https://github.com/marmot-protocol/mdk/pull/3550)).
  They are additions only and Android does not call them yet.

Not adopted in this change: `groupChangesNotDelivered` and its event,
`attachment_removals`, file-backed draft attachments, direct chats founded
with a disappearing-message duration, `unreactFromMessageMatching` and the
sign-in notice's `client` label. None changes existing behavior.

## Upgrade

The disposable hosted upgrade campaign has not run yet. It will start from
0.17.0 at `4df811ae`. Account schema advances from 207 to 216 (migrations 0208
to 0216) and downgrade is unsupported. The campaign is to exercise populated
upgrade, cold reopen, the original pending upload and both recovery relay modes. No
qualification source differs between the two runtimes, so the baseline overlay
directory is empty. See [INT-010](manual-release-testing/cases/INT-010.md).
