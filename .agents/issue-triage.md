# White Noise Android issue triage

The public [White Noise Android project](https://github.com/orgs/marmot-protocol/projects/7)
is the authoritative product planning view. Hermes Kanban may track agent
execution, but it must not become a competing product backlog.

Every open issue and pull request must appear exactly once in project 7. Open
pull requests use `In Progress` status. Every open issue must also have:

- a native `Bug`, `Feature`, `Task`, or `Tracking` issue type;
- `Priority` set to P0, P1, P2, or P3;
- one owning `Area`;
- `Triage health` set;
- native parent/sub-issue and dependency relationships where applicable.

Use native `Tracking`; never recreate the retired `tracking` label. The retired
`HIGH`, `MEDIUM`, and `LOW` labels are represented by P1, P2, and P3 project
values. Read the live Project 7 README before changing priority or rank; its
current steering overrides this static fallback. Product rank is intentionally
sparse and currently records:

1. Text-to-speech
2. Link previews
3. Pinned messages
4. Scheduled messages

Before creating or materially editing an implementation issue, inspect the live
default-branch source, name the owning paths and regression-test target, search
open and closed issues and pull requests, and pass the workspace
source-grounded independent review gate on the exact title/body/metadata.

## Access preflight

Use `gh`, not a GitHub plugin requirement:

```bash
gh auth status
gh project view 7 --owner marmot-protocol --format json
gh repo view marmot-protocol/whitenoise-android --json nameWithOwner
```

The token must expose `repo`, `project`, `read:org`, and `workflow`. Run these
commands inside the assigned worktree. A missing MCP connector is not a blocker.

## Reconciliation

Run:

```bash
python3 scripts/check_github_triage.py
```

Use `--repair-additions` only to add missing open issues. The checker never
guesses product priority, type, area, or readiness. Resolve those deliberately,
then rerun until it exits zero. Preserve closed tracker children that GitHub
automatically adds through native sub-issue relationships.
