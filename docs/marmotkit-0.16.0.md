# Official MarmotKit 0.16.0 adoption

This record replaces the official 0.15.0 pin with the official 0.16.0 release. It
authorizes neither merging nor an Android production release.

## Verified artifact

- Release: [`marmotkit-v0.16.0`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.16.0), published 12 November 2026.
- Asset: `marmotkit-android-0.16.0.zip`, 109,017,284 bytes.
- ZIP SHA-256: `48cb915a16ecc78aea1773245784f1b083f63710cdd38b155f0d5c27d3e98c73`;
  independently calculated and matching both the sibling checksum and the release asset digest.
- Source/builder: `a2e0558a634bbd20607cdd5afb93abc6220ee58d`. `v0.16.0` and
  `marmotkit-v0.16.0` both peel to it. The embedded manifest identifies
  `marmotkit-android`, artifact/workspace version `0.16.0`, Android API 26, rustc
  1.97.1, NDK 27.2.12479018 and the exact-source Cargo.lock SHA-256
  `59ba9306d993ddce8cf648d1cc47597e49c5873ddfb5c8c50854c9ef9d84acb0`.
- Archive root: `marmotkit-android-0.16.0`. The unchanged Android validator checks
  the layout, the three generated Kotlin files and all four ABI libraries.
- Manifest feature enumeration is absent and remains **unverified by the
  manifest**. The release [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/43193260379)
  records `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1` in every Android ABI
  build (arm64-v8a, armeabi-v7a, x86, x86_64) and the Kotlin input at the same
  source and builder commit, matching the unchanged `otlp-export,product-analytics-export`
  lock selection. This verifies the builder selection, not a manifest field.

## API and consumer compatibility

Counts move from 447/447/5 to **463 public types / 477 checksums / 5 helpers**.
`MarmotAndroid.kt` and the keyring helper are byte-identical to 0.15.0. All 16 new
types are additions: NIP-46 sessions (5 records/enums plus the session interface),
relay liveness (3 plus the subscription interface), `RelayEventLimitFfi`, and audit
delivery status and retention (5). The 30 new checksums cover the new methods,
including `startDirectConversation` and `reconnectRelay`. One existing checksum changes:
`marmot_deployment_white_noise`, because the record it returns gained a field. Android
adopts none of the new optional APIs here.

Four existing public types change:

- `AppGroupHydrationQuarantineReasonFfi` gains `REWIND_RESTORE_FAILED`. MDK only
  retries that restore when the account reopens, so Retry always reports the group
  still quarantined. Quarantined groups shows the shortened group ID and offers no Retry
  for that reason. Its guidance, in every shipped locale, is to force stop White Noise in
  Android settings or restart the phone: with the background connection on (the default),
  swiping the app away keeps the process and the open account alive, so it does not reopen
  the account.
- `MarmotKitException` gains `Nip46`. Android has no exhaustive `when` over the
  exception and uses no NIP-46 session, so it stays on existing fallbacks.
- `GroupEventKindFfi.QueuedIntentRefused` adds `artifact` and `sizeClass`. Android
  neither constructs nor destructures it.
- `MarmotDeploymentFfi` adds `relayEventLimits`. Android only calls
  `marmotDeploymentWhiteNoise()`, which already lists 2 MiB for both White Noise relays.

`EventTooLarge` is now raised up front for a group create or invite whose Welcome
fits none of an invitee's inbox relays. Before, that create succeeded with the
Welcome owed. Retry cannot help, so `EventTooLarge(WELCOME)` now says the invitation
is too large for a recipient's inbox relays and asks them to add one that accepts
larger messages, instead of the generic retry text. It applies to direct chats, group
creation and adding or inviting members alike.

Other `EventTooLarge` sources fall to existing generic copy, which changes what two
surfaces show. An oversized block edit used to fail as `BlockListUnavailable` and
show `block_list_unavailable`; it now shows `block_change_failed`. An oversized
sticker sync used to fail as `StickerNetwork` and show `sticker_error_network`; it
now shows `error_try_again`. KeyPackage, profile and relay-list publishes have no
dedicated copy. No generated
binding is edited. Device-group and multi-device APIs stay unused and untouched.

## Native changes and migration boundary

See MDK's [0.16.0 release notes](https://github.com/marmot-protocol/mdk/blob/v0.16.0/docs/release/0.16.0.md)
and [integration guide](https://github.com/marmot-protocol/mdk/blob/v0.16.0/docs/integration/0.16.0.md).
Highlights: OpenMLS moves to upstream with the GHSA-gc79-23g3-8g52 and
GHSA-p276-g3f3-wwgh fixes, relay event size limits, a fixed stall after a late
end-of-stored-events reply, less history loss on quick joins, unstuck leaves and
sign-outs, and a removed chat no longer waking its worker every 10 ms.

Account databases are not migrated. The shared database runs migration 0004
(schema 3 → 4, audit-log retention), and the local audit delivery cursor moves to
version 2. A 0.15.0 binary refuses both afterwards, so downgrade stays unsupported.
Never rewrite migration rows, clear data or downgrade to make qualification pass.

## Qualification

The disposable `.mdkqualification` pair now uses current master's official 0.15.0
pin (`827687ba`) as its baseline and fails closed on any other. The 0.15.0 and 0.16.0
runtimes share every type the qualification sources use, so the former
`QualificationTimelineQuery` baseline overlay is retired. Require usable chats after
upgrade and full stop/cold restart, unchanged retained bytes with acquisition
blocked, the original pending upload sending unchanged, and all seven recovery cases
in both relay modes, with no skips. Record the exact-head run in the PR; historical,
skipped or cancelled runs do not satisfy it.

### Accepted exception

[Run 43208824959](https://github.com/marmot-protocol/whitenoise-android/actions/runs/43208824959)
passed every retained-data phase. Recovery passed 6 of 7 cases in both relay modes;
both failed `healthyAccountReceivesAfterLocalDeletionAndRestart` with
`expected:<[healthy-incoming-0]> but was:<[]>`. This is the intermittent failure tracked as
[mdk#3264](https://github.com/marmot-protocol/mdk/issues/3264), still open in 0.16.0 and
accepted for the 0.15.0 adoption. A maintainer accepted it again for this adoption on
12 November 2026. Without that acceptance this pin would not qualify; the exception covers
only this case.

## Retention

Both the 0.15.0 baseline and the 0.16.0 candidate are immutable formal releases.
