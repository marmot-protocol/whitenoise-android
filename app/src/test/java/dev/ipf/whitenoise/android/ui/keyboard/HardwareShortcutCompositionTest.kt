package dev.ipf.whitenoise.android.ui.keyboard

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalWindowLayout
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import dev.ipf.whitenoise.android.ui.conversation.ChatsReturnShortcut
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingBottomSheet
import dev.ipf.whitenoise.android.ui.navigation.TwoPaneChatFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The messaging shortcuts through a real window: focused fields, dialogs, blockers and both layouts (#4005). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w1280dp-h800dp-mdpi")
class HardwareShortcutCompositionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val router = HardwareShortcutRouter()
    private val runs = HardwareShortcutCommand.entries.associateWith { mutableIntStateOf(0) }

    /** Installs [router] in front of the activity window's callback, as `MainActivity.onCreate` does. */
    @Before
    fun routeWindowKeysThroughTheRouter() {
        composeRule.runOnUiThread { installHardwareShortcuts(composeRule.activity.window, router) }
    }

    /**
     * Typing, Enter and Shift+Enter reach a focused composer-style field, while Ctrl+F and Ctrl+N run once and never
     * reach it. Editing shortcuts such as Ctrl+A are left out here because Robolectric's key map types their letter,
     * and the router test shows they pass through untouched.
     */
    @Test
    fun composerKeysReachTheFocusedFieldWhileShortcutsRunOnce() {
        var text by mutableStateOf(TextFieldValue(""))
        var sends = 0
        setRoutedContent {
            OwnAllCommands()
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier =
                    Modifier.testTag(COMPOSER).onPreviewKeyEvent { event ->
                        // The composer's own rule: Enter sends, Shift+Enter falls through to a line break.
                        val enter = event.type == KeyEventType.KeyDown && event.key == Key.Enter
                        if (enter && !event.isShiftPressed) sends++
                        enter && !event.isShiftPressed
                    },
            )
        }
        composeRule.onNodeWithTag(COMPOSER).requestFocus()

        press(KeyEvent.KEYCODE_H)
        press(KeyEvent.KEYCODE_I)
        press(KeyEvent.KEYCODE_ENTER, SHIFT)
        press(KeyEvent.KEYCODE_ENTER)
        assertEquals("hi\n", text.text)
        assertEquals(1, sends)

        assertTrue(press(KeyEvent.KEYCODE_F, CTRL, repeats = 3))
        assertTrue(press(KeyEvent.KEYCODE_N, CTRL))
        assertEquals("hi\n", text.text)
        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch).intValue)
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage).intValue)
        assertEquals(1, sends)
    }

    /**
     * A keyboard-preserving sheet leaves keys with the activity window, so the window's own dispatch must find the
     * shortcuts held while it is open and released once it closes.
     */
    @Test
    fun keyboardPreservingSheetHoldsShortcutsSentThroughTheWindow() {
        var sheetOpen by mutableStateOf(true)
        setRoutedContent {
            OwnAllCommands()
            if (sheetOpen) {
                KeyboardPreservingBottomSheet(paneTitle = "Sheet", onDismissRequest = { sheetOpen = false }) {
                    Box(Modifier.testTag(SHEET).height(120.dp).fillMaxWidth())
                }
            }
        }
        composeRule.onNodeWithTag(SHEET).assertExists()

        assertFalse(press(KeyEvent.KEYCODE_N, CTRL))
        assertFalse(press(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(emptyList<HardwareShortcutCommand>(), router.availableCommands())
        sheetOpen = false
        composeRule.waitForIdle()
        assertTrue(press(KeyEvent.KEYCODE_N, CTRL))

        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage).intValue)
        assertEquals(0, runs.getValue(HardwareShortcutCommand.ConversationSearch).intValue)
    }

    /** An in-window surface that owns input holds every shortcut until it leaves the composition. */
    @Test
    fun inWindowBlockerHoldsEveryShortcut() {
        var blocked by mutableStateOf(true)
        setRoutedContent {
            OwnAllCommands()
            if (blocked) HardwareShortcutBlocker()
        }

        assertFalse(press(KeyEvent.KEYCODE_N, CTRL))
        assertFalse(press(KeyEvent.KEYCODE_F, CTRL))
        blocked = false
        composeRule.waitForIdle()
        assertTrue(press(KeyEvent.KEYCODE_N, CTRL))

        assertEquals(0, runs.getValue(HardwareShortcutCommand.GlobalSearch).intValue)
        assertEquals(1, runs.getValue(HardwareShortcutCommand.NewMessage).intValue)
    }

    /**
     * Side by side, Ctrl+Shift+F needs the conversation pane to hold focus or, without focus, to be pressed last.
     * Before either pane is focused or pressed, neither owns it.
     */
    @Test
    fun splitPaneFocusDecidesInChatSearch() {
        var clearFocus: () -> Unit = {}
        setRoutedContent {
            clearFocus = LocalFocusManager.current.let { manager -> { manager.clearFocus(force = true) } }
            CompositionLocalProvider(LocalWindowLayout provides WindowLayout.fromSize(DpSize(1280.dp, 800.dp))) {
                TwoPaneChatFrame(
                    twoPane = true,
                    listPane = { Pane(LIST_PANE, LIST_FIELD, HardwareShortcutCommand.GlobalSearch) },
                ) { Pane(CONVERSATION_PANE, CONVERSATION_FIELD, HardwareShortcutCommand.ConversationSearch) }
            }
        }

        val inChatSearches = runs.getValue(HardwareShortcutCommand.ConversationSearch)
        assertFalse(press(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(0, inChatSearches.intValue)
        composeRule.onNodeWithTag(LIST_FIELD).requestFocus()
        press(KeyEvent.KEYCODE_F, CTRL or SHIFT)
        assertEquals(0, inChatSearches.intValue)
        assertTrue(press(KeyEvent.KEYCODE_F, CTRL))
        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch).intValue)
        composeRule.onNodeWithTag(CONVERSATION_FIELD).requestFocus()
        assertTrue(press(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(1, inChatSearches.intValue)

        composeRule.runOnUiThread { clearFocus() }
        composeRule.onNodeWithTag(LIST_PANE).performTouchInput { click() }
        assertFalse(press(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(1, inChatSearches.intValue)
        composeRule.onNodeWithTag(CONVERSATION_PANE).performTouchInput { click() }
        assertTrue(press(KeyEvent.KEYCODE_F, CTRL or SHIFT))
        assertEquals(2, inChatSearches.intValue)
    }

    /** A compact conversation leaves through its own exit, and Chats then runs the carried Ctrl+F once. */
    @Test
    fun compactConversationCarriesTheCommandToChats() {
        var inConversation by mutableStateOf(true)
        var exits = 0
        setRoutedContent {
            TwoPaneChatFrame(twoPane = false, listPane = {}) {
                if (inConversation) {
                    ChatsReturnShortcut(available = true) {
                        exits++
                        inConversation = false
                    }
                } else {
                    Own(HardwareShortcutCommand.GlobalSearch)
                }
            }
        }

        assertTrue(press(KeyEvent.KEYCODE_F, CTRL))
        assertEquals(1, exits)
        assertEquals(1, runs.getValue(HardwareShortcutCommand.GlobalSearch).intValue)
        assertTrue(press(KeyEvent.KEYCODE_F, CTRL))
        assertEquals(1, exits)
        assertEquals(2, runs.getValue(HardwareShortcutCommand.GlobalSearch).intValue)
    }

    /** Sets [content] under this test's router. */
    private fun setRoutedContent(content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalHardwareShortcutRouter provides router) { Column { content() } }
        }
    }

    /** Owns every command with a counter in [runs]. */
    @Composable
    private fun OwnAllCommands() {
        HardwareShortcutCommand.entries.forEach { Own(it) }
    }

    /** Owns [command] with its counter in [runs]. */
    @Composable
    private fun Own(command: HardwareShortcutCommand) {
        val count = runs.getValue(command)
        KeyboardShortcut(command, available = true) { count.intValue += 1 }
    }

    /** A full pane tagged [paneTag] with a focusable field tagged [fieldTag], owning [command]. */
    @Composable
    private fun Pane(
        paneTag: String,
        fieldTag: String,
        command: HardwareShortcutCommand,
    ) {
        Own(command)
        var text by remember { mutableStateOf(TextFieldValue("")) }
        Column(Modifier.fillMaxSize().testTag(paneTag)) {
            BasicTextField(text, { text = it }, Modifier.fillMaxWidth().height(48.dp).testTag(fieldTag))
        }
    }

    /**
     * Presses [keyCode] with [meta] through the activity window, holding it for [repeats] auto-repeats, and reports
     * whether the first key-down was consumed.
     */
    private fun press(
        keyCode: Int,
        meta: Int = 0,
        repeats: Int = 0,
    ): Boolean {
        var handled = false
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            handled = decor.dispatchKeyEvent(keyDown(keyCode, meta))
            repeat(repeats) { decor.dispatchKeyEvent(keyDown(keyCode, meta, it + 1)) }
            decor.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0, meta))
        }
        composeRule.waitForIdle()
        return handled
    }

    private companion object {
        const val COMPOSER = "composer"
        const val SHEET = "sheet"
        const val LIST_PANE = "list_pane"
        const val LIST_FIELD = "list_field"
        const val CONVERSATION_FIELD = "conversation_field"
        const val CONVERSATION_PANE = "conversation_pane"
        const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        const val SHIFT = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON

        /** A hardware key-down for [keyCode] with [meta], the [repeat]th auto-repeat when above zero. */
        fun keyDown(
            keyCode: Int,
            meta: Int,
            repeat: Int = 0,
        ) = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, repeat, meta)
    }
}
