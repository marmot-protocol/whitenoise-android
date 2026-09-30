package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.OLD_WELCOME
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.appState
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.chatListRow
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.group
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberRoster
import dev.ipf.whitenoise.android.state.InviteAcceptanceTestData.memberSnapshot
import dev.ipf.whitenoise.android.ui.conversation.ConversationScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the production screen's foreground callback after a failed invitation authority read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationInviteResolutionLifecycleTest {
    @get:Rule val composeRule = createComposeRule()

    /** Returning from a signer activity restores consent actions without replaying consent. */
    @Test
    fun resumeRetriesFailedAuthorityAndResolvedResumesDoNoExtraRead() {
        val pending = group(pending = true, welcome = OLD_WELCOME)
        val item = chatListItemFromProjection(chatListRow(pending = false), group = pending)
        val nativeAvailable = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val closed = AtomicInteger(0)
        val joins = AtomicInteger(0)
        val app = appState()
        app.liveSubscriptionOverrides.conversation = nativeSubscriptions(pending, nativeAvailable, reads, closed)
        val controller =
            ConversationController(
                appState = app,
                initialGroup = item.group,
                initialChatListRow = item.projection,
                initialInviteConfirmationUnresolved = true,
                initialMemberSnapshot = memberSnapshot(),
                inviteAcceptor = { _, _ ->
                    joins.incrementAndGet()
                    error("unexpected Join")
                },
                groupRosterReader = { _, _ -> memberRoster() },
            )
        try {
            runBlocking { controller.retryInviteAcceptanceAuthority() }
            controller.markAuthoritativeTimelinePublishedForTest()
            assertEquals(GroupRosterLoadState.FAILED, controller.inviteAcceptanceResolutionState)
            lateinit var lifecycleContext: ResolutionLifecycleContext
            composeRule.setContent {
                val context = LocalContext.current
                lifecycleContext = remember(context) { ResolutionLifecycleContext(context) }
                CompositionLocalProvider(LocalContext provides lifecycleContext) {
                    WhiteNoiseTheme {
                        ConversationScreen(appState = app, chat = item, controller = controller, onBack = {})
                    }
                }
            }
            composeRule.onNodeWithText(app.appContext.getString(R.string.couldnt_check_invitation)).assertIsDisplayed()
            assertTrue(controller.inviteAcceptanceResolutionPending)
            nativeAvailable.set(true)
            composeRule.runOnIdle { lifecycleContext.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
            composeRule.waitUntil(timeoutMillis = 5_000) { !controller.inviteAcceptanceResolutionPending }
            composeRule.onNodeWithText(app.appContext.getString(R.string.accept)).assertIsDisplayed()
            assertEquals(2, reads.get())
            assertEquals(2, closed.get())
            assertEquals(0, joins.get())
            composeRule.runOnIdle {
                lifecycleContext.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleContext.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            }
            composeRule.waitForIdle()
            assertEquals(2, reads.get())
        } finally {
            controller.onCleared()
        }
    }

    /** Injects only the native authority boundary needed by the real screen lifecycle. */
    private fun nativeSubscriptions(
        pending: AppGroupRecordFfi,
        nativeAvailable: AtomicBoolean,
        reads: AtomicInteger,
        closed: AtomicInteger,
    ): ConversationLiveSubscriptions =
        ConversationLiveSubscriptions(
            openTimeline = { _, _, _ -> error("unexpected timeline") },
            openGroupState = { _, _ ->
                object : ConversationGroupStateSubscriptionHandle {
                    override fun snapshot(): AppGroupRecordFfi {
                        reads.incrementAndGet()
                        check(nativeAvailable.get()) { "authority unavailable" }
                        return pending
                    }

                    override suspend fun next(): AppGroupRecordFfi? = null

                    override fun close() {
                        closed.incrementAndGet()
                    }
                }
            },
        )

    /** Keeps Android resources/activity intact while independently driving the screen's lifecycle observer. */
    private class ResolutionLifecycleContext(
        base: Context,
    ) : ContextWrapper(base),
        LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.STARTED }
        override val lifecycle: Lifecycle get() = registry
    }
}
