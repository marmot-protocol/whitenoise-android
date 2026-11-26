package dev.ipf.whitenoise.android.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.NotificationBootstrapTestFixture
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class InboundLinkCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun unknownAndMalformedLinksAreConsumedAndLeaveTheAppUsable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val account =
            AccountSummaryFfi(
                label = "fixture",
                accountIdHex = "a".repeat(64),
                localSigning = true,
                externalSigning = false,
                signedOut = false,
                running = true,
                unlinked = false,
            )
        val row = chatRow("b".repeat(64)).copy(title = "Existing chat", groupName = "Existing chat")
        val fixture =
            NotificationBootstrapTestFixture(
                context,
                accounts = listOf(account),
                chatListRows = listOf(row),
                emitStartupNotification = false,
            )
        runBlocking { fixture.bootstrap() }
        val app = fixture.appState
        app.markDefaultNotificationsEnableAttempted()
        val shell = MainShellStateHolder(app, SavedStateHandle())
        val pending = mutableStateOf<String?>("marmot://foo")
        val handled = mutableListOf<String>()
        val visible = mutableStateOf(true)
        try {
            composeRule.setContent {
                WhiteNoiseTheme {
                    if (visible.value) {
                        WhiteNoiseApp(
                            app,
                            shell,
                            0,
                            0,
                            inboundProfilePayload = pending.value,
                            onProfilePayloadHandled = { link ->
                                handled += link
                                if (pending.value == link) pending.value = null
                            },
                        )
                    }
                }
            }
            composeRule.runOnIdle { assertEquals(listOf("marmot://foo"), handled) }
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithText(context.getString(R.string.toast_unsupported_link)).assertExists()
            composeRule.onNodeWithText("Existing chat").assertExists()

            composeRule.runOnIdle { pending.value = "marmot://profile/not-an-npub" }
            composeRule.runOnIdle { assertEquals(listOf("marmot://foo", "marmot://profile/not-an-npub"), handled) }
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
        } finally {
            composeRule.runOnIdle {
                visible.value = false
                shell.release()
            }
            composeRule.waitForIdle()
            fixture.close()
        }
    }
}
