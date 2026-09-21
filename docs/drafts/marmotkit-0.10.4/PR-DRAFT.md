# Proposed title

> Current checkpoint (2026-09-21): #2691 is merged and this branch is rebased onto
> `33a1aee78675f87948f74a79cb22a3c7b3350566` on master. All four actionable
> CodeRabbit findings are fixed locally, touched-function documentation was raised
> above the 80% review threshold, and the final fast gate plus both-flavor focused
> Roborazzi verification pass. The old stacked-publication instructions below are
> historical and superseded. See `IMPLEMENTATION-STATUS.md` for exact evidence.

Adopt MarmotKit 0.10.4 with host-managed attachment downloads

## Draft publication and merge hold

The user authorized publishing this work as a **draft** on 2026-09-20, superseding
the earlier local-only hold. Base this PR on #2691's branch,
`codex/complete-marmotkit-adoption-20260920`, while that PR remains open.

This saved implementation starts at the older parent checkpoint `cf4d1a147`, not
#2691's current reviewed head `2f37d7944`. The stacked diff therefore still includes
older parent commits. **Do not merge or treat it as integration-ready.** After
#2691 merges, transplant only the upgrade commits onto master, preserve its final
fixes, retarget this PR to master, and repeat validation. In particular, reconcile
automatic-to-interactive transfer ownership and native draft recovery after process
recreation, as well as the fixed slider ordering, cancellation and terminal retries.
See `IMPLEMENTATION-STATUS.md` for implementation and qualification boundaries.

## Draft summary

- Pin the immutable 0.10.4 Android cohort and update changed records, terminal
  states and typed errors.
- Hand automatic attachment acquisition to MarmotKit under Android's existing
  network/type policy, with deny-first runtime construction and generation fencing.
- Preserve eligible legacy cache hits and durable cancellation across upgrades;
  prevent local misses, terminal outcomes and reopened chats from requesting
  uncontrolled repeat downloads.
- Hide expired selected-message chat previews at their native retention deadlines.
- Keep #2691's final fixes and existing media pager behavior intact.

Durable-send adoption is included by user request: text/reply admission, matching
revision-bound media sends, upload-only preparation, native status recovery, and
exact timeline-token reconciliation. Non-draft media keeps the supported legacy
publication fallback. Qualification is pending; do not claim all issue #2493
acceptance criteria are satisfied.

## Provenance

- Source/builder: `fcc85edd8dbd07c8293c899ee52230f72c54c897`.
- Release: `marmotkit-v0.10.4`.
- Android ZIP SHA-256: `dcbb4e00c703cce2cf773c860f1f1d46b18ac49e81af5a5089902b8a31e0c16e`.
- Candidate API signature: 326 types / 322 checksums / 5 helper declarations.
- Account schema migrations: 87–89. Backups required; downgrade unsupported.

## Verification and remaining readiness gates

- Final source head and merged #2691 base: pending.
- Active lock, prepared artifact and generated signature agreement: locally verified;
  recheck after rebase.
- Saved source checkpoint: both-flavor compilation, focused Play regression tests
  (54 tests), static analysis and the complete offline fast-gate task set passed.
  This is not the full unit/CI matrix or verification of a rebased implementation.
- Counted transfer/cache/upgrade tests: pending.
- In-place emulator upgrade, restart and retained-local-media evidence: pending.
- Manual test guide/inventory validation: passed; repeat after rebase.
- Tracked expiry baseline: `app/src/test/snapshots/chat_row_expired_selected_preview.png`.
- Native/runtime/release CI: pending PR publication.

## Boundaries

Do not claim the historical 1 TB incident is explained or closed by this upgrade.
Do not claim indefinite migration of legacy cache bytes: no verified-byte import API
was added. Preserve readable old files locally and gate automatic demand explicitly.
Document the final disposition of history pagination, retry/remove UI and durable
send adoption; a dependency bump is not full application adoption.

Refs #2686. Refs #2045. Related #2058 and #2493. Use closing keywords only after
the corresponding issue's complete acceptance criteria have actually passed.
