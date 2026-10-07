# Temporary Compose UI 1.12.1 source backport

White Noise issue [#2647](https://github.com/marmot-protocol/whitenoise-android/issues/2647)
has retained production crashes on the published 2026.10.3 APK, which already
contains Compose UI 1.12.1. Their retraced frames enter alignment recalculation
through `Row` during fling and prefetch. A dirty descendant is placed under an
unplaced parent during an alignment query, violating the spatial RectList invariant.

This directory carries only the four production-file hunks from AndroidX commit
[`fd550bed793b66378c83091532e29c18fdef44cc`](https://github.com/androidx/androidx/commit/fd550bed793b66378c83091532e29c18fdef44cc),
"Lay out children for alignment when recalculating alignment lines", September 30,
2026 (Change-Id `Icec9eff72dbc6e77ee168c26c36f37ca7b244bee`, b/549552303).
The patch propagates the alignment-only placement flag through both measure and
lookahead owners, preserving and restoring the previous child flag.

Google Maven was checked on October 7, 2026: stable UI 1.12.1 and alpha
1.13.0-alpha03 do not contain this fix. The fix is present in the official
snapshot build 16526839, but that snapshot updates several Compose families.
There is no confirmed publication date for a release containing the fix.

## Production evidence

Two retained Pixel crashes from October 4, 2026 (07:05 and 08:38 WAT) were retraced
against the mapping for the verified installed production APK:

- Release: `2026.10.3`, versionCode `21`.
- Source: `b2345661414128df893ebdf30e3e0075d4420160`.
- APK SHA-256: `1cc65c8cf550cdd0533e809a371cbf97f7c4000aaa2bfbfbd2d45ab0fe743e67`.
- Mapping SHA-256: `d1a8abaa5bc96418b03d8471818f7bae42657a27cab80da566312f23950db3a8`.

Selected frames, ordered from the throw toward its callers, are:

```text
RectManager.indexInRectList                         :963
RectManager.recalculateRectIfDirty                  :303
MeasurePassDelegate.markNodeAndSubtreeAsPlaced       :298
MeasurePassDelegate.onNodePlaced$ui                 :382
AlignmentLines.recalculate$lambda$0                 :149
AlignmentLines.recalculate                         :143
RowMeasurePolicy.measure                           :142
```

The throws identify LayoutNode `31679` and `15121`. One trace includes
`DefaultFlingBehavior.performFling`; the other includes
`LayoutNodeSubcompositionsState` premeasurement, `PrefetchHandleProvider` and
`AndroidPrefetchScheduler`. These are selected diagnostic frames, with account and
message data omitted. They support the upstream alignment-placement diagnosis;
they are not a claim that a fresh attempt reproduced it. The monitored fast-scroll
attempt on October 5 did **not** reproduce the crash.

A later Android 17 report with versionCode 21 and `LayoutNode 863` follows the same
frame family. Its installed APK checksum was unavailable, so it cannot establish
the reporter's exact binary or a failure of this backport.

## Build and provenance

`gradle/compose-ui-backport.gradle.kts` registers a cacheable AAR transform for
every Android consumer configuration. `buildSrc/src/main/java/ComposeUiBackport.java`
downloads nothing itself: Gradle resolves the released Google artifacts and the
explicitly pinned Kotlin compiler/Compose compiler plugin. The transform:

1. Verifies these official inputs with SHA-256:
   - `androidx.compose.ui:ui-android:1.12.1` AAR:
     `3f1ae17e237d9dc0a2a0f42cb04b85b8de6e12f67adc5f5c12173fe31e501cb2`.
   - Its `sources` JAR:
     `8ddde80690da97cd840c6f8f59c608701f9e42c245f12f0eabc1434db57e0273`.
2. Extracts only the four affected sources and applies the upstream patch with
   `git apply --check` followed by `git apply`. Both commands force
   `core.autocrlf=false` and `core.eol=lf`, with Git discovery isolated from enclosing
   checkouts. `.gitattributes` also keeps the patch itself LF. All four resulting
   source hashes must match before compilation. A mismatch identifies which input
   needs investigation; a line-ending failure is not a reason to remove the fix.
3. Compiles them against the original UI artifact and its resolved dependencies,
   using Kotlin/Compose compiler 2.4.20, language/API 2.1, `-Xjdk-release=11`,
   invokedynamic lambdas (matching the released classes) and module name `ui`.
   These common sources do not need an Android platform stub jar. The original jar
   is a friend module for its internal APIs. Compose compilation preserves the
   generated stability fields used by unchanged code.
4. Requires every original class generated from those four sources to remain,
   with its existing non-private JVM members, class access flags, superclass,
   interfaces and bytecode target intact. ASM inspects declarations and never
   modifies bytecode. This is an existing-declaration guard, not a general proof of
   JVM linkage. In this pinned source revision, the interface's two implementers
   (`MeasurePassDelegate` and `LookaheadPassDelegate`) are both recompiled.
5. Replaces those 12 source-compiled classes. Other class contents, Kotlin module
   mappings, resources, manifest, consumer rules, lint, inspector and profile
   contents remain those of the original AAR. A classpath marker records the base
   version and upstream commit. ZIP ordering is fixed; the February 1, 1980 timestamp
   avoids the timezone-dependent extended timestamp emitted at the January 1 sentinel.

Maven coordinates and dependency metadata stay on the existing 1.12.1 BOM, even
though these classes differ from Google's released binary. Retrace with the
candidate APK's own R8 mapping and these patched sources: upstream line numbers
shift after the patch. No snapshot repository, layout workaround, exception
suppression, new reuse keys or prefetch disablement is involved.

A different `ui-android` AAR deliberately fails the hash guard. This includes
Dependabot BOM updates and transitive upgrades from other Compose dependencies;
review the resolved UI version and official fix before upgrading. Do not disable
the guard to make a dependency update green.

## Verification and limits

The unit suite checks both the provenance marker and the loaded
`AlignmentLinesOwner.setPlacingForAlignment(boolean)` declaration, including both
implementations. It ports Google's reuse regression and exercises synthetic Row
alignment and lookahead. App-level fixtures use the real Markdown/footer
components and scroll writer for far jumps and off-screen edited-row reactivation.
They assert that scroll jobs complete without cancellation, reach the requested
row, and lay out the edited body at the preserved row size. The unkeyed cases
exercise pooled reuse; the keyed cases model the shipped row wrapper.

For PRs changing the backport, the baseline CI job runs
`scripts/verify_compose_backport_control.py` in a disposable clone of its checkout.
Only that clone loses the transform. A separate identity test requires the original
loaded interface and absence of the marker. All eight behavioral cases must execute;
the upstream reuse case and shipped-key edited-bubble case must fail specifically
with `LayoutNode ... not found in RectList`. Build failures, missing marker
assertions, skipped tests and unrelated exceptions cannot qualify the control.
Per-case XML, source identity and logs are uploaded as
`compose-backport-verification-baseline`. A passing coverage case on unpatched UI
is recorded as such, not claimed as a demonstrated reproducer.

Build-logic unit tests compare archive bytes across UTC, New York and Tokyo and
exercise the real Git command under hostile global CRLF settings. Independent
unsigned Zapstore builds and trusted Play previews check the packaged provenance
marker. These artifact checks complement the runtime assertions and do not replace
them. The shared platform regressions also run in the emulator suite.

The modeled centering sequence is not full-screen navigation. These tests do not
deterministically drive a fling/prefetch race or the production media alignment
envelope. Require the candidate's exact-head CI, both distributions, existing
screenshot comparisons, minified previews and manual scenario CON-029. An earlier
head's green CI or device smoke does not qualify a new head. Android 17 field-report
acceptance remains a separate physical-device check. Compilation and CI-covered
tests run on GitHub per repository policy.

## Removing the backport

After a published version is verified to contain the upstream fix:

1. Upgrade the Compose BOM to that release and remove the root build's backport
   `apply`, `gradle/compose-ui-backport.gradle.kts`, this directory, the patch
   attribute rule and backport-only `buildSrc` classes, tests and dependencies.
   Remove `buildSrc` settings/build files and its ignored build path only if nothing
   else uses them. Remove the standalone build-logic test invocation if obsolete.
2. Remove the backport identity test (marker and reflective assertions), unpatched
   control scripts/tests and their CI steps, APK-marker helper and workflow calls.
   Keep the behavioral unit/platform regressions, strengthened scroll assertions,
   existing screenshot coverage and manual acceptance scenario.
3. Update the provenance documentation. Verify the resolved/packaged official
   artifact, both distributions, screenshots, minified previews and the same device
   scenarios before release.

The accompanying Apache 2.0 license is from the official source archive. Google's
shared regression fixture retains its original 2020 header and a modification notice.
