# Stale-result recurrence baseline and post-policy review plan

This report is the reproducible baseline required to later decide whether the
invariant-gate policy tracked in #2171 should be retained, revised, or
removed. It measures how often defects filed in a fixed historical window
were stale-result defects, and how often a stale-result defect explicitly
recurred after a named earlier fix. It does not implement or modify the
invariant registry, pull-request enforcement, or the visual screenshot gate,
and it makes no causal claim about #2171.

## Reproducing the numbers

The classifier lives in `scripts/stale_result_recurrence.py` with unit tests
in `scripts/test_stale_result_recurrence.py` (run in CI). All aggregate
numbers in this report regenerate offline from the checked-in, privacy-safe
fixture:

```
python3 scripts/stale_result_recurrence.py report
```

To reproduce the classification itself from live GitHub data:

```
python3 scripts/stale_result_recurrence.py fetch /tmp/snapshot.json
python3 scripts/stale_result_recurrence.py classify /tmp/snapshot.json
```

`fetch` retrieves every issue created in the window through the GitHub search
API (bisecting sub-windows so the API's silent 1000-result cap can never drop
issues, and failing loudly on truncated pagination). The snapshot contains
user-authored text and must never be committed. `classify` deterministically
recomputes every classification from the snapshot plus the reviewed
adjudication fixture and verifies the frozen fixture is reproduced exactly;
it exits non-zero on drift or on any ambiguity that lacks an adjudication.

Checked-in fixtures contain only issue numbers, creation dates, boolean
classifications, machine-readable rule/reason codes, and exclusion codes —
no issue titles, bodies, user identifiers, or credentials.

## Baseline window and population

- Window: `2026-05-26T00:00:00Z` through `2026-08-20T23:59:59Z` (UTC,
  inclusive), repository `marmot-protocol/whitenoise-android`.
- Population: issues created in the window that represented product defects
  at filing time. Pull requests are excluded at retrieval (`is:issue`) and
  again defensively at classification.
- Defect rule: the typed **Bug** issue type was only adopted repo-wide during
  August 2026, so type alone would silently exclude nearly all June/July
  defects (June would show 7 eligible instead of 300). The `bug` label is the
  marker that is consistent across the whole window, so an issue is a defect
  when it has the typed Bug type **or** the `bug` label.
- Duplicates: no issue in the window uses GitHub's `duplicate` close reason
  and none opens with a "Duplicate of #N" body, so `excluded_duplicate` is
  zero. The word "duplicate" appears in 201 issues but always as domain
  vocabulary (duplicate events, de-duplication logic), verified by review.
- Audit artifacts: the measurement effort that produced this report (and its
  sibling guard-primitive work) was created after the window closed, so
  `excluded_audit_artifact` is zero for the baseline. The exclusion code and
  adjudication path exist for the post-policy run.

## Classification contract

Two axes are decided per eligible defect, exactly as the measurement contract
defines them:

1. `stale_result` — the report establishes stale work, a stale callback, a
   late async completion, an obsolete generation/session result, or an
   old-account/old-screen result applied after ownership changed.
2. `named_recurrence` — the report names a prior issue or pull request as an
   earlier attempted fix, regression, recurrence, or incomplete fix of this
   behavior.

Keyword matching only generates candidates. Strong patterns classify
directly; medium patterns force an ambiguity that must be adjudicated by a
human-reviewed entry in `scripts/stale_result_recurrence_adjudications.json`;
broad words ("again", "old", "stale") alone never classify an issue as
stale-result — they only flag it for the false-negative sample below. The
classifier refuses to finalize any unadjudicated ambiguity.

### Adjudication policy applied

The 164 reviewed adjudications follow these written rules, applied from the
full issue text:

- `stale_result` is **true** only when the reported defect itself is a stale
  or superseded result, callback, completion, snapshot, or captured context
  applied after it should have been invalidated (examples: a late
  group-creation completion overriding newer navigation, an older full-page
  refresh overwriting a newer live update, a callback bound to a torn-down
  player, a captured account reference used without a re-check after
  ownership changed, a remembered derived value applied to newer input).
