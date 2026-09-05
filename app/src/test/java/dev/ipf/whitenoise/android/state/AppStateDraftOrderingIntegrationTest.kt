package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class AppStateDraftOrderingIntegrationTest {
    /** Verifies a save acknowledgement re-sorts by the accepted update time, not draft creation. */
    @Test
    fun writerAcknowledgementResortsFromUpdatedAtRatherThanCreatedAt() {
        val drafts = DraftStore(OrderingDraftPersistence, nowSeconds = { 100L })
        val gateway = DraftOrderingGateway(saveUpdatedAtMs = 250_000L)
        val appState = appState(drafts, repository(gateway))
        val chats = chats(appState)

        assertEquals(listOf(OTHER_GROUP, GROUP), chats.items.map { it.id })

        appState.setDraft(ACCOUNT_A, GROUP, TextFieldValue("edited"))
        assertEquals(listOf(OTHER_GROUP, GROUP), chats.items.map { it.id })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))

        assertEquals(250uL, drafts.draftedAtSecondsFor(ACCOUNT_A, GROUP))
        assertEquals(listOf(GROUP, OTHER_GROUP), chats.items.map { it.id })
        chats.onCleared()
    }

    /** Verifies only the newest account-fenced summary request can publish after an A-B-A race. */
    @Test
    fun latestAccountFencedSummaryRefreshWinsAcrossDelayedABACompletions() {
        val drafts = DraftStore(OrderingDraftPersistence, nowSeconds = { 100L })
        val gateway = DelayedSummaryGateway()
        val dispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        try {
            val appState = appState(drafts, repository(gateway, dispatcher))

            appState.refreshDraftSummaries(ACCOUNT_A)
            gateway.awaitStarted(0)
            appState.refreshDraftSummaries(ACCOUNT_B)
            gateway.awaitStarted(1)
            appState.refreshDraftSummaries(ACCOUNT_A)
            gateway.awaitStarted(2)

            gateway.release(2)
            awaitCondition { drafts.draftedAtSecondsFor(ACCOUNT_A, GROUP) == 900uL }
            gateway.release(0)
            gateway.release(1)
            awaitCondition { gateway.completed.get() == 3 }

            assertEquals(900uL, drafts.draftedAtSecondsFor(ACCOUNT_A, GROUP))
            assertNull(drafts.draftedAtSecondsFor(ACCOUNT_B, OTHER_GROUP))
        } finally {
            gateway.releaseAll()
            dispatcher.close()
        }
    }

    /** Builds a visible two-row projection whose ordering changes can be asserted synchronously. */
    private fun chats(appState: WhiteNoiseAppState): ChatsController =
        ChatsController(
            appState = appState,
            initialAccountRef = ACCOUNT_A,
            memberSnapshotLoader = { _, _ -> emptyList() },
        ).also { chats ->
            appState.attachChatsController(chats)
            chats.setChatListVisible(false)
            chats.applyChatListRow(row(GROUP, 50uL))
            chats.applyChatListRow(row(OTHER_GROUP, 200uL))
            chats.setChatListVisible(true)
        }

    /** Creates a projected row with one authoritative activity timestamp. */
    private fun row(
        groupIdHex: String,
        timelineAt: ULong,
    ) = notificationChatListRow().copy(
        groupIdHex = groupIdHex,
        title = groupIdHex,
        groupName = groupIdHex,
        lastMessage =
            notifiedMessagePreview().copy(
                messageIdHex = groupIdHex,
                plaintext = groupIdHex,
                timelineAt = timelineAt,
            ),
        activitySortAt = timelineAt,
        updatedAt = timelineAt,
    )

    /** Drains Robolectric's main looper until asynchronous draft publication reaches [condition]. */
    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** Creates an app state with injectable draft storage and repository timing. */
    private fun appState(
        drafts: DraftStore,
        repository: MessageDraftRepository,
    ) = WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext<Context>(),
        draftStore = drafts,
        accountIdHexResolver = { ACCOUNT_ID },
        accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B)),
        activeAccountRef = ACCOUNT_A,
        messageDraftRepository = repository,
    )

    /** Creates a running local account fixture with a stable identity across account labels. */
    private fun account(label: String) =
        AccountSummaryFfi(
            label = label,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    /** Wraps [gateway] with the dispatcher that controls summary completion order. */
    private fun repository(
        gateway: MessageDraftGateway,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ) = MessageDraftRepository(
        gateway = gateway,
        editorSessions = EditorSessionStore(EmptyEditorStrings),
        ioDispatcher = dispatcher,
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GROUP = "group-a"
        const val OTHER_GROUP = "group-b"
        val ACCOUNT_ID = "a1".repeat(32)
    }
}

