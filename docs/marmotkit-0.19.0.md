# MarmotKit 0.19.0 on Android

Android consumes the immutable `marmotkit-v0.19.0` bundle, replacing the
[official 0.18.0 release](marmotkit-0.18.0.md). MDK's
[upgrade guide](https://github.com/marmot-protocol/mdk/blob/v0.19.0/docs/integration/0.19.0.md)
lists every host contract this section relies on.

## Published artifact and provenance

- [Release](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.19.0):
  `marmotkit-android-0.19.0.zip`.
- Source and workflow builder: `3fce95465cdea1edc8456dbd1c6691d1c8dc1f2e`, the
  commit the `v0.19.0` and `marmotkit-v0.19.0` tags both peel to.
- Independently calculated ZIP SHA-256, matching its published sibling checksum:
  `9f4dd11478265a24a98f1f0c40768b7e8eba8f1fbd4fe7b129b2f09198e3a8e4`.
- Embedded manifest: matching source, builder, version and tag, workspace
  `0.19.0`, Android API 26 and four ABIs with ELF validation.
- Generated API signature SHA-256:
  `e1e265769aa2d0edc89975b087afb8f9a3f0f0e86636b8de60ed588e76913263`, with 477
  public types, 486 UniFFI checksums and five helper declarations. The 0.18.0
  bundle had 487 types and 494 checksums: eleven agent-stream types and nine of
  their methods are gone, and `TimelineThreadRefFfi` and `sendThreadMessage`
  are new. `MarmotAndroid.kt` and `Keyring.kt` are byte-identical to 0.18.0.

The manifest does not enumerate Cargo features, so feature selection remains
**unverified by the manifest**. Builder-side evidence comes from successful
[publishing run 45026933984](https://github.com/marmot-protocol/mdk/actions/runs/45026933984),
whose four ABI jobs and Kotlin job all ran with `OTLP_EXPORT: 1` and
`PRODUCT_ANALYTICS_EXPORT: 1`, its exact-builder
[workflow](https://github.com/marmot-protocol/mdk/blob/3fce95465cdea1edc8456dbd1c6691d1c8dc1f2e/.github/workflows/bindings.yaml)
and [Kotlin builder](https://github.com/marmot-protocol/mdk/blob/3fce95465cdea1edc8456dbd1c6691d1c8dc1f2e/crates/marmot-uniffi/kotlin-bindings.sh),
which turn those inputs into `--features otlp-export` and
`--features product-analytics-export`.

## Consumer changes

- The QUIC agent text stream is gone: `startAgentTextStream`,
  `watchAgentTextStream`, `openAgentPublisher` and their types, the
  `AgentStreamActivity` event, the `AgentStreamStarted` update, both stream
  timeline triggers and `agentTextStreamJson`. Android removes its live
  preview row, the stream watchers and their retry bookkeeping, the
  "Streaming" status and the stream copy in every locale. A kind-1200 start
  no longer reaches Android, and an agent's final reply is an ordinary kind-9
  message: it appears once in its own place rather than at the start row,
  and it can be forwarded, copied and read aloud like any message. A blank
  preview of one reads "Message".
- The developer streaming-debug switch stays, because it also shows reactions,
  deletes, edits, unknown kinds and MLS details as debug rows. Only its live
  stream rows are gone, and its description no longer mentions agent streams.

- `TimelineMessageRecordFfi` and `TimelineReplyPreviewFfi` gain a nullable
  `thread`, and reply previews gain their own `replyToMessageIdHex`. Android has
  no thread view. MDK sets a thread message's `replyToMessageIdHex` to its
  parent, so Android shows a kind-1111 thread message from a client with a thread
  view inline, as a text bubble quoting its parent. MDK now counts such a
  message as chat for unread counts, mentions and search, so Android's
  jump-to-mention chip, its in-chat and global search results and its TalkBack
  announcement of a newly arrived message count a kind-1111 row that carries
  `thread` (`isThreadMessage` in `core/ThreadMessages.kt`) as they count kind
  9. A kind 1111 without a parent stays a custom row. A reply sent from Android
  stays a kind-9 inline reply. Fixtures pass `thread = null`.
- `MessageDraftAttachmentInputFfi.Removed` is new, and
  `saveMessageDraftInputsIfRevision` now keeps every attachment a save does not
  name. Android saves drafts only through the whole-list `saveMessageDraft`
  and `saveMessageDraftIfRevision`, which still replace the list, so draft
  behaviour does not change.

Not adopted in this change: `sendThreadMessage` and a thread view. Several
surfaces still treat only kind 9 as chat, so a thread message misses them: the
"Edited" label on an author's edit (the bubble shows the edited text, which MDK
supplies), the Markdown fallback parse, the edit, forward, speak, translate,
share and "show less" actions, multi-select, the files and media library and
the chat-list attachment glyph. With streaming debug on, a thread message
renders as an unknown-kind debug row instead of its bubble. Device-group and multi-device APIs stay as 0.18.0
left them; linked devices now resend invitation decisions after losing a
device-group fork without Android doing anything.

## Upgrade

The first open moves each account database from schema 216 to 218 and rebuilds
the chat list once (projection version 5). Migration 0217 repairs older kind-1111
thread rows sent by White Noise Linux, and 0218 deletes the stream tables, the
stream timeline column and every kind-1200 stream-start row; durable kind-9
replies stay. The shared and directory databases keep their 0.18.0 schemas (5
and 3). The upgrade is one way: a 0.18.0 build cannot reopen a migrated account,
so a rollback needs a copy of the data taken before the upgrade. See
[INT-010](manual-release-testing/cases/INT-010.md).

The [first hosted campaign](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45040792116)
ran on `fd30e05ed2` against the then-current master `4023b6a4`, before #3987
added the audit-choice matrix and the fixed baseline. It passed the populated
upgrade from 0.18.0, key migration, cold restart, the original pending upload
and its restart, corrupt and lost media recovery and the stale-upload retry. Six
of the seven recovery cases passed in both relay modes.
`healthyAccountReceivesAfterLocalDeletionAndRestart` failed in both
(`expected:<[healthy-incoming-0]> but was:<[]>`): receipt in a healthy chat after
another chat is deleted locally is still
[MDK #3264](https://github.com/marmot-protocol/mdk/issues/3264), which the
[0.18.0 adoption](marmotkit-0.18.0.md) also recorded on its 0.17.0 control. In
master's own runs for #3987
([45027349997](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45027349997),
[45032823081](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45032823081)),
one of eight recovery executions passed all seven cases and the other seven
failed; their logs do not name the failing case.

The [rerun](https://github.com/marmot-protocol/whitenoise-android/actions/runs/45071102711)
on `566fd82fa8` built master's `6a59fe30` as the baseline and ran both audit
choices. Each passed every phase before recovery and six of the seven recovery
cases in both relay modes. `healthyAccountReceivesAfterLocalDeletionAndRestart`
failed in all four executions (`pass=0 live_attempts=4 timeline=1`). On
2026-11-22 the maintainer waived that case for this adoption as pre-existing
MDK #3264, as for 0.17.0. The waiver covers 0.19.0 only: a later bump that
still fails the case needs its own decision until MDK #3264 is fixed.
