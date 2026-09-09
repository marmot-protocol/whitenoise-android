package dev.ipf.whitenoise.android.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.Trace
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.NotificationFirstPostTimingEvent
import dev.ipf.whitenoise.android.state.NotificationFirstPostTimingStage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Device evidence for notification timing issues #1995 and #2453.
 *
 * The measured interval ends at NotificationListenerService delivery. It does
 * not prove when a physical vibration began or when SystemUI rendered pixels;
 * correlate the emitted trace sections with the platform/external capture in
 * docs/notification-haptic-visual-timing.md. The #2453 scenarios additionally
 * verify callback payload order and silent same-key correction semantics.
 */
@RunWith(AndroidJUnit4::class)
class NotificationHapticVisualTimingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val listener = ComponentName(context, NotificationTimingListenerService::class.java)
    private var listenerProvisioned = false

    /** Provisions emulator notification access while preserving permission state on physical devices. */
    @Before
    fun provisionNotificationAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED &&
                shell("getprop ro.kernel.qemu").trim() == "1"
            ) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
            assertEquals(
                "Grant notification permission on the selected test device before running; the test preserves it",
                PackageManager.PERMISSION_GRANTED,
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS),
            )
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        // A pre-granted listener may stay bound across methods. Its callbacks own this flag;
        // requestRebind does not guarantee a new onListenerConnected callback for an existing binding.
        if (!notificationManager.isNotificationListenerAccessGranted(listener)) {
            NotificationTimingDeviceEvents.listenerConnected = false
            listenerProvisioned = true
            shell("cmd notification allow_listener ${listener.flattenToString()}")
        }
        NotificationListenerService.requestRebind(listener)
        assertTrue(
            "Debug notification listener did not connect; verify notification-listener access on the device",
            waitUntil(LISTENER_CONNECT_TIMEOUT_MS) { NotificationTimingDeviceEvents.listenerConnected },
        )
    }

    /** Preserves pre-existing listener access and removes only this fixture's grant. */
    @After
    fun revokeNotificationAccess() {
        NotificationTimingDeviceEvents.clear()
        if (listenerProvisioned) {
            shell("cmd notification disallow_listener ${listener.flattenToString()}")
            listenerProvisioned = false
        }
    }

    /** Measures the framework notify-to-listener boundary for external trace correlation. */
    @Test
    fun recordsNotifyAndListenerPostForExternalHapticVisualCorrelation() {
        val update = update()
        val expected = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationTimingDeviceEvents.arm(context.packageName, expected.tag, expected.id)
        val probe = TimingProbe()
        val presenter = timingPresenter(probe)
        presenter.ensureChannels()

        val preparationStartedNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("WN notification preparation")
        val posted =
            try {
                runBlocking {
                    presenter.show(
                        update = update,
                        shortNpub = { "npub1timing" },
                    )
                }
            } finally {
                Trace.endSection()
            }
        try {
            val listenerPost =
                checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)) {
                    "Notification listener did not observe the timing probe"
                }
            assertTrue(posted)
            assertEquals(expected.tag, listenerPost.tag)
            assertEquals(expected.id, listenerPost.id)
            assertTrue(probe.notifyElapsedRealtimeNanos >= preparationStartedNanos)
            assertTrue(listenerPost.elapsedRealtimeNanos >= probe.notifyElapsedRealtimeNanos)
            assertTrue(
                "notify-to-listener delivery exceeded the evidence ceiling",
                listenerPost.elapsedRealtimeNanos - probe.notifyElapsedRealtimeNanos <=
                    TimeUnit.MILLISECONDS.toNanos(MAX_NOTIFY_TO_LISTENER_MS),
            )
            assertNotNull(probe.postedNotification)
            reportTiming(preparationStartedNanos, probe.notifyElapsedRealtimeNanos, listenerPost)
        } finally {
            NotificationManagerCompat.from(context).cancel(expected.tag, expected.id)
        }
    }

    /** Proves a fallback replacement keeps the platform key and suppresses a second alert. */
    @Test
    fun recordsOneSilentSameKeyCorrectionAfterFallback() {
        val update = update()
        val expected = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationTimingDeviceEvents.arm(
            packageName = context.packageName,
            notificationTag = expected.tag,
            notificationId = expected.id,
            fallbackContent = "**fallback notification body**",
            resolvedContent = "Resolved notification body",
        )
        val presenter = timingPresenter(TimingProbe())
        presenter.ensureChannels()

        try {
            assertTrue(
                runBlocking {
                    presenter.show(
                        update = update,
                        previewTextOverride = "**fallback notification body**",
                        shortNpub = { "npub1timing" },
                    )
                },
            )
            val first =
                checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)) {
                    "Notification listener did not observe the fallback post"
                }

            assertTrue(
                runBlocking {
                    presenter.show(
                        update = update,
                        previewTextOverride = "Resolved notification body",
                        silentUpdate = true,
                        replaceCurrentMessage = true,
                        shortNpub = { "npub1timing" },
                    )
                },
            )
            val corrected =
                checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)) {
                    "Notification listener did not observe the silent correction"
                }

            assertEquals(expected.tag, first.tag)
            assertEquals(expected.id, first.id)
            assertEquals(expected.tag, corrected.tag)
            assertEquals(expected.id, corrected.id)
            assertEquals(NotificationTimingContentRevision.Fallback, first.contentRevision)
            assertEquals(NotificationTimingContentRevision.Resolved, corrected.contentRevision)
            assertFalse(first.onlyAlertOnce)
            assertTrue(corrected.onlyAlertOnce)
        } finally {
            NotificationManagerCompat.from(context).cancel(expected.tag, expected.id)
        }
    }

    /** Sends a typed Markdown mention through AppState and expects one resolved first callback. */
    @Test
    fun recordsResolvedMarkdownMentionOnTheFirstAndOnlyListenerPost() {
        val raw = "**hello nostr:$MENTION_NPUB**"
        val resolved = "hello @Alice"
        val update =
            update().copy(
                groupName = null,
                isDm = true,
                previewText = raw,
                sender = user(MENTION_ACCOUNT_ID_HEX, displayName = ""),
            )
        val expected = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        NotificationTimingDeviceEvents.arm(
            packageName = context.packageName,
            notificationTag = expected.tag,
            notificationId = expected.id,
            fallbackContent = raw,
            resolvedContent = resolved,
        )
        val timingEvents = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
        val appState = timingAppState(update, timingEvents::add)
        LocalNotificationPresenter(context).ensureChannels()

        try {
            runBlocking { appState.processNotificationUpdateForTest(update) }
            val first =
                checkNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS)) {
                    "Notification listener did not observe the resolved first post"
                }
            reportFirstPostTiming(timingEvents)
            val received = timingEvents.single { it.stage == NotificationFirstPostTimingStage.Received }
            val eligibility = timingEvents.single { it.stage == NotificationFirstPostTimingStage.EligibilityComplete }
            val contentComplete = timingEvents.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
            val notifyWritten = timingEvents.single { it.stage == NotificationFirstPostTimingStage.NotifyWritten }

            assertEquals("resolved_before_deadline", contentComplete.outcome)
            assertTrue(contentComplete.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS)
            assertTrue(eligibility.observedAtElapsedRealtimeNanos >= received.observedAtElapsedRealtimeNanos)
            assertTrue(contentComplete.observedAtElapsedRealtimeNanos >= eligibility.observedAtElapsedRealtimeNanos)
            assertTrue(notifyWritten.observedAtElapsedRealtimeNanos >= contentComplete.observedAtElapsedRealtimeNanos)
            assertTrue(first.elapsedRealtimeNanos >= contentComplete.observedAtElapsedRealtimeNanos)
            assertEquals(expected.tag, first.tag)
            assertEquals(expected.id, first.id)
            assertEquals(NotificationTimingContentRevision.Resolved, first.contentRevision)
            assertEquals(resolved, first.contentText)
            assertFalse(first.contentText.orEmpty().contains("**"))
            assertFalse(first.contentText.orEmpty().contains(MENTION_NPUB))
            assertFalse(first.onlyAlertOnce)
            assertNull(NotificationTimingDeviceEvents.awaitPost(NO_ADDITIONAL_POST_WINDOW_MS))
        } finally {
            NotificationManagerCompat.from(context).cancel(expected.tag, expected.id)
        }
    }

    /** Holds local resolution through fallback delivery, then verifies one silent correction. */
    @Test
    fun recordsOneSilentCorrectionAfterAppStateContentTimeout() {
        val resolverEntered = CountDownLatch(1)
        val releaseResolver = CountDownLatch(1)
        val resolverCompleted = AtomicBoolean(false)
        try {
            assertAppStateFallbackCorrection(
                expectedOutcome = "timeout_fallback",
                raw = "**resolved after fallback**",
                resolved = "resolved after fallback",
                firstSenderRead = {
                    resolverEntered.countDown()
                    try {
                        check(releaseResolver.await(RESOLVER_BLOCK_SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                            "fallback listener callback did not release the held identity read"
                        }
                        "Alice"
                    } finally {
                        resolverCompleted.set(true)
                    }
                },
                onFallbackDelivered = {
                    assertEquals("the held resolver must enter before fallback", 0L, resolverEntered.count)
                    assertFalse(
                        "fallback must be delivered before the held resolver completes",
                        resolverCompleted.get(),
                    )
                    releaseResolver.countDown()
                },
            )
        } finally {
            releaseResolver.countDown()
        }
    }

    /** Exercises the typed resolver-failure path and its single silent correction on device. */
    @Test
    fun recordsOneSilentCorrectionAfterAppStateContentFailure() {
        val identityReads = AtomicInteger(0)
        assertAppStateFallbackCorrection(
            expectedOutcome = "failed_fallback",
            raw = "**hello nostr:$MENTION_NPUB**",
            resolved = "hello @Alice",
            accountIdHexResolver = { bech32 ->
                check(bech32 == MENTION_NPUB)
                if (identityReads.incrementAndGet() == 1) {
                    error("synthetic local mention failure")
                }
                MENTION_ACCOUNT_ID_HEX
            },
        )
    }

    /** Verifies fallback outcome/order, same-key replacement, and the two-callback ceiling. */
    private fun assertAppStateFallbackCorrection(
        expectedOutcome: String,
        raw: String,
        resolved: String,
        firstSenderRead: () -> String? = { "Alice" },
        onFallbackDelivered: () -> Unit = {},
        accountIdHexResolver: suspend (String) -> String? = { bech32 ->
            MENTION_ACCOUNT_ID_HEX.takeIf { bech32 == MENTION_NPUB }
        },
    ) {
        val update =
            update().copy(
                groupName = null,
                isDm = true,
                previewText = raw,
                sender = user(MENTION_ACCOUNT_ID_HEX, displayName = ""),
            )
        val expected = LocalNotificationFormatter.conversationDismissalKey(update.accountRef, update.groupIdHex)
        val senderReads = AtomicInteger(0)
        val timingEvents = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
        val appState =
            timingAppState(
                update = update,
                timingObserver = timingEvents::add,
                accountIdHexResolver = accountIdHexResolver,
                displayName = { accountIdHex ->
                    if (accountIdHex != MENTION_ACCOUNT_ID_HEX) {
                        "Me"
                    } else if (senderReads.incrementAndGet() == 1) {
                        firstSenderRead()
                    } else {
                        "Alice"
                    }
                },
            )
        NotificationTimingDeviceEvents.arm(context.packageName, expected.tag, expected.id, raw, resolved)
        LocalNotificationPresenter(context).ensureChannels()

        try {
            runBlocking { appState.processNotificationUpdateForTest(update) }
            val fallback = requireNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS))
            reportFirstPostTiming(timingEvents)
            onFallbackDelivered()
            val corrected = requireNotNull(NotificationTimingDeviceEvents.awaitPost(LISTENER_POST_TIMEOUT_MS))

            assertFirstPostTimingOrder(expectedOutcome, timingEvents, fallback)
            assertEquals(expected.tag, fallback.tag)
            assertEquals(expected.id, fallback.id)
            assertEquals(expected.tag, corrected.tag)
            assertEquals(expected.id, corrected.id)
            assertEquals(NotificationTimingContentRevision.Fallback, fallback.contentRevision)
            assertFalse(fallback.onlyAlertOnce)
            assertEquals(NotificationTimingContentRevision.Resolved, corrected.contentRevision)
            assertTrue(corrected.onlyAlertOnce)
            assertNull(NotificationTimingDeviceEvents.awaitPost(NO_ADDITIONAL_POST_WINDOW_MS))
        } finally {
            NotificationManagerCompat.from(context).cancel(expected.tag, expected.id)
        }
    }

    /** Requires the measured content-stage ceiling for every outcome, including held local work. */
    private fun assertFirstPostTimingOrder(
        expectedOutcome: String,
        events: List<NotificationFirstPostTimingEvent>,
        fallback: NotificationTimingListenerPost,
    ) {
        val received = events.single { it.stage == NotificationFirstPostTimingStage.Received }
        val eligibility = events.single { it.stage == NotificationFirstPostTimingStage.EligibilityComplete }
        val contentComplete = events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
        val notifyWritten = events.single { it.stage == NotificationFirstPostTimingStage.NotifyWritten }
        val timingTrace = "first-post timing=$events"
        assertEquals(timingTrace, expectedOutcome, contentComplete.outcome)
        assertTrue(
            timingTrace,
            contentComplete.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS,
        )
        assertTrue(eligibility.observedAtElapsedRealtimeNanos >= received.observedAtElapsedRealtimeNanos)
        assertTrue(contentComplete.observedAtElapsedRealtimeNanos >= eligibility.observedAtElapsedRealtimeNanos)
        assertTrue(notifyWritten.observedAtElapsedRealtimeNanos >= contentComplete.observedAtElapsedRealtimeNanos)
        assertTrue(fallback.elapsedRealtimeNanos >= contentComplete.observedAtElapsedRealtimeNanos)
    }

    /** Builds a platform-free AppState around the synthetic local MDK identity boundary. */
    private fun timingAppState(
        update: NotificationUpdateFfi,
        timingObserver: (NotificationFirstPostTimingEvent) -> Unit,
        accountIdHexResolver: suspend (String) -> String? = { bech32 ->
            MENTION_ACCOUNT_ID_HEX.takeIf { bech32 == MENTION_NPUB }
        },
        displayName: (String) -> String? = { accountIdHex ->
            if (accountIdHex == update.accountIdHex) "Me" else "Alice"
        },
    ): WhiteNoiseAppState {
        val marmot = notificationMarmot(update, displayName)
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = accountIdHexResolver,
            accounts = listOf(signingAccount(update.accountRef, update.accountIdHex)),
            activeAccountRef = update.accountRef,
            initialMarmotRuntime = AppMarmotRuntime(rootPath = "notification-device-test", marmot = marmot),
            notificationFirstPostTimingObserver = timingObserver,
        )
    }

    /** Fakes only the local typed-update calls used by the device acceptance path. */
    private fun notificationMarmot(
        update: NotificationUpdateFfi,
        displayName: (String) -> String?,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "chatNotificationSettings" ->
                    ChatNotificationSettingsFfi(
                        accountRef = update.accountRef,
                        accountIdHex = update.accountIdHex,
                        groupIdHex = update.groupIdHex,
                        muted = false,
                        mutedUntilMs = null,
                        updatedAtMs = 0L,
                    )
                "displayName" -> displayName(arguments?.firstOrNull() as String)
                "parseMarkdown" -> markdownDocument(arguments?.firstOrNull() as? String)
                "timelineMessages" -> TimelinePageFfi(emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                "userProfile" -> null
                "npub" -> MENTION_NPUB
                "chatList", "groupMemberIdsPage", "groupMembers" -> emptyList<Any>()
                "toString" -> "NotificationDeviceMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    /** Projects the fixture Markdown into the same strong/mention token shape as MDK. */
    private fun markdownDocument(raw: String?): MarkdownDocumentFfi {
        val source = raw.orEmpty()
        val strongContent = source.removePrefix("**").removeSuffix("**")
        val inlines =
            if (strongContent == "hello nostr:$MENTION_NPUB") {
                listOf(
                    MarkdownInlineFfi.Text("hello "),
                    MarkdownInlineFfi.NostrMention(
                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, MENTION_NPUB),
                    ),
                )
            } else {
                listOf(MarkdownInlineFfi.Text(strongContent))
            }
        return MarkdownDocumentFfi(
            truncated = false,
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Strong(
                                inlines,
                            ),
                        ),
                    ),
                ),
            blankLinesBefore = ByteArray(0),
        )
    }

    /** Creates the active local-signing account required by notification ownership checks. */
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

    /** Captures the exact NotificationManager write boundary while preserving real posting. */
    private fun timingPresenter(probe: TimingProbe): LocalNotificationPresenter =
        LocalNotificationPresenter(
            context = context,
            shortcutPublisher = { },
            notificationPoster = { manager, tag, id, notification ->
                Trace.beginSection("WN notification notify")
                try {
                    probe.notifyElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    probe.postedNotification = notification
                    manager.notify(tag, id, notification)
                } finally {
                    Trace.endSection()
                }
            },
        )

    /** Emits aggregate, PII-free device timing values to instrumentation output. */
    private fun reportTiming(
        preparationStartedNanos: Long,
        notifyElapsedRealtimeNanos: Long,
        listenerPost: NotificationTimingListenerPost,
    ) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                putLong(
                    "notification_preparation_ms",
                    TimeUnit.NANOSECONDS.toMillis(notifyElapsedRealtimeNanos - preparationStartedNanos),
                )
                putLong(
                    "notify_to_listener_post_ms",
                    TimeUnit.NANOSECONDS.toMillis(
                        listenerPost.elapsedRealtimeNanos - notifyElapsedRealtimeNanos,
                    ),
                )
                putString(
                    "measurement_scope",
                    "App preparation and framework-listener delivery only; not physical haptic or rendered pixels",
                )
            },
        )
    }

    /** Emits only fixed-stage, fixed-outcome first-post timings before device assertions run. */
    private fun reportFirstPostTiming(events: List<NotificationFirstPostTimingEvent>) {
        instrumentation.sendStatus(
            0,
            Bundle().apply {
                events.forEach { event ->
                    val prefix = "first_post_${event.stage.name.lowercase()}"
                    putLong("${prefix}_elapsed_ms", event.elapsedSinceReceiptMillis)
                    putLong("${prefix}_stage_ms", event.stageElapsedMillis ?: -1L)
                    putString("${prefix}_outcome", event.outcome)
                }
            },
        )
    }

    private data class TimingProbe(
        var notifyElapsedRealtimeNanos: Long = Long.MIN_VALUE,
        var postedNotification: Notification? = null,
    )

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** Returns a stable typed update whose identity and body are synthetic. */
    private fun update(): NotificationUpdateFfi =
        NotificationUpdateFfi(
            notificationKey = "timing-device-test",
            conversationKey = "timing-conversation",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "timing-account",
            accountIdHex = "timing-account",
            groupIdHex = "timing-group",
            groupName = "Timing group",
            isDm = false,
            isMention = false,
            messageIdHex = "timing-message",
            sender = user("timing-sender", "Timing sender"),
            receiver = user("timing-receiver", "Timing receiver"),
            previewText = "Notification timing probe",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = System.currentTimeMillis(),
            isFromSelf = false,
        )

    /** Builds one synthetic notification participant without an avatar dependency. */
    private fun user(
        accountIdHex: String,
        displayName: String,
    ): NotificationUserFfi =
        NotificationUserFfi(
            accountIdHex = accountIdHex,
            displayName = displayName,
            pictureUrl = null,
        )

    /** Runs a bounded fixture shell command and returns its UTF-8 output. */
    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(command),
            ).use { output ->
                output.readBytes().toString(Charsets.UTF_8)
            }

    /** Polls a device-owned readiness condition up to a fixed deadline. */
    private fun waitUntil(
        timeoutMillis: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private companion object {
        // Instrumentation can kill an already-bound process; allow Android's 10-second rebind delay.
        const val LISTENER_CONNECT_TIMEOUT_MS = 15_000L
        const val LISTENER_POST_TIMEOUT_MS = 5_000L
        const val NO_ADDITIONAL_POST_WINDOW_MS = 500L
        const val MAX_NOTIFY_TO_LISTENER_MS = 2_000L
        const val FIRST_POST_CONTENT_DEADLINE_MS = 100L
        const val RESOLVER_BLOCK_SAFETY_TIMEOUT_MS = 10_000L
        const val MENTION_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        const val MENTION_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
