# MarmotKit 0.20.0 nightly of 25 November 2026 on Android

Android consumes the immutable `marmotkit-v0.20.0-nightly.20261125` bundle,
replacing the [official 0.19.0 release](marmotkit-0.19.0.md), so agent chats can
read agent items from MDK and send answers and controls as kind 1202
([MDK #3613](https://github.com/marmot-protocol/mdk/pull/3613),
[MDK #3640](https://github.com/marmot-protocol/mdk/pull/3640)). MDK publishes
nightlies for testing; replace this pin with the formal `marmotkit-v0.20.0`
release before a store release.

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.20.0-nightly.20261125):
  `marmotkit-android-0.20.0-nightly.20261125.zip`.
- Source and workflow builder: `6c0127fad052688184803751ff6a73abfb669f19`.
- Independently calculated ZIP SHA-256, matching its published sibling checksum:
  `cf3692c6f748469268dc41919883a439c785975e5b542ba97f6ae17a0557b9d6`.
- Embedded manifest: matching source, builder, version and tag, workspace
  `0.20.0-dev`, Android API 26 and four ABIs with ELF validation.
- Generated API: 0.19.0 plus `AgentItemFfi`, `AgentItemStateFfi` and the
  invite-delivery records (`InviteDeliveryFfi`, `InviteDeviceDeliveryFfi`,
  `InviteRelayCountsFfi`, `InviteCancelReasonFfi`, `InviteDeliveryProblemFfi`,
  `InviteDeliveryStateFfi`). `TimelineUpdateTriggerFfi.AGENT_ACTIVITY` and
  `AGENT_OPERATION` become `AGENT_ITEM`, and
  `NotificationTrafficClassFfi.AGENT_ACTIVITY` is gone.

The manifest does not enumerate Cargo features, so feature selection remains
**unverified by the manifest**. Builder-side evidence comes from successful
[publishing run 45519931121](https://github.com/marmot-protocol/mdk/actions/runs/45519931121),
its exact-builder
[workflow](https://github.com/marmot-protocol/mdk/blob/6c0127fad052688184803751ff6a73abfb669f19/.github/workflows/bindings.yaml)
(lines 51–52 set both inputs) and
[Kotlin builder](https://github.com/marmot-protocol/mdk/blob/6c0127fad052688184803751ff6a73abfb669f19/crates/marmot-uniffi/kotlin-bindings.sh)
(lines 53–61 turn `OTLP_EXPORT` and `PRODUCT_ANALYTICS_EXPORT` into
`--features otlp-export` and `--features product-analytics-export`). Each
Android input job ran
`ANDROID_ABIS="$PART" ./crates/marmot-uniffi/kotlin-bindings.sh native`, and its
log shows `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1`. Neither the
workflow, the builder nor any job log passes `--dev-device-groups` or
`test-policy-overrides`.

| Input (`PART`) | Job |
| --- | --- |
| `arm64-v8a` | [138889323214](https://github.com/marmot-protocol/mdk/actions/runs/45519931121/job/138889323214) |
| `armeabi-v7a` | [138889323038](https://github.com/marmot-protocol/mdk/actions/runs/45519931121/job/138889323038) |
| `x86` | [138889323347](https://github.com/marmot-protocol/mdk/actions/runs/45519931121/job/138889323347) |
| `x86_64` | [138889323019](https://github.com/marmot-protocol/mdk/actions/runs/45519931121/job/138889323019) |
| `kotlin` | [138889322993](https://github.com/marmot-protocol/mdk/actions/runs/45519931121/job/138889322993) |

## Consumer changes

- Agent items: a kind-1201 row carries `agentItem`, the highest version MDK
  holds, on the item's first-event row. MDK applies kind-1009 versions there and
  never gives kind-1202 answers or controls a row, so Android draws each item
  from `agentItem` (`core/agent/AgentItemReader.kt`) without folding versions
  itself. A kind-1201 row without one stays a generic row. Answers and Stop are
  kind 1202 events with one `p`, one `e` naming the item's row and one
  `details` object, sent through `sendCustomEvent`.
- Timeline and search triggers use `AGENT_ITEM` where they listed
  `AGENT_ACTIVITY` and `AGENT_OPERATION`. Agent items notify as `STANDARD`, and
  only while an approval or question waits or when a turn failed, so the
  agent-activity traffic class branches are removed. The Agent activity
  channel stays registered; no update routes to it.
- Fixtures pass `agentItem = null` and `agentItemFirstEventMissing = false`.

Not adopted in this change: `sendCustomEventWithClientToken`, the
`submitTagged…` acceptance calls, invite delivery state and
`agentItemFirstEventMissing` (a late joiner's row is drawn like any other).
Android continues to leave device-group and multi-device APIs unused.

## Native runtime changes

- History recovery checks incomplete seconds when relay metadata is unavailable.
  Recovery sweeps and known-history fetches use bounded requests that keep bulk
  history off the live SDK reader. A relay's explicit refusal can release live
  messages held behind recovery. [MDK #3608](https://github.com/marmot-protocol/mdk/pull/3608),
  [#3641](https://github.com/marmot-protocol/mdk/pull/3641),
  [#3644](https://github.com/marmot-protocol/mdk/pull/3644),
  [#3664](https://github.com/marmot-protocol/mdk/pull/3664).
- Invitation delivery tracks partial KeyPackage reads and attempts every known
  inbox relay on the first Welcome pass. MDK records delivery and delays retries
  when a recipient has no inbox route. [MDK #3669](https://github.com/marmot-protocol/mdk/pull/3669),
  [#3686](https://github.com/marmot-protocol/mdk/pull/3686),
  [#3687](https://github.com/marmot-protocol/mdk/pull/3687),
  [#3701](https://github.com/marmot-protocol/mdk/pull/3701).
- Newly linked devices receive small state before MDK adds them to chats. MDK
  refuses small state from devices the roster no longer lists.
  [MDK #3635](https://github.com/marmot-protocol/mdk/pull/3635),
  [#3650](https://github.com/marmot-protocol/mdk/pull/3650),
  [#3680](https://github.com/marmot-protocol/mdk/pull/3680).

## Upgrade

The account database moves from schema 0218 to 0225.

| Migration | Change |
| --- | --- |
| 0219 | Indexes agent item order and removes existing kind-1202 rows. |
| 0220 | Repairs fallback chat activity timestamps. |
| 0221 | Tracks expired agent item targets. |
| 0222 | Records the roster targeted by the last small-state broadcast. |
| 0223 | Preserves invitation status for locally deleted chats. |
| 0224 | Preserves epoch-gap evidence across recovery and restart. |
| 0225 | Records outbound Welcome deliveries. |

The upgrade is one way. A 0.19.0 build cannot reopen a migrated account, so a preview built from
this pin must not be installed over data another 0.19.0 preview still needs.
See [INT-010](manual-release-testing/cases/INT-010.md).

The [hosted upgrade run](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45719974512)
tested Android commit `c2982e32d` against populated 0.19.0 accounts with audit
sharing enabled and declined. Both
runs opened the retained conversation through MainActivity, preserved account
keys and retained media across restart, sent the original pending upload, and
recovered missing and corrupt media.

All seven recovery cases passed when the relay refused history comparison.
Six passed when it silently ignored comparison. In both audit modes,
`healthyAccountReceivesAfterLocalDeletionAndRestart` failed before the restart.
The healthy chat's first peer message missed the 60-second receive window. This relay behavior
remains open in [MDK #3643](https://github.com/marmot-protocol/mdk/issues/3643).
