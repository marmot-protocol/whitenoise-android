# White Noise Android issue triage

[Project 7](https://github.com/orgs/marmot-protocol/projects/7) is the planning
authority. Read its live README and field options before metadata changes;
execution ledgers link GitHub work rather than becoming another backlog.

## Agent filing process

Agents creating issues through the forms, CLI or API follow this process. Ordinary
reporters can submit their observations without performing source investigation.
GitHub's collapsed form guidance is not included in the submitted issue body;
record the investigation in the form's Evidence / Context field or the CLI body.
The checklist is guidance, not an enforcement mechanism.

1. Inspect current default-branch source. Separate reported observations,
   reproduced behavior, source findings and hypotheses. Identify the app version,
   device or inspected revision, and state unavailable evidence. Never invent
   reproduction steps, device tests, logs, results or a root cause.
2. Search open and closed issues and PRs, including merged fixes, by symptom,
   subsystem and likely cause. Read plausible matches, link canonical scope or
   regression history, and contribute to an existing issue when it covers the cause.
3. Trace affected entry points, the state owner and visible result. Check sibling
   callers and relevant account/chat switches, cancellation, failure/recovery and
   lifecycle behavior. Cite immutable source links and state what was inspected.
4. Establish the Android/MDK boundary and verified upstream prerequisites. Preserve
   shared ownership; do not propose another Android store/cache for shared state.
5. Describe one bounded problem or user outcome, alternatives and observable
   acceptance criteria. Name existing tests, proposed regression scenarios and,
   for bugs, the applicable invariant gate or justified exemption. Keep proposed
   checks separate from checks actually performed.
6. Follow the current Project contract below for metadata and real relationships.
   Preserve active owners and evidence-based priority. Respect `agent-ok` review
   and authorization requirements; state any metadata action blocked by permissions.
7. Obtain required review and sanitize private data before publication. Exclude
   keys, tokens and private conversation data; report vulnerabilities privately.
   Read back the published issue and metadata. If submission has an uncertain
   outcome, locate the existing issue before retrying to avoid duplicates.

## Ownership and publication

- Apply the [MDK/Android boundary](../AGENTS.md#architecture-minimal-android-display-layer).
  File shared runtime defects in MDK; link missing upstream capabilities as dependencies.
- Check live assignees, related PRs and current source before implementation;
  establish sole authorized ownership and preserve active contributors.
- Search open/closed issues and PRs (including merged PRs) by symptom, subsystem
  and cause before publication. Update the canonical scope instead of duplicating it.
- Draft exact title/body, native type, labels, Project fields and relationships.
  For CLI/API submissions, begin with `## Summary`; use the existing Problem /
  User need fields when submitting through the forms. Include source paths,
  acceptance criteria and regression tests in the report.
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
