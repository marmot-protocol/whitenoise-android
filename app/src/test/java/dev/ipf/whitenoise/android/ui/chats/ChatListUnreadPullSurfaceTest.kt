package dev.ipf.whitenoise.android.ui.chats

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListUnreadPullSurfaceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun resistanceNeverOutrunsFingerAtAnyDensityAndRemainsBounded() {
        listOf(72f, 216f).forEach { threshold ->
            listOf(0.01f, 0.1f, 0.5f, 1f, 2f, 100f).forEach { ratio ->
                val displacement = unreadPullDisplacement(ratio, threshold)
                assertTrue(displacement > 0f)
                assertTrue(displacement < ratio * threshold)
                assertTrue(displacement < threshold * 1.5f)
            }
        }
    }

    @Test fun actualRowsFollowBothDownwardTogglesWithOneHapticEach() {
        val unread = mutableStateOf(false)
        var haptics = 0
        var toggles = 0
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalHapticFeedback provides
                        object : HapticFeedback {
                            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                                haptics++
                            }
                        },
                ) {
                    Box(Modifier.size(300.dp, 500.dp)) {
                        ChatListUnreadPullSurface(unread.value, true, true, { true }, {
                            unread.value = it
                            toggles++
                        }) {
                            LazyColumn {
                                items(20) { Text("Chat $it", Modifier.size(48.dp).testTag("row.$it")) }
                            }
                        }
                    }
                }
            }
        }
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        repeat(2) { direction ->
            val before =
                rule
                    .onNodeWithTag("row.0")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                down(Offset(width / 2f, height * 0.3f))
                moveBy(Offset(0f, 40f))
            }
            val moved =
                rule
                    .onNodeWithTag("row.0")
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            assertTrue(moved > before)
            assertTrue(moved - before < 40f)
            assertEquals(direction, toggles)
            rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                moveBy(Offset(0f, height * 0.4f))
                up()
            }
            rule.waitForIdle()
            assertEquals(direction + 1, toggles)
            assertEquals(direction + 1, haptics)
            assertEquals(direction == 0, unread.value)
        }
    }

    @Test fun accessibleActionAndWholePillClearAnEmptyBodyEvenWithGestureDisabled() {
        val unread = mutableStateOf(false)
        var changes = 0
        rule.setContent {
            WhiteNoiseTheme {
                ChatListUnreadPullSurface(unread.value, true, false, { true }, {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper())
                    unread.value = it
                    changes++
                }) { Text("No chats") }
            }
        }
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        rule.onNodeWithTag("chats.unread_filter").performClick()
        rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
        assertEquals(2, changes)
        assertEquals(false, unread.value)
    }
}
