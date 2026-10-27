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

## Two smoke tests before a human handoff

Every candidate needs two separately recorded smoke results before its APK or
preview link is sent for human testing:

1. Upgrade the populated shared-preview fixture in place from a build using the
   current master MarmotKit artifact. Open the prepared account and synthetic
   history and verify that bootstrap completes without a migration error.
2. Fully stop the candidate process, launch it cold and verify the same retained
   account and history again. An installation-only check cannot pass either test.

Bind both results to the exact source and APK digest, baseline runtime, device
and observed ready state. Run the changed-feature assertions after these checks.
A clean or older-schema fixture does not cover the first smoke test. Before
requesting a stable preview, compare its complete MarmotKit lock with current
master and adopt the current artifact when they differ. Never clear storage,
alter migration records or lower the schema number to make a smoke test pass.
See `INT-010` in the combined manual checklist.

## Queue-gated PR feedback and full integration

The initial rollout preserves full master validation. Reduced PR work is enabled
only when `ANDROID_CI_MODE` is exactly `merge-queue`, after native queue rules,
external integration authorization and the existing source-owner repair loop are
verified. Unset and unknown values retain the prior PR lanes described below.
Merge admission requires separate live proof of queue enforcement and authorization.

In activated mode, ordinary code PRs run tooling, dependency/security contracts,
the loopback ZSP v2 publisher, ktlint/detekt and Kotlin/Java compilation of Zapstore and
Play main, unit-test and Android-test sources. Separate per-flavor runners use
one active Gradle worker and the existing configured heaps. Tooling owns benchmark
source compilation in prechecks and benchmark assembly in full runs.
The isolated compile matrix runs only in prechecks, including drafts. Legacy
PR lanes retain Zapstore and Play Android-test Kotlin compilation; full suite
runners compile Android-test Kotlin for their own flavor. PR prechecks defer
full unit/golden execution, Kover, Android lint, baseline packaging and the
separate Play parity job to integration CI. Complete suites remain the unit of
integration testing. Required source regressions and
baseline review remain unchanged. Drafts have the same precheck cost but retain
the distinct draft aggregate context and require readiness before queue admission.

Every merge group, master push, nightly and manual run uses the full core matrix:
both complete unit/golden suites and comparison evidence, current coverage gates,
both lint variants, compile/style/security/ZSP contracts and baseline
packaging. Compose reports remain risk-selected supplemental work. Merge groups do not use the PR documentation shortcut. Master remains
full in this first rollout; removing duplicate master work requires separately
reviewed accepted-integration provenance and a full fallback.

A live `ci:full` label selects that same full core matrix on a PR, including a
draft. Changing a label does not start or cancel CI; request a fresh run with
GitHub’s Re-run all jobs after adding it. Reruns read labels from GitHub again
using the current observation. If that read fails in activated
mode, or any complete-diff evidence is missing, malformed or touches build,
dependency, native, workflow or unknown inputs, the PR also gets the full matrix.
Independent review/controller full-preflight holds persist after label removal;
verify their exact accepted run before admission.

Only merge-group executions use distinct run IDs and disable cancellation.
PR, master, scheduled and manual runs retain same-ref supersession.
Unit execution has a 30-minute step budget within the 40-minute job budget,
leaving time to retain failure reports and timing evidence. A timeout fails the gate; every retry requires a retained diagnosis.

