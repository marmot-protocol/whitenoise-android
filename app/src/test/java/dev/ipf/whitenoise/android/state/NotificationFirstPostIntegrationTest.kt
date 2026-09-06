package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationCompat
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownNostrEntityFfi
import dev.ipf.marmotkit.MarkdownNostrHrpFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.notifications.ConversationCardBarrier
import dev.ipf.whitenoise.android.notifications.ConversationCardOp
import dev.ipf.whitenoise.android.notifications.ConversationCardPostSynchronizer
import dev.ipf.whitenoise.android.notifications.ConversationCardTestHook
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One cancellation race applied to the production fixture and typed update. */
private typealias NotificationCancellationAction =
    suspend (NotificationBootstrapTestFixture, NotificationUpdateFfi) -> Unit

/** End-to-end typed-update coverage for the #2453 first-post and one-correction contract. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Suppress("LargeClass") // Typed first-post and correction scenarios share one process-global notification fixture.
class NotificationFirstPostIntegrationTest {
    private val context: Application = RuntimeEnvironment.getApplication()

    /** Resets platform cards and avatar state before each production-path scenario. */
    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        cancelMessageCard()
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
    }

    /** Releases synchronizer hooks and any notification state retained by Robolectric. */
    @After
    fun tearDown() {
        ConversationCardPostSynchronizer.testHook = null
        AvatarImageLoader.clear()
        AvatarImageLoader.resetProfileImageFetcherForTests()
        cancelMessageCard()
    }

    /** Proves typed Markdown and a known mention are complete on the sole first write. */
    @Test
    fun typedMarkdownMentionIsResolvedOnTheOnlyFirstWriteWithinTheDeadline() =
        runBlocking {
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    previewText = "**hello nostr:$MENTION_NPUB**",
                    accountIdHexResolver = { bech32 ->
                        MENTION_ACCOUNT_ID_HEX.takeIf { bech32 == MENTION_NPUB }
                    },
                    markdownDocumentFactory = ::mentionMarkdown,
                    notificationFirstPostTimingObserver = events::add,
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    val contentEvent = events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
                    assertEquals("resolved_before_deadline", contentEvent.outcome)
                    assertTrue(contentEvent.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS)
                    assertEquals("hello @Alice", fixture.activeNotification().contentText())
                    assertFalse(fixture.activeNotification().contentText().contains("**"))
                    assertFalse(fixture.activeNotification().contentText().contains(MENTION_NPUB))
                    assertEquals(1, writes.get())
                }
            } finally {
                fixture.close()
            }
        }

    /** A warm presentation cache must satisfy sender identity without another MDK read. */
    @Test
    fun warmProfilePresentationOwnsTheFirstSenderName() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = null,
                    onDisplayName = { _, _ -> error("warm profile presentation must avoid a cold MDK read") },
                )
            fixture.appState.applyAccountSwitchProfileSeed(
                AccountSwitchProfileSeed(
                    accountIdHex = fixture.update.sender.accountIdHex,
                    profile = null,
                    displayName = "Cached Alice",
                    avatarUrl = null,
                ),
            )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    assertEquals(
                        "Cached Alice",
                        fixture
                            .activeMessagingStyle()
                            .messages
                            .single()
                            .person
                            ?.name,
                    )
                    assertEquals(0, fixture.senderDisplayNameCalls.get())
                    assertEquals(1, writes.get())
                }
            } finally {
                fixture.close()
            }
        }

    /** A cold presentation cache must still use the bounded persisted-name reader. */
    @Test
    fun coldProfilePresentationResolvesBeforeTheFirstWrite() =
        runBlocking {
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    localDisplayName = "Persisted Alice",
                    notificationFirstPostTimingObserver = events::add,
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    val contentEvent = events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
                    assertEquals("resolved_before_deadline", contentEvent.outcome)
                    assertTrue(contentEvent.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS)
                    assertEquals(
                        "Persisted Alice",
                        fixture
                            .activeMessagingStyle()
                            .messages
                            .single()
                            .person
                            ?.name,
                    )
                    assertTrue(fixture.senderDisplayNameCalls.get() >= 1)
                    assertEquals(1, writes.get())
                }
            } finally {
                fixture.close()
            }
        }

    /** Prefers a persisted local name over an untrusted active-account payload hint. */
    @Test
    fun coldPersistedNamePrecedesTheNotificationPayloadHint() =
        runBlocking {
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = true,
                    localDisplayName = "Persisted Alice",
                    accounts =
                        listOf(
                            AccountSummaryFfi(
                                label = ACCOUNT_REF,
                                accountIdHex = "self",
                                localSigning = true,
                                externalSigning = false,
                                signedOut = false,
                                running = true,
                            ),
                        ),
                )
            try {
                fixture.bootstrap()
                fixture.awaitNotificationPosted()

                assertEquals(
                    "Persisted Alice",
                    fixture
                        .activeMessagingStyle()
                        .messages
                        .single()
                        .person
                        ?.name,
                )
                assertTrue(fixture.senderDisplayNameCalls.get() >= 1)
            } finally {
                fixture.close()
            }
        }

    /** Covers captioned and captionless media precedence through the real resolver. */
    @Test
    fun firstPostMediaMatrixUsesCaptionBeforeTypeAndTypeBeforeGenericFallback() =
        runBlocking {
            assertFirstBody(
                preview = "**release notes**",
                timeline = timelineRecord(tags = imageTags()),
                expected = "release notes",
            )
            val captionlessRecord = messageRecord(tags = imageTags())
            assertEquals(ReplyMediaKind.Photo, MessageProjector.mediaKind(captionlessRecord))
            assertFirstBody(
                preview = "",
                timeline = timelineRecord(tags = imageTags()),
                messageRecords = listOf(captionlessRecord),
                expected = "sent a picture",
            )
        }

    /** Requires structured localized group-system text instead of raw event JSON. */
    @Test
    fun firstPostUsesStructuredGroupSystemTextAndNeverTheRawPayload() =
        runBlocking {
            val raw = "{\"v\":1,\"system_type\":\"group_avatar_changed\"}"
            val system =
                GroupSystemEventFfi(
                    systemType = "group_avatar_changed",
                    text = "Group avatar changed",
                    actorAccountIdHex = MENTION_ACCOUNT_ID_HEX,
                    subjectAccountIdHex = null,
                    name = null,
                    oldName = null,
                    oldRetentionSeconds = null,
                    newRetentionSeconds = null,
                )
            assertFirstBody(
                preview = raw,
                timeline = timelineRecord(kind = 1210uL, direction = "system", groupSystem = system, plaintext = raw),
                expected = "Alice changed the group avatar",
            )
        }

    /** Uses the generic safe body when Markdown projection contains no visible text. */
    @Test
    fun emptyMarkdownFirstPostUsesTheSafeGenericBody() =
        runBlocking {
            assertFirstBody(
                preview = "",
                timeline = timelineRecord(),
                expected = "New message",
                markdownDocumentFactory = { emptyMarkdown() },
            )
        }

    /** Carries a proven cached avatar across eviction without a hidden third write. */
    @Test
    fun cachedAvatarIsCarriedAcrossEvictionInExactlyOneSilentCorrection() =
        runBlocking {
            val avatar = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            AvatarImageLoader.putCached(AVATAR_URL, avatar.asImageBitmap())
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    isDm = true,
                    senderPictureUrl = AVATAR_URL,
                )
            try {
                withNotificationWriteCount(
                    beforeSecondWrite = { AvatarImageLoader.clear() },
                ) { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    awaitWrites(writes, 2)
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    assertNotNull(
                        fixture
                            .activeMessagingStyle()
                            .messages
                            .single()
                            .person
                            ?.icon,
                    )
                    assertEquals(2, writes.get())
                }
            } finally {
                fixture.close()
            }
        }

    /** Starts cold remote avatar download only after the bounded first platform write. */
    @Test
    fun coldRemoteAvatarWorkStartsOnlyAfterTheBoundedFirstWrite() =
        runBlocking {
            val releaseFetch = CountDownLatch(1)
            val fetchStarted = CountDownLatch(1)
            val writesAtFetch = AtomicInteger(-1)
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            lateinit var observedWrites: AtomicInteger
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    isDm = true,
                    senderPictureUrl = AVATAR_URL,
                    notificationFirstPostTimingObserver = events::add,
                    profileImageDownload = { _, _ ->
                        writesAtFetch.compareAndSet(-1, observedWrites.get())
                        fetchStarted.countDown()
                        releaseFetch.await(5, TimeUnit.SECONDS)
                        byteArrayOf(0)
                    },
                )
            fixture.appState.applyAccountSwitchProfileSeed(
                AccountSwitchProfileSeed(
                    accountIdHex = fixture.update.sender.accountIdHex,
                    profile = null,
                    displayName = "Cached Alice",
                    avatarUrl = null,
                ),
            )
            try {
                withNotificationWriteCount { writes ->
                    observedWrites = writes
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    awaitLatch(fetchStarted)

                    val contentEvent = events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }
                    assertTrue(
                        "cold avatar work must not outlive the same bounded content outcome",
                        contentEvent.outcome in setOf("resolved_before_deadline", "timeout_fallback"),
                    )
                    assertTrue(contentEvent.stageElapsedMillis in 0L..FIRST_POST_CONTENT_DEADLINE_MS)
                    val expectedWritesBeforeAvatar =
                        if (contentEvent.outcome == "resolved_before_deadline") 1 else 2
                    assertEquals(expectedWritesBeforeAvatar, writesAtFetch.get())
                    assertEquals(expectedWritesBeforeAvatar, writes.get())
                }
            } finally {
                releaseFetch.countDown()
                fixture.close()
            }
        }

    /** Gives a timed-out content correction priority over an already-ready avatar. */
    @Test
    fun timedOutContentBeatsReadyAvatarAndConsumesTheOnlyCorrection() =
        runBlocking {
            val releaseFirstRead = CountDownLatch(1)
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            AvatarImageLoader.putCached(
                AVATAR_URL,
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap(),
            )
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    isDm = true,
                    senderPictureUrl = AVATAR_URL,
                    previewText = "**resolved text**",
                    notificationFirstPostTimingObserver = events::add,
                    onDisplayName = { call, _ ->
                        if (call == 1) releaseFirstRead.await(5, TimeUnit.SECONDS)
                        "Alice"
                    },
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    assertEquals(
                        "timeout_fallback",
                        events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }.outcome,
                    )
                    releaseFirstRead.countDown()
                    fixture.awaitNotificationBody("resolved text")
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    assertEquals(2, writes.get())
                }
            } finally {
                releaseFirstRead.countDown()
                fixture.close()
            }
        }

    /** Converts a local resolver failure into one fallback and one silent correction. */
    @Test
    fun failedFirstContentPostsFallbackThenOneSilentCorrection() =
        runBlocking {
            val releaseLateRead = CountDownLatch(1)
            val identityReads = AtomicInteger(0)
            val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
            val raw = "**hello nostr:$MENTION_NPUB**"
            val fixture =
                NotificationBootstrapTestFixture(
                    context = context,
                    notificationUsersHaveDisplayNames = false,
                    previewText = raw,
                    markdownDocumentFactory = ::mentionMarkdown,
                    notificationFirstPostTimingObserver = events::add,
                    accountIdHexResolver = { bech32 ->
                        check(bech32 == MENTION_NPUB)
                        if (identityReads.incrementAndGet() == 1) {
                            error("synthetic local mention failure")
                        }
                        releaseLateRead.await(5, TimeUnit.SECONDS)
                        MENTION_ACCOUNT_ID_HEX
                    },
                )
            try {
                withNotificationWriteCount { writes ->
                    fixture.bootstrap()
                    fixture.awaitNotificationPosted()
                    assertEquals(
                        "failed_fallback",
                        events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }.outcome,
                    )
                    assertEquals(raw, fixture.activeNotification().contentText())
                    releaseLateRead.countDown()
                    fixture.awaitNotificationBody("hello @Alice")
                    delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                    assertTrue(fixture.activeNotification().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
                    assertEquals(2, writes.get())
                }
            } finally {
                releaseLateRead.countDown()
                fixture.close()
            }
        }

    /** Prevents a registered late correction from resurrecting a dismissed card. */
    @Test
    fun userDismissalCannotBeUndoneByARegisteredLateCorrection() =
        runBlocking {
            assertRegisteredCorrectionCannotResurrect { fixture, _ ->
                fixture.appState.dismissConversationNotifications(ACCOUNT_REF, GROUP_ID)
            }
        }

    /** Prevents a registered late correction from resurrecting a mark-read card. */
    @Test
    fun markReadCancellationCannotBeUndoneByARegisteredLateCorrection() =
        runBlocking {
            assertRegisteredCorrectionCannotResurrect { fixture, update ->
                assertTrue(
                    fixture.appState.markNotificationMessageRead(
                        ACCOUNT_REF,
                        GROUP_ID,
                        update.messageIdHex.orEmpty(),
                    ),
                )
                LocalNotificationPresenter(context).dismissActionNotificationAndOlderSiblings(
                    notificationTag = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID).tag,
                    notificationId = LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID,
                    actedMessageIdHex = update.messageIdHex,
                    accountRef = ACCOUNT_REF,
                    groupIdHex = GROUP_ID,
                    sinceMs = System.currentTimeMillis(),
                )
            }
        }

    /** Runs one typed content fixture and requires exactly one resolved platform write. */
    private suspend fun assertFirstBody(
        preview: String,
        timeline: TimelineMessageRecordFfi,
        expected: String,
        markdownDocumentFactory: ((String?) -> MarkdownDocumentFfi)? = null,
        messageRecords: List<AppMessageRecordFfi> = emptyList(),
    ) {
        val events = CopyOnWriteArrayList<NotificationFirstPostTimingEvent>()
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                previewText = preview,
                timelinePage = timelinePage(timeline),
                messageRecords = messageRecords,
                markdownDocumentFactory = markdownDocumentFactory,
                notificationFirstPostTimingObserver = events::add,
            )
        try {
            withNotificationWriteCount { writes ->
                fixture.bootstrap()
                fixture.awaitNotificationPosted()
                delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

                assertEquals(
                    "resolved_before_deadline",
                    events.single { it.stage == NotificationFirstPostTimingStage.ContentComplete }.outcome,
                )
                if (messageRecords.isNotEmpty()) {
                    assertTrue(
                        "captionless media must use the production history lookup",
                        fixture.notificationMessageHistoryCalls.get() > 0,
                    )
                }
                assertEquals(expected, fixture.activeNotification().contentText())
                assertEquals(1, writes.get())
            }
        } finally {
            fixture.close()
            cancelMessageCard()
        }
    }

    /** Pauses a correction after registration so a cancellation can win deterministically. */
    private suspend fun assertRegisteredCorrectionCannotResurrect(action: NotificationCancellationAction) {
        val releaseFirstRead = CountDownLatch(1)
        val correctionRegistered = CountDownLatch(1)
        val releaseCorrection = CountDownLatch(1)
        val writes = AtomicInteger(0)
        val registrations = AtomicInteger(0)
        val fixture =
            NotificationBootstrapTestFixture(
                context = context,
                previewText = "**resolved after fallback**",
                messageIdHex = MESSAGE_ID_HEX,
                onDisplayName = { _, _ ->
                    releaseFirstRead.await(5, TimeUnit.SECONDS)
                    "Alice"
                },
            )
        ConversationCardPostSynchronizer.testHook =
            correctionRegistrationBarrier(registrations, writes, correctionRegistered, releaseCorrection)
        try {
            fixture.bootstrap()
            fixture.awaitNotificationPosted()
            awaitWrites(writes, 1)
            releaseFirstRead.countDown()
            awaitLatch(correctionRegistered)

            action(fixture, fixture.update)
            releaseCorrection.countDown()
            delay(NO_ADDITIONAL_WRITE_WINDOW_MS)

            assertNull(activeNotificationOrNull())
            assertEquals(1, writes.get())
        } finally {
            releaseFirstRead.countDown()
            releaseCorrection.countDown()
            ConversationCardPostSynchronizer.testHook = null
            fixture.close()
        }
    }

    /** Builds the synchronization hook that exposes late-registration and write boundaries. */
    private fun correctionRegistrationBarrier(
        registrations: AtomicInteger,
        writes: AtomicInteger,
        correctionRegistered: CountDownLatch,
        releaseCorrection: CountDownLatch,
    ): ConversationCardTestHook =
        object : ConversationCardTestHook {
            override fun onBarrier(
                op: ConversationCardOp,
                barrier: ConversationCardBarrier,
                notificationTag: String,
                notificationId: Int,
            ) {
                if (op != ConversationCardOp.SHOW_NOTIFY) return
                if (barrier == ConversationCardBarrier.AFTER_REGISTER && registrations.incrementAndGet() == 2) {
                    correctionRegistered.countDown()
                    check(releaseCorrection.await(5, TimeUnit.SECONDS))
                }
                if (barrier == ConversationCardBarrier.AFTER_WRITE) writes.incrementAndGet()
            }
        }

    /** Counts successful serialized writes and optionally evicts state before replacement. */
    private suspend fun withNotificationWriteCount(
        beforeSecondWrite: () -> Unit = {},
        block: suspend (AtomicInteger) -> Unit,
    ) {
        val writes = AtomicInteger(0)
        val cleared = AtomicBoolean(false)
        ConversationCardPostSynchronizer.testHook =
            object : ConversationCardTestHook {
                override fun onBarrier(
                    op: ConversationCardOp,
                    barrier: ConversationCardBarrier,
                    notificationTag: String,
                    notificationId: Int,
                ) {
                    if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.BEFORE_WRITE) {
                        if (writes.get() == 1 && cleared.compareAndSet(false, true)) beforeSecondWrite()
                    }
                    if (op == ConversationCardOp.SHOW_NOTIFY && barrier == ConversationCardBarrier.AFTER_WRITE) {
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

    /** Produces a strong-text plus NIP-19 mention tree for projector parity. */
    private fun mentionMarkdown(raw: String?): MarkdownDocumentFfi {
        check(raw == "**hello nostr:$MENTION_NPUB**")
        return MarkdownDocumentFfi(
            truncated = false,
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        listOf(
                            MarkdownInlineFfi.Strong(
                                listOf(
                                    MarkdownInlineFfi.Text("hello "),
                                    MarkdownInlineFfi.NostrMention(
                                        MarkdownNostrEntityFfi(MarkdownNostrHrpFfi.NPUB, MENTION_NPUB),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            blankLinesBefore = ByteArray(0),
        )
    }

    /** Creates one authoritative timeline row for system/media content projection. */
    private fun timelineRecord(
        kind: ULong = 9uL,
        direction: String = "received",
        groupSystem: GroupSystemEventFfi? = null,
        plaintext: String = "",
        tags: List<MessageTagFfi> = emptyList(),
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = "message-a",
            sourceMessageIdHex = null,
            direction = direction,
            groupIdHex = GROUP_ID,
            sender = MENTION_ACCOUNT_ID_HEX,
            plaintext = plaintext,
            contentTokens = emptyMarkdown(),
            kind = kind,
            tags = tags,
            timelineAt = 1uL,
            receivedAt = 1uL,
            replyToMessageIdHex = null,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = groupSystem,
            reactions = TimelineReactionSummaryFfi(emptyList(), emptyList()),
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
        )

    /** Creates one history record used by captionless-media fallback detection. */
    private fun messageRecord(tags: List<MessageTagFfi>): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = "message-a",
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = MENTION_ACCOUNT_ID_HEX,
            plaintext = "",
            contentTokens = emptyMarkdown(),
            kind = 9uL,
            tags = tags,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    /** Returns a valid Markdown document with no visible content. */
    private fun emptyMarkdown(): MarkdownDocumentFfi =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = ByteArray(0),
        )

    /** Wraps one authoritative row in an exhausted local timeline page. */
    private fun timelinePage(record: TimelineMessageRecordFfi): TimelinePageFfi =
        TimelinePageFfi(messages = listOf(record), hasMoreBefore = false, hasMoreAfter = false)

    /** Returns the canonical image imeta tag used by media classification. */
    private fun imageTags(): List<MessageTagFfi> =
        listOf(
            MessageTagFfi(
                listOf("imeta", "m image/png", "filename photo.png"),
            ),
        )

    /** Requires the fixture's conversation card to be active. */
    private fun NotificationBootstrapTestFixture.activeNotification(): Notification =
        requireNotNull(
            activeNotificationOrNull(),
        )

    /** Requires MessagingStyle from the fixture's active conversation card. */
    private fun NotificationBootstrapTestFixture.activeMessagingStyle(): NotificationCompat.MessagingStyle =
        requireNotNull(
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(activeNotification()),
        )

    /** Reads the user-visible notification body as plain text. */
    private fun Notification.contentText(): String =
        extras
            .getCharSequence(Notification.EXTRA_TEXT)
            ?.toString()
            .orEmpty()

    /** Finds the issue fixture's stable platform card key without inspecting other cards. */
    private fun activeNotificationOrNull(): Notification? =
        context
            .getSystemService(NotificationManager::class.java)
            .activeNotifications
            .firstOrNull { it.tag == "$ACCOUNT_REF|$GROUP_ID" }
            ?.notification

    /** Removes only the synthetic issue fixture's conversation card. */
    private fun cancelMessageCard() {
        val key = LocalNotificationFormatter.conversationDismissalKey(ACCOUNT_REF, GROUP_ID)
        context.getSystemService(NotificationManager::class.java).cancel(key.tag, key.id)
    }

    /** Pumps Robolectric main until the expected serialized-write count arrives. */
    private suspend fun awaitWrites(
        writes: AtomicInteger,
        expected: Int,
    ) {
        withTimeout(5_000L) {
            while (writes.get() < expected) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Pumps Robolectric main while waiting for a deterministic race checkpoint. */
    private suspend fun awaitLatch(latch: CountDownLatch) {
        withTimeout(5_000L) {
            while (latch.count > 0L) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    private companion object {
        const val ACCOUNT_REF = "account-a"
        const val GROUP_ID = "group-a"
        val MESSAGE_ID_HEX = "ab".repeat(32)
        const val AVATAR_URL = "https://profiles.example/alice.png"
        const val MENTION_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
        const val MENTION_ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val FIRST_POST_CONTENT_DEADLINE_MS = 100L
        const val NO_ADDITIONAL_WRITE_WINDOW_MS = 250L
    }
}
