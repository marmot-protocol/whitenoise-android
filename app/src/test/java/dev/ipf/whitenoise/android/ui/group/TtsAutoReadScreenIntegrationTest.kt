package dev.ipf.whitenoise.android.ui.group

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.TtsAutoReadOverride
import dev.ipf.whitenoise.android.state.TtsAutoReadPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.settings.TextToSpeechScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en")
class TtsAutoReadScreenIntegrationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val accountA = "account-a"
    private val accountB = "account-b"
    private val groupId = "group-a"

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    /** Verifies the uniquely tagged global toggle persists through app state. */
    @Test
    fun textToSpeechScreenTogglePersistsGlobalDefaultThroughAppState() {
        val appState = appState(activeAccountRef = accountA)

        composeRule.setContent {
            WhiteNoiseTheme {
                TextToSpeechScreen(appState = appState, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG).assertIsOff()
        composeRule.onNodeWithTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG).performClick()
        composeRule.runOnIdle {
            assertTrue(appState.ttsAutoReadPreferences.state.value.globalDefaultEnabled)
        }
        composeRule.onNodeWithTag(TTS_AUTO_READ_GLOBAL_DEFAULT_ROW_TAG).assertIsOn()
    }

    @Test
    fun groupDetailsStylePickerUsesActiveAccountForOverrides() {
        val appState = appState(activeAccountRef = accountA)
        composeRule.setContent {
            val prefs by appState.ttsAutoReadPreferences.state.collectAsState()
            val accountRef = appState.activeAccountRef!!
            val selectedOverride =
                appState.ttsAutoReadPreferences.overrideFor(accountRef, groupId)
            WhiteNoiseTheme {
                TtsAutoReadPickerContent(
                    globalDefaultEnabled = prefs.globalDefaultEnabled,
                    selectedOverride = selectedOverride,
                    onSelect = { appState.setConversationAutoReadOverride(groupId, it) },
                )
            }
        }

        composeRule
            .onNodeWithText(context.getString(R.string.tts_auto_read_override_on))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                TtsAutoReadOverride.ON,
                appState.ttsAutoReadPreferences.overrideFor(accountA, groupId),
            )
            assertNull(appState.ttsAutoReadPreferences.overrideFor(accountB, groupId))
        }

        composeRule.runOnIdle { runBlocking { appState.setActiveAccount(accountB) } }
        composeRule.runOnIdle {
            assertFalse(appState.isConversationAutoRead(groupId))
        }

        composeRule
            .onNodeWithText(context.getString(R.string.tts_auto_read_override_off))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                TtsAutoReadOverride.OFF,
                appState.ttsAutoReadPreferences.overrideFor(accountB, groupId),
            )
            assertEquals(
                TtsAutoReadOverride.ON,
                appState.ttsAutoReadPreferences.overrideFor(accountA, groupId),
            )
        }
    }

    private fun appState(activeAccountRef: String): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(InMemoryDraftPersistence),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    accountSummary(accountA, "id-a"),
                    accountSummary(accountB, "id-b"),
                ),
            activeAccountRef = activeAccountRef,
        )

    private fun accountSummary(
        label: String,
        accountIdHex: String,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )

    private object InMemoryDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