The aggregate validates the explicit `precheck`, `full` or `legacy-pr` mode and
the complete expected job set. `legacy-pr` retains the earlier primary-flavor
suite; `full` identifies the complete two-flavor core matrix. Only
the named precheck, docs, supplemental or legacy draft skips are accepted.
Queue core suite jobs must succeed; precheck-only compiler runners must skip. A green PR precheck admits source for integration. Merge qualification requires
successful current integration CI and independent authorization.
Activation, failure recovery and rollback are specified in the
[serial queue contract](android-serial-merge-queue.md#pr-precheck-activation).

## Documentation-only core validation

The core workflow always starts and reports the existing required check. Its
`changes` job examines the **whole PR diff**, with rename detection disabled.
A separate `tooling-contracts` job runs the non-Gradle tooling/metadata/manual-guide/
fuzz-policy validators after classification, alongside Android jobs, including
the preview security/update contracts. It does not delay Android job startup.
Only ordinary non-executable Markdown blobs in the named root guides or `docs/`
qualify. JSON inventories, source-tree Markdown, build/workflow inputs,
executables, symlinks, deleted code and unknown paths require full Android CI.
`docs/composer-dictation-device-matrix.md` remains a full-preflight input.
The invariant registry and manual surface JSON inventories are test-affecting
metadata: they never qualify as docs-only, but may use the activated PR precheck
when the complete diff is otherwise ordinary. Their validators remain required,
and full integration validates their Kotlin consumers. Invalid metadata fails
validation; executable/symlink modes and unknown paths select full preflight.
Document any future build/test input in the classifier.
An unavailable/empty diff, missing refs or any non-PR event also requires full CI.

The aggregate accepts skipped Android jobs only when classification succeeded
with `docs_only=true` on a PR and the separate tooling validators passed. It
rejects missing or unexpected outcomes, code-path skips, failures and cancellations. A docs fixup
on a code PR does not turn that PR into a docs-only candidate.

With queue prechecks disabled, ready code candidates run every core check for the Zapstore distribution,
including coverage, screenshots, baseline packaging and the loopback ZSP v2 publisher
contract. Zapstore is the PR primary because its own source set carries the
self-updater, downloader and installer; Play's adds only a store-listing redirect.
Play is checked on PRs by compiling its main, unit-test and androidTest sources
and running the unit tests that read `BuildConfig.SELF_UPDATE_ENABLED`. Play's
complete unit suite and lint run on every `master` push, nightly, manual dispatch
and merge-queue run. Every complete unit suite also verifies every committed
screenshot golden in the same execution, with matching verification inputs for
Kover reuse; there are no separate screenshot test runners.

Release runtime verification and reproduction (with release lint) run on
`master` pushes, nightly, tags, manual dispatch and the merge queue. PRs run them
only when their path filters match release, signing, build or dependency inputs.
CodeQL runs on `master` pushes, weekly and on dispatch. None of these is a
required check. A `master` failure in any of them is fixed through an owned PR.

## Supplemental campaigns and draft PRs

With queue prechecks disabled, draft PRs run the fast lane: classification, tooling contracts, the offline
signer contract, Zapstore lint and unit/screenshot tests, compilation, ktlint and
detekt. They defer coverage, baseline packaging, Compose reports, the Play check,
emulator workflows and release verification. `ready_for_review` starts a new run
with every applicable lane, so the required aggregate is recomputed for the ready
head. The aggregate accepts the draft skips only when the event is a draft PR,
and on drafts it reports as `Compile, test, ktlint, detekt, Android lint (draft
fast lane)`. The required context therefore comes only from a ready run, so a
green draft result cannot satisfy it after the PR is marked ready.
Do not rely on a green draft run for readiness; wait for the ready run.
The visual-description updater follows opening, reopening and pushes and displays at most
four inline comparisons, retaining links and the bounded list of all changes.

Compose compiler reports remain risk-selected supplemental work, independent of
the full core matrix. Independent unsigned
APK reproduction remains a supplemental campaign. A complete raw diff permits
its deferral for
known ordinary Kotlin/Java source, main resources, committed goldens and prose.
Validated invariant/manual-surface metadata also permits deferral while remaining
non-docs-only. Build, packaging, native pins, manifests, CI/tooling, executable/symlink changes,
unknown inputs or unavailable evidence run reproduction. In full mode, all other compilation,
full-unit/golden/coverage, style and lint gates stay required for ready PRs.
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
The nightly/manual/tag/master-push events cannot accept deferred reproduction
builds. Release workflows accept skipped lanes only for draft PRs.

Measure job requests, summed runner minutes, queue wait and time to final green
checks together. Adding fewer jobs does not guarantee a particular speedup.
This policy adds no paid runners or subscriptions. Further candidate-only release
or security deferral requires exact-head/current-base evidence that the readiness
and merge consumers enforce.

## Selective host tooling regressions

Every run retains actual metadata, guide, dependency, security, authorization,
fuzz-trigger and screenshot-owner validation. Maestro tests that inspect current
production UI source and screen coverage also remain unconditional.

Only the isolated attachment-fixture suite and Maestro artifact, offline-suite,
environment, host-pressure and SDK-image regression suites can be deferred.
The complete PR diff must contain only known ordinary source/resources/goldens,
prose or validated test metadata, with regular file modes. Any instrumented (`androidTest`)
input change, tooling/scripts/workflows, `.maestro` input, build/dependency
change, unknown/malformed diff, unavailable labels or `ci:full` runs all suites.
Missing classifier output also runs them. Merge-group, master, nightly and
manual events always run every host suite, independently of PR precheck mode.
This selection does not activate merge-queue mode or change its admission gates.
