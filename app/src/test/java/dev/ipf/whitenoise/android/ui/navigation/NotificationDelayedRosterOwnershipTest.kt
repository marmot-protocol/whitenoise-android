package dev.ipf.whitenoise.android.ui.navigation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.EngineTrust
import dev.ipf.whitenoise.android.audio.tts.FakeSessionEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineInfo
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.NotificationNavigation
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.TtsAutoReadPreferences
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.awaitOpenedTimelineSubscriptionsClosed
import dev.ipf.whitenoise.android.state.timelineMessageIds
import dev.ipf.whitenoise.android.state.timelinePage
import dev.ipf.whitenoise.android.state.timelineRecord
import dev.ipf.whitenoise.android.state.transcriptPresentationNeedsRetry
import dev.ipf.whitenoise.android.state.usesDirectTranscriptChrome
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Exercises notification ownership while an inactive account's semantic-group roster is unresolved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class NotificationDelayedRosterOwnershipTest : NotificationDelayedRosterFixture() {
    /** Target-first preload keeps source cards while a cold target roster gates the first transcript frame. */
    @Test
    fun inactiveTwoMemberGroup_preloadFirst_preservesSourceCardsThroughRosterReveal() {
        verifyDelayedTargetRosterNotificationIsolation(preloadFinishesFirst = true)
    }

    /** Target activation keeps source cards while the exact preload and cold target roster complete later. */
    @Test
    fun inactiveTwoMemberGroup_activationFirst_preservesSourceCardsThroughRosterReveal() {
        verifyDelayedTargetRosterNotificationIsolation(preloadFinishesFirst = false)
    }

    /** Withheld text stays silent, then revealed live and foreground-resume rows remain audible. */
    @Test
    fun withheldRosterGatesAutomaticSpeechUntilTheTranscriptReveals() {
        verifyWithheldTranscriptAutoRead()
    }
}

