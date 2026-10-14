package dev.ipf.whitenoise.android.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationWidgetRefreshSignalsTest {
    @Test
    fun ordinaryRowBurstsRefreshOnceWithoutBlankingExistingRows() =
        runTest {
            val signals = MutableSharedFlow<WidgetRefreshSignal>()
            val requests = mutableListOf<Boolean>()
            backgroundScope.launch { collectWidgetRefreshes(signals) { requests.add(it) } }
            runCurrent()
            signals.emit(WidgetRefreshSignal(listOf("account", false), listOf(0)))
            runCurrent()
            assertEquals(listOf(true), requests)
            for (row in 1..3) {
                signals.emit(WidgetRefreshSignal(listOf("account", false), listOf(row)))
                runCurrent()
                advanceTimeBy(100)
            }
            assertEquals(listOf(true), requests)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(true, false), requests)
        }

    @Test
    fun privacyChangeRedactsImmediatelyAndCancelsPendingContentRefresh() =
        runTest {
            val signals = MutableSharedFlow<WidgetRefreshSignal>()
            val requests = mutableListOf<Boolean>()
            backgroundScope.launch { collectWidgetRefreshes(signals) { requests.add(it) } }
            runCurrent()
            signals.emit(WidgetRefreshSignal(listOf("account", false), listOf(0)))
            runCurrent()
            signals.emit(WidgetRefreshSignal(listOf("account", false), listOf(1)))
            runCurrent()
            advanceTimeBy(100)
            signals.emit(WidgetRefreshSignal(listOf("account", true), listOf(1)))
            runCurrent()
            assertEquals(listOf(true, true), requests)
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf(true, true), requests)
        }
}
