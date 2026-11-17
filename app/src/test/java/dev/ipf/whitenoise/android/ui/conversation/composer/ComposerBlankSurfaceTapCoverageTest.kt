package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unused composer surface opens the keyboard only for an empty, typeable draft, and every control
 * keeps its full hit target, including while disabled (#3857). Behavior is exercised by
 * ComposerExpansionBehaviorTest and ComposerToolbarProductionTest; this gate pins the wiring.
 */
class ComposerBlankSurfaceTapCoverageTest {
    private val pill = appModuleSource("$COMPOSER/ComposerPills.kt").functionBody("ComposerPill")
    private val tap = appModuleSource("$COMPOSER/ComposerBlankSurfaceTap.kt")

    @Test
    fun blankSurfaceTapIsGuardedAndSharesTheFieldsKeyboardRequest() {
        val call =
            pill
                .substringAfter(".composerBlankSurfaceTap(")
                .substringBefore(".composerFabContent()")
        listOf(
            "inputContentVisible",
            "inputFocusEnabled",
            "textFieldValue.text.isEmpty()",
            "voiceReviewContent == null",
            "recordingContent == null",
            "showKeyboardAfterTouchTap = composerFocus.requestFocus()",
        ).forEach { guard -> assertTrue("blank-surface tap must keep `$guard`", guard in call) }
        val owners = Regex("""\.composerBlankSurfaceTap\(""").findAll(pill).count()
        assertEquals("one blank-surface owner per composer", 1, owners)
    }

    @Test
    fun blankSurfaceTapLeavesTheResizeStripChildGesturesAndHoldsAlone() {
        listOf(
            "awaitFirstDown(requireUnconsumed = true)",
            "PointerType.Touch",
            "ComposerResizeStripHeight",
            "isConsumed",
            "touchSlop",
            "longPressTimeoutMillis",
            "event.changes.size > 1",
        ).forEach { rule -> assertTrue("blank-surface tap must keep `$rule`", rule in tap) }
    }

    @Test
    fun everyComposerControlClaimsItsWholeTarget() {
        assertEquals(
            "both configured toolbars must claim their slots",
            2,
            Regex("""slotModifier\.composerControlTarget\(\)""").findAll(pill).count(),
        )
        assertTrue(
            "the sending identity avatar must claim its target",
            "sendingIdentity?.invoke(Modifier.composerControlTarget())" in pill,
        )
        assertTrue(
            "shared action discs (Send, voice, emoji) must claim their target while disabled",
            "modifier.composerControlTarget()" in appModuleSource("$COMPOSER/ComposerActionDisc.kt"),
        )
    }

    private companion object {
        const val COMPOSER = "src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer"
    }
}
