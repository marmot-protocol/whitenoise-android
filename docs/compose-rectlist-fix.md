# Official Compose RectList fix (#2647)

The app consumes `androidx.compose:compose-bom-beta:2026.10.00`, which selects
Compose UI, Foundation, Runtime and Animation `1.13.0-beta01`. This is an official
beta release, not a stable release. It replaces the temporary UI 1.12.1 source
backport from [PR #3136](https://github.com/marmot-protocol/whitenoise-android/pull/3136).

## Upstream and artifact evidence

The [October 7, 2026 release notes](https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0-beta01)
include the alignment-line placement correction for Android issue 549552303,
change `Icec9eff72dbc6e77ee168c26c36f37ca7b244bee`, matching
[AndroidX commit fd550bed793](https://github.com/androidx/androidx/commit/fd550bed793b66378c83091532e29c18fdef44cc).
The September 9 `1.13.0-alpha03` release predates this correction.

Inspected from Google Maven on October 8, 2026:

| Artifact | SHA-256 |
| --- | --- |
| `ui-android-1.13.0-beta01.aar` | `65e12f1406d3f9196716509c5a892e6c05f7b82a640a16ebb8db218a2fc05bb5` |
| `ui-android-1.13.0-beta01-sources.jar` | `dc792b09830252c0c9f285933172ed63a1e7c67c4ec7e1588a962e04c525241e` |

Both the source archive and disassembled AAR's `AlignmentLines.recalculate`
propagate the owner's alignment-only placement flag to a dirty child, lay out
that child, then restore the flag. The owner interface and both measure/lookahead
implementations expose the flag. These are the changes previously backported.
The downloaded AAR declares minimum compile SDK 37.1 and AGP 9.1.0; the project
uses compile SDK 37.1 and AGP 9.4.1. Target SDK remains 36.

Material3 stays at the existing `1.5.0-alpha25` pin, including its Android and
ripple modules. Explicit strict constraints at the app, unit-test and instrumented
test roots prevent each separately imported beta BOM from advancing those
components or conflicting with the tested app. Other Compose versions remain BOM-owned. There is
no local Compose compiler invocation, source patch, class replacement or AAR
transform. The checksums above record inspected provenance; they are not a new
dependency verification mechanism.

## Retained regression and acceptance coverage

- `ComposeRectListRegressionTest`: three shared alignment/reuse cases on both
  distribution unit-test classpaths.
- `ComposeRectListRegressionAndroidTest`: the same three cases retained in the
  required emulator smoke manifest.
- `BubbleFooterRectListReuseTest`: five production footer/Markdown reuse,
  reactivation and far-snap cases.
- Existing scrolling, jump-to-bottom, unread-marker and screenshot tests remain
  unchanged. CI retains results as `compose-rectlist-regressions-Play` and
  `compose-rectlist-regressions-Zapstore`.
- [CON-029](manual-release-testing/cases/CON-029.md) remains the manual acceptance
  scenario for minified candidates on both distributions, including Android 17.

The old backport identity assertion, its original-1.12.1 negative control and
workflow calls to the APK marker checker are removed because the runtime now
comes from Google Maven. The legacy `scripts/verify_compose_backport_apk.py` path
temporarily remains for the trusted preview workflow dispatched from `master`,
which checks out this PR before invoking that path. Its replacement implementation
requires unique official UI `1.13.0-beta01` version metadata and rejects the old
custom marker. Remove this compatibility entry point and its Python tests in a
follow-up after the updated preview workflow lands on `master`.
The original positive/negative-control evidence is preserved in PR #3136.
The shared fixture retains its AndroidX copyright/modification notice and
[Apache license](../third_party/androidx-test-fixtures/LICENSE.txt).

Source and artifact inspection do not qualify a device or a release. Before
readiness, require the candidate's CI (both distributions, all committed screenshot
baselines, required emulator cases and release packaging) plus CON-029 device
acceptance. Keep existing baselines until any rendering differences are inspected;
do not treat a library upgrade as permission to accept arbitrary screenshot drift.
