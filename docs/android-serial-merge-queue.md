# Serial Android merge queue

A serial GitHub merge queue tests the reviewed PR against the current `master`
while preserving the source heads of remaining PRs. Up to five reviewed
PRs can queue: one active front and four waiting entries. One candidate is built
and merged at a time. After a merge, the next front needs fresh qualification
against its new base and integration commit while keeping its source head.

## CI contract

`android-ci.yml`, `android-release-runtime.yml`, `android-repro-verify.yml` and
`fuzz-pr.yml` handle `merge_group: checks_requested`. Queue runs keep both
distributions and every lane that a ready PR ran before PR-side narrowing; path
filters and draft skips apply only to `pull_request`. Checkouts use the event's
immutable SHA. Queue concurrency keys use the workflow run ID, allowing each
integration run to finish independently. Queue caches are read-only; all workflows reserve the authorization check name
and restrict token writes to the existing explicit publishers. Repository
default token permissions must be read-only with PR approval disabled; preview and release publishing
remain separate.

Classification uses the complete `base_sha..head_sha` integration diff and
requires the queue base to be its ancestor. Missing objects, malformed diffs and
unknown paths run conservative validation. Existing documentation and
supplemental campaign selections remain in force. Fuzz selection uses the same
literal paths as the PR workflow; unrelated known changes avoid compiling fuzz.
The existing required aggregate check remains
`Compile, test, ktlint, detekt, Android lint`.

## Authorization contract

`scripts/whitenoise_android_serial_queue.py` is the pure policy used by the
external watchdog. It computes the next action; the adapter supplies authenticated
live reads and independently verified evidence. PR descriptions remain untrusted
input.

The policy accepts a complete, ordered inventory of up to five entries. Waiting
entries await their integration mapping. Integration authorization is reserved
for a mapped current front. Duplicate entries, incomplete
inventories, reordered positions and a sixth entry hold admission.

The journal format and effect keys remain unchanged. Queue membership and entry
state are validated afresh for each action. A follower joining or leaving, or a
front state transition, retains the existing effect key and retry counts for an
unchanged identity, proof and admission generation. Effective
protection rules are checked on both reads before a new effect is attempted.

There are two separate authorization transitions:

1. Reviewed source `H` receives source admission before enqueueing, pinned by
   `expectedHeadOid`. Source review, applicable CI, signatures, resolved
   discussion, and required device/human proof must pass. Before source
   authorization or enqueue, the trusted host validator checks the complete
   workflow inventories of H and H integrated into current B. Queue workflows
   cannot use repository secrets, publish authorization checks, or grant
   status/check write permissions. A workflow check that impersonates the
   authorization name holds the candidate.
2. Integration `G` receives merge authorization only after its exact PR, source
   `H`, current base `B`, native entry ID, tree mapping, compatibility review and
   applicable CI are verified again. A source admission status cannot authorize
   `G`. All expected queue workflows must register and complete before release;
   trusted workflow classifications may skip inapplicable jobs. The Android
   aggregate and campaign classification must succeed.

The watchdog writes `Android merge authorization`; the ruleset requires it on
both source admission and queue integration. Shared authentication is an
operational safeguard, not credential isolation. Administrators retain the
repository's configured bypass ability; the watchdog does not use it.

Each effect has an immutable payload hash. The existing single producer must
hold its lease, read identity/proof twice, and fsync an attempted intent before
calling GitHub. Lost responses and process death enter read-only reconciliation;
authoritative absence does not permit automatic replay. Queue failures keep the
affected PR with its existing source owner. They do not launch a competing
branch writer or grant another PR authorization. Obsolete integration authorization is
revoked before reassessment. Final squash signature, parent and tree must match
before releasing the slot. A known timeout/refusal can receive a new
logged admission generation from the original source owner after fresh proof;
this never replays an uncertain effect. Known refusals and exhausted retries hold
the affected PR and admission generation; other qualified PRs may advance.
The default planner authorizes a mapped front before considering admission.
A host caller holding one admitted PR’s lease can pass `target_number` to
`plan()` or `tick()`; it must match the source candidate. This scopes work to
that PR while validating the complete queue, so admitting a peer cannot borrow
the front’s integration proof or lease.

