package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ErrorPresentationTest {
    @Test
    fun identicalTransientCopyStillProducesDistinctNoticeKeys() {
        val title = AppText.Plain("Saved")

        assertNotEquals(
            TransientNotice(id = 1L, title = title),
            TransientNotice(id = 2L, title = title),
        )
    }

    @Test
    fun conversationNoticeMatchesOnlyItsOriginatingAccountAndGroup() {
        val notice =
            TransientNotice(
                id = 1L,
                title = AppText.Plain("Admin removed"),
                conversation = ConversationNoticeDestination("account-a", "group-a"),
            )

        assertTrue(notice.isForConversation("account-a", "group-a"))
        assertFalse(notice.isForConversation("account-a", "group-b"))
        assertFalse(notice.isForConversation("account-b", "group-a"))
    }

    @Test
    fun globalNoticeNeverMatchesAConversation() {
        val notice = TransientNotice(id = 1L, title = AppText.Plain("Saved"))

        assertFalse(notice.isForConversation("account-a", "group-a"))
    }

    @Test
    fun profileAdminPromotionScopesItsConfirmationToTheMutatedGroup() {
        val body = appStateSource().readText().functionBody("promoteProfileInGroup")

        assertTrue(
            "profile-sheet admin success must use the originating account and group",
            "presentConversationTransient(" in body &&
                "accountRef = account" in body &&
                "groupIdHex = groupId" in body,
        )
        assertFalse(
            "profile-sheet admin success must not escape through the app-global confirmation host",
            "presentTransient(R.string.toast_admin_added)" in body,
        )
    }

    @Test
    fun presentationSeparatesLocalizedCopyFromDiagnostics() {
        val secret = "nsec1" + "q".repeat(60)
        val presentation =
            privacySafeErrorPresentation(
                operationCode = "group admin update",
                throwable = IllegalStateException("failed with $secret"),
                message = AppText.Plain("Couldn\'t update admin. Try again."),
                appVersion = "test",
                androidVersion = "test",
                occurredAtUtc = "2026-08-10T12:00:00Z",
            )

        assertTrue(presentation.message is AppText.Plain)
        assertTrue(presentation.report.contains("operation=GROUP_ADMIN_UPDATE"))
        assertFalse(presentation.report.contains(secret))
        assertFalse(presentation.report.contains("failed with"))
        assertFalse((presentation.message as AppText.Plain).value.contains("IllegalStateException"))
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt source file")
}
