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
        val focusedSource = focusedMessageActionsSource().readText()
        val focusedBody = focusedSource.functionBody("FocusedMessageActions")

        assertTrue("Native commands must reach the focused presentation", "FocusedMessageActions(" in body)
        listOf(
            "sourceBounds = anchorBoundsInWindow",
            "touchY = anchorWindowYPx",
            "actions = actions",
            "previewReady = previewReady",
            "onDismiss = onDismissRequest",
        ).forEach { binding -> assertTrue("Missing native binding: $binding", binding in body) }
        assertTrue("Focused presentation must use the keyboard-safe owner", "KeyboardSafePopup(" in focusedBody)
        assertTrue("Dismissal must reach the native owner", "onDismissRequest = onDismiss" in focusedBody)
        listOf(body, focusedBody).forEach { layer ->
            assertFalse(
                "Message presentation should not duplicate the scrim popup",
                Regex("""Popup\s*\(\s*properties\s*=\s*PopupProperties\(""").containsMatchIn(layer),
            )
        }
        assertTrue(
            "Frozen message bounds and the original touch point must own positioning",
            "remember(sourceBounds, touchY)" in focusedBody &&
                "FocusedMessageActionsPositionProvider(sourceBounds, touchY)" in focusedBody &&
                "popupPositionProvider = position" in focusedBody,
        )
        val provider = focusedSource.functionBody("calculatePosition")
        assertTrue(
            "The measured preview/action stack must center on its anchor and clamp within the usable window",
            "sourceBounds?.center?.y ?: touchY?.roundToInt()" in provider &&
                "popupContentSize.height / 2" in provider &&
                "(windowSize.width - popupContentSize.width) / 2" in provider &&
                "desiredY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))" in provider,
        )
        assertTrue(
            "Tall action content must scroll within the keyboard-safe frame",
            ".heightIn(max = maxHeight)" in focusedBody && ".verticalScroll(rememberScrollState())" in focusedBody,
        )
        assertTrue(
            "The stack must remain transparent until both its layout and the real preview are ready",
            ".onSizeChanged { measured = it.width > 0 && it.height > 0 }" in focusedBody &&
                "alpha = if (measured && previewReady) 1f else 0f" in focusedBody,
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
    fun forwardMessagePickerUsesItsOwnFullScreenDialog() {
        val body = forwardPickerSource().readText().functionBody("ForwardMessagePickerFullScreen")

        assertTrue(
            "ForwardMessagePicker must own a full-screen modal window",
            "Dialog(" in body && "usePlatformDefaultWidth = false" in body,
        )
        assertFalse(
            "ForwardMessagePicker must not use a popup overlay",
            "KeyboardSafePopup(" in body,
        )
    }

    private fun keyboardSafePopupSource(): File = sourceFile("ui/design/KeyboardSafePopup.kt")

    private fun messageActionsSource(): File = sourceFile("ui/conversation/messages/MessageActions.kt")

    private fun forwardPickerSource(): File = sourceFile("ui/conversation/messages/ForwardMessagePicker.kt")

    private fun focusedMessageActionsSource(): File = sourceFile("ui/conversation/messages/FocusedMessageActions.kt")

    private fun reactionsSource(): File = sourceFile("ui/conversation/reactions/Reactions.kt")

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull { it.exists() }
            ?: error("Missing source file $relativePath")
}
