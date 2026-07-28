package dev.ipf.whitenoise.android.ui.share

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShareChatPickerSheetBackCoverageTest {
    @Test
    fun sharePickerBackCommitAnimatesBeforeClearingTheRequest() {
        val body = sharePickerSource().readText().functionBody("ShareChatPickerSheet")

        assertTrue(
            "committed Back must animate the sheet closed via SheetState.hide()",
            "sheetState.hide()" in body,
        )
        assertTrue(
            "programmatic SheetState.hide() must clear the owning share request after the sheet is hidden",
            Regex("""sheetState\.hide\(\)[\s\S]*!sheetState\.isVisible[\s\S]*currentOnDismiss\(\)""")
                .containsMatchIn(body),
        )
        assertFalse(
            "Back must not bypass the sheet and call onDismiss directly",
            Regex("""OnBackInvokedCallback\s*\{\s*onDismiss\(\)\s*\}""").containsMatchIn(body) ||
                Regex("""BackHandler[^{]*\{\s*onDismiss\(\)\s*\}""").containsMatchIn(body),
        )
    }

    @Test
    fun cancelledPredictiveBackKeepsTheSheetWhenTheImeIsHidden() {
        val body = sharePickerSource().readText().functionBody("ShareChatPickerSheet")

        assertTrue(
            "Material3 must retain ownership of predictive progress and cancellation when no IME override is active",
            "ModalBottomSheetProperties(shouldDismissOnBackPress = !useOverlayBack)" in body,
        )
        assertTrue(
            "the overlay-priority override is limited to search focus and an already-started dismissal",
            "val useOverlayBack = pickerState.searchFocused || dismissing" in body &&
                "ShareChatPickerBackHandler(enabled = useOverlayBack" in body,
        )
    }

    @Test
    fun repeatedBackWhileSheetIsSettlingDoesNotStartAnotherDismissal() {
        val body = sharePickerSource().readText().functionBody("ShareChatPickerSheet")

        assertTrue(
            "a second Back during the hide animation must be consumed without launching another dismissal",
            "if (!dismissing)" in body && "dismissing = true" in body,
        )
    }

    @Test
    fun sharePickerUsesOverlayPriorityBackWhenSearchCanShowIme() {
        val source = sharePickerSource().readText()
        val backHandlerBody = source.functionBody("ShareChatPickerBackHandler")

        assertTrue(
            "search focus can raise the IME, so Back must beat the IME callback",
            "OnBackInvokedDispatcher.PRIORITY_OVERLAY" in backHandlerBody,
        )
        assertTrue(
            "preview hosts without a platform dispatcher still need Back dismissal",
            "BackHandler(enabled = enabled)" in backHandlerBody,
        )
        assertTrue(
            "dismissal must clear search focus and hide the IME in the same action",
            "runShareChatPickerDismissal(" in source.functionBody("ShareChatPickerSheet") &&
                "clearFocus" in source.functionBody("ShareChatPickerSheet") &&
                "hide()" in source.functionBody("ShareChatPickerSheet"),
        )
    }

    private fun sharePickerSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/share/ShareChatPickerSheet.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/share/ShareChatPickerSheet.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ShareChatPickerSheet.kt")
}
