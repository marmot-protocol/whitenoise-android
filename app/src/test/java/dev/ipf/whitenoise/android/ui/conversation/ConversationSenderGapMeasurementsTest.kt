package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reading projection looks up a measured gap by the same key that native LazyColumn publishes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ConversationSenderGapMeasurementsTest {
    @get:Rule val compose = createComposeRule()

    /** Replacing or disposing a row immediately drops its old decoration from reading geometry. */
    @Test
    fun lazyItemKeyOwnsItsGapThroughLayoutAndDisposal() {
        val measurements = ConversationSenderGapMeasurements()
        val list = LazyListState()
        val firstKey = "presentation:newer"
        val secondKey = "presentation:older"
        var gap by mutableStateOf(6.dp)
        var showFirst by mutableStateOf(true)
        compose.setContent {
            LazyColumn(modifier = Modifier.height(160.dp), state = list, reverseLayout = true) {
                if (showFirst) {
                    item(key = firstKey) {
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .measureConversationSenderGap(measurements, firstKey, gap)
                                .padding(top = gap)
                                .height(40.dp),
                        )
                    }
                }
                item(key = secondKey) { Spacer(Modifier.fillMaxWidth().height(40.dp)) }
            }
        }

        compose.runOnIdle {
            val first = list.layoutInfo.visibleItemsInfo.single { it.key == firstKey }
            val second = list.layoutInfo.visibleItemsInfo.single { it.key == secondKey }
            assertEquals(6, measurements[first.key])
            assertEquals(0, measurements[second.key])
            assertEquals(second.size, first.size - measurements[first.key])
            gap = 0.dp
        }
        compose.runOnIdle {
            assertEquals(0, measurements[firstKey])
            gap = 6.dp
        }
        compose.runOnIdle {
            assertEquals(6, measurements[firstKey])
            showFirst = false
        }
        compose.runOnIdle {
            assertFalse(list.layoutInfo.visibleItemsInfo.any { it.key == firstKey })
            assertEquals(0, measurements[firstKey])
        }
    }
}