Uncertain sent effects remain a global read-only reconciliation hold.
A positive local receipt that no
request reached GitHub permits at most three invocations for the same effect
(the initial invocation and two retries), with persisted attempt counts,
backoff and full fresh identity/proof checks. A third never-sent failure persists
`not-sent-exhausted` and holds the affected PR and generation across process restarts, including
direct execution of the same effect. After repairing the local fault, the
original owner may explicitly record a new admission generation; the exhausted
record is retained, and the new effect still requires fresh identity/proof checks.
The exhausted record continues to hold changed proof keys for the same PR and
admission generation. Other PRs remain eligible for independent qualification.
Revocation and dequeue maintenance use their own finite budgets and uncertain-write
reconciliation guards. Exhausted maintenance holds the same operation and exact
identity at that generation across proof changes and journal reloads; other
operations and identities remain eligible for freshly validated cleanup.
Confirmed maintenance recovery must match the exhausted full identity, including
its queue entry. A new admission generation permits fresh recovery for the named
PR. The historical exhaustion remains as evidence; matching confirmed recovery
releases its hold. Attempted or never-sent recovery leaves that record unresolved,
and unknown sent effects require global read-only reconciliation. The trusted
adapter retains each PR's owner and history until verified merge or retirement.
The companion host adapter must use `exhaustion_unresolved` when retiring a
selection. Explicit recovery may rebind the same PR to freshly verified current
source/base commits, retaining the exhausted payload and double-checking new
source evidence. A receipt-bound owner withdrawal releases exhaustion only
after matching live target-absence/PR and survivor observations and absence of
outstanding integration authorization. It retains the attempt counter, exhaustion and
withdrawal proof. Closed selected PRs retain their owner handle until positive
closed/target-absent retirement; historical retirement never clears a different
source record. Confirmed recovery and withdrawal use the same exhaustion predicate
as candidate retirement. These live observations and owner receipts are trusted
host-adapter responsibilities; the pure policy validates the retained record,
but cannot authenticate an owner or query GitHub itself.
Changing generation never releases an uncertain sent write. A remote API refusal stays held for explicit
recovery; a sent request with a lost response stays uncertain. The CLI child
verifies the journal intent and its actual ancestor producer lock.
An explicit owner recovery can retire a positively closed, unmerged PR after
two matching reads of the PR, target absence and the complete survivor queue.
It preserves the uncertain effect as terminal evidence, excludes the PR from future selection and never
retries that write.
Queue holds, their age, uncertain effects and API budget are exposed through
the existing watchdog health report.

## Controlled activation

Queue activation remains a separate deployment. The host operator must adopt
the reviewed adapter and existing-watchdog changes as
one deployment, retaining all old uncertain intents and independent review
receipts. No additional scheduler or merge producer is introduced.

The deployment admits up to five entries while configuring GitHub's native queue
for one entry to build and merge, minimum one, zero grouping wait, squash,
`ALLGREEN`, and a 120-minute
check timeout. Existing signature, discussion and zero-formal-approval rules
remain. Strict source-branch base checks become integration checks through the
queue; the required CI and authorization contexts remain enforced.

Activation is closed until a canary proves the actual GitHub entry/source/base/
integration mapping and invalidation after a PR head changes, including after
integration authorization. A canary mode is restricted to one pinned PR head
and requires an additional canary hold in live branch rules. The operator
satisfies that hold on the source head only; it stays unsatisfied on generated
integrations, preventing the canary from merging even after authorization.
Workflow job names cannot claim either authorization or canary hold. Verify that green CI alone cannot merge, then observe three
natural shadow ticks. Finally admit one qualified PR, verify its signed squash
and an empty queue, and confirm the existing watchdog remains healthy. Before
raising admission capacity, also verify multiple queued entries, waiting entries
without integration credit, target-only cleanup and fresh front qualification
after promotion.

If a canary fails or a read is incomplete, keep authorization closed. Rollback
first enters draining mode to revoke known live integrations and withdraw pinned
entries; frozen mode still permits read-only uncertain-effect reconciliation. It
then freezes the sole producer, reconciles uncertain effects, withdraws only known
entries, restores strict current-base rules and full-PR mode under the current
sole coordinator/effect executor, and verifies queue absence and current-base
CI before allowing direct merges again. Quarantined legacy discovery, repair
and merge services remain stopped. A
read-only snapshot helper verifies the saved strict rules and token defaults.
The explicit legacy-restored config binds that snapshot hash to live readback,
retains the journal and freezes on drift; deleting the audit directory is
unnecessary. Do not
remove required authorization while an entry remains capable of merging.

## PR precheck activation

`ANDROID_CI_MODE=merge-queue` enables the smaller PR lane only after the current
coordinator verifies native queue enforcement and external integration
authorization. The variable controls operations; live enforcement evidence authorizes admission.
Unset or unrecognized values retain full PR validation. Rollback also requires queue draining and uncertain-effect reconciliation.

Source admission checks the applicable exact-source precheck or required full
preflight. Integration admission independently verifies the exact repository,
workflow revision, event, run ID/attempt, entry, source H, base B and integration G,
and every required full job. Workflow outputs and artifacts declaring `full`
provide diagnostics; authenticated workflow evidence provides authority. Every merge group runs both full core variants,
including documentation-only integrations. A direct merge requires full current-base qualification and authorization.

Before activation, prove a deliberately failed integration reaches its existing
source owner with retained diagnostics, one acknowledged notification, a bounded
repair, fresh review and successful reentry. Unknown effects, active owners,
feedback failures and the author exception retain their existing holds.

## References

- [GitHub merge queue requirements](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
- [Repository rules REST API](https://docs.github.com/en/rest/repos/rules)
- [Required status check semantics](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)
