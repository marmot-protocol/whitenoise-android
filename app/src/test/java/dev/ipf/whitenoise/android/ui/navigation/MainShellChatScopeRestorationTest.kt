package dev.ipf.whitenoise.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import dev.ipf.whitenoise.android.ui.chats.ChatScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The actual shell-owned receipt survives restoration while obsolete account/runtime callbacks are rejected. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MainShellChatScopeRestorationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test fun leftSurvivesSavedStateAndExplicitChatsResetsIt() {
        var holder: MainShellChatScopeState? = null
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val current = rememberMainShellChatScope("a", 1)
            SideEffect { holder = current }
        }
        composeRule.runOnIdle { assertTrue(checkNotNull(holder).select(ChatScope.Left)) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle {
            assertEquals(ChatScope.Left, checkNotNull(holder).scope)
            assertTrue(checkNotNull(holder).select(ChatScope.Chats))
        }
        composeRule.runOnIdle { assertEquals(ChatScope.Chats, checkNotNull(holder).scope) }
    }

    @Test fun accountChangeRejectsOldCallbackAndCannotRestoreOtherAccountLeft() = ownerChange(accountChange = true)

    @Test fun runtimeChangeRejectsOldCallbackAndClearsLeft() = ownerChange(accountChange = false)

    private fun ownerChange(accountChange: Boolean) {
        var account by mutableStateOf("a")
        var runtime by mutableStateOf(1)
        var holder: MainShellChatScopeState? = null
        var oldSelect: ((ChatScope) -> Boolean)? = null
        composeRule.setContent {
            val current = rememberMainShellChatScope(account, runtime)
            SideEffect { holder = current }
        }
        composeRule.runOnIdle {
            oldSelect = checkNotNull(holder).select
            checkNotNull(holder).select(ChatScope.Left)
        }
        composeRule.runOnIdle { if (accountChange) account = "b" else runtime++ }
        composeRule.runOnIdle {
            assertEquals(ChatScope.Chats, checkNotNull(holder).scope)
            assertFalse(checkNotNull(oldSelect).invoke(ChatScope.Left))
        }
        composeRule.runOnIdle { assertEquals(ChatScope.Chats, checkNotNull(holder).scope) }
    }
}
