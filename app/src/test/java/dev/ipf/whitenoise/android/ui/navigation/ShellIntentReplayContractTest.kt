package dev.ipf.whitenoise.android.ui.navigation

import android.content.Intent
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTapTokens
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.controller.ComponentController
import org.robolectric.annotation.Config

/**
 * Contract 7, third rule: a notification tap or an inbound share is a one-shot request. Once it has been handled,
 * recreating the production Activity must not route it a second time.
 *
 * Only Activity recreation is pinned here. After a real process death the platform re-delivers the task's original
 * launch intent and the process starts with empty app state, which a Robolectric host cannot reproduce faithfully.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi", application = ContractShellTestApplication::class)
class ShellIntentReplayContractTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    /** A handled notification tap stays handled: after Back and an Activity recreation the chat list stays. */
    @Test
    fun aHandledNotificationTapIsNotReplayedByActivityRecreation() {
        withLaunchedActivity(notificationTapIntent()) { activity, fixture -> handledThenRecreated(activity, fixture) }
    }

    /**
     * A share the shell has acknowledged stays handled: the recreated Activity does not parse the share intent
     * again, so no request is pending. The Robolectric host cannot persist a share's payload, so the shell never
     * routes it here, and the test acknowledges it through the Activity's own lifecycle seam instead.
     */
    @Test
    fun anAcknowledgedShareIsNotReplayedByActivityRecreation() {
        val share =
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, SHARED_TEXT)
        withLaunchedActivity(share) { activity, fixture ->
            val request = requireNotNull(activity.get().pendingInboundShareRequestForTest) { "no share was parsed" }
            assertEquals(SHARED_TEXT, request.payload.text)
            activity.get().acknowledgeInboundShareForTest(request.requestId)
            assertNull(activity.get().pendingInboundShareRequestForTest)

            relaunchWithCurrentIntent(activity)
            awaitCondition { rowShown(fixture) }
            repeat(SETTLE_ROUNDS) { settle() }

            assertTrue("the recreated Activity shows the chat list", rowShown(fixture))
            assertNull("no share may be pending again", activity.get().pendingInboundShareRequestForTest)
        }
    }

    /**
     * The launch intent opens the target chat, Back returns to the list, and the recreated Activity keeps showing
     * the list with no chat selected.
     */
    private fun handledThenRecreated(
        activity: ActivityController<MainActivity>,
        fixture: ReturnFixture,
    ) {
        val first = activity.get()
        val process = (first.application as WhiteNoiseApplication).mainShellProcessState
        awaitCondition { process.selectedChat.value?.id == fixture.target.groupIdHex }
        awaitCondition { messageShown() }

        Espresso.pressBack()
        awaitCondition { process.selectedChat.value == null && rowShown(fixture) }
        assertFalse("Back must leave the conversation", messageShown())

        relaunchWithCurrentIntent(activity)
        val second = activity.get()
        assertTrue("recreation must build a new Activity", first !== second)
        awaitCondition { rowShown(fixture) }
        repeat(SETTLE_ROUNDS) { settle() }

        assertTrue("the recreated Activity shows the chat list", rowShown(fixture))
        assertNull("the handled request must not select its chat again", process.selectedChat.value)
        assertFalse("the handled request must not reopen its conversation", messageShown())
    }

    /**
     * Launches the production Activity with [intent] over a ready fixture whose conversation shows one message, and
     * always releases the seeded bootstrap and the fixture state.
     */
    private fun withLaunchedActivity(
        intent: Intent,
        exercise: (ActivityController<MainActivity>, ReturnFixture) -> Unit,
    ) {
        val message =
            timelineRecord("cd".repeat(32), 1uL)
                .copy(groupIdHex = ConversationTimelineTestIds.GROUP_ID, plaintext = CONVERSATION_MESSAGE)
        val scripts = List(OPEN_SCRIPTS) { ScriptedConversationTimelineSubscription(timelinePage(message)) }
        val fixture = ReturnFixture(timelineScripts = scripts)
        val seededBootstrap = CompletableDeferred<Unit>()
        seedReadyStartup(fixture.appState, seededBootstrap)
        ContractShellTestApplication.fixtureAppState = fixture.appState
        try {
            withGlobalSnapshotApply {
                val activity = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
                try {
                    exercise(activity, fixture)
                } finally {
                    activity.pause().stop().destroy()
                }
            }
        } finally {
            seededBootstrap.complete(Unit)
            ContractShellTestApplication.fixtureAppState = null
        }
    }

    /**
     * Recreates the Activity the way the platform relaunches it for a configuration change: the new instance gets
     * the intent the old one last held (`ActivityThread.handleRelaunchActivityInner` passes `activity.mIntent` on),
     * not the original launch intent, which Robolectric's controller would otherwise reuse.
     */
    private fun relaunchWithCurrentIntent(activity: ActivityController<MainActivity>) {
        val current = activity.get().intent
        ComponentController::class.java
            .getDeclaredField("intent")
            .apply { isAccessible = true }
            .set(activity, current)
        activity.recreate()
    }

    /** A trusted tap on the fixture chat's message notification, signed with a live tap token. */
    private fun notificationTapIntent(): Intent {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val target =
            NotificationTarget(
                accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                groupIdHex = ConversationTimelineTestIds.GROUP_ID,
                messageIdHex = null,
                kind = NotificationTargetKind.MESSAGE,
            )
        val token = NotificationTapTokens.create(context).tokenFor(NOTIFICATION_KEY)
        return Intent(context, MainActivity::class.java).also {
            NotificationNavigation.applyToIntent(it, target, NOTIFICATION_KEY, token)
        }
    }

    /**
     * Puts [appState] on its ready surface with a bootstrap attempt already in flight, so the Activity's own
     * bootstrap call attaches to that attempt and never starts a native runtime.
     */
    private fun seedReadyStartup(
        appState: WhiteNoiseAppState,
        attempt: CompletableDeferred<Unit>,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setPhase", AppPhase::class.java)
            .apply { isAccessible = true }
            .invoke(appState, AppPhase.Ready)
        runBlocking { appState.accountSession.bootstrapAttempts.currentOrStart { attempt } }
    }

    /** True while the target chat's list row is composed. */
    private fun rowShown(fixture: ReturnFixture): Boolean {
        val rows = composeRule.onAllNodesWithTag("chat.row.${fixture.target.groupIdHex}")
        return rows.fetchSemanticsNodes().isNotEmpty()
    }

    /** True while the conversation's one message is composed. */
    private fun messageShown(): Boolean {
        val carriers = composeRule.onAllNodes(hasText(CONVERSATION_MESSAGE, substring = true))
        return carriers.fetchSemanticsNodes().isNotEmpty()
    }

    /** Lets route transitions and a deferred open's bound pass. */
    private fun settle() {
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)
        shadowOf(Looper.getMainLooper()).idle()
        composeRule.waitForIdle()
    }

    /** Waits for asynchronous shell settlement, idling the Robolectric main looper between checks. */
    private fun awaitCondition(condition: () -> Boolean) {
        composeRule.waitUntil(WAIT_MILLIS) {
            shadowOf(Looper.getMainLooper()).idle()
            condition()
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val CONVERSATION_MESSAGE = "replay contract only message"
        const val SHARED_TEXT = "shared once"
        const val NOTIFICATION_KEY = "replay-contract-notification"
        const val OPEN_SCRIPTS = 3
        const val SETTLE_ROUNDS = 3
        const val SETTLE_MILLIS = 1_000L
        const val WAIT_MILLIS = 10_000L
    }
}
