package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Device/emulator coverage for the delayed profile/prewarm first-frame contract. */
@SdkSuppress(minSdkVersion = 36)
class NewMessagePreparationAndroidTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun recipientRemainsActionableWhileProfilePrewarmAndLookupAreDelayed() {
        val targetHex = "b".repeat(64)
        val target = RecipientSearch.Candidate(targetHex, "npub fallback", "npub1target")
        val profileStarted = CompletableDeferred<Unit>()
        val prewarmStarted = CompletableDeferred<Unit>()
        val lookupStarted = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        val releasePrewarm = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        var taps = 0

        composeRule.setContent {
            LaunchedEffect(Unit) {
                launch {
                    profileStarted.complete(Unit)
                    releaseProfile.await()
                }
                NewMessageRecipientPreparationCoordinator()
                    .prepare(
                        scope = this,
                        key = NewMessageRecipientPreparationKey("account", 1, target.npub, target.npub, 0),
                        prewarm = {
                            prewarmStarted.complete(Unit)
                            releasePrewarm.await()
                        },
                        lookup = {
                            lookupStarted.complete(Unit)
                            releaseLookup.await()
                            NewMessageDirectChatResolution(item = null, createRequired = true)
                        },
                    ).awaitCompletion()
            }
            WhiteNoiseTheme {
                NewMessageContent(
                    queryState = TextFieldState(target.npub),
                    people = listOf(NewMessagePerson(target, target.npub, null)),
                    search = RecipientUserSearchState(),
                    identifierQuery = true,
                    resolvingIdentifier = false,
                    connectQrEnabled = false,
                    creatingHex = null,
                    error = null,
                    actions = NewMessageActions({}, {}, {}, {}, {}, {}, {}, { taps++ }, {}, {}),
                    isValidNpub = { true },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            profileStarted.isCompleted && prewarmStarted.isCompleted && lookupStarted.isCompleted
        }
        assertFalse(releaseProfile.isCompleted)
        assertFalse(releasePrewarm.isCompleted)
        assertFalse(releaseLookup.isCompleted)
        composeRule
            .onNodeWithTag("creation.person.$targetHex")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, taps) }

        releaseProfile.complete(Unit)
        releasePrewarm.complete(Unit)
        releaseLookup.complete(Unit)
    }
}
