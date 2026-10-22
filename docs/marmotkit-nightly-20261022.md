# MarmotKit nightly 20261022 evaluation

This testing-only adoption addresses the remaining replay qualification in
[Android #3289](https://github.com/marmot-protocol/whitenoise-android/issues/3289).
It advances current master's October 21 nightly. Readiness requires the exact-head
hosted gates below; this is not production adoption or permission to merge.
Use hosted disposable emulators only. Preserve all existing apps, data and recovery
artifacts; never uninstall, clear storage, downgrade or rewrite migration records.

## Verified artifact and API

| Field | Verified value |
| --- | --- |
| Release | [`marmotkit-v0.14.0-nightly.20261022`](https://github.com/marmot-protocol/mdk/releases/tag/marmotkit-v0.14.0-nightly.20261022) |
| ZIP | `marmotkit-android-0.14.0-nightly.20261022.zip` |
| ZIP SHA-256 | `7762c715c3803de2adaf6fc4a172619c05329336899ee5a23cde4c3db8266d3e` |
| Source / builder | `77f22e35499a42bc96402ce19b79ceed57ffbcc3` |
| Manifest / archive identity | `marmotkit-android` / `0.14.0-nightly.20261022` |
| Archive root | `marmotkit-android-0.14.0-nightly.20261022` |
| Workspace / Android API | `0.14.0-dev` / `26` |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |
| Cargo.lock SHA-256 | `f49752b5d680cd63e2818657d53db1e2e6f005ae59ca44d050a25a79a5599953` |
| Toolchain | Rust/Cargo `1.97.1`, NDK `27.2.12479018` |

The complete 95,504,251-byte download matches both the sibling checksum and
GitHub's asset digest. The sibling checksum's digest is
`9232b50958464ea9edf5b75c404bdb556681e2fed27554013217d3ddec5a9a1b`.
The unchanged artifact validator accepts all nine payload files and checks each
ELF's architecture, digest, required exports and load alignment, including 16 KiB
alignment for both 64-bit libraries. The exact-source Cargo.lock also matches.

The embedded manifest still omits `features`; its status remains **unverified**.
Separate exact-source [builder settings](https://github.com/marmot-protocol/mdk/blob/77f22e35499a42bc96402ce19b79ceed57ffbcc3/.github/workflows/bindings.yaml#L51-L52)
and successful [nightly publication](https://github.com/marmot-protocol/mdk/actions/runs/39724430695)
support retaining `otlp-export,product-analytics-export`. No validator was weakened
and no absent manifest field was invented.

The previous signature is the retained October 21 artifact's
`e9be2810f149ae87aa0e8527af2b4b83e8f2792f4a84852c1c6d016aacdb9869`.
Counts remain **384 types / 371 checksums / 5 helper declarations**. Static
signature inspection produces
`9728b08a689f7562589b37526ef69733192ae41875a89d2df4bc7404e8c0e78f`;
hosted `:app:stageMarmotKitApiSignature` must reproduce it.

- `AttachmentStorageUsageFfi.cacheBytes` reports clearable avatar/group-image
  cache bytes separately from retained attachments. Android has no constructors
  requiring adaptation; this adoption does not add a storage-breakdown UI.
- `MarmotKitException.FollowListTooLarge` is a zero-field safety refusal, distinct
  from retryable `FollowListUnavailable`. Diagnostics preserve that distinction.
  Its insertion shifts generated error discriminants; the binding and native
  library must always be consumed together from this ZIP.
- `followUser` and `unfollowUser` keep their method shapes. Their documentation
  and checksums change (26050 → 64554, 7605 → 44806). Native follow edits preserve
  existing tags/content and refuse capped lists without publishing.
- Both Kotlin platform helpers are byte-identical. The complete generated diff
  was reviewed; no generated binding is hand-edited.

## Native fixes and migration boundary

The [23-commit range](https://github.com/marmot-protocol/mdk/compare/8c214fee07466b0b7b63f8dfa39877cce1f63573...77f22e35499a42bc96402ce19b79ceed57ffbcc3)
includes:

- [MDK #2513](https://github.com/marmot-protocol/mdk/pull/2513): ordered offline
  catch-up, fixing #2375 and #2381. Migration **0115_recovery_replay_admission**
  extends the durable recovery ledger while preserving its frozen scope windows.
  Android re-enables both regressions and requires all recovered plaintexts.
- [#2500](https://github.com/marmot-protocol/mdk/pull/2500): backward paging for
  epoch-gap fallback beyond the newest 256 events when comparison is unavailable.
- [#2504](https://github.com/marmot-protocol/mdk/pull/2504),
  [#2524](https://github.com/marmot-protocol/mdk/pull/2524) and
  [#2527](https://github.com/marmot-protocol/mdk/pull/2527): avatar/group-image files,
  migration **0114_avatar_media_files**, capped open-time image copying and native
  cache migration/refetch. The cap is 32 MiB of database-held image bytes, smallest
  first; cache eviction can require reacquisition. Retained attachments are separate.
- [#2523](https://github.com/marmot-protocol/mdk/pull/2523): stop retrying terminal
  relay rejections; transient failures remain retryable.
- [#2525](https://github.com/marmot-protocol/mdk/pull/2525): strict directory fetches
  use at most three filters per request.
- [#2529](https://github.com/marmot-protocol/mdk/pull/2529): preserve kind-3 contact
  list data and refuse unsafe edits at the read cap. Directory extraction and the
  WebPKI update are also included.

All migration, protocol ordering, retry policy and recovery remain MDK-owned.
This does not certify complete history when a relay has deleted events or solve
arbitrary same-second event ordering. Broader invitation UI (#2452), storage UI
(#2842), sign-out cleanup (#2047) and negotiated media limits (#1499/#2284) retain
their own acceptance criteria; this PR does not close them.

## Qualification and issue closure

The existing typed invitation/admission presentation from #3335 remains intact.
Current-master `8c214fee` already uses attachment files, so the new hosted pair
checks the actual 0114/0115 upgrade instead of applying the old 0113 SQL-body
compaction/crash hooks to an unrelated native binary. The historical #3371/October
21 evidence stays bound to its original source and artifacts.

Required candidate evidence:

- Full hosted core CI, generated signature, focused diagnostic/invitation/group
  admission regressions and unchanged tracked screenshot comparisons.
- Populated current-master baseline → candidate update in place, usable normal
  MainActivity chats, then full process stop/cold restart. Original account IDs,
  history, retained attachment bytes and cached avatar digests must survive with
  acquisition disabled. Pending upload promotion/restart and explicit corrupt/lost
  disposable-media recovery remain separate required phases.
- All **seven** recovery cases in **both** comparison-refusal and silence modes:
  six-sender 600-message replay, single-sender 600-message replay, 150-second
  offline replay, epoch-gap fallback, catching-up acknowledgement, lookup retry
  into exactly one group, and healthy bidirectional receive after another local
  deletion and receiver restart. Missing, failed or ignored tests fail the campaign.
- Packaged native smoke, production/staging release-runtime verification and
  arm64 reproduction under current hosted CI policy; exact-head review and gates.

The baseline avatar is the 2,898-byte launcher PNG from Android commit
`512dcf241e155ff71d6751b0d06d9cb8b9830cd4`, fetched through MDK over public HTTPS.
Its required SHA-256 is
`a447905dfc5868e09924d9539d05832bdf058863a144a73476f67741b315d6a6`.
After seeding, UID-scoped IPv4/IPv6 rules deny public egress for the disposable
app before any candidate reads. Loopback relay/blob access remains available;
the blob fixture separately refuses retained attachment acquisition until the
explicit recovery phases. Verified firewall rules and original image digests
prevent a network refetch from concealing lost avatar cache data. This changes
only the hosted fixture; it does not relax MDK's HTTPS or destination policy.

[Full MDK CI at the exact source](https://github.com/marmot-protocol/mdk/actions/runs/39685697283)
already passed the composed #2318 regressions:
`quarantined_group_does_not_block_account_receives` (shard 5),
`locally_deleted_quarantined_group_does_not_block_account_receives` and
`quarantined_group_events_do_not_retain_account_projection` (shard 4).
These cover native quarantine, deletion, retries, send-before-drain and restart.
The additional Android test checks the packaged binding's healthy peer receive;
it does not inject or claim to reproduce quarantine. This avoids host-owned SQL
or recovery workarounds and does not mislabel upstream evidence as Android evidence.

At initial submission the Android hosted campaigns and independent PR review are
pending. The PR body records their actual exact-head results. Keep draft while
required qualification is missing. Add the closing reference for #3289 only when
its remaining acceptance is evidenced; do not manually close it.

No physical-device or trusted-preview qualification is claimed. The isolated
`.mdkqualification` package and disposable signer are for hosted evidence. No
human APK handoff is part of this request; a future trusted preview handoff needs
its own exact signed-APK current-master upgrade and cold-restart proof.

## Retention

The nightly was published on 22 October and expires after 30 days, approximately
**21 November 2026**. Keep the verified ZIP, sibling checksum, manifest, Cargo.lock,
API signature/diff and exact tested APKs with their provenance beyond that deadline.
This task retains the downloaded archive and verification evidence locally;
GitHub qualification artifacts are retained for 30 days and are not a durable
archive. Release deletion can break a clean build even though an existing
content-addressed cache still works. Preserve reviewed bytes or move to another
reviewed published artifact before expiry; never substitute a mutable build or
silently roll a migrated installation back.
