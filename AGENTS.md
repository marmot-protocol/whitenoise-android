# White Noise Android agent guide

Read [README.md](README.md) for build commands and repository conventions.
`CLAUDE.md` points here; keep specialized procedures in their linked guides.

## Architecture: minimal Android display layer

**Android owns presentation and Android platform integration. MDK owns shared
product logic**, including protocol, validation, persistence, queries, relay/media
operations and recovery. Follow [MDK's host boundary](https://github.com/marmot-protocol/mdk/blob/master/docs/marmot-architecture/overview/app-core-boundary.md#host-app-boundary).

- Render MDK state in Compose; keep only transient view/editing state here.
  Platform adapters supply inputs to MDK; they do not recreate its rules.
- Fix slow native reads in MDK. Do not add a second store/cache for protocol data;
  keep blocking binding calls off the main thread.
- Add missing shared capabilities to MDK first, then adopt a reviewed, published
  artifact via [the MarmotKit guide](docs/updating-marmotkit.md). Never edit generated bindings.
  Preserve existing compatibility adapters until native adoption is validated.

## Working and validation

- Preserve unrelated changes and active owners; check live assignees and related
  PRs before issue implementation. Follow workspace signing/review/publication gates.
- Follow existing Kotlin/Compose patterns; close subscriptions, cancel screen jobs,
  and reject late results after account/chat changes.
- Run focused checks and exact-head PR CI. Docs-only changes need link/path checks
  and `git diff --check`. State-wiping connected tests require explicit authorization.
- For user-visible changes, update permanent IDs in the [manual checklist](docs/manual-release-testing.md)
  and [surface inventory](docs/manual-release-testing-surfaces.json) in the same PR.
  Never reuse/renumber IDs; keep boxes unchecked. Run
  `python3 scripts/check_manual_test_guide.py` and
  `python3 -m unittest scripts/test_check_manual_test_guide.py`.
- For rendering changes, follow [Screenshot tests](README.md#screenshot-tests): cover
  relevant themes, RTL, font scales and UI states, regenerate/commit PNG baselines,
  and verify both distributions. The PR's **Visual changes** must show current-head
  baselines; missing-screenshot CI is blocking.
- Datawav PRs open draft until CI passes, conflicts are absent and findings are
  addressed. Recheck discussion before marking ready. Pending checks alone do not
  justify demoting a ready PR; readiness never authorizes merging.

## Task guides

- [Issue/Project triage](.agents/issue-triage.md): follow live Project 7 steering before metadata writes.
- [MarmotKit adoption](docs/updating-marmotkit.md): artifact pins and API compatibility.
- [Performance](docs/performance.md) and [analytics](docs/product-analytics.md): measurement and MDK/host boundaries.
- [Releases](docs/android-release-pipeline.md): signing, artifact provenance and distribution gates;
  distribute reviewed bytes without rebuilding. Internal testing does not authorize public Zapstore publication.
