# Settings search

The Settings home toolbar opens the same compact search field used by the chat
list. Empty input keeps the home viewport; non-empty input shows only static
Settings UI metadata. Search never indexes account/profile values, keys, relay
URLs, blocked identities, chat/folder names or imported emoji. There is no query
history, persistent index, telemetry or remote search.

`SettingsSearchEntry` supplies a stable ID, existing localized title/category and
optional summary/curated alias resources, destination and keyed list/row anchor.
The catalog is resolved for the current locale and prerequisite snapshot. Every
query token must match; exact title/alias matches precede title prefixes, title
words/infixes and metadata. Stable catalog order breaks ties. Filtering fixes the strongest result immediately below the toolbar; it does not animate placement or opacity for that first row. Lower rows use keyed lazy-item placement with the existing 200 ms Settings timing and a short 120 ms membership fade-in. Each actual query edit requests the top result viewport, while returning from a detail leaves the unchanged query and saved viewport intact. Removed results retire immediately, following the chat list policy, so stale clickable/semantics nodes never linger during an exit fade. Current matches publish without a debounce or stagger, and system reduced motion settles immediately. Input is bounded to
160 characters and the production catalog is bounded by the coverage test.

Selection reveals the existing control without activating it. Inline dialogs,
permission requests, export, update, sign-out and wipe keep their original
buttons, confirmations and authentication. A search-origin return goes directly
back to the saved results; ordinary Settings parent navigation is unchanged.
Account/runtime changes, lock, sign-out and wipe retire the visit. Platform push
capability is resolved per account/runtime/resume revision, never per query edit;
a push result is checked again when selected.

The checked-in `settings-search-coverage.json` inventories reachable destinations
and source row declarations independently of the production catalog. Each control
is indexed, represented by its picker/management owner, or excluded with a reason.
Its contract tests reject missing destinations, changed declarations, duplicate
IDs and invalid list/row references. Transient retry controls and user-generated
entities are deliberately absent from metadata. Resource-backed ranking fixtures
exercise English and Spanish.

## Qualification and latency

Source-render screenshot baselines cover the real toolbar, results, expanded
empty field and no results at a normal 360 × 780 dp viewport/font scale 1, plus
separate RTL and font scale 2 cases in Light/Dark/AMOLED. Frozen-clock motion evidence records first/middle/settled filtering positions and live reduced motion; assertions check a stable top anchor, lower-row placement, immediate first-row replacement and removal of stale result semantics. They are not APK/device
qualification. Exact-head CI, reviewed images and physical testing remain required.

The bounded benchmark fixture uses the production catalog and the sequence
`f`, `fo`, `font`, `font s`, `font size`, `font`, empty, `text size`, repeated 100
times. Record cold catalog resolution separately from warm edit-to-result matching
and frame presentation. The unit fixture exercises the work but makes no
representative-hardware latency claim.

Proposed qualification budgets, to be confirmed against a representative Pixel
6a before release: cold catalog preparation ≤ 50 ms; warm matching p95 ≤ 8 ms;
edit-to-presented-results p95 ≤ 32 ms. Measure both distributions, cold launch and
warm repeated edits with normal font scaling. Retain sample count, median, p95,
maximum, build identity and platform separately. Investigate regressions beyond
these budgets; do not turn a CI-host timing into a device performance PASS.
No representative-device latency measurement is recorded by this source change.

Manual scenarios: SET-042 (matching/reveal/return), SET-043 (ownership/eligibility)
and SET-044 (accessibility/layout/performance).
