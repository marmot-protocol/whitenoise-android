package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutCommand
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutRouter
import dev.ipf.whitenoise.android.ui.keyboard.LocalHardwareShortcutRouter
import dev.ipf.whitenoise.android.ui.navigation.LocalFloatingControlShown
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Ctrl+N follows the New Message button: shown, relay check finished, and New Message offered (#4005). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ChatsNewMessageFabShortcutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = chatOrganizationAppState(context)
    private val router = HardwareShortcutRouter()
    private var opens = 0

    /** Ctrl+N waits for the relay check, then opens New Message once through the button's own callback. */
    @Test
    fun newMessageWaitsForTheRelayCheck() {
        val relays = CompletableDeferred<AccountRelayListsFfi?>()
        show { relays.await() }

        assertFalse(dispatchNewMessage())
        relays.complete(chatOrganizationRelays(configured = true))
        composeRule.waitForIdle()
        assertTrue(dispatchNewMessage())

        assertEquals(1, opens)
    }

    /** While the button shows Check relays, Ctrl+N is unavailable and opens nothing. */
    @Test
    fun checkRelaysStateWithholdsNewMessage() {
        show { chatOrganizationRelays(configured = false) }

        assertFalse(dispatchNewMessage())
        assertEquals(emptyList<HardwareShortcutCommand>(), composeRule.runOnIdle { router.availableCommands() })
        assertEquals(0, opens)
    }

    /** A leaving button withdraws Ctrl+N with it. */
    @Test
    fun hiddenButtonWithholdsNewMessage() {
        var shown by mutableStateOf(true)
        show(shown = { shown }) { chatOrganizationRelays(configured = true) }

        assertTrue(dispatchNewMessage())
        shown = false
        composeRule.waitForIdle()
        assertFalse(dispatchNewMessage())

        assertEquals(1, opens)
    }

    /** A key that arrives after the account changed in the same frame opens nothing, as a captured tap does. */
    @Test
    fun keyAfterSameFrameAccountReplacementOpensNothing() {
        show { chatOrganizationRelays(configured = true) }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            replaceChatOrganizationAccount(app, "bob")
            router.dispatch(HardwareShortcutCommand.NewMessage)
        }
        composeRule.waitForIdle()

        assertEquals(0, opens)
    }

    /** Runs Ctrl+N through the router on the main thread and reports whether something took it. */
    private fun dispatchNewMessage(): Boolean {
        val handled = composeRule.runOnIdle { router.dispatch(HardwareShortcutCommand.NewMessage) }
        composeRule.waitForIdle()
        return handled
    }

    /** Shows the FAB under this test's router, with [shown] as the floating control's visibility. */
    private fun show(
        shown: () -> Boolean = { true },
        load: suspend (String) -> AccountRelayListsFfi?,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalHardwareShortcutRouter provides router,
                LocalFloatingControlShown provides shown(),
            ) {
                WhiteNoiseTheme { ChatsNewMessageFab(app, { opens++ }, load) }
            }
        }
    }
}
