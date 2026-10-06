# Temporary Compose UI 1.12.1 source backport

White Noise issue [#2647](https://github.com/marmot-protocol/whitenoise-android/issues/2647)
recurs on the published 2026.10.3 APK, which already contains Compose UI 1.12.1.
The fully retraced Pixel crashes enter alignment recalculation through `Row` while
flinging and prefetching. A dirty descendant is placed under an unplaced parent
during an alignment query, violating the spatial RectList invariant.

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
   `git apply --check` followed by `git apply`, with Git discovery isolated from
   enclosing checkouts. All four resulting source hashes must match the reviewed
   patched sources before compilation; a successful Git exit alone is insufficient.
3. Compiles them against the original UI artifact and its resolved dependencies,
   using Kotlin/Compose compiler 2.4.20, language/API 2.1, JVM 11, invokedynamic
   lambdas (matching the released classes) and module name `ui`. These common
   sources do not need an Android platform stub jar. The original jar is a friend module for its internal APIs. Compose
   compilation preserves the generated stability fields used by unchanged code.
4. Requires every original class generated from those four sources to remain,
   with its existing non-private JVM members, superclass, interfaces and bytecode
   target intact. ASM is used to inspect declarations, never to modify bytecode.
5. Replaces only those source-compiled classes. Other class contents, Kotlin
   module mappings, resources, manifest, consumer rules, lint, inspector and
   profile contents remain those of the original AAR. A small classpath marker
   records the base version and upstream commit. ZIP ordering/timestamps are fixed.

Maven coordinates and dependency metadata stay on the existing 1.12.1 BOM.
No snapshot repository, layout workaround, exception suppression, new reuse keys,
or prefetch disablement is involved. A different `ui-android` AAR fails the hash
check, requiring explicit removal/review when upgrading Compose.

## Verification and limits

The unit suite asserts the backport marker is actually present, ports Google's
reuse regression, and exercises Row alignment and lookahead. App-level fixtures
use the real Markdown/footer components and scroll writer for far jumps and
off-screen edited-row reactivation. The modeled centering sequence is not a full
screen navigation test. Platform regressions also run in the emulator suite.

During the investigation, unpatched 1.12.1 failed the upstream reuse test and the
Row-alignment and edited-bubble fixtures; the official fixed snapshot passed the
upstream fixture. Those results establish the regression, not qualification of
this separately built backport. Require this PR's exact-head CI, both distributions,
existing screenshot comparisons, minified preview builds and device acceptance.
Android 17 field-report acceptance remains a separate physical-device check.

Per repository policy, compilation and tests run on GitHub. Never uninstall or
clear a personal device's app data. Install an approved, matching candidate in
place, following the Pixel dev migration runbook when using that package.

## Removing the backport

After a published version is verified to contain the upstream fix:

1. Upgrade the Compose BOM to that release and remove the root build's backport
   `apply`, `gradle/compose-ui-backport.gradle.kts`, this directory, and the
   backport-only `buildSrc` code/dependency. Remove its ignored build path if the
   directory is otherwise empty.
2. Remove only the marker assertion from `ComposeRectListBackportTest`; retain
   the behavioral unit/platform regressions and manual acceptance scenario.
3. Verify the resolved/packaged official artifact, both distributions, screenshots,
   minified previews and the same device scenarios before release.

The accompanying Apache 2.0 license is from the official source archive. Google's
test has been adapted in the regression test files with its license retained.
