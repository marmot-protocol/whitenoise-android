# White Noise Android Agent Notes

## Source Of Truth

The Android app must not create its own persistent data cache for White Noise protocol data.

White Noise's SQLite databases are the source of truth for accounts, groups, messages, profiles, relay state, key packages, notification settings, and other protocol data. Performance work should make reads from that source fast and well-shaped. Do not add a second Android-owned cache to hide slow database or binding access.

Allowed Android state:

- UI state, such as selected screens, dialog state, draft text, loading flags, and optimistic send state.
- Short-lived lifecycle state, such as active stream jobs and in-flight requests.
- Android platform preferences, such as theme, language, notification permission UI state, and foreground service preferences.

Avoid:

- Room, DataStore, SharedPreferences, files, or in-memory singleton maps as a duplicate source for White Noise protocol data.
- Long-lived Android caches for message timelines, chat summaries, profiles, key packages, or relay projections.
- Fixing slow screens by copying White Noise protocol data into Android-owned storage.

Prefer:

- Changing White Noise APIs so Android can read exactly the data it needs from SQLite quickly.
- Moving binding and database calls off the main thread.
- Keeping stream subscriptions lifecycle-bound and explicitly closed.
- Returning pre-shaped projections from White Noise when a screen needs them.

If Android seems to need a cache for protocol data, stop and ask whether the data should instead be indexed, projected, or exposed differently by White Noise.

## Working Rules

- Keep Android changes small and tied to a user-visible behavior.
- Use the existing Kotlin, Compose, and Marmot binding patterns before adding new architecture.
- Do not run connected device tests that can wipe local app state unless the user asks for them.
- Prefer `rg` for searching.
- Before editing files, check the dirty worktree and preserve changes you did not make.

## Manual release testing guide

[`docs/manual-release-testing.md`](docs/manual-release-testing.md) is the canonical novice-facing release checklist. Every change to a user-visible route, label, action, state, permission, notification, Android entry point, supported configuration, or failure/recovery path must update the corresponding permanent test IDs in the same pull request. Add a new ID for new behavior; never renumber or reuse an ID. When behavior is removed, move its ID to the Retired IDs table and record the pull request or commit that removed it.

Keep [`docs/manual-release-testing-surfaces.json`](docs/manual-release-testing-surfaces.json) synchronized with source symbols and active test IDs. Run `python3 scripts/check_manual_test_guide.py` and `python3 -m unittest scripts/test_check_manual_test_guide.py` after modifying the guide, its inventory, or a user-facing surface. The guide's boxes stay unchecked in Git; testers copy the checklist elsewhere for a run.

## Visual Change Evidence

- Every pull request that changes rendered UI must add or update a deterministic
  Roborazzi screenshot test and commit the generated PNG baseline under
  `app/src/test/snapshots/`.
- Cover the states materially affected by the change. Include relevant themes,
  RTL, large font scale, loading/empty/error states, and loaded/fallback content
  when those states can alter the result; do not create unrelated snapshot
  variants merely to increase coverage.
- After every commit that changes rendering, regenerate and commit the affected
  baselines with `./gradlew :app:recordRoborazziDevZapstoreDebug` or
  `:app:recordRoborazziDevPlayDebug`. Before pushing, run
  `./gradlew :app:verifyRoborazziDevZapstoreDebug
  :app:verifyRoborazziDevPlayDebug`.
- Verify that the pull request description's generated **Visual changes** section
  points to the exact current head commit and shows every affected baseline.
- Treat a missing-screenshot CI result as blocking. Do not dismiss it as an
  informational warning or bypass it by changing unrelated snapshots.

## GitHub triage

- The public White Noise Android project is
  `https://github.com/orgs/marmot-protocol/projects/7`.
- Use the authenticated `gh` CLI for repository and GitHub Project operations.
  A GitHub MCP connector or plugin is not required. Start with `gh auth status`
  and require `repo`, `project`, `read:org`, and `workflow` scopes.
