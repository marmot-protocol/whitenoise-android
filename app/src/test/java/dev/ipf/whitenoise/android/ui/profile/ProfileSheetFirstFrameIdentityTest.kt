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
        val effect = source.substringAfter("LaunchedEffect(npub) {").substringBefore("}")

        // `hex ?:` is what stops the IO hop re-resolving an identity the local
        // decode already produced, and stops it reassigning identical state.
        assertTrue(
            "the suspend resolver must be guarded by the seeded value; found: $effect",
            effect.contains("hex ?: appState.accountIdHex(npub)"),
        )
    }

    @Test
    fun exactlyOneFallbackResolutionIsAttempted() {
        val effect = source.substringAfter("LaunchedEffect(npub) {").substringBefore("}")

        // Keyed on npub with a single call site, so an unresolvable reference
        // settles at null instead of retrying in a loop.
        assertEquals(
            "the fallback must be invoked at most once per npub; found: $effect",
            1,
            Regex("appState\\.accountIdHex\\(").findAll(effect).count(),
        )
        assertTrue(
            "the effect must be keyed on npub so it does not re-run on recomposition",
            source.contains("LaunchedEffect(npub) {"),
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
