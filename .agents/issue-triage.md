# White Noise Android issue triage

[Project 7](https://github.com/orgs/marmot-protocol/projects/7) is the planning
authority. Read its live README and field options before metadata changes;
execution ledgers link GitHub work rather than becoming another backlog.

## Ownership and publication

- Apply the [MDK/Android boundary](../AGENTS.md#architecture-minimal-android-display-layer).
  File shared runtime defects in MDK; link missing upstream capabilities as dependencies.
- Check live assignees, related PRs and current source before implementation;
  establish sole authorized ownership and preserve active contributors.
- Search open/closed issues and PRs (including merged PRs) by symptom, subsystem
  and cause before publication. Update the canonical scope instead of duplicating it.
- Draft exact title/body, native type, labels, Project fields and relationships.
  Begin with `## Summary`; include source paths, acceptance criteria and regression tests.
  Bug drafts name the applicable [invariant gate](../docs/invariant-gates.md) or exemption.
- Obtain source-grounded independent review of the exact artifact under workspace
  policy. Publish from the reviewed body; read back issue and Project metadata.

## Project contract

- Every open issue/PR appears exactly once; open PRs use `In Progress`.
- Use native `Bug`, `Feature`, `Task` or `Tracking` types and real parent/dependency
  relationships. Trackers need bounded completion criteria; preserve user relationships.
- Set `Status`, `Release gate`, `Priority`, `Area`, `Triage health`, `Impact` and
  `Confidence` from live steering/evidence; PRs inherit issue classification where
  appropriate. Inspect optional fields; leave unsupported commitments unset.
- Follow live priority/health definitions; release gate does not imply priority.
  Use `Product rank` only for an established product order.
- Do not recreate retired `CRITICAL`, `HIGH`, `MEDIUM`, `LOW` or `tracking` labels.
  Bug uses `bug`; Feature uses `enhancement`. Avoid labels duplicating Project fields.
- Apply `agent-ok` only under current autonomy policy to independently reviewed,
  executable scopes without unresolved product/upstream gates; never broad trackers.

## Verification

Use maintained authenticated tools and workspace discovery/quota rules; mutations
require uncached live readback. On the shared Hermes host, use its Project tools
(`gh project item-list` is blocked). Elsewhere, `python3 scripts/check_github_triage.py`
checks its configured subset only; `--repair-additions` adds missing open issues/PRs
without classifying them. Verify the full field contract, uniqueness, hierarchy and status
separately; preserve legitimate history when reconciling obsolete/duplicate work.
