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

A source file contains `source`, `legacy_sha256`, `categories`, and optionally
`discovery_exceptions`. Its categories completely replace the legacy entries
for that source. Preserve all relevant entries, including those unaffected by
your change. Every entry must belong to that exact source; other sources and
global discovery exceptions remain unchanged. Updating an anchor or adding a
scenario mapping belongs here.

The hashes bind replacements to the legacy definitions they were extracted
from. If an older PR later edits those definitions, CI fails with the owning
fragment path. Reconcile the old and new instructions, then update the hash
from the reconciled legacy input. Never blindly refresh it: this is a real
same-scenario conflict, and silently preferring one version would lose coverage.

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

Both case files and inventory files are validated on every PR. A daily drift
check could provide an additional safeguard, but coverage must not wait until
the next daily run. This change adds no daily scheduler or publication trigger.
