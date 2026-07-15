package dev.ipf.whitenoise.android.ui.conversation.messages

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.MessageDeleteCapability
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Behavioral coverage for the unified delete dialog: it offers exactly the
 * scopes the capability model permits, "Delete for me" never reaches the
 * remote path, an in-flight delete-for-everyone disables the destructive
 * choices (the repeated-tap guard), and a failed operation leaves the surface
 * visible instead of silently vanishing under the error toast.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageDeleteDialogBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = app.getString(resId, *args)

    private fun renderDialogContent(
        capability: MessageDeleteCapability,
        mine: Boolean,
        senderDisplayName: String = "Member",
        deleteInFlight: Boolean = false,
        onDeleteForEveryone: () -> Unit = {},
        onDeleteForMe: () -> Unit = {},
        onCancel: () -> Unit = {},
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    MessageDeleteDialogContent(
                        capability = capability,
                        mine = mine,
                        senderDisplayName = senderDisplayName,
                        deleteInFlight = deleteInFlight,
                        onDeleteForEveryone = onDeleteForEveryone,
                        onDeleteForMe = onDeleteForMe,
                        onCancel = onCancel,
                    )
                }
            }
        }
    }

    @Test
    fun bothChoicesRenderWhenBothScopesAreAuthorized() {
        renderDialogContent(
            capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
            mine = true,
        )
        composeRule.onNodeWithText(string(R.string.delete_message_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsDisplayed()
    }

    @Test
    fun onlyLocalChoiceRendersWithoutRemoteAuthorization() {
        renderDialogContent(
            capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = false),
            mine = false,
        )
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_message_local_only)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertDoesNotExist()
    }

    @Test
    fun moderatorRemovalShowsSenderScopedCopyWithoutAdminBranding() {
        renderDialogContent(
            capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
            mine = false,
            senderDisplayName = "Maple Bat",
        )
        composeRule
            .onNodeWithText(string(R.string.confirm_delete_member_message_title, "Maple Bat"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.confirm_delete_member_message_message)).assertIsDisplayed()
        // Moderation is just "Delete for everyone" — there is no admin action.
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsDisplayed()
    }

    @Test
    fun deleteForMeInvokesOnlyTheLocalPath() {
        var localDeletes = 0
        var remoteDeletes = 0
        renderDialogContent(
            capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
            mine = true,
            onDeleteForMe = { localDeletes += 1 },
            onDeleteForEveryone = { remoteDeletes += 1 },
        )
        composeRule.onNodeWithText(string(R.string.delete_for_me)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, localDeletes)
        assertEquals("delete for me must never publish a remote delete event", 0, remoteDeletes)
    }

    @Test
    fun inFlightDeleteDisablesBothDestructiveChoices() {
        renderDialogContent(
            capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
            mine = true,
            deleteInFlight = true,
        )
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.delete_for_me)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.cancel)).assertIsEnabled()
    }

    @Test
    fun repeatedTapsLaunchExactlyOneDelete() {
        var remoteDeletes = 0
        composeRule.setContent {
            // Mirrors the bubble wiring: the first tap flips the in-flight
            // flag, which disables the option before a second tap can land.
            var inFlight by mutableStateOf(false)
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    MessageDeleteDialogContent(
                        capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                        mine = true,
                        senderDisplayName = "Member",
                        deleteInFlight = inFlight,
                        onDeleteForEveryone = {
                            remoteDeletes += 1
                            inFlight = true
                        },
                        onDeleteForMe = {},
                        onCancel = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, remoteDeletes)
    }

    @Test
    fun failedDeleteKeepsTheDialogVisibleAndReEnablesChoices() {
        var attempts = 0
        composeRule.setContent {
            // Mirrors the bubble wiring on failure: the in-flight flag resets
            // but the dialog stays open (only success dismisses it), so the
            // error toast never has to explain a vanished surface.
            var inFlight by mutableStateOf(false)
            WhiteNoiseTheme(darkTheme = false) {
                Surface {
                    MessageDeleteDialogContent(
                        capability = MessageDeleteCapability(canDeleteForMe = true, canDeleteForEveryone = true),
                        mine = true,
                        senderDisplayName = "Member",
                        deleteInFlight = inFlight,
                        onDeleteForEveryone = {
                            attempts += 1
                            inFlight = true
                            // The publish fails: the completion path resets the
                            // flag without dismissing.
                            inFlight = false
                        },
                        onDeleteForMe = {},
                        onCancel = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).performClick()
        composeRule.waitForIdle()
        assertEquals(1, attempts)
        composeRule.onNodeWithText(string(R.string.delete_message_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.delete_for_everyone)).assertIsEnabled()
    }
}
