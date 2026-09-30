# White Noise Android agent guide

Read [README.md](README.md) for the build matrix and commands. This file is the
repository-wide agent entry point; task-specific guides are linked below.

## Architecture: minimal Android display layer

**MDK owns shared product behavior. Android renders MDK state and integrates
Android platform APIs.** Apply the
[MDK app-core boundary](https://github.com/marmot-protocol/mdk/blob/master/docs/marmot-architecture/overview/app-core-boundary.md#host-app-boundary)
before choosing where to implement a change.

- Put protocol/cryptography, account/group/message rules, validation, parsing,
  queries/projections, persistence, unread/delivery state, retention, relay
  operations, media acquisition/retry, and shared diagnostics in MDK's Rust
  runtime. Expose capabilities through its bindings.
- Keep Compose rendering, navigation, accessibility, transient UI state,
  lifecycle-bound subscriptions, and Android permissions, notifications,
  background execution, file pickers/sharing, and Keystore/Amber integration here.
  Platform adapters provide inputs to MDK; they do not reimplement its rules.
- Composer editing and optimistic display may be transient UI state. Durable
  drafts and authoritative send/delivery state belong in MDK.
- MDK-managed stores are the source of truth. Do not add Room, DataStore,
  SharedPreferences, files, or long-lived maps as a second store for protocol
  records or projections. Fix slow reads with MDK indexes, bounded queries, or
  binding projections; keep blocking binding calls off the main thread.
- When MDK lacks a capability, implement and review it there first where
  possible. Link the upstream dependency in the Android PR and adopt the
  published artifact via [the MarmotKit update guide](docs/updating-marmotkit.md).
  Do not hide the missing capability in Kotlin or edit generated bindings.
- Existing host caches/adapters may require compatibility during migration.
  Replace them only after the native capability is released and adoption is
  validated; keep that work explicit rather than extending duplicate authority.

## Working and validation

- Preserve unrelated changes and active issue/PR owners. Before implementing an
  issue, check live assignees and open PRs and establish sole authorized ownership.
- Follow existing Kotlin/Compose/binding patterns. Close subscriptions and cancel
  screen jobs with their lifecycle; fence late results after account/chat changes.
- Run focused checks for affected behavior; use the PR's exact-head CI for the
  complete matrix. Common unit/lint commands are in the README. Documentation-only
  changes need link/path checks and `git diff --check`, not device tests.
- Do not run connected tests that wipe app state without explicit authorization.
- Keep agent instructions short: put specialized procedures in the linked guides,
  and update their links when files or commands move. `CLAUDE.md` points here.

## User-visible changes

- Update permanent test IDs in [the manual release checklist](docs/manual-release-testing.md)
  and [its surface inventory](docs/manual-release-testing-surfaces.json) in the
  same PR. Add IDs for new behavior; retire removed IDs without reuse or
  renumbering. Keep checked-in boxes unchecked.
- After changing the guide, inventory, or a user-facing surface, run
  `python3 scripts/check_manual_test_guide.py` and
  `python3 -m unittest scripts/test_check_manual_test_guide.py`.
- Rendering changes require deterministic Roborazzi coverage and committed PNG
  baselines in `app/src/test/snapshots/`. Cover the affected themes, RTL, font
  scales, and loading/empty/error/content states where relevant.
- Regenerate affected baselines after rendering commits with
  `./gradlew :app:recordRoborazziDevZapstoreDebug` or
  `:app:recordRoborazziDevPlayDebug`; verify both distributions with
  `./gradlew :app:verifyRoborazziDevZapstoreDebug :app:verifyRoborazziDevPlayDebug`.
  The PR's generated **Visual changes** section must show the affected baselines
  at the current head. Missing-screenshot CI is blocking.

## Issues and PRs

[GitHub Project 7](https://github.com/orgs/marmot-protocol/projects/7) is the
product planning authority; execution ledgers must not become another backlog.
Read [.agents/issue-triage.md](.agents/issue-triage.md) before issue/Project writes.
Every open issue and PR belongs in the project exactly once; open PRs use
`In Progress`. Use live project steering instead of copying priority lists here.

Datawav PRs open draft until exact-head CI passes, conflicts are absent, and
there are no actionable readiness findings. Recheck the complete discussion
and mark a verified clean PR ready. Pending/unknown checks or an addressed stale
change-request verdict alone do not justify demoting an already-ready PR.
Readiness does not authorize a merge or complete an issue; follow the workspace's
review, signing, ownership, and publication gates.

## Task guides

- [MarmotKit adoption](docs/updating-marmotkit.md): immutable artifact pin and API validation.
- [Performance](docs/performance.md): measured journeys, host timings, and device-safe runners.
- [Product analytics](docs/product-analytics.md): MDK consent/aggregation and host event boundaries.
- [Release pipeline](docs/android-release-pipeline.md): versioning, signed candidates,
  verification and distribution. Distribute the reviewed bytes without rebuilding;
  internal testing does not authorize public Zapstore publication. Record source,
  run/attempt, manifest/artifact hashes and destination readback.
