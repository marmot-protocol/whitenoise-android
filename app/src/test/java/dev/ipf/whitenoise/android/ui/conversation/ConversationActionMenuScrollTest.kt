package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationActionMenuScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun transcriptScrollClearsActionMenuOwnerAndReverseScrollDoesNotReopenIt() {
        val openActionMenuId = mutableStateOf<String?>("message-2")

        composeRule.setContent {
            val listState = rememberLazyListState()
            DismissMessageActionMenuOnScroll(listState) {
                openActionMenuId.value = null
            }
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .height(160.dp)
                        .testTag(TRANSCRIPT_TAG),
            ) {
                items((0 until 30).toList()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(80.dp),
                    )
                }
            }
        }

        composeRule.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertNull(openActionMenuId.value)

        composeRule.onNodeWithTag(TRANSCRIPT_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertNull(openActionMenuId.value)
    }

    @Test
    fun disposingAnOwningRowClearsItWithoutClosingANewerOwner() {
        var openActionMenuId by mutableStateOf<String?>("message-2")
        var showMessage2 by mutableStateOf(true)

        composeRule.setContent {
            if (showMessage2) {
                val messageId = "message-2"
                DismissMessageActionMenuOnDispose(
                    messageId = messageId,
                    isOpen = openActionMenuId == messageId,
                ) {
                    if (openActionMenuId == messageId) {
                        openActionMenuId = null
                    }
                }
            }
        }

        composeRule.runOnUiThread {
            showMessage2 = false
        }
        composeRule.waitForIdle()
        assertNull(openActionMenuId)

        composeRule.runOnUiThread {
            openActionMenuId = "message-2"
            showMessage2 = true
        }
        composeRule.waitForIdle()
        composeRule.runOnUiThread {
            openActionMenuId = "message-3"
            showMessage2 = false
        }
        composeRule.waitForIdle()

        assertEquals("message-3", openActionMenuId)
    }

    private companion object {
        const val TRANSCRIPT_TAG = "conversation-transcript"
    }
}
