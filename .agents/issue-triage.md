# White Noise Android issue triage

[Project 7](https://github.com/orgs/marmot-protocol/projects/7) is the product
planning authority. Read its live README and field options before metadata
changes; product ordering and release steering must not be frozen in agent docs.
Execution ledgers may track delivery but must link existing GitHub work.

## Ownership and publication

- Apply the [root MDK/Android boundary](../AGENTS.md#architecture-minimal-android-display-layer).
  File shared runtime defects in MDK; use Android issues for presentation and
  platform integration. Represent a missing upstream capability as a real
  dependency, not a Kotlin workaround.
- Before implementation, check live assignees, related PRs, and current source.
  Establish sole authorized ownership; preserve another contributor's active work.
- Before creating or materially editing an issue, search open/closed issues and
  open/closed/merged PRs by symptom, subsystem and root cause. Inspect plausible
  matches; update the canonical scope instead of creating duplicates.
- Draft the exact title, body, native type, labels, all Project fields, parent,
  dependencies and duplicate disposition. Begin the body with `## Summary` and
  name the owning source paths, acceptance criteria and regression-test target.
- Pass the workspace's source-grounded independent issue review on that exact
  artifact. Substantive edits invalidate the review. Publish from the reviewed
  body and read back both issue and Project metadata before reporting success.

## Project contract

- Every open issue and PR appears exactly once. Open PRs use `In Progress`.
- Issues use native `Bug`, `Feature`, `Task` or `Tracking` types. Trackers use
  native parent/sub-issue relationships and a bounded completion rule; do not
  force unrelated standalone work under one tracker.
- Set `Status`, `Release gate`, `Priority`, `Area`, `Triage health`, `Impact` and
  `Confidence` from live options/evidence. For PRs, inherit classification from
  the canonical issue where appropriate. Review every optional planning field;
  leave unsupported dates, ranks, iterations and commitments unset.
- `Priority` controls execution order. Follow the live README for `Release gate`,
  including any compatibility-only disposition; never infer priority from it.
  Use P0 for the immediate emergency/critical-path lane, P1
  for high-impact committed work, P2 for normal next/active work, and P3 for
  intentionally deferred work. Live steering takes precedence.
- `Triage health` distinguishes executable `Ready` work from `Needs design`,
  `Needs upstream`, concrete `Blocked` work and temporary `Needs triage` intake.
- Use `Product rank` only for an established product order. Use native
  dependencies only for actual blockers. Preserve user-authored relationships.
- Retired labels `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, and `tracking` must not be
  recreated. Native Bug uses the existing `bug` label; Feature uses `enhancement`.
  Do not invent labels duplicating type or Project fields.
- `agent-ok` is a separate autonomy decision. Apply it only when current policy
  permits implementation and the independently reviewed scope has no unresolved
  product/upstream gate; never add it to broad trackers.

## Access and reconciliation

Use the existing authenticated GitHub CLI and preserve its credential boundary.
A missing MCP/plugin is not evidence that GitHub is unavailable. Require only
permissions needed for the operation; do not create another login or print tokens.
Use local mirrored discovery where the workspace provides it; exact mutation
and readiness gates require uncached live readback through the maintained tools.

Prefer supported Projects v2 REST reads for current project fields/items. Follow
workspace quota/admission rules rather than switching clients or credentials.
On the shared Hermes host, use its maintained Project audit/publisher tools;
`gh project item-list` is blocked there, so the repository checker below is not
an API-error fallback.

For environments supporting the repository checker's CLI reads, run
`python3 scripts/check_github_triage.py` after Project changes. Its
`--repair-additions` option only adds missing open issues; it does not classify
items. Also verify the full live field contract above: the checker covers only
its configured subset. Reconcile missing/duplicate items, retired labels, native
hierarchy and stale status; close/archive shipped, obsolete or duplicate work
without deleting legitimate history.
