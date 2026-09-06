package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Looper
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // Startup ordering scenarios share one process-global AppState/notification fixture.
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

    /** Resolves fast local content before the only first-draw platform write. */
    @Test
    fun fastLocalContentIsCompleteOnTheOnlyFirstDrawWrite() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val accounts =
                listOf(
                    signingAccount("account-a", "self"),
                    signingAccount("account-b", "other-self"),
                )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    notificationReceiverHasDisplayName = true,
                    previewText = "**ready on first draw**",
                    accounts = accounts,
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    delay(250L)

                    val notification = fixture.activeNotification()
                    val style = fixture.activeMessagingStyle()
                    assertEquals("Alice", style.latestSenderName())
                    assertEquals("General", style.conversationTitle?.toString())
                    assertEquals(
                        "ready on first draw",
                        style.messages
                            .single()
                            .text
                            .toString(),
                    )
                    assertEquals("Alice", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
                    assertTrue(fixture.markdownParseCalls.get() >= 1)
                    assertEquals(1, writes.get())
                }
            } finally {
                fixture.close()
            }
        }

    /** Avoids a replacement when late resolution is visually identical to fallback. */
    @Test
    fun timedOutContentThatRendersLikeItsFallbackDoesNotRewriteTheCard() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val releaseFirstRead = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    previewText = "already final",
                    onDisplayName = { _, _ ->
                        releaseFirstRead.await(5, TimeUnit.SECONDS)
                        "Alice"
                    },
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()

                    assertEquals(
                        "already final",
                        fixture
                            .activeMessagingStyle()
                            .messages
                            .single()
                            .text
                            .toString(),
                    )
                    assertEquals(1, writes.get())

                    releaseFirstRead.countDown()
                    fixture.awaitSenderDisplayNameCalls(expected = 2)
                    fixture.awaitNotificationEnrichmentAttempt()

                    assertEquals(
                        "already final",
                        fixture
                            .activeMessagingStyle()
                            .messages
                            .single()
                            .text
                            .toString(),
                    )
                    assertEquals(1, writes.get())
                }
            } finally {
                releaseFirstRead.countDown()
                fixture.close()
            }
        }

    /** Keeps profile hydration outside the fast local mention deadline. */
    @Test
    fun fastLocalMentionNeverStartsProfileHydrationBeforeTheFirstWrite() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val mentionNpub = "npub1" + "z".repeat(58)
            val mentionHex = "f".repeat(64)
            withNotificationWriteCount { writes ->
                val refreshRequest = CompletableDeferred<String>()
                val writesAtRefresh = AtomicInteger(-1)
                val fixture =
                    NotificationBootstrapTestFixture(
                        context = context,
                        previewText = "hello mention",
                        accountIdHexResolver = { reference -> mentionHex.takeIf { reference == mentionNpub } },
                        markdownDocumentFactory = {
                            MarkdownDocumentFfi(
                                truncated = false,
                                blocks =
                                    listOf(
                                        MarkdownBlockFfi.Paragraph(
                                            listOf(
                                                MarkdownInlineFfi.Text("hello "),
                                                MarkdownInlineFfi.NostrMention(
                                                    MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, mentionNpub),
                                                ),
                                            ),
                                        ),
                                    ),
                                blankLinesBefore = ByteArray(0),
                            )
                        },
                        onDisplayName = { _, accountIdHex ->
                            "Alice".takeUnless { accountIdHex == mentionHex }
                        },
                        profileRefreshRequest = { accountIdHex ->
                            if (accountIdHex == mentionHex) {
                                writesAtRefresh.set(writes.get())
                                refreshRequest.complete(accountIdHex)
                            }
                        },
                    )
                try {
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()

                    withTimeoutOrNull(500L) { refreshRequest.await() }

                    assertTrue(writesAtRefresh.get() == -1 || writesAtRefresh.get() >= 1)
                    assertEquals(1, writes.get())
                } finally {
                    fixture.close()
                }
            }
        }

    /** Publishes one fallback then one same-key silent correction after timeout. */
    @Test
    fun timedOutLocalContentPostsFallbackThenOneSilentMatchingCorrection() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val releaseFirstRead = CountDownLatch(1)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    previewText = "**resolved after fallback**",
                    onDisplayName = { _, _ ->
                        releaseFirstRead.await(5, TimeUnit.SECONDS)
                        "Alice"
                    },
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()

                    val fallback = fixture.activeNotification()
                    assertEquals(
                        "**resolved after fallback**",
                        fallback.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    )
                    assertEquals(0, fallback.flags and Notification.FLAG_ONLY_ALERT_ONCE)

                    releaseFirstRead.countDown()
                    fixture.awaitNotificationBody("resolved after fallback")

                    val corrected = fixture.activeNotification()
                    assertTrue(corrected.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                    assertEquals(2, writes.get())
                    assertEquals(
                        listOf("resolved after fallback"),
                        NotificationCompat.MessagingStyle
                            .extractMessagingStyleFromNotification(corrected)
                            ?.messages
                            ?.map { it.text.toString() },
                    )
                    assertEquals("Alice", fixture.activeMessagingStyle().latestSenderName())
                }
            } finally {
                releaseFirstRead.countDown()
                fixture.close()
            }
        }

    /** Rejects a late correction after the notification lifecycle epoch changes. */
    @Test
    fun lifecycleEpochChangeAtTheFinalWriteBoundaryRejectsLateCorrection() =
        runBlocking {
            assertLateCorrectionRejectedAtFinalWrite(
                accounts = listOf(signingAccount("account-a", "self")),
            ) { fixture ->
                fixture.appState.setAppInForeground(
                    foreground = true,
                    dismissRetainedVisibleConversation = false,
                )
            }
        }

    /** Rejects a late correction when notification policy changes before write. */
    @Test
    fun notificationPolicyChangeAtTheFinalWriteBoundaryRejectsLateCorrection() =
        runBlocking {
            assertLateCorrectionRejectedAtFinalWrite(
                accounts = listOf(signingAccount("account-a", "self")),
            ) { fixture ->
                fixture.appState.setConversationNotifyForMode("group-a", ChatNotifyMode.MENTIONS_ONLY)
            }
        }

    /** Rejects unredacted correction when the app locks at the write boundary. */
    @Test
    fun appLockAtTheFinalWriteBoundaryRejectsUnredactedLateCorrection() =
        runBlocking {
            assertLateCorrectionRejectedAtFinalWrite(
                accounts = listOf(signingAccount("account-a", "self")),
            ) { fixture ->
                fixture.appState.setAppLockScreenVisibleForTest(true)
            }
        }

    /** Rejects a late correction after account-scoped profile caches are cleared. */
    @Test
    fun accountCacheLifetimeChangeAtTheFinalWriteBoundaryRejectsLateCorrection() =
        runBlocking {
            assertLateCorrectionRejectedAtFinalWrite(
                accounts =
                    listOf(
                        signingAccount("account-a", "self"),
                        signingAccount("account-b", "other-self"),
                    ),
            ) { fixture ->
                assertTrue(
                    fixture.appState.setActiveAccount(
                        label = "account-b",
                        preloadPolicy = AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST,
                    ),
                )
            }
        }

    /** Gives a newer typed update ownership over a registered older correction. */
    @Test
    fun newerTypedUpdateRegisteredAtTheFinalWriteBoundaryRejectsOldCorrection() =
        runBlocking {
            shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val releaseFirstRead = CountDownLatch(1)
            val correctionBeforeWrite = CountDownLatch(1)
            val releaseCorrection = CountDownLatch(1)
            val correctionFinished = CountDownLatch(1)
            val newerRegistered = CountDownLatch(1)
            val registrations = AtomicInteger(0)
            val writes = AtomicInteger(0)
            val correctionClaimed = AtomicBoolean(false)
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    previewText = "**old fallback**",
                    onDisplayName = { _, _ ->
                        releaseFirstRead.await(5, TimeUnit.SECONDS)
                        "Alice"
                    },
                )
            ConversationCardPostSynchronizer.testHook =
                object : ConversationCardTestHook {
                    override fun onBarrier(
                        op: ConversationCardOp,
                        barrier: ConversationCardBarrier,
                        notificationTag: String,
                        notificationId: Int,
                    ) {
                        if (op != ConversationCardOp.SHOW_NOTIFY) return
                        if (barrier == ConversationCardBarrier.AFTER_REGISTER) {
                            when (registrations.incrementAndGet()) {
                                3 -> newerRegistered.countDown()
                                else -> Unit
                            }
                        }
                        if (
                            barrier == ConversationCardBarrier.BEFORE_WRITE &&
                            writes.get() == 1 &&
                            correctionClaimed.compareAndSet(false, true)
                        ) {
                            correctionBeforeWrite.countDown()
                            check(releaseCorrection.await(5, TimeUnit.SECONDS))
                        }
                        if (barrier == ConversationCardBarrier.AFTER_WRITE) writes.incrementAndGet()
                    }

                    override fun onLockReleased(
                        op: ConversationCardOp,
                        notificationTag: String,
                        notificationId: Int,
                    ) {
                        if (op == ConversationCardOp.SHOW_NOTIFY && correctionClaimed.get()) {
                            correctionFinished.countDown()
                        }
                    }
                }
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                awaitWrites(writes, expected = 1)
                assertEquals(1, writes.get())

                releaseFirstRead.countDown()
                awaitLatch(correctionBeforeWrite)
                fixture.emitUpdate(
                    fixture.update.copy(
                        messageIdHex = "message-b",
                        previewText = "newer",
                        timestampMs = 1_983L,
                    ),
                )
                awaitLatch(newerRegistered)
                releaseCorrection.countDown()
                awaitLatch(correctionFinished)
                awaitWrites(writes, expected = 2)

                assertEquals(
                    listOf("**old fallback**", "newer"),
                    fixture.activeMessagingStyle().messages.map { it.text.toString() },
                )
                assertEquals(2, writes.get())
            } finally {
                releaseFirstRead.countDown()
                releaseCorrection.countDown()
                ConversationCardPostSynchronizer.testHook = null
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

    /** Builds the local-signing account required by account ownership tests. */
    private fun signingAccount(
        label: String,
        accountIdHex: String,
    ): AccountSummaryFfi =
        AccountSummaryFfi(
            label = label,
            accountIdHex = accountIdHex,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Counts only successful synchronized notification write boundaries. */
    private suspend fun withNotificationWriteCount(block: suspend (AtomicInteger) -> Unit) {
        val writes = AtomicInteger(0)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.BEFORE_WRITE) {
                        writes.incrementAndGet()
                    }
                }
            }
        try {
            block(writes)
        } finally {
            ConversationCardPostSynchronizer.testHook = null
        }
    }

    /** Holds a late writer at its final gate, applies invalidation, then releases it. */
    private suspend fun assertLateCorrectionRejectedAtFinalWrite(
        accounts: List<AccountSummaryFfi>,
        invalidate: suspend (NotificationBootstrapTestFixture) -> Unit,
    ) {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val releaseFirstRead = CountDownLatch(1)
        val correctionBeforeWrite = CountDownLatch(1)
        val releaseCorrection = CountDownLatch(1)
        val correctionFinished = CountDownLatch(1)
        val writes = AtomicInteger(0)
        val correctionClaimed = AtomicBoolean(false)
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                notificationUsersHaveDisplayNames = true,
                previewText = "**resolved after fallback**",
                accounts = accounts,
                onDisplayName = { _, _ ->
                    releaseFirstRead.await(5, TimeUnit.SECONDS)
                    "Alice"
                },
            )
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op != ConversationCardOp.SHOW_NOTIFY) return
                    if (
                        barrier == ConversationCardBarrier.BEFORE_WRITE &&
                        writes.get() == 1 &&
                        correctionClaimed.compareAndSet(false, true)
                    ) {
                        correctionBeforeWrite.countDown()
                        check(releaseCorrection.await(5, TimeUnit.SECONDS))
                    }
                    if (barrier == ConversationCardBarrier.AFTER_WRITE) writes.incrementAndGet()
                }

                override fun onLockReleased(
                    op: ConversationCardOp,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_NOTIFY && correctionClaimed.get()) {
                        correctionFinished.countDown()
                    }
                }
            }
        try {
            fixture.bootstrap()
            fixture.awaitNotificationPosted()
            awaitWrites(writes, expected = 1)
            assertEquals(1, writes.get())

            releaseFirstRead.countDown()
            awaitLatch(correctionBeforeWrite)
            invalidate(fixture)
            releaseCorrection.countDown()
            awaitLatch(correctionFinished)

            assertEquals(1, writes.get())
            assertEquals(
                "**resolved after fallback**",
                fixture
                    .activeMessagingStyle()
                    .messages
                    .single()
                    .text
                    .toString(),
            )
        } finally {
            releaseFirstRead.countDown()
            releaseCorrection.countDown()
            ConversationCardPostSynchronizer.testHook = null
            fixture.appState.setAppLockScreenVisibleForTest(false)
            fixture.appState.setAppInForeground(false)
            if (fixture.appState.activeAccountRef != "account-a") {
                fixture.appState.setActiveAccount(
                    label = "account-a",
                    preloadPolicy = AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST,
                )
            }
            fixture.appState.setConversationNotifyForMode("group-a", ChatNotifyMode.ALL)
            fixture.close()
        }
    }

    /** Pumps the paused main looper until a deterministic race checkpoint arrives. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(5_000L) {
            while (latch.count > 0L) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Pumps the paused main looper until the requested write count arrives. */
    private suspend fun awaitWrites(
        writes: AtomicInteger,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (writes.get() < expected) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

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
