package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.conversation.collectionOwnedDraftIds
import dev.ipf.whitenoise.android.ui.conversation.discardCollection
import dev.ipf.whitenoise.android.ui.profile.personTestGroup
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins which native draft attachments a restored composer shelf must not offer again: the draft holds a send's
 * attachments until MDK accepts it, and offering them meanwhile lets one pick be sent twice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SendOwnedDraftIdsTest {
    private val group = personTestGroup("group", "Team", members = null).group
    private val app = appState()

    /** Stops the app state's background work. */
    @After
    fun tearDown() {
        app.mutationsScope.cancel()
    }

    /** A send still uploading, or failed with its Retry, hides its stored file and inline rows from the shelf. */
    @Test
    fun activeSendHidesItsDraftAttachments() {
        retain("msg:one", stored("clip"), inline("photo-1"))
        app.activeUploadKeys("acct", group.groupIdHex) += "msg:one"

        assertEquals(setOf("clip", "photo-1"), app.collectionOwnedDraftIds("acct", group.groupIdHex))
        assertTrue(app.collectionOwnedDraftIds("other", group.groupIdHex).isEmpty())
    }

    /** A reply keeps its picks on the shelf until it is accepted, and a send no longer active hides nothing. */
    @Test
    fun repliesAndSettledSendsHideNothing() {
        retain("msg:reply", inline("photo-2"), replyTarget = "target")
        retain("msg:settled", stored("old"))
        app.activeUploadKeys("acct", group.groupIdHex) += "msg:reply"

        assertTrue(app.collectionOwnedDraftIds("acct", group.groupIdHex).isEmpty())
    }

    /** Discarding a send returns what the draft still holds to the shelf and tells that chat's composer to read. */
    @Test
    fun discardedSendReturnsItsAttachmentsToTheShelf() {
        val controller = ConversationController(appState = app, initialGroup = group)
        retain("msg:one", stored("clip"))
        app.activeUploadKeys("acct", group.groupIdHex) += "msg:one"
        val before = app.nativeComposerCleanupRevision("acct", group.groupIdHex)

        controller.discardOptimisticSend("msg:one", "one", OptimisticSendDiscard.CANCELLED)

        assertTrue(app.collectionOwnedDraftIds("acct", group.groupIdHex).isEmpty())
        assertTrue(app.nativeComposerCleanupRevision("acct", group.groupIdHex) > before)
    }

    /** A collection the user discards stops hiding its unsent originals and refreshes the shelf it hid them from. */
    @Test
    fun discardedCollectionReturnsItsOriginalsToTheShelf() {
        val item =
            CollectionItem(
                "pick",
                "content://fixture/file",
                CollectionItemKind.DOCUMENT,
                storedDraft = CollectionStoredDraft("waiting", 900L, "application/pdf"),
            )
        val chunk = CollectionChunk("chunk", 0, listOf("pick"), true)
        val groupId = group.groupIdHex
        val operation = AttachmentCollectionOperation("op", "acct", groupId, listOf(item), listOf(chunk), 1L, 1L)
        val registry = app.attachmentCollections
        assertTrue(registry.start(operation, registry.admissions.begin("acct", group.groupIdHex)) { true })
        assertEquals(setOf("waiting"), app.collectionOwnedDraftIds("acct", group.groupIdHex))
        val before = app.nativeComposerCleanupRevision("acct", group.groupIdHex)

        app.discardCollection("op")
        awaitAttachmentCollectionJournal()

        assertTrue(app.collectionOwnedDraftIds("acct", group.groupIdHex).isEmpty())
        assertTrue(app.nativeComposerCleanupRevision("acct", group.groupIdHex) > before)
    }

    /** Retains a send of [attachments] under [key], as a queued composer send does. */
    private fun retain(
        key: String,
        vararg attachments: PendingAttachment,
        replyTarget: String? = null,
    ) {
        val upload = RetainedMediaUpload(attachments.toList(), caption = null, replyTarget = replyTarget)
        app.retainedMediaUploads("acct", group.groupIdHex).put(key, upload)
    }

    /** A pick the native draft stores as a file under [id]. */
    private fun stored(id: String): PendingAttachment {
        val file = DraftStoredFile(id, 900L)
        return PendingAttachment(ByteArray(0), "video/mp4", "$id.mp4", draftFile = file)
    }

    /** A pick the native draft holds inline under [id]. */
    private fun inline(id: String): PendingAttachment {
        val bytes = byteArrayOf(1)
        return PendingAttachment(bytes, "image/jpeg", "$id.jpg", nativeDraftAttachmentId = id)
    }

    /** A real app state over a native boundary that refuses every call this test does not expect. */
    private fun appState(): WhiteNoiseAppState {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val runtime = AppMarmotRuntime("test", nativeBoundary { method, _ -> error("unexpected native call $method") })
        return WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore.forContext(context),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "acct",
            initialMarmotRuntime = runtime,
            marmotRuntimeFactory = { runtime },
        )
    }
}
