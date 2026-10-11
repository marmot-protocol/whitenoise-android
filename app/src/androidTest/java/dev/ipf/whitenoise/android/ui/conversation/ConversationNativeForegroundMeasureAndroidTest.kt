package dev.ipf.whitenoise.android.ui.conversation

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ResponsivenessDeviceAcceptance
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Uses native window frames; Compose test-clock traversal would conceal measurement starvation behind pre-draw. */
@PullRequestDeviceSmoke
@ResponsivenessDeviceAcceptance
@RunWith(AndroidJUnit4::class)
class ConversationNativeForegroundMeasureAndroidTest {
    @get:Rule
    val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    /** A same-size publication arriving behind the gate must measure and paint without the liveness fallback. */
    @Test
    fun queuedPublicationMeasuresBeforePresentationDeadline() = exerciseMeasurement(requestNativeLayout = true)

    /** The same native fixture without the scheduling repair demonstrates why pre-draw alone cannot settle. */
    @Test
    fun missingNativeLayoutRequestRequiresPresentationFallback() = exerciseMeasurement(requestNativeLayout = false)

    private fun exerciseMeasurement(requestNativeLayout: Boolean) {
        val fixture = NativeMeasureFixture(requestNativeLayout)
        try {
            scenario.scenario.onActivity { fixture.mount(it) }
            assertTrue("initial publication must paint naturally", fixture.initialDraw.await(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { fixture.arm() }
            assertTrue("current publication must paint naturally", fixture.currentDraw.await(10, TimeUnit.SECONDS))
            assertEquals("only the negative control may need fallback", !requestNativeLayout, fixture.deadline.get())
            assertFalse("retained A must never paint after arming", fixture.draws.any { it != listOf("msg:B") })
            assertTrue("actual B keys must paint", fixture.draws.contains(listOf("msg:B")))
        } finally {
            // Dispose before ActivityScenario's idle-waiting teardown, including assertion failures.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { fixture.close() }
        }
    }
}

/** Keeps A/B geometry identical and publishes B only after a real blocked pre-draw has been observed. */
private class NativeMeasureFixture(
    private val requestNativeLayout: Boolean,
) {
    val initialDraw = CountDownLatch(1)
    val currentDraw = CountDownLatch(1)
    val deadline = AtomicBoolean(false)
    val draws = CopyOnWriteArrayList<List<String>>()
    private val before = publication("A")
    private val after = publication("B")
    private val publication = mutableStateOf(before)
    private val listState = LazyListState()
    private val viewport = ConversationTimelineViewport(listState)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: ComposeView? = null
    private var armed = false
    private var blocked = false
    private var publicationPosted = false
    private val publishCurrent = Runnable { publication.value = after }

    /** Mounts a real window recomposer without ComposeTestRule or forced owner measurements. */
    fun mount(activity: ComponentActivity) {
        val view = ComposeView(activity)
        host = view
        activity.setContentView(view)
        view.setContent { WhiteNoiseTheme { Content() } }
    }

    /** Arms the unchanged production settle wait while the fixed-size A composition remains mounted. */
    fun arm() {
        armed = true
        blocked = true
        scope.launch {
            awaitConversationForegroundPresentation(
                preDrawSignals = signals,
                currentState = {
                    ConversationForegroundSettleState(
                        geometry = ConversationForegroundGeometry(240, 0, 0),
                        imeTargetBottomPx = 0,
                        bottomChromeMeasured = true,
                        timelineMeasured = viewport.hasMeasuredTimeline(after),
                    )
                },
                expectedImeVisible = false,
                expectedVisibilityTimeoutMillis = 1_500L,
                onSettleDeadlineExpired = {
                    deadline.set(true)
                    unblock()
                },
            )
            unblock()
        }
        host?.invalidate()
    }

    /** Opens presentation and schedules a native frame using the same production invalidation helper. */
    private fun unblock() {
        blocked = false
        host?.let(::requestConversationForegroundFrame)
    }

    /** Dispatches the queued publication after the blocked traversal, without changing window or row dimensions. */
    private fun onPreDraw() {
        if (armed && blocked && !publicationPosted) {
            publicationPosted = true
            host?.post(publishCurrent)
        }
        signals.trySend(Unit)
    }

    /** Captures keys from actual content drawing, never from a later semantics poll or test-clock traversal. */
    @Suppress("FunctionNaming")
    @Composable
    private fun Content() {
        val current = publication.value
        ConversationForegroundDrawGateEffect(isBlocked = { blocked }, onPreDraw = ::onPreDraw)
        if (requestNativeLayout) {
            ConversationForegroundTimelineMeasureEffect(viewport, current, armed)
        }
        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .size(240.dp)
                    .measureConversationTimelinePadding(viewport, 0.dp, 0.dp, current)
                    .drawWithContent {
                        drawContent()
                        if (armed) {
                            draws.add(listState.layoutInfo.visibleItemsInfo.map { it.key.toString() })
                            if (current === after && viewport.hasMeasuredTimeline(after)) currentDraw.countDown()
                        } else {
                            initialDraw.countDown()
                        }
                    },
        ) {
            items(current, key = { it.id }) { item -> Text(item.id, Modifier.height(48.dp)) }
        }
    }

    /** Removes only this test's host; the app and its data remain installed. */
    fun close() {
        blocked = false
        host?.let { view ->
            view.removeCallbacks(publishCurrent)
            (view.parent as? ViewGroup)?.removeView(view)
            view.disposeComposition()
        }
        scope.cancel()
        signals.close()
    }

    /** Uses distinct immutable publications with equal row count, text length and fixed row height. */
    private fun publication(key: String): List<TimelineMessage> =
        listOf(
            TimelineMessage(
                id = "msg:$key",
                record = TimelineProjector.toAppMessageRecord(timelineRecord(key, 1uL, "fixture $key")),
                status = MessageStatus.Received,
            ),
        )
}