/** Draft gateway whose saves deliberately distinguish creation time from update time. */
private open class DraftOrderingGateway(
    private val saveUpdatedAtMs: Long = 250_000L,
) : MessageDraftGateway {
    /** Returns no pre-existing draft so tests own all local state transitions. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? = null

    /** Returns a persisted draft stamped with [saveUpdatedAtMs]. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ) = MessageDraftFfi(
        groupIdHex = groupIdHex,
        content = content,
        replyToMessageIdHex = replyToMessageIdHex,
        mediaAttachments = mediaAttachments,
        createdAtMs = 1_000L,
        updatedAtMs = saveUpdatedAtMs,
    )

    /** Accepts cleanup because deletion behavior is outside these ordering tests. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) = Unit

    /** Supplies no summaries in save-acknowledgement scenarios. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}

/** Three-request gateway that exposes deterministic completion order for A-B-A races. */
private class DelayedSummaryGateway : DraftOrderingGateway() {
    private val next = AtomicInteger()
    private val started = List(3) { CountDownLatch(1) }
    private val releases = List(3) { CountDownLatch(1) }
    val completed = AtomicInteger()

    /** Blocks each request independently, then returns the timestamp assigned to its start order. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> {
        val index = next.getAndIncrement()
        started[index].countDown()
        check(releases[index].await(5, TimeUnit.SECONDS)) { "summary release timed out" }
        completed.incrementAndGet()
        val groupId = if (accountRef == "account-b") "group-b" else "group-a"
        val updatedAt =
            if (index == 2) {
                900_000L
            } else if (index == 1) {
                800_000L
            } else {
                300_000L
            }
        return listOf(
            MessageDraftSummaryFfi(
                groupIdHex = groupId,
                content = "metadata-only",
                replyToMessageIdHex = null,
                mediaAttachments = emptyList(),
                createdAtMs = 1_000L,
                updatedAtMs = updatedAt,
            ),
        )
    }

    /** Waits until request [index] has captured its position in the race. */
    fun awaitStarted(index: Int) = check(started[index].await(5, TimeUnit.SECONDS)) { "summary did not start" }

    /** Releases exactly one delayed summary request. */
    fun release(index: Int) = releases[index].countDown()

    /** Unblocks every request during cleanup so a failed assertion cannot strand worker threads. */
    fun releaseAll() = releases.forEach(CountDownLatch::countDown)
}

/** No-op lifecycle persistence used because MDK owns durable draft state in production. */
private object OrderingDraftPersistence : DraftPersistence {
    /** Starts each integration test without legacy lifecycle drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Ignores lifecycle writes while leaving in-memory Compose state observable. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

/** Empty encrypted editor-session store for metadata-only draft tests. */
private object EmptyEditorStrings : EditorStringStore {
    /** Returns no retained attachment sessions. */
    override fun readAll(): Map<String, String> = emptyMap()

    /** Accepts replacement because no attachment session data participates in these tests. */
    override fun replaceAll(values: Map<String, String>): Boolean = true

    /** Clears the already-empty fixture. */
    override fun clear() = Unit
}