- A sandbox or isolated worktree must preserve access to the existing gh config
  and network. Do not conclude that GitHub is unavailable merely because an MCP
  tool is absent.
- Read `.agents/issue-triage.md` before creating or materially editing an issue.
  Run `python3 scripts/check_github_triage.py` after project changes.

## Issues and project planning

GitHub Issues are the product backlog. The public
[White Noise Android project](https://github.com/orgs/marmot-protocol/projects/7)
is the authoritative planning and triage view. Do not maintain a competing
product backlog in documents, checklists, or an agent task system. Agent task
systems may track durable execution, but should link back to the GitHub issue
when product work already exists.

### Required structure

- Every open issue and pull request must appear exactly once in the White Noise
  Android project. Open pull requests use `In Progress` status.
- Give every issue one native GitHub type: `Bug`, `Feature`, `Task`, or
  `Tracking`.
- A tracker must use the organization-native `Tracking` type. A `tracking`
  label, `Tracking:` title, or Markdown checklist is not a substitute.
- Use native parent/sub-issue relationships for a tracker's hierarchy. Use
  native dependencies only for genuine blockers, not preferred ordering.
- Do not force unrelated standalone work into a tracker. Trackers should define
  a coherent outcome, completion criteria, and a bounded set of sub-issues.
- Close or archive shipped, obsolete, and duplicate work instead of retaining
  it as an unofficial record.

### Required project fields

Every open issue must have these project fields set:

- `Priority`: `P0` for an active critical incident or release-stopping
  security, data-loss, or core-availability defect; `P1` for high-priority
  committed work; `P2` for qualified next work; `P3` for valid, intentionally
  deferred work.
- `Area`: choose the single best owning product area.
- `Triage health`: `Needs triage`, `Ready`, `Blocked`, `Needs design`, or
  `Needs upstream`. `Ready` means the scope and acceptance criteria are
  implementable; it does not mean implementation has started.
- `Status`: `Todo`, `In Progress`, or `Done`, reflecting delivery state.
- `Product rank`: set only when product leadership has established a relative
  order. Lower numbers rank first; do not invent ranks merely to fill the field.

The former `HIGH`, `MEDIUM`, `LOW`, and `tracking` labels are retired. Do not
recreate or reapply them. Their migration equivalents are `P1`, `P2`, `P3`,
and the native `Tracking` type. Labels may still express useful cross-cutting
classifications, but must not duplicate native types or project fields.

Read the live Project 7 README before changing priority or rank. Its current
steering overrides this static fallback. When priority and readiness are
otherwise equal, current product order is:

1. Text-to-speech
2. Link previews
3. Pinned messages
4. Scheduled messages

Represent this order through `Product rank`, not severity labels or issue
numbers.

### Creating or materially changing an issue

1. Inspect the live default branch and identify the owning code, affected call
   sites, and a named regression-test target.
2. Search open and closed issues and pull requests by symptom, subsystem, and
   root cause. Describe recurrences or remaining gaps as such.
3. Draft the exact title, body, native type, project fields, labels, parent,
   dependencies, and duplicate disposition.
4. Pass the workspace source-grounded independent issue-review gate on that
   exact draft. Any substantive correction invalidates the prior review.
5. Publish from the reviewed body, add the issue to project 7, populate the
   required fields, and read back both issue and project item to verify them.

The `agent-ok` label is a separate autonomy decision, not a type, priority, or
readiness marker. Apply it only when current repository policy permits
autonomous implementation and the source-grounded scope is safe and complete.

### Reconciliation

Regular reconciliation must compare all open repository issues and pull
requests with project 7 and flag missing or duplicate project items, missing
native types, and unset
Priority/Area/Triage health fields, invalid tracker types, retired labels, and
stale delivery status. Preserve user-authored content and native relationships.
Surface ambiguous product priority, design, or upstream ownership for decision
instead of guessing.
