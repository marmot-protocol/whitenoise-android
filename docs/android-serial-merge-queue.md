# Serial Android merge queue

A serial GitHub merge queue tests the reviewed PR against the current `master`
without adding a base-refresh commit to every remaining PR. One candidate is
built and merged at a time. The next candidate starts after the preceding merge
is verified.

## CI contract

`android-ci.yml`, `android-release-runtime.yml`, `android-repro-verify.yml` and
`fuzz-pr.yml` handle `merge_group: checks_requested`. Checkouts use the event's
immutable SHA. Queue concurrency keys include that SHA and do not cancel earlier
integration runs. Queue caches are read-only; preview and release publishing
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
external watchdog. It performs no GitHub calls, inference, scheduling or branch
writes. Authenticated live reads and independently verified evidence are trusted
adapter responsibilities, never values accepted from a PR description.

There are two separate authorization transitions:

1. Reviewed source `H` receives source admission before enqueueing, pinned by
   `expectedHeadOid`. Source review, applicable CI, signatures, resolved
   discussion, and required device/human proof must pass.
2. Integration `G` receives merge authorization only after its exact PR, source
   `H`, current base `B`, native entry ID, tree mapping, compatibility review and
   applicable CI are verified again. A source admission status cannot authorize
   `G`.

The watchdog writes `Android merge authorization`; the ruleset requires it on
both source admission and queue integration. Shared authentication is an
operational safeguard, not credential isolation. Administrators retain the
repository's configured bypass ability; the watchdog does not use it.

Each effect has an immutable payload hash. The existing single producer must
hold its lease, read identity/proof twice, and fsync an attempted intent before
calling GitHub. Lost responses and process death enter read-only reconciliation;
authoritative absence does not permit automatic replay. Queue failures keep the
selected PR with its existing source owner. They do not launch a competing
branch writer or advance another PR. Obsolete integration authorization is
revoked before reassessment. Final squash signature, parent and tree must match
before releasing the slot.

## Controlled activation

Merging these workflow and policy changes does not activate the queue. The
host operator must adopt the reviewed adapter and existing-watchdog changes as
one deployment, retaining all old uncertain intents and independent review
receipts. No additional scheduler or merge producer is introduced.

The deployment configures GitHub's native queue for one entry to build and
merge, minimum one, zero grouping wait, squash, `ALLGREEN`, and a 120-minute
check timeout. Existing signature, discussion and zero-formal-approval rules
remain. Strict source-branch base checks become integration checks through the
queue; the required CI and authorization contexts remain enforced.

Activation is closed until a canary proves the actual GitHub entry/source/base/
integration mapping and invalidation after a PR head changes, including after
integration authorization. An additional required canary hold prevents that
probe from merging. Verify that green CI alone cannot merge, then observe three
natural shadow ticks. Finally admit one qualified PR, verify its signed squash
and an empty queue, and confirm the existing watchdog remains healthy.

If a canary fails or a read is incomplete, keep authorization closed. Rollback
freezes the sole producer, reconciles uncertain effects, withdraws only known
entries, restores the matching strict rules and legacy controller, and verifies
queue absence and current-base CI before allowing legacy merges again. Do not
remove required authorization while an entry remains capable of merging.

## References

- [GitHub merge queue requirements](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/configuring-pull-request-merges/managing-a-merge-queue)
- [Repository rules REST API](https://docs.github.com/en/rest/repos/rules)
- [Required status check semantics](https://docs.github.com/en/pull-requests/how-tos/merge-and-close-pull-requests/troubleshooting-required-status-checks)
