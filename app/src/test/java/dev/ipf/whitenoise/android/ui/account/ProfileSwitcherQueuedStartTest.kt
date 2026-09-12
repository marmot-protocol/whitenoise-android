package dev.ipf.whitenoise.android.ui.account

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/** Queued UI intent must be checked before native setup lookup or retained-account sign-in begins. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProfileSwitcherQueuedStartTest {
    @Test fun closeBeforeMutationDispatchNeverEntersNativeRecovery() = queuedStart(close = true)

    @Test fun teardownBeforeMutationDispatchNeverEntersNativeRecovery() = queuedStart(close = false)

    /** Await real process-scope mutation jobs through IO so late native calls cannot evade assertions. */
    @Suppress("LongMethod")
    private fun queuedStart(close: Boolean) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        var app: WhiteNoiseAppState? = null
        try {
            val calls = CopyOnWriteArrayList<String>()
            val native =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { _, method, _ ->
                    calls += method.name
                    when (method.name) {
                        "onboardingSnapshot" -> null
                        "signInAccount" -> error("Queued selection must not begin sign-in")
                        else -> error("Unexpected native call: ${method.name}")
                    }
                } as MarmotInterface
            val context = ApplicationProvider.getApplicationContext<Context>()
            val state =
                WhiteNoiseAppState(
                    context,
                    DraftStore.forContext(context),
                    { null },
                    listOf(
                        AccountSummaryFfi("a", "aa".repeat(32), true, false, false, true),
                        AccountSummaryFfi("retained", "bb".repeat(32), true, false, true, false),
                    ),
                    "a",
                    profileReader = { null },
                    profileRefreshRequest = {},
                    initialMarmotRuntime = AppMarmotRuntime(rootPath = "test", marmot = native),
                )
            app = state
            val selection = ProfileSwitcherSelection()
            var completed = 0
            selection.select(state, "retained") { completed++ }
            val queued = checkNotNull(state.mutationsScope.coroutineContext[Job]).children.toList()
            assertTrue(queued.isNotEmpty())
            assertTrue(calls.isEmpty())
            if (close) selection.close() else state.wipeInProgress = true
            runTest(dispatcher) { queued.joinAll() }
            assertTrue(calls.isEmpty())
            assertEquals("a", state.activeAccountRef)
            assertEquals(0, completed)
            assertEquals(null, selection.pendingLabel)
        } finally {
            app?.mutationsScope?.cancel()
            Dispatchers.resetMain()
        }
    }
}
