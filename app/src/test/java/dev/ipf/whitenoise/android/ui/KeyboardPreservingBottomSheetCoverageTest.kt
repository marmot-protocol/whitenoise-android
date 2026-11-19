package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardPreservingBottomSheetCoverageTest {
    /** The sheet reuses KeyboardSafePopup's overlay and Back handling, ending above the keyboard or a given y. */
    @Test
    fun bottomSheetDelegatesOverlayPlumbingToKeyboardSafePopup() {
        val source = appSheetsSource().readText()
        val body = source.functionBody("KeyboardPreservingBottomSheet")

        assertTrue("sheet must use the shared non-focusable overlay", "KeyboardSafePopup(" in body)
        assertTrue(
            "sheet must anchor above the visible-window bottom, including an open IME",
            "BottomAnchoredPopupPositionProvider" in body && "popupPositionProvider = placement" in body,
        )
        assertTrue(
            "a sheet given a window y must keep its bottom there and fit the space above it",
            "AboveWindowYPopupPositionProvider" in body && ".then(heightBound ?: Modifier)" in body,
        )
        assertFalse("sheet wrapper must not duplicate Popup plumbing", Regex("""\bPopup\(""").containsMatchIn(body))
        assertFalse("sheet wrapper must not duplicate Back handling", "BackHandler(" in body || "OnBackInvoked" in body)
    }

    @Test
    fun bottomSheetAddsVisualAndAccessibleModalSemantics() {
        val body = appSheetsSource().readText().functionBody("KeyboardPreservingBottomSheet")

        assertTrue(
            "shared dismissal scrim must remain visible and accessible",
            "scrimModifier" in body &&
                ".background(BottomSheetDefaults.ScrimColor)" in body &&
                "contentDescription = dismissLabel" in body &&
                "role = Role.Button" in body &&
                "onClick(label = dismissLabel)" in body,
        )
        assertTrue(
            "sheet content must retain dialog, traversal, and pane semantics",
            "isTraversalGroup = true" in body &&
                "dialog()" in body &&
                "this.paneTitle = paneTitle" in body,
        )
        assertTrue(
            "sheet must retain Material sizing, shape, and navigation-bar padding",
            "BottomSheetDefaults.SheetMaxWidth" in body &&
                "BottomSheetDefaults.ExpandedShape" in body &&
                ".navigationBarsPadding()" in body,
        )
    }

    /**
     * A held composer control's explanation keeps the keyboard: the composer marks its visible surface as the edge,
     * the hold hands the host that edge's reader, and an explanation with an edge opens this sheet, not the focusable
     * modal one.
     */
    @Test
    fun composerExplanationsUseTheKeyboardPreservingSheet() {
        val explanation = source("ui/common/LongPressExplanation.kt").readText()
        val sheet = explanation.functionBody("ExplanationSheet")
        assertTrue(
            "an explanation with a bottom edge must open the keyboard-preserving sheet at that edge",
            "KeyboardPreservingBottomSheet(" in sheet && "bottomWindowY = bottomWindowY" in sheet,
        )
        assertTrue(
            "holding a control must pass the composer's edge reader to the host, so the sheet follows the composer",
            "explanations.show(current, latestBottomEdge)" in explanation.functionBody("explainOnLongPress"),
        )
        assertTrue(
            "the editor's Back must close a composer explanation before it hides the keyboard",
            "val closeExplanation = composerExplanationClose()" in
                source("ui/conversation/composer/ComposerBar.kt").readText(),
        )
        assertTrue(
            "the conversation must explain its composer's controls above the composer",
            "ExplainAboveComposer {\n" in source("ui/conversation/ConversationBottomBar.kt").readText(),
        )
        assertTrue(
            "the composer must mark the box around its visible surface as the edge, not its taller root",
            ".composerExplanationEdge()" in source("ui/conversation/composer/ComposerBar.kt").readText(),
        )
    }

    @Test
    fun forwardPickerUsesAFullScreenDialogInsteadOfASheet() {
        val body = forwardPickerSource().readText()

        assertTrue("the forward flow should own a full-screen Dialog", "Dialog(" in body)
        assertFalse("the forward flow should not use a draggable sheet", "ModalBottomSheet(" in body)
    }

    /** The AppSheets source file under either working directory. */
    private fun appSheetsSource(): File = source("ui/design/AppSheets.kt")

    private fun forwardPickerSource(): File = source("ui/conversation/messages/ForwardMessagePicker.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $relativePath source file")
}
