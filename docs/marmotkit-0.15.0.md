# Official MarmotKit 0.15.0 adoption

This record replaces the November 9 nightly pin with the official release. It
authorizes neither merging nor an Android production release.

## Verified artifact

- Release: [`marmotkit-v0.15.0`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.15.0), published 9 November 2026.
- Asset: `marmotkit-android-0.15.0.zip`, 106,209,569 bytes.
- ZIP SHA-256: `f5c1fc59080e0d1eb9e657ab3bb1ae8ca86c720b94d691c8dd8ef97ca52ff9a9`;
  independently calculated and matching the sibling checksum.
- Source/builder: `827687ba7fdbb05ec1b8f17667ad919fedde7266`, MDK's `Release MDK 0.15.0`
  commit. The embedded manifest identifies `marmotkit-android`, artifact/workspace
  version `0.15.0`, Android API 26, rustc 1.97.1, NDK 27.2.12479018 and the
  exact-source Cargo.lock SHA-256
  `81ce514527c7583cc7211071e2aa612756e7d767e16209d1a2dbd43bef16c7e3`.
- Archive root: `marmotkit-android-0.15.0`. The unchanged Android validator checks
  the layout, the three generated Kotlin files and all four ABI libraries.
- Manifest feature enumeration is absent and remains **unverified by the
  manifest**. The release [publishing run](https://github.com/marmot-protocol/mdk/actions/runs/42647056766)
  records `OTLP_EXPORT: 1` and `PRODUCT_ANALYTICS_EXPORT: 1` in every Android ABI
  build (arm64-v8a, armeabi-v7a, x86, x86_64) at the same source and builder
  commit, which `kotlin-bindings.sh` maps to the unchanged
  `otlp-export,product-analytics-export` lock selection. This verifies the
  builder selection, not an invented manifest field.

The inspected Android master baseline is
`2ab061896da33b02fde8cec591c4379098eab422`, pinning November 9 nightly source
`b3e484687968d5e1172d3c7a998c09da44e7e005`. The hosted pair records its actual
master commit and rejects any different native baseline before building.

## API and consumer compatibility

Counts stay at **447 public types / 447 checksums / 5 helpers**. The generated
Kotlin diff changes two things Android consumes:

- `TimelineMessageQueryFfi` gains required `sender`, `since`, `until` and
  `cursorGroupId` fields and defaulted `attachmentType`/`searchWallClock` fields
  for advanced search. Every Android query goes through
  `TimelineMessageQueryFfi.page`, which leaves the new filters unset, so each
  read returns the same page as before. Android does not adopt advanced search here.
- `HistoryNoticeCauseFfi` gains `BEFORE_REJOIN`. MDK lists a rejoin's notice
  among the group's `historyNoticeIds`, so the conversation's history notice now
  reads "You rejoined this chat. Messages from before you rejoined may be missing."
  when its first notice is a rejoin, in every shipped locale.

`MarmotAndroid.kt` and the keyring helper are unchanged. No generated binding is
edited. Device-group and multi-device APIs stay unused.

## Native changes and migration boundary

The [14-commit delta](https://github.com/marmot-protocol/mdk/compare/b3e484687968d5e1172d3c7a998c09da44e7e005...827687ba7fdbb05ec1b8f17667ad919fedde7266)
includes advanced timeline search (#3245), retiring a rejoined group's history
from before its new copy (#3247) with the new notice cause, sign-out wipes that
step an admin down and leave chats with another admin (#3255, mdk#2818), recording
an admin's leave before its step-down (#3250), buffered replay retry after a
replacement Welcome (#3244), committed rejoin status announcements (#3246), idle
maintenance no longer repeating finished housekeeping (#3035), dropped relay
transport release (#3249), SQL-filtered cached user search (#3257), hint-less
event previews (#3213) and bare-domain profile links in onboarding (#3260).

Sign Out and Delete Data can now wait up to about 30 seconds more while it leaves
chats. The wipe progress sheet says it can take up to a minute and asks to keep
White Noise open. An interrupted wipe that reports `localCleanup.completed == false`
already keeps the account on Android.

Account migration 0153 (recovery copies) is new. It lands below the
already-applied 0201 and must apply exactly once. Downgrading a migrated account
stays unsupported. Never rewrite migration rows, clear data or downgrade to make
qualification pass.

## Qualification

Exact-source upstream [CI 42640881603](https://github.com/marmot-protocol/mdk/actions/runs/42640881603)
passed the merge-group matrix, and the release bindings build passed.
Publication alone is not Android runtime evidence.

Required Android checks run through hosted CI. The disposable
`.mdkqualification` pair uses one signer and the reviewed November 9 baseline.
The baseline checkout compiles the qualification package with
`scripts/mdk-baseline-overlay/QualificationTimelineQuery.kt.txt` in place of the
candidate's query helper, since the query's fields differ between the two runtimes.
Require usable chats after upgrade and full stop/cold restart, unchanged retained
bytes with acquisition blocked, the original pending upload sending unchanged,
and all seven recovery cases in both relay modes, with no skips. Record the
exact-head run in the PR; historical nightly, skipped or cancelled runs do not
satisfy it.

## Retention

The official asset is an immutable formal release. The November 9 baseline
nightly expires after 30 days; preserve its verified archive and qualification
APKs so the recorded upgrade pair stays reproducible.
