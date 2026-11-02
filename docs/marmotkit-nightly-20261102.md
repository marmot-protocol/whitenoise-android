# MarmotKit November 2 nightly evaluation

This is testing-only adoption of the latest MDK nightly. It brings in MDK fixes that
are not yet in a formal release, so Android can test them before MarmotKit 0.15.0:
sends that can no longer overtake the same group's queued sends (mdk#2842, mdk#2849),
full-disk failures that no longer latch the account (mdk#2826) and a group that no
longer fails to load over one undecodable queued proposal (mdk#2806). It does not
authorize production distribution. Replace it with the formal `marmotkit-v0.15.0`
release once MDK publishes one.

## Published artifact and baseline

The [nightly release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0-nightly.20261102)
publishes `marmotkit-android-0.15.0-nightly.20261102.zip` from MDK source and
workflow builder `2854335c6e29f6bf236b1f986c62c1b9ab31114c`.

- ZIP SHA-256: `578f67eb2071ad15f08b3d3d79d69556ebcdb653975fea0aac4695a639e92cc9`.
  Independently calculated and matched to the sibling checksum and GitHub digest.
- Embedded manifest: `marmotkit-android`, version `0.15.0-nightly.20261102`,
  matching tag/source/builder, workspace `0.15.0-dev`, Android API 26. The manifest's
  Cargo.lock SHA-256 `929e10cb477f9fd21bb8dfe8ea4e64dee8d7787fe81fac85234d96d6b9313ae9`
  matches the exact-source file.
- The archive holds the generated Kotlin bindings and the four ABI libraries:
  arm64-v8a, armeabi-v7a, x86 and x86_64. Android's unchanged preparation script
  validates the layout and ELF metadata.
- Manifest features are absent, so feature selection is **unverified by the
  manifest**. The [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/41397816125)
  sets `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1` in all four Android ABI
  build jobs, and the exact-source
  [`kotlin-bindings.sh`](https://github.com/marmot-protocol/mdk/blob/2854335c6e29f6bf236b1f986c62c1b9ab31114c/crates/marmot-uniffi/kotlin-bindings.sh)
  maps them to the unchanged `otlp-export,product-analytics-export` Cargo features.
  `test-policy-overrides` appears only in MDK's own CI test lanes. Retained excerpts
  from that run's four "Build Android input" jobs:

  ```text
  Build Android input (arm64-v8a)    2026-11-02T06:07:03.0184808Z  OTLP_EXPORT: 1
  Build Android input (arm64-v8a)    2026-11-02T06:07:03.0185058Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (armeabi-v7a)  2026-11-02T06:07:03.4955031Z  OTLP_EXPORT: 1
  Build Android input (armeabi-v7a)  2026-11-02T06:07:03.4955463Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (x86)          2026-11-02T06:07:03.9794259Z  OTLP_EXPORT: 1
  Build Android input (x86)          2026-11-02T06:07:03.9794697Z  PRODUCT_ANALYTICS_EXPORT: 1
  Build Android input (x86_64)       2026-11-02T06:07:04.4567706Z  OTLP_EXPORT: 1
  Build Android input (x86_64)       2026-11-02T06:07:04.4568157Z  PRODUCT_ANALYTICS_EXPORT: 1
  ```
- The baseline is master's October 30 nightly pin, MDK
  `f7178f0e997e3d6d450fb0af45a32e4780ed862c` ([October 30 record](marmotkit-nightly-20261030.md)).
  The candidate is 77 MDK commits ahead of it.

## API and compatibility

The generated Kotlin diff against the October 30 nightly adds 21 public types and
27 methods, for device linking, sign-in checks, compromise warnings, chat history
import, account recovery status and Sonar-compatible stickers. Nothing is removed.
Counts advance from 391/378/5 to **412 types / 405 checksums / 5 helpers**. The
staged API signature SHA-256 is
`16e01b4374946ce5a7d74eb3c814a64158a0d5534fa57157ab0930a5c979f72c`; hosted
`stageMarmotKitApiSignature` must produce the same bytes.

Existing records gain fields without defaults, so every Kotlin construction now
passes them:

| Record | New fields |
| --- | --- |
| `AppMessageRecordFfi`, `ReceivedMessageFfi`, `ChatListMessagePreviewFfi`, `NotificationUpdateFfi`, `TimelineReplyPreviewFfi` | `sticker` |
| `TimelineMessageRecordFfi` | `sticker`, `importedFrom` |
| `AccountSummaryFfi` | `unlinked` |
| `WipeOutcomeFfi` | `groupsLeftToSiblings`, `siblingsNotReached` |
| `LinkedDeviceFfi` | `lastActiveAt`, `inactive`, `removalPending` |

Android changes needed to keep current behavior:

- Records Android builds from another MDK record carry `sticker` across
  (`TimelineProjector`, the chat-list latest message and the initial timeline
  seed). Optimistic local sends and test fixtures pass `null`, `false` or `0u`.
  Android shows no sticker UI yet.
- `MarmotEventFfi` adds `UnrecognizedSignInsChanged`, `CompromiseWarningChanged`,
  `DeviceLinkChanged`, `ChatHistoryImportsChanged`, `RecoveryStatusChanged` and
  `DeviceUnlinked`. Each gets a content-free diagnostic line in the exhaustive
  `DiagnosticFormatter.describe`.
- `SelfMembershipFfi` and `ConversationParticipationFfi` add `DEVICE_REMOVED`: this
  device's leaf left a chat the account stays in through another of its devices.
  Android treats it as terminal for this device (`isNonMember`, invite
  availability) but never as the account leaving the roster or as an eviction.
- `HistoryNoticeCauseFfi.UNPROVEN_HISTORY` and the new `MarmotKitException`
  variants (`AccountUnlinked` and five sticker errors) need no exhaustive
  handling in Android today.

## Device groups stay inert

Everything under mdk#2628 (device linking, sign-in checks, sibling sign-out,
history bundles, account backups under a recovery key, removing a linked device)
runs only when `device_groups_enabled()` holds, which requires the
`test-policy-overrides` feature this artifact does not enable. In this build the
new device-group methods return empty or unchanged state and the six new events
are not emitted. `DEVICE_REMOVED` cannot arise until the gate opens.

## Storage-full presentation

mdk#2826 stops a transaction that SQLite rolled back on its own (full disk, I/O
error, out of memory, a failed `COMMIT`) from latching the account connection until
it reopens. The caller now receives the failure's own error, so a full disk reaches
Android as `MarmotKitException.StorageFull` on paths that used to report a backend
failure. Sends already presented it. Opening a chat and creating a group or
inviting now say "Not enough storage on this device. Free up space and try again."
instead of the generic retry text, and opening a chat keeps its Retry action
because the account is no longer latched.

## Behavior that changes without Android code

- A send never overtakes the same group's queued sends (mdk#2842, mdk#2849).
  Before this, members who were offline or recovering could silently lose up to
  about 100 messages that overtook a sender's queue.
- A group no longer fails to load over one undecodable queued-proposal row
  (mdk#2806), and one damaged `app_events` tag blob no longer blocks erasure,
  withdrawals, poll tallies or history export (mdk#2845).
- An application message is pinned to its epoch's branch, not only its number, so
  encrypted media cannot ship under a key schedule recipients cannot use (mdk#2819).
- Welcome delivery: a failure in maintenance or convergence now raises
  `WelcomeDeliveryPending` (mdk#2745), a successful retry marks the Welcome
  delivered (mdk#2733), and an invite writes no pending row before its commit is
  exposed (mdk#2756). Android still only logs that event.
- Device-group kinds 457, 459, 460 and 462 received in an ordinary chat are dropped
  instead of projected as timeline rows or notifications (mdk#2757).

## Migration and retained data

MDK adds account storage migrations 0133, 0135–0149, 0160–0164 and 0166 (device
links, recovery fences and conversion, device renames and activity, history keys
and imports, private and announced KeyPackages, Sonar stickers, roster tag keys,
device removals, the `DEVICE_REMOVED` membership trigger, compromise warnings,
history deliveries, sibling Welcomes, backups and the recovery key). The runner
records each version separately, so 0133 still applies over an October 30 install
that already ran 0134. The upgrade is one way: an install opened by this nightly
cannot be reopened by the October 30 runtime. Never install it over a phone that
has to go back to a master build.

The populated hosted campaign now uses master's October 30 pin as its reviewed
baseline: `scripts/build-mdk-qualification.sh` and
`scripts/stage_mdk_baseline_fixture.py` fail closed on any other baseline runtime.
Dispatch it once on this branch:

```bash
gh workflow run android-instrumented.yml --repo marmot-protocol/whitenoise-android \
  --ref chore/marmotkit-nightly-20261102 -f mdk_qualification=true
```

Its upgrade, cold restart, pending-upload promotion and recovery phases must all
pass before this pin counts as qualified. Physical-device evidence stays separate.
