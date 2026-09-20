# Proposed title

Adopt MarmotKit 0.10.4 with host-managed attachment downloads

## Publication hold

LOCAL DRAFT ONLY. Do not submit this body until #2691 is merged and the implementation
has been rebased/transplanted onto that final master. The sections below describe
planned scope, not implemented or verified behavior. Replace future tense and every
pending verification with actual evidence before publication.

## Planned summary

- Pin the immutable 0.10.4 Android cohort and update changed records, terminal
  states and typed errors.
- Hand automatic attachment acquisition to MarmotKit under Android's existing
  network/type policy, with deny-first runtime construction and generation fencing.
- Preserve eligible legacy cache hits and durable cancellation across upgrades;
  prevent local misses, terminal outcomes and reopened chats from requesting
  uncontrolled repeat downloads.
- Hide expired selected-message chat previews at their native retention deadlines.
- Keep #2691's final fixes and existing media pager behavior intact.

Durable-send adoption: pending user scope decision. Existing APIs remain supported;
do not claim token-aware composer adoption unless implemented and qualified.

## Provenance

- Source/builder: `fcc85edd8dbd07c8293c899ee52230f72c54c897`.
- Release: `marmotkit-v0.10.4`.
- Android ZIP SHA-256: `dcbb4e00c703cce2cf773c860f1f1d46b18ac49e81af5a5089902b8a31e0c16e`.
- Candidate API signature: 326 types / 322 checksums / 5 helper declarations.
- Account schema migrations: 87–89. Backups required; downgrade unsupported.

## Verification to fill before publication

- Final source head and merged #2691 base: pending.
- Active lock, prepared artifact and generated signature agreement: pending implementation.
- Both-flavor consumer compilation and regression tests: pending.
- Counted transfer/cache/upgrade tests: pending.
- In-place emulator upgrade, restart and retained-local-media evidence: pending.
- Manual test guide/inventory validation and completion gate: pending.
- Exact-head Roborazzi baseline links and rendered evidence: pending.
- Native/runtime/release CI: pending PR publication.

## Boundaries

Do not claim the historical 1 TB incident is explained or closed by this upgrade.
Do not claim indefinite migration of legacy cache bytes: no verified-byte import API
was added. Preserve readable old files locally and gate automatic demand explicitly.
Document the final disposition of history pagination, retry/remove UI and durable
send adoption; a dependency bump is not full application adoption.

Refs #2686. Refs #2045. Related #2058 and #2493. Use closing keywords only after
the corresponding issue's complete acceptance criteria have actually passed.
