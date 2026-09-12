package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.trackWhiteNoiseHeader
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Restored native list position and disposal drive header color without synthetic scroll events. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileHeaderScrollTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun restoredListAndEmptyRefreshUpdateActualHeaderOffset() {
        var count by mutableStateOf(20)
        lateinit var behavior: TopAppBarScrollBehavior
        composeRule.setContent {
            WhiteNoiseTheme {
                behavior = TopAppBarDefaults.pinnedScrollBehavior()
                behavior.state.heightOffsetLimit = -64f
                CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides behavior) {
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = 4)
                    LazyColumn(Modifier.height(180.dp).trackWhiteNoiseHeader(list), state = list) {
                        items(count) { Text("Row $it", Modifier.height(48.dp)) }
                    }
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue(behavior.state.contentOffset < 0f)
            count = 0
        }
        composeRule.runOnIdle { assertEquals(0f, behavior.state.contentOffset, 0f) }
    }

    @Test fun disposedListClearsItsHeaderOffset() {
        var visible by mutableStateOf(true)
        lateinit var behavior: TopAppBarScrollBehavior
        composeRule.setContent {
            WhiteNoiseTheme {
                behavior = TopAppBarDefaults.pinnedScrollBehavior()
                behavior.state.heightOffsetLimit = -64f
                CompositionLocalProvider(LocalWhiteNoiseHeaderScroll provides behavior) {
                    if (visible) {
                        val list = rememberLazyListState(initialFirstVisibleItemIndex = 4)
                        LazyColumn(Modifier.height(180.dp).trackWhiteNoiseHeader(list), state = list) {
                            items(20) { Text("Row $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle {
            assertTrue(behavior.state.contentOffset < 0f)
            visible = false
        }
        composeRule.runOnIdle { assertEquals(0f, behavior.state.contentOffset, 0f) }
    }
}
