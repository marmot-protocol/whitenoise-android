package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.kotlinBlockFrom
import dev.ipf.whitenoise.android.ui.profile.ProfileImageTarget
import dev.ipf.whitenoise.android.ui.profile.profileImageFailureOperation
import org.junit.Assert.assertEquals
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
                conversation = ConversationNoticeDestination("account-a", "A1B2"),
            )

        assertTrue(notice.isForConversation("account-a", "a1b2"))
        assertFalse(notice.isForConversation("account-a", "group-b"))
        assertFalse(notice.isForConversation("account-b", "a1b2"))
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

    @Test
    fun migratedLegacyCallersProduceBoundedReport() {
        val secret = "nsec1" + "q".repeat(60)
        val failure = java.io.IOException("failed with $secret at https://user:pass@example.test")
        val operationsBySource =
            mapOf(
                "ui/chats/newchat/NewGroupSetupScreen.kt" to listOf("NEW_GROUP_IMAGE_PREPARE"),
                "ui/group/GroupEditScreen.kt" to
                    listOf("GROUP_IMAGE_PREPARE", "GROUP_AVATAR_UPDATE", "GROUP_IMAGE_UPLOAD"),
                "ui/profile/ProfileEditScreen.kt" to listOf("PROFILE_EDIT_LOAD"),
                "ui/conversation/media/MediaViewer.kt" to listOf("MEDIA_VIEWER_IMAGE_SHARE"),
                "ui/medialibrary/MediaLibrary.kt" to
                    listOf(
                        "MEDIA_LIBRARY_VOICE_LOAD",
                        "MEDIA_LIBRARY_FILE_OPEN",
                        "MEDIA_LIBRARY_FILE_SHARE",
                        "MEDIA_LIBRARY_URL_OPEN",
                    ),
            )

        operationsBySource.forEach { (path, operationCodes) ->
            val source = mainSource(path).readText()
            operationCodes.forEach { operationCode ->
                assertTrue("Missing migrated operation $operationCode in $path", operationCode in source)
                assertCausePreservingFailureHandler(source, path, operationCode)
                val presentation =
                    privacySafeErrorPresentation(
                        operationCode = operationCode,
                        throwable = failure,
                        appVersion = "test",
                        androidVersion = "test",
                        occurredAtUtc = "2026-08-15T12:00:00Z",
                    )
                assertTrue(presentation.report.contains("operation=$operationCode"))
                assertTrue(presentation.report.isNotBlank())
                assertTrue(presentation.report.length <= 600)
                assertFalse(presentation.report.contains(secret))
                assertFalse(presentation.report.contains("user:pass"))
            }
        }

        val profileOperations =
            listOf(
                profileImageFailureOperation(ProfileImageTarget.Picture, prepared = false),
                profileImageFailureOperation(ProfileImageTarget.Picture, prepared = true),
                profileImageFailureOperation(ProfileImageTarget.Banner, prepared = false),
                profileImageFailureOperation(ProfileImageTarget.Banner, prepared = true),
            )
        assertEquals(
            listOf(
                "PROFILE_IMAGE_PREPARE",
                "PROFILE_IMAGE_UPLOAD",
                "PROFILE_BANNER_PREPARE",
                "PROFILE_BANNER_UPLOAD",
            ),
            profileOperations,
        )
        val profileSource = mainSource("ui/profile/ProfileEditScreen.kt").readText()
        val profileFailureCall =
            failurePresentationCalls(profileSource).single { call ->
                "operationCode = profileImageFailureOperation(target, prepared)" in call
            }
        assertTrue("throwable = error" in profileFailureCall)
        profileOperations.forEach { operationCode ->
            val presentation =
                privacySafeErrorPresentation(
                    operationCode = operationCode,
                    throwable = failure,
                    appVersion = "test",
                    androidVersion = "test",
                    occurredAtUtc = "2026-08-15T12:00:00Z",
                )
            assertTrue(presentation.report.contains("operation=$operationCode"))
            assertTrue(presentation.report.length <= 600)
            assertFalse(presentation.report.contains(secret))
        }
    }

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt source file")

    private fun mainSource(relativePath: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).firstOrNull(File::exists)
            ?: error("Missing source file: $relativePath")

    private fun assertCausePreservingFailureHandler(
        source: String,
        path: String,
        operationCode: String,
    ) {
        val call =
            failurePresentationCalls(source).firstOrNull { candidate ->
                operationCode in candidate && Regex("""(?:throwable\s*=\s*)?\berror\b""").containsMatchIn(candidate)
            }
        assertTrue(
            "Handler for $operationCode in $path must present the caught error",
            call != null,
        )
    }

    private fun failurePresentationCalls(source: String): List<String> =
        Regex("""\b(?:presentFailure|presentMediaLaunchFailure)\s*\(""")
            .findAll(source)
            .mapNotNull { match ->
                val openParen = source.indexOf('(', match.range.first)
                runCatching {
                    source.substring(match.range.first, openParen) +
                        source.kotlinBlockFrom(openParen, "failure presentation", '(', ')')
                }.getOrNull()
            }.toList()
}
