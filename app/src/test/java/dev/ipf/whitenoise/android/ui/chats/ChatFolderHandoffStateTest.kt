package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatFolderHandoffStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun accountSwitchClearsAllFolderHandoffState() {
        var activeAccountRef by mutableStateOf("account-a")
        lateinit var handoff: ChatFolderHandoffState
        lateinit var accountAHandoff: ChatFolderHandoffState

        composeRule.setContent {
            handoff = rememberFolderHandoff(activeAccountRef)
        }
        composeRule.runOnIdle {
            accountAHandoff = handoff
            handoff.pickerChatIds = listOf("chat-a")
            handoff.editorChatIds = setOf("chat-a")
            handoff.editingFolderId = "folder-a"
            activeAccountRef = "account-b"
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertNotSame(accountAHandoff, handoff)
            assertNull(handoff.pickerChatIds)
            assertNull(handoff.editorChatIds)
            assertNull(handoff.editingFolderId)
        }
    }
}
