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

The description's preview links belong only to their displayed source revision.
A new push makes an older preview unsuitable for current-head test proof. Verify
the full head/artifact provenance through the existing handoff before recording
human PASS. Missing current-head previews hold any required device/human gate;
they are not permission to use old APKs or build a substitute locally.

## Documentation-only core validation

The core workflow always starts and reports the existing required check. Its
`changes` job examines the **whole PR diff**, with rename detection disabled.
A separate `tooling-contracts` job runs the non-Gradle tooling/metadata/manual-guide/
fuzz-policy validators in parallel; it does not delay Android job startup.
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
contract. Release/runtime/reproducibility/security workflows retain their gates.
Draft status is not permission to skip mandatory final validation.

## Further optimization requires complete proof

Deferring suites to a finalized candidate needs trusted evidence bound to the
PR head, current base and workflow definition, visible to readiness/merge
automation. A green fast check, skipped draft job, or successful dispatch on
the default branch is not that evidence. Do not introduce candidate-only
release/security checks until those consumers require the complete proof.

Measure job requests, summed runner minutes, queue wait and time to final green
checks together. Adding fewer jobs does not guarantee a particular speedup.
This policy adds no paid runners or subscriptions.
