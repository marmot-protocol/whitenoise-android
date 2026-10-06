# CI request policy

This policy applies to contributors and coding agents. Reduce unnecessary
requests while preserving exact-head/current-base validation and testing gates.

## Submit coherent candidates

- Finish currently actionable edits and review fixes within the same owned scope
  before one signed candidate push. Several local commits can travel together.
  Preserve separate issue/PR owners and keep unrelated work separate.
- Inspect source and run required non-compiling preflight checks locally. Use
  GitHub for compilation, unit/screenshot tests, lint and preview APK builds.
  Required scoped screenshot baseline generation remains local where CI does
  not produce that output; CI verifies the committed baselines.
- Reuse passing results only for the matching revision and applicable base.
  A new candidate needs fresh validation. Do not make empty commits to start CI.
- Preserve an existing matching queued/running run. Retry only failed jobs for a
  diagnosed transient infrastructure failure; fix deterministic failures first.
  Do not continually refresh every PR against moving master or cancel active
  publishers. Retain the current-base readiness and merge requirements.

## Request preview APKs when needed

Pushing a PR does not automatically build previews. When debugging or required
human/device testing needs the current candidate, first look for existing trusted
artifacts for that exact head and a matching active request. One request builds
both the stable (update-compatible) and isolated channels:

```sh
gh workflow run android-pr-apk.yml --repo marmot-protocol/whitenoise-android \
  --ref master -f pr_number=NUMBER
```

Hermes agents use the maintained authenticated CLI at `/opt/data/.local/bin/gh`
with `HERMES_GH_NO_CACHE=1` for live reads and writes. Dispatch from the trusted
default branch, not PR-controlled workflow code. The workflow resolves and
rechecks the open internal PR's exact head; the separate trusted publisher
validates both candidates before signing, upload and description updates.

Play-flavor release packaging and minification are checked by the requested
preview build; core debug checks do not replace that coverage. For Play-specific
release packaging or shrinker changes, request the current-head previews before
readiness and inspect their build results.

The description's preview links belong only to their displayed source revision.
A new push makes an older preview unsuitable for current-head test proof. Verify
the full head/artifact provenance through the existing handoff before recording
human PASS. Missing current-head previews hold any required device/human gate;
they are not permission to use old APKs or build a substitute locally.

## Documentation-only core validation

The core workflow always starts and reports the existing required check. Its
`changes` job examines the **whole PR diff**, with rename detection disabled.
A separate `tooling-contracts` job runs the non-Gradle tooling/metadata/manual-guide/
fuzz-policy validators in parallel, including the preview security/update
contracts; it does not delay Android job startup.
Only ordinary non-executable Markdown blobs in the named root guides or `docs/`
qualify. JSON inventories, source-tree Markdown, build/workflow inputs,
executables, symlinks, deleted code and unknown paths require full Android CI.
`docs/composer-dictation-device-matrix.md` is a Kotlin-test fixture and also
requires the full suite; document any future build/test input in the classifier.
An unavailable/empty diff, missing refs or any non-PR event also requires full CI.

The aggregate accepts skipped Android jobs only when classification succeeded
with `docs_only=true` on a PR and the separate tooling validators passed. It
rejects missing or unexpected outcomes, code-path skips, failures and cancellations. A docs fixup
on a code PR does not turn that PR into a docs-only candidate.

Code candidates still run every existing core check, including both
distributions, coverage, screenshots, baseline packaging and the offline signer
contract. Release lint, runtime regressions and security workflows retain their per-PR gates.
Both complete unit suites also verify every committed screenshot golden in the same
execution, with matching verification inputs for Kover reuse; there are no separate
screenshot test runners.

## Supplemental campaigns and draft parity

The same checks run on draft and ready PRs. Opening, reopening or pushing a PR
starts its applicable CI; changing only readiness does not start another phase.
The visual-description updater follows those same events and displays at most
four inline comparisons, retaining links and the bounded list of all changes.

Compose compiler reports and independent unsigned APK reproduction are
supplemental campaigns. A complete raw diff permits their deferral only for
known ordinary Kotlin/Java source, main resources, committed goldens and prose.
Build, packaging, native pins, manifests, CI/tooling, executable/symlink changes,
unknown inputs or unavailable evidence run the campaigns. All other compilation,
full-unit/golden/coverage, style, lint, runtime and security gates stay required.
A mixed PR uses the most demanding classification of its entire diff.

Core CI runs daily at 02:43 UTC and reproduction at 03:17 UTC on default-branch
HEAD, including both campaigns. Manual dispatches run them too; tag reproduction
continues to require two independent builds and byte-for-byte comparison.
Check the scheduled run summary each day, investigate a failure using its logs
and artifacts, and fix the default branch through an owned PR. Do not publish a
release using yesterday's nightly result: require successful reproduction for
the exact release candidate via the tag/manual workflow, in addition to the
release runbook's signing and distribution gates. A nightly failure is not
permission to bypass those gates.

The existing aggregate check names remain required. A successful explicit
classification permits only the named supplemental skips; absent evidence,
failures, cancellations and unrelated skips remain red. Reproduction's aggregate
also requires both release-lint variants even when its two builds are deferred.
The nightly/manual/tag events cannot accept deferred reproduction builds.

Measure job requests, summed runner minutes, queue wait and time to final green
checks together. Adding fewer jobs does not guarantee a particular speedup.
This policy adds no paid runners or subscriptions. Further candidate-only release
or security deferral requires exact-head/current-base evidence that the readiness
and merge consumers actually enforce.
