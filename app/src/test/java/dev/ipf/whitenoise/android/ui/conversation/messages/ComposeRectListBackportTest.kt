/*
 * Copyright 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Properties

/** Ports AndroidX fd550bed793's reuse regression and the production Row alignment trigger. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposeRectListBackportTest {
    @get:Rule
    val rule = createComposeRule()

    /** Guards against tests silently resolving an unpatched UI jar on either distribution. */
    @Test
    fun runtimeContainsTheReviewedSourceBackport() {
        val stream = javaClass.classLoader?.getResourceAsStream("META-INF/whitenoise-compose-rectlist-backport.properties")
        assertNotNull("the Compose source backport must be on the test runtime classpath", stream)
        val properties = Properties()
        requireNotNull(stream).use(properties::load)
        assertEquals("1.12.1", properties.getProperty("base"))
        assertEquals("fd550bed793b66378c83091532e29c18fdef44cc", properties.getProperty("upstream"))
    }

    /** Item four reuses item zero's nodes and queries a dirty descendant's baseline in measure. */
    @Test
    fun baselineQueryDuringReusedItemMeasurementDoesNotPlaceChildrenForReal() {
        exerciseReuse(rowAlignment = false, lookahead = false)
    }

    /** Exercises the flag's lookahead owner as well as the ordinary measure owner. */
    @Test
    fun lookaheadBaselineQueryDuringReuseKeepsRectListConsistent() {
        exerciseReuse(rowAlignment = false, lookahead = true)
    }

    /** Row.alignBy is the query path in the fully retraced production fling/prefetch crashes. */
    @Test
    fun rowAlignmentDuringFarSnapKeepsRectListConsistent() {
        exerciseReuse(rowAlignment = true, lookahead = false)
    }

    /** Drives pooled reuse without changing the same-height outer row's measured dimensions. */
    private fun exerciseReuse(rowAlignment: Boolean, lookahead: Boolean) {
        val itemPx = 20
        val itemDp = with(rule.density) { itemPx.toDp() }
        val state = LazyListState()
        val content: @Composable () -> Unit = {
            LazyColumn(state = state, modifier = Modifier.height(itemDp * 2)) {
                items(200) { index ->
                    val leaf: @Composable () -> Unit = {
                        Box(Modifier.fillMaxWidth()) {
                            Spacer(
                                Modifier.layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    layout(10 + index, itemPx, mapOf(FirstBaseline to index)) {
                                        placeable.place(0, 0)
                                    }
                                },
                            )
                        }
                    }
                    if (rowAlignment) {
                        Row {
                            Box(Modifier.size(4.dp).alignBy { it.measuredHeight })
                            Column(Modifier.alignBy(MessageBubbleBottomAlignmentLine)) {
                                leaf()
                                Box(
                                    Modifier.layout { measurable, constraints ->
                                        val placeable = measurable.measure(constraints)
                                        layout(placeable.width, 0, mapOf(MessageBubbleBottomAlignmentLine to 0)) {
                                            placeable.place(0, 0)
                                        }
                                    },
                                ) { Spacer(Modifier.size(4.dp)) }
                            }
                        }
                    } else {
                        BaselineQueryingLayout(content = leaf)
                    }
                }
            }
        }
        rule.setContent {
            if (lookahead) LookaheadScope { content() } else content()
        }
        if (rowAlignment) {
            listOf(150, 12, 180, 3, 120, 0).forEach { target ->
                rule.runOnIdle { runBlocking { state.scrollToItem(target) } }
                rule.waitForIdle()
                rule.runOnIdle { assertEquals(target, state.firstVisibleItemIndex) }
            }
        } else {
            // Off-screen -> reuse pool -> reactivated with a different descendant size/baseline.
            repeat(3) { rule.runOnIdle { runBlocking { state.scrollBy(itemPx.toFloat()) } } }
            rule.waitForIdle()
            rule.runOnIdle { assertEquals(3, state.firstVisibleItemIndex) }
        }
    }

    /** Reads inherited baselines while measuring, matching the upstream regression's wrapper. */
    @Composable
    private fun BaselineQueryingLayout(content: @Composable () -> Unit) {
        Layout(content = { Box { content() } }) { measurables, constraints ->
            val placeable = measurables.single().measure(constraints)
            val first = placeable[FirstBaseline]
            val last = placeable[LastBaseline]
            layout(placeable.width, placeable.height, mapOf(FirstBaseline to first, LastBaseline to last)) {
                placeable.placeRelative(0, 0)
            }
        }
    }
}