/** Owns the production-shell fixture shared by the two independently ordered roster cases. */
abstract class NotificationDelayedRosterFixture {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** Gives each route case an isolated notification channel and empty platform card set. */
    @Before
    fun setUp() {
        manager.cancelAll()
        context.clearDelayedRosterAutoReadPreferences()
        manager.createNotificationChannel(
            NotificationChannel(TEST_CHANNEL, "Test", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Removes only the fixture's notification channel and cards. */
    @After
    fun tearDown() {
        manager.cancelAll()
        manager.deleteNotificationChannel(TEST_CHANNEL)
        context.clearDelayedRosterAutoReadPreferences()
    }

    /** Runs one ordering through the hidden and revealed ownership phases with exception-safe cleanup. */
    protected fun verifyDelayedTargetRosterNotificationIsolation(preloadFinishesFirst: Boolean) {
        val fixture = createFixture(preloadFinishesFirst)
        try {
            mountRoute(fixture)
            advanceRouteToHeldRoster(fixture, preloadFinishesFirst)
            val controller = awaitAuthoritativeController(fixture)
            assertHeldRosterIsNotOwned(fixture, controller)
            fixture.gate.releaseTargetRoster.countDown()
            awaitCondition { controller.membersVerified && controller.usesDirectTranscriptChrome }
            assertRevealedRosterOwnsOnlyTarget(fixture, controller)
        } finally {
            releaseAndClose(fixture)
        }
    }

    /** Exercises withheld, live, and foreground-resume speech through the production shell and engine boundary. */
    protected fun verifyWithheldTranscriptAutoRead() {
        val fixture = createFixture(preloadFinishesFirst = true, targetRosterFailures = 1)
        val engine = FakeSessionEngine()
        fixture.appState.forceUsableTtsResolutionForDelayedRosterTest()
        fixture.appState.setTtsAutoReadGlobalDefault(true)
        fixture.appState.ttsController.attachEngine(engine)
        try {
            mountRoute(fixture)
            advanceRouteToHeldRoster(
                fixture = fixture,
                preloadFinishesFirst = true,
                verifyCardOwnership = false,
            )
            val controller = awaitAuthoritativeController(fixture)
            assertHeldTranscriptIsNotRevealed(fixture, controller)
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            assertTrue("withheld transcript was narrated before reveal", engine.spoken.isEmpty())

            fixture.gate.releaseTargetRoster.countDown()
            awaitCondition { controller.transcriptPresentationNeedsRetry }
            publishAndResumeWhileTranscriptIsWithheld(fixture, controller, engine)
            composeRule.waitForIdle()
            composeRule
                .onNodeWithText(context.getString(R.string.retry))
                .assertIsDisplayed()
                .performClick()
            awaitCondition { controller.membersVerified && controller.usesDirectTranscriptChrome }
            assertRevealOwnsOneBacklogAndLiveRun(fixture, controller, engine)
            assertForegroundResumeOwnsOnlyItsNewRow(fixture, controller, engine)
        } finally {
            releaseAndClose(fixture)
        }
    }

    /** Consumes a foreground generation while hidden without narrating its newly materialized row. */
    private fun publishAndResumeWhileTranscriptIsWithheld(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
        engine: FakeSessionEngine,
    ) {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        fixture.subscriptions.timelineScripts.single().emitWindow(
            delayedRosterTimelinePage(
                MESSAGE_ID to NOTIFIED_BODY,
                HIDDEN_RESUME_MESSAGE_ID to HIDDEN_RESUME_BODY,
            ),
        )
        awaitCondition { timelineMessageIds(controller) == listOf(MESSAGE_ID, HIDDEN_RESUME_MESSAGE_ID) }
        assertTrue("background transcript was narrated while hidden", engine.spoken.isEmpty())
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        ShadowLooper.idleMainLooper()
        assertTrue("hidden resume narrated before reveal", engine.spoken.isEmpty())
    }

    /** Requires reveal-owned backlog speech once per row, followed by one live append. */
    private fun assertRevealOwnsOneBacklogAndLiveRun(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
        engine: FakeSessionEngine,
    ) {
        awaitCondition { engine.spoken.any { NOTIFIED_BODY in it.text } }
        awaitCondition { engine.spoken.any { HIDDEN_RESUME_BODY in it.text } }
        assertEquals(1, engine.spoken.count { NOTIFIED_BODY in it.text })
        assertEquals(1, engine.spoken.count { HIDDEN_RESUME_BODY in it.text })

        fixture.subscriptions.timelineScripts.single().emitWindow(
            delayedRosterTimelinePage(
                MESSAGE_ID to NOTIFIED_BODY,
                HIDDEN_RESUME_MESSAGE_ID to HIDDEN_RESUME_BODY,
                LIVE_MESSAGE_ID to LIVE_BODY,
            ),
        )
        awaitCondition {
            timelineMessageIds(controller) == listOf(MESSAGE_ID, HIDDEN_RESUME_MESSAGE_ID, LIVE_MESSAGE_ID)
        }
        awaitCondition { engine.spoken.any { LIVE_BODY in it.text } }
    }

    /** Stops the active queue, then proves only the row received while paused is spoken on resume. */
    private fun assertForegroundResumeOwnsOnlyItsNewRow(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
        engine: FakeSessionEngine,
    ) {
        fixture.appState.stopSpeaking()
        awaitCondition { fixture.appState.ttsController.state.value is TtsState.Idle }
        val spokenBeforeResume = engine.spoken.size
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        fixture.subscriptions.timelineScripts.single().emitWindow(
            delayedRosterTimelinePage(
                MESSAGE_ID to NOTIFIED_BODY,
                HIDDEN_RESUME_MESSAGE_ID to HIDDEN_RESUME_BODY,
                LIVE_MESSAGE_ID to LIVE_BODY,
                RESUMED_MESSAGE_ID to RESUMED_BODY,
            ),
        )
        awaitCondition {
            timelineMessageIds(controller) ==
                listOf(MESSAGE_ID, HIDDEN_RESUME_MESSAGE_ID, LIVE_MESSAGE_ID, RESUMED_MESSAGE_ID)
        }
        assertEquals(spokenBeforeResume, engine.spoken.size)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitCondition { engine.spoken.drop(spokenBeforeResume).any { RESUMED_BODY in it.text } }
    }

    /** Creates account-isolated route, timeline, card, and attachment owners before composition starts. */
    private fun createFixture(
        preloadFinishesFirst: Boolean,
        targetRosterFailures: Int = 0,
    ): DelayedRosterFixture {
        val subscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts =
                    listOf(
                        ScriptedConversationTimelineSubscription(
                            delayedRosterTimelinePage(MESSAGE_ID to NOTIFIED_BODY),
                        ),
                    ),
                group = group(),
            )
        val gate = RouteOrderGate(preloadFinishesFirst, targetRosterFailures)
        val appState = appState(fakeMarmot(gate), subscriptions)
        appState.cacheGroupMemberSnapshot(SOURCE_ACCOUNT, SHARED_GROUP, sourceAccountThreeMemberSnapshot())
        assertNull(appState.cachedGroupMemberSnapshot(TARGET_ACCOUNT, SHARED_GROUP))
        val targetAttachment = delayedRosterAttachment(TARGET_ACCOUNT)
        return DelayedRosterFixture(
            gate = gate,
            appState = appState,
            subscriptions = subscriptions,
            routed = routedTarget(),
            handled = AtomicBoolean(false),
            sourceKeys = manager.postConversationCards(context, SOURCE_ACCOUNT, "source-delayed-roster"),
            targetKeys = manager.postConversationCards(context, TARGET_ACCOUNT, "target-delayed-roster"),
            lateSource = "source-during-delayed-roster" to 53,
            targetAttachment = targetAttachment,
            sourceAttachment = targetAttachment.copy(accountRef = SOURCE_ACCOUNT),
        )
    }

    /** Mounts the production shell with a one-shot typed notification route. */
    private fun mountRoute(fixture: DelayedRosterFixture) {
        fixture.appState.setAppInForeground(true)
        composeRule.setContent {
            var inboundTarget by remember { mutableStateOf(fixture.routed.notificationTarget) }
            if (fixture.shellMounted.value) {
                WhiteNoiseTheme {
                    MainShell(
                        appState = fixture.appState,
                        inboundNotificationTarget = inboundTarget,
                        inboundNotificationRequestId = fixture.routed.notificationRequestId,
                        onNotificationTargetHandled = { _, _ ->
                            fixture.handled.set(true)
                            inboundTarget = null
                        },
                    )
                }
            }
        }
    }

    /** Drives the requested preload/activation ordering without releasing the independent roster gate. */
    private fun advanceRouteToHeldRoster(
        fixture: DelayedRosterFixture,
        preloadFinishesFirst: Boolean,
        verifyCardOwnership: Boolean = true,
    ) {
        awaitCondition { fixture.gate.preloadStarted.count == 0L }
        manager.notify(
            fixture.lateSource.first,
            fixture.lateSource.second,
            delayedRosterNotification(context, SOURCE_ACCOUNT),
        )
        if (preloadFinishesFirst) {
            awaitCondition { fixture.appState.activeAccountRef == TARGET_ACCOUNT }
            if (verifyCardOwnership) {
                awaitNotificationKeys((fixture.sourceKeys + fixture.lateSource).toSet())
            }
            fixture.gate.releaseActivation.countDown()
        } else {
            awaitCondition { fixture.appState.activeAccountRef == TARGET_ACCOUNT }
            assertEquals(1L, fixture.gate.broadBindStarted.count)
            assertEquals(
                (fixture.sourceKeys + fixture.targetKeys + fixture.lateSource).toSet(),
                activeNotificationKeys(),
            )
            fixture.gate.releasePreload.countDown()
        }
    }

    /** Waits for the real target controller to publish its exact authoritative message page. */
    private fun awaitAuthoritativeController(fixture: DelayedRosterFixture): ConversationController {
        check(fixture.gate.preloadCompleted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "notification preload did not complete"
        }
        awaitCondition { fixture.handled.get() }
        awaitCondition {
            fixture.appState.attachedConversationControllersForTest().singleOrNull()?.let { controller ->
                controller.hasPublishedAuthoritativeTimeline && timelineMessageIds(controller) == listOf(MESSAGE_ID)
            } == true
        }
        check(fixture.gate.targetRosterStarted.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            "target roster read did not start"
        }
        return fixture.appState.attachedConversationControllersForTest().single()
    }

    /** Proves a withheld transcript owns neither notification suppression nor attachment routing. */
    private fun assertHeldRosterIsNotOwned(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
    ) {
        assertHeldTranscriptIsNotRevealed(fixture, controller)
        awaitNotificationKeys((fixture.sourceKeys + fixture.lateSource).toSet())
        assertTrue(fixture.appState.shouldPostFreshTargetMessage())
        val freshTarget = LocalNotificationFormatter.conversationDismissalKey(TARGET_ACCOUNT, SHARED_GROUP)
        manager.notify(freshTarget.tag, freshTarget.id, delayedRosterNotification(context, TARGET_ACCOUNT))
        awaitNotificationKeys((fixture.sourceKeys + fixture.lateSource + (freshTarget.tag to freshTarget.id)).toSet())
        assertNull(fixture.appState.attachmentOpens.openRequest(fixture.targetAttachment))
        assertNull(fixture.appState.attachmentOpens.openRequest(fixture.sourceAttachment))
    }

    /** Proves the authoritative target timeline remains withheld while roster verification is pending. */
    private fun assertHeldTranscriptIsNotRevealed(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
    ) {
        assertEquals(TARGET_ACCOUNT, controller.boundAccountRef)
        assertFalse(controller.isDm)
        assertFalse(controller.membersVerified)
        assertFalse(controller.usesDirectTranscriptChrome)
        assertEquals(0, controller.memberCount)
        assertNull(fixture.appState.cachedGroupMemberSnapshot(TARGET_ACCOUNT, SHARED_GROUP))
        assertEquals(0, fixture.gate.rosterReadCount.get())
        assertEquals(1, fixture.gate.targetRosterReadCount.get())
        assertTrue(
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }

    /** Proves roster reveal transfers card and attachment ownership only to the target account. */
    private fun assertRevealedRosterOwnsOnlyTarget(
        fixture: DelayedRosterFixture,
        controller: ConversationController,
    ) {
        assertSame(controller, fixture.appState.attachedConversationControllersForTest().single())
        assertEquals(2, controller.memberCount)
        assertFalse(controller.isDm)
        composeRule.onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE).assertIsDisplayed()
        composeRule.onNodeWithText(NOTIFIED_BODY).assertIsDisplayed()
        awaitCondition { fixture.appState.attachmentOpens.openRequest(fixture.targetAttachment) != null }
        assertEquals(
            fixture.targetAttachment,
            requireNotNull(fixture.appState.attachmentOpens.openRequest(fixture.targetAttachment)).transferRequest,
        )
        assertNull(fixture.appState.attachmentOpens.openRequest(fixture.sourceAttachment))
        assertFalse(fixture.appState.shouldPostFreshTargetMessage())
        awaitNotificationKeys((fixture.sourceKeys + fixture.lateSource).toSet())
    }

    /** Releases every test gate, detaches fixture-owned controllers, and closes scripted subscriptions. */
    private fun releaseAndClose(fixture: DelayedRosterFixture) {
        fixture.gate.releasePreload.countDown()
        fixture.gate.releaseActivation.countDown()
        fixture.gate.releaseTargetRoster.countDown()
        val fixtureControllers = mutableListOf<ConversationController>()
        try {
            try {
                composeRule.runOnIdle {
                    fixture.appState.attachedConversationControllersForTest().forEach(fixtureControllers::addByIdentity)
                    fixture.shellMounted.value = false
                }
                composeRule.waitForIdle()
            } finally {
                fixture.appState.attachedConversationControllersForTest().forEach(fixtureControllers::addByIdentity)
                fixtureControllers.forEach { controller ->
                    fixture.appState.detachConversationController(controller)
                    controller.onCleared()
                }
            }
        } finally {
            try {
                fixture.appState.ttsController.detachEngine()
            } finally {
                awaitOpenedTimelineSubscriptionsClosed(fixture.subscriptions)
            }
        }
    }

    /** Parses a trusted notification target through the same inbound-routing contract as MainActivity. */
    private fun routedTarget(): InboundIntentRouting {
        val target =
            NotificationTarget(
                accountRef = TARGET_ACCOUNT,
                groupIdHex = SHARED_GROUP,
                messageIdHex = MESSAGE_ID,
                kind = NotificationTargetKind.MESSAGE,
            )
        val intent = Intent()
        val notificationKey = "$TARGET_ACCOUNT-card"
        NotificationNavigation.applyToIntent(intent, target, notificationKey, TAP_TOKEN)
        val parsed =
            NotificationNavigation.parse(intent) { parsedKey, tapToken ->
                parsedKey == notificationKey && tapToken == TAP_TOKEN
            }
        return routeInboundIntent(
            parsedTarget = parsed,
            shareRequest = null,
            dataString = null,
            current = InboundIntentRouting(notificationTarget = null, profilePayload = null),
        )
    }

    /** Evaluates a new target message against the production suppression policy for the mounted route. */
    private fun WhiteNoiseAppState.shouldPostFreshTargetMessage(): Boolean {
        val method =
            WhiteNoiseAppState::class.java
                .getDeclaredMethod(
                    "shouldPostNotification",
                    NotificationUpdateFfi::class.java,
                    Boolean::class.javaPrimitiveType!!,
                ).apply { isAccessible = true }
        return method.invoke(this, delayedRosterFreshTargetUpdate(), false) as Boolean
    }

    /** Builds two account identities and attaches only this fixture's scripted conversation source. */
    private fun appState(
        marmot: MarmotInterface,
        subscriptions: ScriptedConversationLiveSubscriptions,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(NoopDraftPersistence),
            accountIdHexResolver = { accountRef ->
                when (accountRef) {
                    SOURCE_ACCOUNT -> SOURCE_ID
                    TARGET_ACCOUNT -> TARGET_ID
                    else -> null
                }
            },
            accounts = listOf(account(SOURCE_ACCOUNT, SOURCE_ID), account(TARGET_ACCOUNT, TARGET_ID)),
            activeAccountRef = SOURCE_ACCOUNT,
            notificationDispatcher = Dispatchers.IO,
            profileDisplayNameReader = { id -> "Peer".takeIf { id == PEER_ID } },
        ).also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
            state.liveSubscriptionOverrides.conversation = subscriptions.subscriptions
        }

    /** Enforces exact account/group reads while independently gating preload, activation, and roster work. */
    private fun fakeMarmot(gate: RouteOrderGate): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "groupRoster" -> gatedRoster(gate, arguments)
                "chatListRow" -> gatedProjection(gate, arguments)
                "subscribeChatList" -> gatedBroadBind(gate, arguments)
                "toString" -> "NotificationDelayedRosterMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> error("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** Blocks only the target roster and returns an account-owned two-member result. */
    private fun gatedRoster(
        gate: RouteOrderGate,
        arguments: Array<out Any?>?,
    ): GroupRosterFfi {
        val accountRef = arguments?.firstOrNull() as? String
        val groupIdHex = arguments?.getOrNull(1) as? String
        check(accountRef == SOURCE_ACCOUNT || accountRef == TARGET_ACCOUNT) { "unknown roster account" }
        check(groupIdHex == SHARED_GROUP) { "wrong roster group" }
        if (accountRef == TARGET_ACCOUNT) {
            gate.targetRosterReadCount.incrementAndGet()
            gate.targetRosterStarted.countDown()
            check(gate.releaseTargetRoster.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "target roster gate timed out"
            }
            val failsThisAttempt =
                gate.remainingTargetRosterFailures
                    .getAndUpdate { failures -> (failures - 1).coerceAtLeast(0) } > 0
            if (failsThisAttempt) {
                error("target roster unavailable")
            }
        }
        return twoMemberRoster(requireNotNull(accountRef))
    }

    /** Gates the exact target projection without coupling it to roster hydration. */
    private fun gatedProjection(
        gate: RouteOrderGate,
        arguments: Array<out Any?>?,
    ): ChatListRowFfi {
        val accountRef = arguments?.firstOrNull() as? String
        val groupIdHex = arguments?.getOrNull(1) as? String
        check(accountRef == SOURCE_ACCOUNT || accountRef == TARGET_ACCOUNT) { "unknown projection account" }
        check(groupIdHex == SHARED_GROUP) { "wrong projection group" }
        if (accountRef == TARGET_ACCOUNT) {
            gate.preloadStarted.countDown()
            check(gate.releasePreload.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "preload gate timed out"
            }
            gate.preloadCompleted.countDown()
        }
        return chatListRow(requireNotNull(groupIdHex))
    }

    /** Holds target broad-list binding according to the selected ordering. */
    private fun gatedBroadBind(
        gate: RouteOrderGate,
        arguments: Array<out Any?>?,
    ): Nothing {
        val accountRef = arguments?.firstOrNull() as? String
        if (accountRef == TARGET_ACCOUNT) {
            gate.broadBindStarted.countDown()
            check(gate.releaseActivation.await(ROUTE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "activation gate timed out"
            }
        }
        error("Skip broad-list startup in the focused route test")
    }

    /** Returns current platform notification keys without depending on list order. */
    private fun activeNotificationKeys(): Set<Pair<String?, Int>> {
        val notifications = manager.activeNotifications
        return notifications.mapTo(linkedSetOf()) { it.tag to it.id }
    }

    /** Waits until only the expected account-owned notification cards remain. */
    private fun awaitNotificationKeys(expected: Set<Pair<String?, Int>>) {
        awaitCondition(
            failureMessage = {
                "notification ownership mismatch: active=${activeNotificationKeys()} expected=$expected"
            },
        ) {
            activeNotificationKeys() == expected
        }
    }

    /** Pumps Compose, Robolectric, and real workers until a bounded route condition holds. */
    private fun awaitCondition(
        failureMessage: (() -> String)? = null,
        condition: () -> Boolean,
    ) {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ROUTE_TIMEOUT_MILLIS)
        while (System.nanoTime() <= deadlineNanos) {
            composeRule.waitForIdle()
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
            ShadowLooper.idleMainLooper(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
        throw AssertionError(failureMessage?.invoke() ?: "Condition not met within ${ROUTE_TIMEOUT_MILLIS}ms")
    }

    /** Returns the semantic group used by both account-owned projections. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = SHARED_GROUP,
            protocolProfile = AppProtocolProfileFfi.CURRENT,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Shared group",
            description = "",
            admins = emptyList(),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "e4".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    /** Returns a target projection with the notification's unread boundary. */
    private fun chatListRow(groupIdHex: String) =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = groupIdHex,
            archived = false,
            pendingConfirmation = false,
            title = "Shared group",
            groupName = "Shared group",
            avatarUrl = null,
            avatar = null,
            lastMessage = null,
            unreadCount = 3uL,
            hasUnread = true,
            firstUnreadMessageIdHex = MESSAGE_ID,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 0uL,
            updatedAt = 0uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.GROUP,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    /** Returns one local account summary for fixture account selection. */
    private fun account(
        ref: String,
        id: String,
    ) = AccountSummaryFfi(
        label = ref,
        accountIdHex = id,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = true,
    )
}

/** Posts the four conversation families and one invite for one account. */
private fun NotificationManager.postConversationCards(
    context: Context,
    accountRef: String,
    inviteTag: String,
): List<Pair<String?, Int>> {
    val keys =
        listOf(
            LocalNotificationFormatter.conversationDismissalKey(accountRef, SHARED_GROUP),
            LocalNotificationFormatter.reactionDismissalKey(accountRef, SHARED_GROUP),
            LocalNotificationFormatter.mentionDismissalKey(accountRef, SHARED_GROUP),
            LocalNotificationFormatter.agentActivityDismissalKey(accountRef, SHARED_GROUP),
        )
    keys.forEach { key -> notify(key.tag, key.id, delayedRosterNotification(context, accountRef)) }
    notify(inviteTag, 51, delayedRosterNotification(context, accountRef))
    return keys.map { it.tag to it.id } + (inviteTag to 51)
}

/** Owns all independently gated values for one delayed-roster route. */
private data class DelayedRosterFixture(
    val gate: RouteOrderGate,
    val appState: WhiteNoiseAppState,
    val subscriptions: ScriptedConversationLiveSubscriptions,
    val routed: InboundIntentRouting,
    val handled: AtomicBoolean,
    val shellMounted: MutableState<Boolean> = mutableStateOf(true),
    val sourceKeys: List<Pair<String?, Int>>,
    val targetKeys: List<Pair<String?, Int>>,
    val lateSource: Pair<String, Int>,
    val targetAttachment: AttachmentTransferRequest,
    val sourceAttachment: AttachmentTransferRequest,
)

/** Coordinates route phases without serializing the exact projection behind roster hydration. */
private class RouteOrderGate(
    preloadFinishesFirst: Boolean,
    targetRosterFailures: Int,
) {
    val preloadStarted = CountDownLatch(1)
    val preloadCompleted = CountDownLatch(1)
    val broadBindStarted = CountDownLatch(1)
    val releasePreload = CountDownLatch(if (preloadFinishesFirst) 0 else 1)
    val releaseActivation = CountDownLatch(if (preloadFinishesFirst) 1 else 0)
    val targetRosterStarted = CountDownLatch(1)
    val releaseTargetRoster = CountDownLatch(1)
    val rosterReadCount = AtomicInteger()
    val targetRosterReadCount = AtomicInteger()
    val remainingTargetRosterFailures = AtomicInteger(targetRosterFailures)
}

/** Avoids disk writes while preserving DraftStore ownership. */
private object NoopDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/** Clears only this subsystem's test preferences so speech policy cannot leak between cases. */
private fun Context.clearDelayedRosterAutoReadPreferences() {
    getSharedPreferences(TtsAutoReadPreferences.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
}

/** Reads attached route controllers under the same lock used by production state. */
@Suppress("UNCHECKED_CAST")
private fun WhiteNoiseAppState.attachedConversationControllersForTest(): List<ConversationController> {
    val lock =
        WhiteNoiseAppState::class.java
            .getDeclaredField("conversationControllerLock")
            .apply { isAccessible = true }
            .get(this)
            .let(::requireNotNull)
    val controllers =
        WhiteNoiseAppState::class.java
            .getDeclaredField("conversationControllers")
            .apply { isAccessible = true }
            .get(this) as Set<ConversationController>
    return synchronized(lock) { controllers.toList() }
}

/** Retains each fixture controller once using lifecycle identity rather than value equality. */
private fun MutableList<ConversationController>.addByIdentity(controller: ConversationController) {
    if (none { it === controller }) add(controller)
}

/** Makes the injected local engine visible through the same AppState capability gate as production discovery. */
private fun WhiteNoiseAppState.forceUsableTtsResolutionForDelayedRosterTest() {
    val delegateField = WhiteNoiseAppState::class.java.getDeclaredField("ttsResolution\$delegate")
    delegateField.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val delegate = delegateField.get(this) as MutableState<TtsResolutionResult?>
    delegate.value =
        TtsResolutionResult(
            status = TextToSpeech.SUCCESS,
            engines = listOf(TtsEngineInfo("com.test.tts", "Test TTS", EngineTrust.Local)),
            defaultEnginePackage = "com.test.tts",
            handle = null,
        )
}

/** Builds the target account's production attachment-open request key. */
private fun delayedRosterAttachment(accountRef: String) =
    AttachmentTransferRequest(
        accountRef = accountRef,
        groupIdHex = SHARED_GROUP,
        messageIdHex = MESSAGE_ID,
        attachmentIndex = 0,
    )

/** Creates one platform card carrying exact account/group dismissal metadata. */
private fun delayedRosterNotification(
    context: Context,
    accountRef: String,
) = NotificationCompat
    .Builder(context, TEST_CHANNEL)
    .setSmallIcon(android.R.drawable.ic_dialog_info)
    .setContentTitle("Test")
    .addExtras(
        Bundle().apply {
            putString(LocalNotificationFormatter.EXTRA_DISMISS_ACCOUNT_REF, accountRef)
            putString(LocalNotificationFormatter.EXTRA_DISMISS_GROUP_ID, SHARED_GROUP)
        },
    ).build()

/** Supplies a distinct incoming message without exposing fixture identities in diagnostics. */
private fun delayedRosterFreshTargetUpdate() =
    NotificationUpdateFfi(
        notificationKey = "message:$TARGET_ACCOUNT:fresh-message",
        conversationKey = "conversation:$TARGET_ACCOUNT:$SHARED_GROUP",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = TARGET_ACCOUNT,
        accountIdHex = TARGET_ID,
        groupIdHex = SHARED_GROUP,
        groupName = "Fixture group",
        isDm = false,
        isMention = false,
        messageIdHex = "fresh-message",
        sender = NotificationUserFfi(PEER_ID, "Peer", null),
        receiver = NotificationUserFfi(TARGET_ID, "Fixture owner", null),
        previewText = "Fresh incoming message",
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = 1_982L,
        isFromSelf = false,
    )

/** Builds a complete authoritative page in the exact order supplied by the live source. */
private fun delayedRosterTimelinePage(vararg messages: Pair<String, String>) =
    timelinePage(
        *messages
            .mapIndexed { index, (messageId, body) ->
                timelineRecord(
                    messageId = messageId,
                    timelineAt = (index + 1).toULong(),
                    plaintext = body,
                ).copy(
                    groupIdHex = SHARED_GROUP,
                    sender = PEER_ID,
                )
            }.toTypedArray(),
    )

/** Provides the requested account's authoritative two-member semantic-group roster. */
private fun twoMemberRoster(accountRef: String): GroupRosterFfi {
    val accountId = if (accountRef == TARGET_ACCOUNT) TARGET_ID else SOURCE_ID
    return GroupRosterFfi(
        groupIdHex = SHARED_GROUP,
        members =
            listOf(
                GroupMemberDetailsFfi(
                    memberIdHex = accountId,
                    account = accountRef,
                    local = true,
                    isAdmin = true,
                    isSelf = true,
                    npub = "npub-$accountId",
                    displayName = accountRef,
                ),
                GroupMemberDetailsFfi(
                    memberIdHex = PEER_ID,
                    account = null,
                    local = false,
                    isAdmin = false,
                    isSelf = false,
                    npub = "npub-$PEER_ID",
                    displayName = "Peer",
                ),
            ),
        epoch = 1uL,
        rosterRevision = 1uL,
        selfMembership = SelfMembershipFfi.MEMBER,
        memberCount = 2u,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
    )
}

/** Seeds source-only presentation state so cross-account member reuse remains observable. */
private fun sourceAccountThreeMemberSnapshot(): List<AppGroupMemberRecordFfi> =
    listOf(
        AppGroupMemberRecordFfi(memberIdHex = SOURCE_ID, account = SOURCE_ACCOUNT, local = true),
        AppGroupMemberRecordFfi(memberIdHex = PEER_ID, account = null, local = false),
        AppGroupMemberRecordFfi(memberIdHex = THIRD_MEMBER_ID, account = null, local = false),
    )

private const val SOURCE_ACCOUNT = "account-a"
private const val TARGET_ACCOUNT = "account-b"
private val SOURCE_ID = "a1".repeat(32)
private val TARGET_ID = "b2".repeat(32)
private val SHARED_GROUP = "c3".repeat(32)
private val MESSAGE_ID = "d4".repeat(32)
private val HIDDEN_RESUME_MESSAGE_ID = "d7".repeat(32)
private val LIVE_MESSAGE_ID = "d5".repeat(32)
private val RESUMED_MESSAGE_ID = "d6".repeat(32)
private val PEER_ID = "e5".repeat(32)
private val THIRD_MEMBER_ID = "f6".repeat(32)
private const val NOTIFIED_BODY = "account-isolated notified body"
private const val HIDDEN_RESUME_BODY = "withheld foreground-return body"
private const val LIVE_BODY = "post-reveal live body"
private const val RESUMED_BODY = "post-resume body"
private const val TAP_TOKEN = "trusted-test-token"
private const val TEST_CHANNEL = "notification-delayed-roster-test"
private const val ROUTE_TIMEOUT_MILLIS = 30_000L
private const val POLL_INTERVAL_MILLIS = 20L
