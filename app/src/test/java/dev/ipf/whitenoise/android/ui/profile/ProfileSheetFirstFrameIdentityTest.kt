package dev.ipf.whitenoise.android.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The profile sheet must know its identity on its first composed frame (#1432).
 *
 * `ModalBottomSheet` animates toward the height it measures, so content that
 * only appears after a suspend resolver returns moves that target mid-animation
 * and reads as a stutter on open.
 *
 * These assert against the production source rather than a re-implementation of
 * the resolution order. `ProfileSheet` takes a concrete `WhiteNoiseAppState`
 * with no interface to fake and no injectable resolver seam, so a behavioural
 * test would have to reconstruct the ordering it is meant to be checking — and
 * would then pass even if the composable still initialised `hex` to null. The
 * repository already uses this pattern where no seam exists
 * (`ChatsScreenSelectionActionsCoverageTest`, `TtsMarkdownCallSiteCoverageTest`).
 */
class ProfileSheetFirstFrameIdentityTest {
    private val source =
        File("src/main/java/dev/ipf/whitenoise/android/ui/profile/ProfileSheet.kt")
            .readText()

    private val appState =
        File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt")
            .readText()

    /** The `hex` declaration, which is what decides the first frame. */
    private val hexDeclaration =
        source
            .lineSequence()
            .first { it.contains("var hex by remember(npub)") }

    /**
     * The whole block introduced by [header], brace-balanced.
     *
     * Naive truncation at the first `}` would stop inside a nested lambda such
     * as `?.also { hex = it }`, so anything added after it — a second resolver
     * call, a retry — would escape these assertions.
     */
    private fun balancedBlock(
        text: String,
        header: String,
    ): String {
        val start = text.indexOf(header)
        check(start >= 0) { "block not found: $header" }
        var depth = 0
        for (index in (start + header.length - 1) until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("unbalanced block: $header")
    }

    private val identityEffect get() = balancedBlock(source, "LaunchedEffect(npub) {")

    @Test
    fun identityIsSeededSynchronouslySoTheFirstFrameMeasuresTheSettledHeight() {
        assertTrue(
            "hex must be seeded from the synchronous decode; found: $hexDeclaration",
            hexDeclaration.contains("profileReferenceAccountIdHex(npub)"),
        )
    }

    @Test
    fun identityIsNotInitialisedToNull() {
        // The pre-fix form. Restoring it reintroduces the mid-animation height
        // change that #1432 is about, so pin it explicitly.
        assertFalse(
            "hex must not start null; found: $hexDeclaration",
            hexDeclaration.contains("mutableStateOf<String?>(null)"),
        )
    }

    @Test
    fun theSuspendResolverRunsOnlyWhenTheLocalDecodeFailed() {
        val effect = identityEffect

        // `hex ?:` is what stops the IO hop re-resolving an identity the local
        // decode already produced, and stops it reassigning identical state.
        assertTrue(
            "the suspend resolver must be guarded by the seeded value; found: $effect",
            effect.contains("hex ?: appState.accountIdHex(npub)"),
        )
    }

    @Test
    fun exactlyOneFallbackResolutionIsAttempted() {
        val effect = identityEffect

        // Keyed on npub with a single call site inside the effect, so an
        // unresolvable reference settles at null instead of retrying in a loop.
        assertEquals(
            "the fallback must be invoked at most once per npub; found: $effect",
            1,
            Regex("appState\\.accountIdHex\\(").findAll(effect).count(),
        )
        // Asserted on the extracted block, not the whole file, so the call is
        // proven to sit inside the npub-keyed effect.
        assertTrue(
            "the fallback must live in the npub-keyed effect; found: $effect",
            effect.startsWith("LaunchedEffect(npub) {"),
        )
    }

    @Test
    fun theExtractorSurvivesTheNestedLambdaInTheEffect() {
        // Guards the assertions above: truncating at the first `}` would stop
        // inside `?.also { hex = it }` and hide anything added after it.
        val effect = identityEffect

        assertTrue("must span past the nested lambda", effect.contains("?.also { hex = it }"))
        assertTrue("must close the effect block", effect.trimEnd().endsWith("}"))
        assertTrue(
            "must reach the refreshProfile call at the end of the effect",
            effect.contains("refreshProfile("),
        )
    }

    @Test
    fun theSeedResolverIsThePureLocalDecodeAndNotASuspendCall() {
        val declaration =
            appState
                .lineSequence()
                .first { it.contains("fun profileReferenceAccountIdHex") }

        assertFalse(
            "the seed must not suspend — it runs during composition; found: $declaration",
            declaration.contains("suspend"),
        )
        assertTrue(
            "the seed must delegate to the pure local decode; found: $declaration",
            declaration.contains("nostrEntityAccountIdHex"),
        )
    }
}
