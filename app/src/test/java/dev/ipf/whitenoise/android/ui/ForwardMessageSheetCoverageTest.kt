package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.conversation.messages.confirmForwardTargets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForwardMessageSheetCoverageTest {
    @Test
    fun acceptedForwardStartsInAppScopeAndDismissesPickerImmediately() {
        var dismissed = false

        val accepted =
            confirmForwardTargets(
                targets = listOf("one", "two"),
                start = { it == listOf("one", "two") },
                dismiss = { dismissed = true },
            )

        assertTrue(accepted)
        assertTrue(dismissed)
    }

    @Test
    fun rejectedForwardKeepsPickerOpen() {
        var dismissed = false

        val accepted =
            confirmForwardTargets(
                targets = listOf("one"),
                start = { false },
                dismiss = { dismissed = true },
            )

        assertFalse(accepted)
        assertFalse(dismissed)
    }

    @Test
    fun forwardingUsesAFullScreenPickerWithAStickyAction() {
        val source = forwardPickerSource().readText()
        val window = source.functionBody("ForwardMessagePickerFullScreen")
        val content = source.functionBody("ForwardMessagePickerContent")

        assertTrue("forwarding must own a full-screen modal window", "Dialog(" in window)
        assertTrue(
            "the picker must use full-height scaffold layout",
            "Scaffold(" in content && ".fillMaxSize()" in content,
        )
        assertTrue("the Forward action must remain visible", "StickyFormActionBar" in content)
        assertFalse("the picker must not compete with a draggable sheet", "ModalBottomSheet(" in source)
    }

    @Test
    fun pickerShowsCountsInsteadOfConcatenatedMessageBodies() {
        val source = forwardPickerSource().readText()
        val summary = source.functionBody("ForwardSelectionSummary")
        val wrapper = messageActionsSource().readText().functionBody("ForwardMessageSheet")

        assertTrue("R.plurals.forward_message_count" in summary)
        assertTrue("R.plurals.forward_attachment_count" in summary)
        assertFalse("the message body must not be forwarded into the recipient picker", "body =" in wrapper)
        assertFalse("the old raw preview must stay removed", "forwardPreviewText" in source)
    }

    @Test
    fun pickerObservesLoadingErrorsAndSelectionAccessibility() {
        val source = forwardPickerSource().readText()
        val content = source.functionBody("ForwardMessagePickerContent")
        val targetList = source.functionBody("ForwardTargetList")
        val targetRow = source.functionBody("ForwardTargetRow")

        assertTrue("rememberShareChatPickerDataSource(" in content)
        assertTrue("dataSource.isLoading" in content)
        assertTrue("dataSource.error" in content)
        assertTrue("dataSource.memberSnapshotsRevision" in content)
        assertTrue("remember(dataSource.targets, originGroupIdHex)" in content)
        assertTrue("ErrorContent(" in targetList && "InlineErrorBanner(" in targetList)
        assertTrue("onRetry = retryLoad" in targetList)
        assertTrue("Modifier.semantics { this.selected = selected }" in targetRow)
    }

    /** Account and chat identifiers must never enter the plain saved-state Bundle. */
    @Test
    fun forwardPickerStateStaysOutOfTheSavedStateBundle() {
        assertFalse("rememberSaveable" in forwardPickerSource().readText())
        val sheet = messageActionsSource().readText().functionBody("ForwardMessageSheet")
        assertFalse("rememberSaveable" in sheet)
    }

    /** Member previews resolve through the owner-scoped nickname cache. */
    @Test
    fun groupMemberPreviewsUsePrivateContactNicknames() {
        val body = forwardPickerSource().readText().functionBody("ForwardTargetRow")

        assertTrue("appState.contactDisplayNameCached(ownerAccountRef, memberIdHex)" in body)
        assertFalse("appState.chatMemberTitleCached(" in body)
    }

    private fun forwardPickerSource(): File = sourceFile("ui/conversation/messages/ForwardMessagePicker.kt")

    private fun messageActionsSource(): File = sourceFile("ui/conversation/messages/MessageActions.kt")

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists) ?: error("Missing $relativePath")
}
