package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.AppQuarantinedGroupFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationTimelineTestDraftPersistence
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class QuarantinedGroupsSummaryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun missingRuntimeShowsUnavailableInsteadOfLoadingForever() {
        show(state(null))
        awaitText(context.getString(R.string.quarantine_unavailable))
    }

    @Test fun nativeFailureShowsSanitizedLoadError() {
        show(state(native { error("private native state") }))
        awaitText(context.getString(R.string.quarantine_load_failed))
        compose.onNodeWithText("private native state").assertDoesNotExist()
    }

    @Test fun nativeCancellationShowsLoadError() {
        show(state(native { throw CancellationException("private native cancellation") }))
        awaitText(context.getString(R.string.quarantine_load_failed))
        compose.onNodeWithText("private native cancellation").assertDoesNotExist()
    }

    @Test fun inventoryIsReadOnceAndShowsItsCount() {
        val calls = AtomicInteger()
        show(
            state(
                native {
                    calls.incrementAndGet()
                    rows()
                },
            ),
        )
        awaitText(context.getString(R.string.quarantine_count, 1))
        compose.runOnIdle { assertEquals(1, calls.get()) }
    }

    @Test fun teardownRejectsLateCountAndRestoringEligibilityReloads() {
        val calls = AtomicInteger()
        val pending = AtomicReference<Continuation<List<AppQuarantinedGroupFfi>>?>()
        val appState =
            state(
                native { arguments ->
                    if (calls.incrementAndGet() == 1) {
                        @Suppress("UNCHECKED_CAST")
                        pending.set(arguments.last() as Continuation<List<AppQuarantinedGroupFfi>>)
                        COROUTINE_SUSPENDED
                    } else {
                        rows()
                    }
                },
            )
        show(appState)
        try {
            compose.waitUntil(5_000) { pending.get() != null }
            compose.runOnIdle { appState.signOutInProgress = true }
            awaitText(context.getString(R.string.quarantine_unavailable))
            pending.getAndSet(null)!!.resume(rows())
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.quarantine_unavailable)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.quarantine_count, 1)).assertDoesNotExist()
            compose.runOnIdle { appState.signOutInProgress = false }
            awaitText(context.getString(R.string.quarantine_count, 1))
            compose.runOnIdle { assertEquals(2, calls.get()) }
        } finally {
            pending.getAndSet(null)?.resume(emptyList())
        }
    }

    private fun show(state: WhiteNoiseAppState) {
        state.updateDeveloperMode(true)
        compose.setContent { WhiteNoiseTheme { Text(rememberQuarantinedGroupsSummary(state)) } }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun rows() =
        listOf(
            AppQuarantinedGroupFfi("a".repeat(64), AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED),
        )

    private fun state(native: MarmotInterface?) =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        ConversationTimelineTestIds.ACCOUNT_REF,
                        ConversationTimelineTestIds.ACCOUNT_ID,
                        true,
                        false,
                        false,
                        true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            initialMarmotRuntime = native?.let { AppMarmotRuntime("private", it) },
        )

    private fun native(read: (Array<out Any?>) -> Any?): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "quarantinedGroups" -> read(arguments ?: emptyArray())
                "toString" -> "QuarantineSummaryFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("unexpected native call")
            }
        } as MarmotInterface
}
