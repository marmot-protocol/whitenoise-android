package dev.ipf.whitenoise.android.ui.design

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KeyboardSafePopupCoverageTest {
    @Test
    fun keyboardSafePopupUsesZeroSizeBoxWrapper() {
        val body = keyboardSafePopupSource().readText().functionBody("KeyboardSafePopup")

        assertTrue("overlay must stay in a single zero-size Box wrapper", "Box {" in body)
        assertTrue(
            "expanded=false must not emit popup siblings into the caller layout",
            "if (!expanded) return@Box" in body,
        )
    }

    @Test
    fun keyboardSafePopupScrimAndContentAreNonFocusableWithManualDismiss() {
        val body = keyboardSafePopupSource().readText()

        assertTrue("shared popup properties must disable focus stealing", "focusable = false" in body)
        assertTrue(
            "full-window popups must not claim the system Back-gesture edges",
            "excludeFromSystemGesture = false" in body,
        )
        assertTrue(
            "outside taps must be owned by the scrim, not Popup's built-in dismiss",
            "dismissOnClickOutside = false" in body,
        )
        assertTrue(
            "scrim must consume outside taps instead of letting them click through",
            "detectTapGestures { currentOnDismissRequest() }" in body,
        )
        assertEquals(
            "scrim and content popups must share the same non-focusable properties",
            2,
            Regex("""properties\s*=\s*keyboardSafePopupProperties""").findAll(body.functionBody("KeyboardSafePopup")).count(),
        )
    }

    @Test
    fun keyboardSafePopupDismissesBeforeTheImeBackCallback() {
        val body = keyboardSafePopupSource().readText().functionBody("KeyboardSafePopup")

        assertTrue(
            "Back must use overlay priority so the sheet closes before the IME",
            "OnBackInvokedDispatcher.PRIORITY_OVERLAY" in body,
        )
        assertTrue(
            "preview hosts without a platform dispatcher still need Back dismissal",
            "BackHandler(enabled = true) { currentOnDismissRequest() }" in body,
        )
    }

    @Test
    fun keyboardSafePopupAcceptsCallerScrimStylingAndSemantics() {
        val source = keyboardSafePopupSource().readText()
        val body = source.functionBody("KeyboardSafePopup")

        assertTrue("scrim customization must remain optional", "scrimModifier: Modifier = Modifier" in source)
        assertTrue("the full-window dismissal scrim must apply caller decoration", ".then(scrimModifier)" in body)
    }

    @Test
    fun keyboardSafePopupAcceptsCallerPositionProvider() {
        val source = keyboardSafePopupSource().readText()
        val body = source.functionBody("KeyboardSafePopup")

        assertTrue(
            "positioning must stay caller-controlled",
            "popupPositionProvider: PopupPositionProvider" in source &&
                "popupPositionProvider = popupPositionProvider" in body,
        )
        assertTrue(
            "bottom sheets can bottom-anchor via a shared provider",
            "BottomAnchoredPopupPositionProvider" in source,
        )
    }

    @Test
    fun bottomAnchoredPopupPositionProviderPinsContentToWindowBottom() {
        val offset =
            bottomAnchoredPosition(
                windowSize = IntSize(360, 800),
                popupContentSize = IntSize(360, 240),
            )

        assertEquals(0, offset.x)
        assertEquals(560, offset.y)
    }

    @Test
    fun bottomAnchoredPopupPositionProviderUsesImeShrunkVisibleFrame() {
        // windowSize comes from the host visible display frame, which already
        // excludes the IME under adjustResize; do not subtract IME insets again.
        val offset =
            bottomAnchoredPosition(
                windowSize = IntSize(360, 400),
                popupContentSize = IntSize(360, 240),
            )

        assertEquals(0, offset.x)
        assertEquals(160, offset.y)
    }

    @Test
    fun bottomAnchoredPopupPositionProviderKeepsBottomPinnedWhenContentIsOversized() {
        val offset =
            bottomAnchoredPosition(
                windowSize = IntSize(360, 400),
                popupContentSize = IntSize(360, 520),
            )

        assertEquals(0, offset.x)
        assertEquals(-120, offset.y)
    }

    @Test
    fun bottomAnchoredPopupPositionProviderCentersHorizontallyWhenNarrowerThanWindow() {
        val offset =
            bottomAnchoredPosition(
                windowSize = IntSize(360, 800),
                popupContentSize = IntSize(280, 200),
            )

        assertEquals(40, offset.x)
        assertEquals(600, offset.y)
    }

    private fun bottomAnchoredPosition(
        windowSize: IntSize,
        popupContentSize: IntSize,
    ) = BottomAnchoredPopupPositionProvider.calculatePosition(
        anchorBounds = IntRect(0, 0, 0, 0),
        windowSize = windowSize,
        layoutDirection = LayoutDirection.Ltr,
        popupContentSize = popupContentSize,
    )

    @Test
    fun messageActionMenuUsesKeyboardSafePopup() {
        val body = messageActionsSource().readText().functionBody("MessageActionMenu")

        assertTrue("MessageActionMenu should delegate overlay plumbing", "KeyboardSafePopup(" in body)
        assertFalse(
            "MessageActionMenu should not duplicate the scrim popup",
            Regex("""Popup\s*\(\s*properties\s*=\s*PopupProperties\(""")
                .containsMatchIn(body),
        )
        assertTrue(
            "touch-point positioning must remain caller-owned",
            "popupPositionProvider = positionProvider" in body,
        )
        assertTrue(
            "first-frame height estimate for placement must remain in MessageActionMenu",
            "estimatedOneColumnHeightPx" in body &&
                "estimatedTwoColumnHeightPx" in body &&
                "MessageActionMenuPositionProvider(" in body,
        )
        val provider = positionSource().readText()
        assertFalse(
            "measured height must not reposition an already-painted action menu",
            "popupContentSize.height" in provider || "measuredPopupHeightPx" in body,
        )
    }

    @Test
    fun reactionDetailsSheetUsesKeyboardSafePopup() {
        val body = reactionsSource().readText().functionBody("ReactionDetailsSheet")

        assertTrue("ReactionDetailsSheet should use the shared keyboard-safe overlay", "KeyboardSafePopup(" in body)
        assertTrue(
            "ReactionDetailsSheet should bottom-anchor like a simple sheet",
            "BottomAnchoredPopupPositionProvider" in body,
        )
        assertFalse("ReactionDetailsSheet must not use focus-stealing ModalBottomSheet", "ModalBottomSheet(" in body)
    }

    @Test
    fun forwardMessageSheetRemainsModalBottomSheet() {
        val body = messageActionsSource().readText().functionBody("ForwardMessageSheet")

        assertTrue(
            "ForwardMessageSheet intentionally keeps a focusable search field in ModalBottomSheet",
            "ModalBottomSheet(" in body &&
                "FlowSearchField(" in body &&
                ".onFocusChanged { searchFocused = it.isFocused }" in body,
        )
        assertFalse(
            "ForwardMessageSheet must not migrate to KeyboardSafePopup",
            "KeyboardSafePopup(" in body,
        )
    }

    private fun keyboardSafePopupSource(): File = sourceFile("ui/design/KeyboardSafePopup.kt")

    private fun messageActionsSource(): File = sourceFile("ui/conversation/messages/MessageActions.kt")

    private fun positionSource(): File = sourceFile("ui/conversation/messages/MessageActionMenuPositionProvider.kt")

    private fun reactionsSource(): File = sourceFile("ui/conversation/reactions/Reactions.kt")

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing source file $relativePath")
}
