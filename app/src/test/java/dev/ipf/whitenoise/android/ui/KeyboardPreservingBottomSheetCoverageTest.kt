package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardPreservingBottomSheetCoverageTest {
    @Test
    fun bottomSheetDelegatesOverlayPlumbingToKeyboardSafePopup() {
        val source = appSheetsSource().readText()
        val body = source.functionBody("KeyboardPreservingBottomSheet")

        assertTrue("sheet must use the shared non-focusable overlay", "KeyboardSafePopup(" in body)
        assertTrue(
            "sheet must anchor above the visible-window bottom, including an open IME",
            "popupPositionProvider = BottomAnchoredPopupPositionProvider" in body,
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

    @Test
    fun remainingMessageSheetsUseKeyboardPreservingContainer() {
        listOf(
            editHistorySource().readText().functionBody("EditHistorySheet"),
            messageFullScreenSource().readText().functionBody("MessageInfoSheet"),
        ).forEach { body ->
            assertTrue("conversation sheet must use KeyboardPreservingBottomSheet", "KeyboardPreservingBottomSheet(" in body)
            assertFalse("conversation sheet must not open a focus-stealing ModalBottomSheet", "ModalBottomSheet(" in body)
        }
    }

    @Test
    fun forwardSheetKeepsItsFocusableModalForSearch() {
        val body = messageActionsSource().readText().functionBody("ForwardMessageSheet")

        assertTrue("the forward flow still needs a focusable ModalBottomSheet for its search field", "ModalBottomSheet(" in body)
    }

    private fun appSheetsSource(): File = source("ui/design/AppSheets.kt")

    private fun editHistorySource(): File = source("ui/conversation/messages/EditHistory.kt")

    private fun messageFullScreenSource(): File = source("ui/conversation/messages/MessageFullScreen.kt")

    private fun messageActionsSource(): File = source("ui/conversation/messages/MessageActions.kt")

    private fun source(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $relativePath source file")
}
