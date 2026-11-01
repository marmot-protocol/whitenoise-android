package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.ScrollEdgeFadeMotion
import dev.ipf.whitenoise.android.ui.common.rememberScrollEdgeFadeMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises actual measured lazy-list eligibility while its pointer/folder composition key changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatListFadeContinuityTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Folder keys retire gesture ownership without rebuilding the already-visible edge animation. */
    @Test
    fun folderKeyKeepsVisibleFadeAndFittingDestinationClearsIt() {
        val folder = mutableStateOf("all")
        val count = mutableStateOf(20)
        lateinit var motion: ScrollEdgeFadeMotion
        composeRule.setContent {
            val list = rememberLazyListState()
            motion = rememberScrollEdgeFadeMotion("account-runtime")
            key(folder.value) {
                LazyColumn(
                    Modifier.size(240.dp, 160.dp).chatListEdgeFade(list, motion = motion),
                    state = list,
                ) {
                    items(count.value) { Text("Row $it", Modifier.height(48.dp)) }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(200)
        composeRule.runOnIdle { assertTrue(motion.bottom.value > 0.dp) }
        val before = motion
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { folder.value = "work" }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertSame(before, motion)
            assertTrue(motion.bottom.value > 0.dp)
            count.value = 1
            folder.value = "short"
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertEquals(0.dp, motion.top.value)
            assertEquals(0.dp, motion.bottom.value)
        }
    }
}