- `stale_result` is **false** when the stale keyword appears only in
  acceptance-criteria or test-plan boilerplate ("…invalidate stale
  callbacks"), when the defect is a plain data race or lock-bypass with no
  stale application, when the defect is retained/unrefreshed display or a
  missing cache invalidation, or when a stale mechanism is only one of
  several speculative hypotheses.
- `named_recurrence` is **true** when the report asserts that named earlier
  work attempted to fix this behavior and the behavior recurred or the fix
  was incomplete or itself introduced the defect in the behavior it changed.
- `named_recurrence` is **false** for references that are context, lineage,
  sibling surfaces ("same family as…"), scope boundaries, "no regression
  to X" constraints, "regression coverage" test-plan headings, and any
  suspicion-shaped framing — "likely regression source", "regression lead",
  "suspect PRs" — that names earlier work without asserting that a fix for
  this behavior was attempted and failed. A report that itself traces the
  named fix's path as still sound is likewise not a recurrence.

## Baseline results

```text
month    created  eligible  stale  named  stale+named  note
2026-05       43        40      0      0            0  partial: 2026-05-26 through 2026-05-31
2026-06      514       300     11     11            0  full month
2026-07      455       230      4     15            0  full month
2026-08      199        99      6     15            0  partial: 2026-08-01 through 2026-08-20

totals      1211       669     21     41            0
```

- Eligible defects: **669** (542 excluded as non-defect task/feature/tracking
  issues; no PRs, duplicates, out-of-window, or audit artifacts).
- Stale-result defects: **21** (3.1% of eligible): #109, #225, #470, #471,
  #680, #778, #805, #809, #821, #856, #878, #1239, #1292, #1316, #1323,
  #1804, #1844, #1849, #1953, #2060, #2065.
- Stale-result defects that explicitly recur after a named earlier fix:
  **0**. The nearest candidate, #1804, names prior work only as "related
  history" and "the most recent regression lead", which the adjudication
  policy classifies as suspicion rather than an asserted failed fix.
- Named-recurrence reports across all eligible defects (any category): **41**,
  emitted as the `named` column by the `report` command.
- May and August are partial intervals and are labeled as such; their raw
  counts must not be compared against full calendar months.

## Validation and error risks

Manual validation performed for this baseline:

- Every automatic positive on both axes was individually reviewed against the
  issue text. Of 26 automatic stale positives, 16 were overturned (almost all
  matched stale vocabulary inside acceptance-criteria or test-plan
  boilerplate rather than the reported defect). Of 102 automatic named
  positives, 67 were overturned as context-only references, sibling-surface
  citations, "no regression to X" constraints, or suspicion-shaped
  "regression lead" framing.
- A second, independent review pass re-read a ten-issue sample plus the
  outcome-determining candidates and overturned four named-recurrence
  verdicts (#1366, #1804, #1251, #1800) under the suspicion rule above; the
  fixture records each with an explicit reason code, and the committed-
  fixture pinning test now locks the published totals in CI.
- All 85 ambiguous cases raised by medium-confidence patterns were manually
  adjudicated with recorded reason codes.
- A loose-pattern false-negative sweep over all eligible non-candidates
  (140 hits reviewed) recovered eight stale-result defects the strong
  patterns missed (#109, #225, #680, #1292, #1316, #1844, #1849, #2065) and
  one named recurrence spelled "reoccurrence" (#1171, now matched by the
  classifier), all folded back into the frozen fixture.
- The 16 eligible issues with neither strong, medium, nor broad signals were
  reviewed as the non-candidate sample; none was stale-result.

Remaining known risks:

- **False negatives**: a stale-result defect described without any of the
  pattern vocabulary (or purely as a symptom, e.g. "shows the wrong thing
  sometimes") is invisible to candidate generation; the loose sweep bounds
  but does not eliminate this. Reports whose bodies were edited after filing
  are classified from their current text.
- **False positives**: agent-authored issues quote large amounts of prior
  discussion; quoted stale vocabulary can survive review as a
  misclassification, though every positive was individually read.
- **Denominator drift**: labels and issue types are observed retroactively
  (as of 2026-09-01), not as they were at filing time. Bug-labeling practice
  is consistent across the window, but relabeled issues shift the
  denominator.
- The distinction between "stale result applied" and "state never refreshed"
  is a judgment boundary; the adjudication policy above pins it, and every
  boundary call carries a reason code in the fixture.

## Post-policy review (declared before reading its outcome)

These parameters are fixed now, before any post-policy data exists or is
read:

- **Policy-live date**: the merge date of #2171's CI enforcement to
  `master`, recorded in the post-policy report from the merge commit.
- **Review window**: the first 90 full days (UTC) beginning at 00:00:00Z on
  the day after the policy-live date. This is close to the baseline's 87-day
  span; monthly sub-intervals will be labeled partial exactly as here.
- **Method**: rerun `fetch`/`classify`/`report` for the review window at a
  pinned revision of this classifier, with new ambiguities adjudicated under
  the same written policy and recorded in the same fixture format. Issues
  created by the audit/measurement effort itself are excluded as
  `excluded_audit_artifact`.
- **Recorded outputs**: eligible defects; stale-result defects; stale-result
  defects recurring after a named earlier fix; denominator and every
  exclusion; exact policy-live dates; confidence limitations.
- **Decision thresholds** (on the stale-result share of eligible defects,
  baseline 3.1%, and the stale+named count, baseline 0):
  - **Retain** if the stale share is at most 1.6% (half the baseline) and
    stale+named recurrences stay at zero — the baseline is zero, so any
    recurrence of a named fix is a regression, not a tolerable rate.
  - **Remove** (or fundamentally rethink) if the stale share is at least
    3.1% (no improvement on baseline) — the policy's cost is not paying for
    measurable association with fewer stale-result defects.
  - **Revise** for any result between those bounds, for any non-zero
    stale+named count with an otherwise low stale share, or whenever the
    eligible denominator differs from the baseline by more than a factor of
    two,
    which would make the shares incomparable without adjustment.

The final review must state that a rate change is correlational only.
Changes in issue volume, labeling and typing practice, contributor and agent
behavior, release activity, audit campaigns that batch-file defects, and the
observation duration are all confounders that can move these rates
independently of #2171; the recommendation must weigh them explicitly and
must not claim the policy caused the observed change.
