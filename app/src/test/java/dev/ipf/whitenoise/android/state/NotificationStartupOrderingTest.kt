package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationStartupOrderingTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    @Test
    fun coldBootstrapAttachesReceiverBeforeFirstPostStartFfiWork() =
        runBlocking {
            val fixture = NotificationBootstrapTestFixture(context)
            try {
                fixture.bootstrap()
                fixture.awaitUpdateConsumed()

                assertTrue(fixture.receiverWasAttachedAtPostStartEmission)
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun coldGroupFirstPostUsesKnownLocalNameWithoutCanonicalNpubFfiWork() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                )
            try {
                withFirstCardEnrichmentBlocked {
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()

                    val style = fixture.activeMessagingStyle()
                    assertEquals("Alice", style.latestSenderName())
                    assertEquals("General", style.conversationTitle?.toString())
                }

                assertEquals(
                    "the first complete card must use cached or deterministic identity text",
                    0,
                    fixture.npubCalls.get(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun contactNicknameFirstPostBypassesProfileBindingRead() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val account =
                AccountSummaryFfi(
                    label = "account-a",
                    accountIdHex = "self",
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = null,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                    accounts = listOf(account),
                )
            val senderIdHex = fixture.update.sender.accountIdHex
            try {
                fixture.bootstrap()
                fixture.appState.setContactNickname(senderIdHex, "Alice (work)")
                withFirstCardEnrichmentBlocked {
                    fixture.releaseNotificationDispatch()
                    fixture.awaitNotificationPosted()

                    assertEquals("Alice (work)", fixture.activeMessagingStyle().latestSenderName())
                }
                assertEquals(0, fixture.senderDisplayNameCalls.get())
            } finally {
                fixture.appState.setContactNickname(senderIdHex, "")
                fixture.close()
            }
        }

    @Test
    fun warmDmFirstPostUsesKnownLocalNameAndDmTitle() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                    isDm = true,
                )
            try {
                withFirstCardEnrichmentBlocked {
                    fixture.bootstrap()
                    fixture.warmNpubCache()
                    fixture.releaseNotificationDispatch()
                    fixture.awaitNotificationPosted()

                    val notification = fixture.activeNotification()
                    val style =
                        requireNotNull(
                            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification),
                        )
                    assertEquals("Alice", style.latestSenderName())
                    assertEquals("Alice", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                    assertEquals(null, style.conversationTitle)
                }
                assertEquals(0, fixture.npubCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun trulyUnresolvedFirstPostUsesWarmShortNpubFallback() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = null,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                )
            try {
                withFirstCardEnrichmentBlocked {
                    fixture.bootstrap()
                    fixture.warmNpubCache()
                    fixture.releaseNotificationDispatch()
                    fixture.awaitNotificationPosted()

                    assertEquals(
                        IdentityFormatter.short("npub1coldidentityfallback", prefix = 10, suffix = 8),
                        fixture.activeMessagingStyle().latestSenderName(),
                    )
                }
            } finally {
                fixture.close()
            }
        }

    @Test
    fun coldBootstrapHasNoPostStartListenerDispatchGap() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    delayFirstNotificationDispatchAfterRuntimeStart = true,
                )
            try {
                fixture.bootstrap()

                assertTrue(
                    "the first subscription attempt must begin without a post-start dispatcher hop",
                    fixture.receiverWasAttachedAtPostStartEmission,
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun subscriptionFailureIsBoundedAndTheSameRuntimeCanRecover() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyFailSubscriptions = true,
                    receiverTimeoutMillis = 25L,
                )
            try {
                fixture.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertTrue(fixture.subscriptionCalls.get() >= 1)

                fixture.allowSubscriptions(recoveryTimeoutMillis = 2_000L)
                fixture.ensureNotificationRuntimeStarted()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun synchronousSubscriptionSetupCannotEscapeStartupTimeout() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockSubscriptionsSynchronously = true,
                    receiverTimeoutMillis = 25L,
                )
            val bootstrap =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.bootstrap()
                }
            try {
                val completedWithinBound =
                    withTimeoutOrNull(2_000L) {
                        bootstrap.join()
                        true
                    } ?: false

                assertTrue("bootstrap must remain bounded while native subscription setup blocks", completedWithinBound)
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())

                fixture.allowSubscriptions(recoveryTimeoutMillis = 2_000L)
                fixture.ensureNotificationRuntimeStarted()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
            } finally {
                fixture.allowSubscriptions()
                bootstrap.cancelAndJoin()
                fixture.close()
            }
        }

    @Test
    fun cancelledBootstrapLeavesTheSharedListenerAndRuntimeReusable() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockSubscriptions = true,
                    receiverTimeoutMillis = 60_000L,
                )
            try {
                val bootstrap =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.bootstrap()
                    }

                withTimeout(5_000L) {
                    while (fixture.subscriptionCalls.get() == 0) yield()
                }
                bootstrap.cancelAndJoin()

                assertTrue(fixture.appState.phase is AppPhase.Bootstrapping)
                assertEquals(1, fixture.runtimeStartCalls.get())

                fixture.allowSubscriptions()
                fixture.bootstrap()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun repeatedStartupCallsReuseOneRuntimeAndReceiver() =
        runBlocking {
            val fixture = NotificationBootstrapTestFixture(context)
            try {
                fixture.bootstrap()
                fixture.bootstrap()
                fixture.ensureNotificationRuntimeStarted()

                assertEquals(1, fixture.runtimeStartCalls.get())
                assertEquals(1, fixture.subscriptionCalls.get())
                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun backgroundRetryCannotReplaceAnActionableBootstrapFailureWithLoading() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockRuntimeStartSynchronously = true,
                    bootstrapActionableTimeoutMillis = 100L,
                )
            try {
                fixture.bootstrap()
                assertTrue(fixture.appState.phase is AppPhase.Failed)

                val retry = async { runCatching { fixture.ensureNotificationRuntimeStarted() } }
                var reclaimedLoading = false
                while (!retry.isCompleted) {
                    reclaimedLoading = reclaimedLoading || fixture.appState.phase is AppPhase.Bootstrapping
                    yield()
                }
                retry.await()

                assertFalse("background recovery must preserve the actionable failure", reclaimedLoading)
                assertTrue(fixture.appState.phase is AppPhase.Failed)
                assertEquals(1, fixture.runtimeStartCalls.get())

                fixture.allowRuntimeStart()
                fixture.ensureNotificationRuntimeStarted()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun explicitRetryRestoresLoadingAndReusesTheInFlightBootstrapAttempt() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    initiallyBlockRuntimeStartSynchronously = true,
                    bootstrapActionableTimeoutMillis = 100L,
                )
            try {
                fixture.bootstrap()
                assertTrue(fixture.appState.phase is AppPhase.Failed)

                val retry = async { fixture.retryBootstrap() }
                withTimeout(1_000L) {
                    while (fixture.appState.phase !is AppPhase.Bootstrapping) yield()
                }

                assertEquals(1, fixture.runtimeStartCalls.get())
                fixture.allowRuntimeStart()
                retry.await()

                assertTrue(fixture.appState.phase is AppPhase.Onboarding)
                assertEquals(1, fixture.runtimeStartCalls.get())
            } finally {
                fixture.close()
            }
        }

    private fun NotificationBootstrapTestFixture.activeNotification(): Notification =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .single { it.tag == "account-a|group-a" }
            .notification

    private fun NotificationBootstrapTestFixture.activeMessagingStyle(): NotificationCompat.MessagingStyle =
        requireNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(activeNotification()))

    private fun NotificationCompat.MessagingStyle.latestSenderName(): String? =
        messages
            .last()
            .person
            ?.name
            ?.toString()

    private suspend fun withFirstCardEnrichmentBlocked(block: suspend () -> Unit) {
        val release = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (
                        op == ConversationCardOp.SHOW_ENRICH &&
                        barrier == ConversationCardBarrier.AFTER_REGISTER &&
                        claimed.compareAndSet(false, true)
                    ) {
                        release.await(5, TimeUnit.SECONDS)
                    }
                }
            }
        try {
            block()
        } finally {
            release.countDown()
            ConversationCardPostSynchronizer.testHook = null
        }
    }
}
