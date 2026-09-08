package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.text.input.TextFieldValue
import dev.ipf.whitenoise.android.audio.ConversationDictationMode
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.ConversationDictationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationDictationControlOwnershipTest {
    /** A retained outgoing controller cannot hide root controls after the selected route changes. */
    @Test
    fun departureAndReturnUseTheCurrentRouteWithoutRecognitionCallbacks() {
        assertEquals(ConversationDictationControlOwner.Composer, owner(origin))
        assertEquals(
            ConversationDictationControlOwner.Persistent,
            owner(origin.copy(selectedChatId = null, selectedGroupIdHex = null)),
        )
        assertEquals(ConversationDictationControlOwner.Composer, owner(origin))
        assertSame(target, listening.target)
    }

    /** Matching group IDs cannot transfer ownership across account or rendered-controller boundaries. */
    @Test
    fun staleAccountAndOutgoingControllersNeverOwnTheComposer() {
        listOf(
            origin.copy(navigationAccountStable = false),
            origin.copy(renderedAccountRef = "other-account"),
            origin.copy(renderedAccountRef = null),
            origin.copy(renderedChatId = "outgoing-chat"),
            origin.copy(renderedChatId = null),
            origin.copy(selectedGroupIdHex = "other-group"),
        ).forEach { route ->
            assertEquals(ConversationDictationControlOwner.Persistent, owner(route))
        }
    }

    /** Hidden composers transfer ownership; the root lock boundary suppresses both surfaces. */
    @Test
    fun coveringSurfacesAndLockHaveExplicitOwners() {
        assertEquals(ConversationDictationControlOwner.Persistent, owner(origin.copy(composerVisible = false)))
        assertEquals(
            ConversationDictationControlOwner.Hidden,
            conversationDictationControlOwner(listening, origin, appLockScreenVisible = true),
        )
        assertEquals(
            ConversationDictationControlOwner.Hidden,
            conversationDictationControlOwner(ConversationDictationState.Idle, origin, appLockScreenVisible = false),
        )
    }

    /** Permission and disclosure surfaces must not grow an unrelated persistent control bar. */
    @Test
    fun preflightOnlyBelongsToTheVisibleOrigin() {
        listOf(
            ConversationDictationState.DisclosureRequired(1L, target),
            ConversationDictationState.PermissionRequired(1L, target),
        ).forEach { state ->
            assertEquals(
                ConversationDictationControlOwner.Composer,
                conversationDictationControlOwner(state, origin, appLockScreenVisible = false),
            )
            assertEquals(
                ConversationDictationControlOwner.Hidden,
                conversationDictationControlOwner(
                    state,
                    origin.copy(composerVisible = false),
                    appLockScreenVisible = false,
                ),
            )
        }
    }

    @Suppress("MaxLineLength")
    private fun owner(route: ConversationDictationComposerRoute) = conversationDictationControlOwner(listening, route, appLockScreenVisible = false)

    private companion object {
        val target =
            ConversationDictationTarget(
                accountRef = "account",
                groupIdHex = "group",
                capturedDraft = TextFieldValue("Keep"),
                capturedDraftRevision = 1L,
                mode = ConversationDictationMode.InApp,
            )
        val listening = ConversationDictationState.Listening(1L, target, 10L)
        val origin =
            ConversationDictationComposerRoute(
                selectedChatId = "chat",
                selectedGroupIdHex = "group",
                renderedChatId = "chat",
                renderedAccountRef = "account",
                navigationAccountStable = true,
                composerVisible = true,
            )
    }
}
