# Maintaining manual test coverage without shared-file conflicts

Keep testing instructions with the behavior-changing PR. Prefer one scenario
file per permanent test ID and one inventory file per source. Unrelated updates
then avoid editing the shared checklist or its large JSON inventory.

The original guide and inventory remain supported so open PRs do not need a
bulk migration. CI validates their combined view and uploads
`manual-release-testing-<commit>` with the assembled guide, inventory and actual
checkout revision. Use that artifact when testing a candidate. Keep run results
separate, recording the commit, APK SHA-256, variant and tester; canonical boxes
must stay unchecked. Generated files under `build/` are never committed.

On a PR, the guide reflects GitHub's synthetic merge checkout, including the
base branch. Its revision file also records the PR head and base. Match these
to the candidate before testing; the artifact name alone is not a preview APK
head. On master, the recorded checkout is the candidate source commit.

## Update an existing scenario and its source coverage

From the repository root, extract the affected inputs once:

```sh
python3 scripts/check_manual_test_guide.py --extract-case MED-003
python3 scripts/check_manual_test_guide.py --extract-source app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationMediaDraft.kt
```

If the files already exist, edit them directly. Extraction refuses to overwrite
them. Scenario files live in `cases/<ID>.md`; source files live in
`surfaces/<source-path>.json` beneath this directory. No shared index is needed.

A scenario file has one legacy hash comment, a blank line, and exactly one
unchecked action → expected-result definition with ordinal `1`. The assembler
places it at its existing position; permanent IDs never change. To add a new
scenario, append an unused ID above the prefix's previous maximum, use
`<!-- legacy-sha256: none -->`, and use the existing prefix's checklist section.
Coordinate simultaneous new IDs; duplicate IDs are errors.
When a prefix spans several checklist sections, add a new ID to the shared
guide in its intended section first. Existing IDs in those sections can still
be extracted and edited independently; the assembler never guesses a section.
New definitions are appended before the section's next heading, after any
trailing notes; their IDs and the existing scenario positions are unchanged.

A source file contains `source`, `legacy_sha256`, `categories`, and optionally
`discovery_exceptions`. Its categories completely replace the legacy entries
for that source. Preserve all relevant entries, including those unaffected by
your change. Every entry must belong to that exact source; other sources and
global discovery exceptions remain unchanged. Updating an anchor or adding a
scenario mapping belongs here.
For an existing source file without any inventory entries, extraction creates
an empty mapping with the correct legacy hash. Add the source's actual coverage
entries before validating the production change.

The hashes bind replacements to the legacy definitions they were extracted
from. If an older PR later edits those definitions, CI fails with the owning
fragment path. Reconcile the old and new instructions, then update the hash
from the reconciled legacy input. Never blindly refresh it: this is a real
same-scenario conflict, and silently preferring one version would lose coverage.
This can be a semantic conflict even when Git merges the files cleanly. Require
successful validation against current master immediately before merging. Do not
merge a stale hash, skip a cancelled validation run, or automatically union the
two definitions. New fragments should make real coverage changes; this tooling
change deliberately creates no no-op replacements for in-flight scenarios.
Historical ID checks tolerate a stale legacy hash in the base revision so a PR
can repair it. They read every legacy and fragment definition independently of
the base's formatting, references or section placement, preserving all prior
IDs even when an unvalidated merge broke assembly. Current inputs still require
valid structure and reconciled hashes; unreadable history or malformed fragment
identities are errors.

## Validate the effective coverage

```sh
python3 -m unittest scripts/test_check_manual_test_guide.py scripts/test_manual_test_fragments.py
python3 scripts/check_manual_test_guide.py --base BASE_COMMIT
python3 scripts/check_manual_test_guide.py --output-dir build/manual-release-testing
```

The existing checks for source discovery, anchors, semantic ownership, active
and retired IDs and history apply to the assembled inputs. Fragment maintenance
must change effective scenarios and mappings associated with production sources
changed in the PR. Extracting unchanged inputs or editing unrelated fragments
does not satisfy that requirement. The original shared-file route remains
compatible. Deleting a fragment must still preserve coverage and retire any
removed permanent ID.
If only scenario wording changes and the source's mappings remain identical,
use the legacy or mixed route; the fragment-only route requires an effective
mapping change rather than a no-op extraction.

Both case files and inventory files are validated on every PR. A daily drift
check could provide an additional safeguard, but coverage must not wait until
the next daily run. This change adds no daily scheduler or publication trigger.
