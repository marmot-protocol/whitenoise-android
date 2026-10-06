package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.marmotkit.MessageDraftFfi
import dev.ipf.marmotkit.MessageDraftSummaryFfi
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorStringStore
import dev.ipf.whitenoise.android.media.editor.MessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.media.editor.stagedPhotoAttachmentId
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerAttachmentShelf
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.composerVisualAttachmentWidth
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Exercises the real composer owner while a controlled native read returns a stale snapshot. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationDraftRestoreRemovalTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun nativeSendCleanupRemovesRestoredPhotosButPreservesANewPickerSelection() {
        var finalShelf: List<PendingMediaSlot> = emptyList()
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val photos = cleanupAttachments(group.groupIdHex)
                val gateway = RestoreGateway(MessageDraftFfi(group.groupIdHex, "caption", null, photos, 1L, 1L))
                val repository = MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher)
                val app = appForRestore(context, repository)
                val controller = ConversationController(appState = app, initialGroup = group)
                val owner =
                    ConversationMediaDraftState(
                        app,
                        controller,
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                val savedSlots =
                    listOf(
                        savedNativeSlots(photos).first(),
                        PendingMediaSlot("saved-picker", Uri.parse("content://picker/saved")),
                    )
                owner.updateInputs(savedSlots, listOf(Uri.parse("content://picker/document")), "account")
                val restored = requireNotNull(owner.restorePersistedAttachments())
                assertEquals(2, restored.mediaSlots.size)
                val newerPick =
                    PendingMediaSlot(
                        "new-picker-occurrence",
                        newPhotoUri(context),
                    )
                val pendingClear = requireNotNull(app.captureDraftForSend("account", group.groupIdHex))
                app.clearDraftAfterSuccessfulSend(pendingClear)
                advanceUntilIdle()
                assertNull(gateway.current)

                // A picker change before SideEffect must reject publication without committing it.
                assertNull(owner.restorePersistedAttachments { false })
                assertEquals(2, owner.preparedAttachments().size)
                assertEquals(1, owner.preparedDocumentAttachments().size)
                owner.updateInputs(restored.mediaSlots + newerPick, restored.documentUris, "account")
                val reconciled = requireNotNull(owner.restorePersistedAttachments { true })

                assertEquals(listOf(newerPick), reconciled.mediaSlots)
                finalShelf = reconciled.mediaSlots
                assertTrue(owner.preparedAttachments().isEmpty())
                assertTrue(reconciled.documentUris.isEmpty())
                assertTrue(owner.preparedDocumentAttachments().isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }
        captureReconciledShelf(finalShelf)
    }

    /** Native refresh must not shadow editable backing or relabel a freshly staged local source. */
    @Test
    fun cleanupProjectionKeepsFreshEditableAndPreparedSourcesLocallyOwned() {
        val group = conversationTimelineTestGroup()
        val editable =
            nativeAttachment(
                stagedPhotoAttachmentId("account", group.groupIdHex, "editable"),
                "edited.png",
                "image/png",
            )
        val fresh =
            nativeAttachment(stagedPhotoAttachmentId("account", group.groupIdHex, "fresh"), "fresh.png", "image/png")
        val restored = nativeAttachment("restored", "old.png", "image/png")
        val freshPrepared = DraftPreparedPhoto(fresh, "local-digest")
        val nativePhotos =
            nativePhotosNeedingRestoration(
                mapOf("editable" to editable, "fresh" to fresh, "restored" to restored),
                setOf("editable"),
                mapOf("fresh" to freshPrepared, "restored" to DraftPreparedPhoto(restored, "old-digest", true)),
            )
        assertEquals(setOf("restored"), nativePhotos.keys)
        assertTrue(!freshPrepared.restoredFromNative)

        val freshUri = Uri.parse("content://picker/fresh-document")
        val restoredUri = Uri.parse("content://picker/restored-document")
        val document = nativeAttachment("document", "document.pdf", "application/pdf")
        val nativeDocuments =
            nativeDocumentsNeedingRestoration(
                mapOf(freshUri.toString() to document, restoredUri.toString() to document),
                mapOf(freshUri to DraftPreparedPhoto(document, "fresh-digest")),
            )
        assertEquals(setOf(restoredUri), nativeDocuments.keys)
    }

    /** Covers native-id and saved picker-id photos plus a saved picker document. */
    private fun cleanupAttachments(groupIdHex: String): List<MessageDraftAttachmentFfi> =
        listOf(
            nativeAttachment("native-photo-1", "photo-1.jpg", "image/jpeg"),
            nativeAttachment(
                stagedPhotoAttachmentId("account", groupIdHex, "saved-picker"),
                "photo-2.jpg",
                "image/jpeg",
            ),
            nativeAttachment(
                stagedDocumentAttachmentId("account", groupIdHex, "content://picker/document"),
                "document.pdf",
                "application/pdf",
            ),
        )

    private fun nativeAttachment(
        id: String,
        name: String,
        mediaType: String,
    ): MessageDraftAttachmentFfi =
        MessageDraftAttachmentFfi(id, name, mediaType, byteArrayOf(1, 2, 3), null, null, null, emptyList())

    private fun newPhotoUri(context: Context): Uri {
        val image = File(context.cacheDir, "new-photo.png")
        val bitmap = Bitmap.createBitmap(160, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(64, 128, 144))
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(image)
    }

    /** The consumed album disappears while the new composition remains visible in both themes. */
    private fun captureReconciledShelf(slots: List<PendingMediaSlot>) {
        val accessible = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = accessible.value) {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, if (accessible.value) 2f else 1f),
                    LocalLayoutDirection provides if (accessible.value) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Surface(Modifier.width(360.dp)) {
                        ComposerAttachmentShelf(slots, emptyList(), emptyMap(), {}, {}, {})
                    }
                }
            }
        }
        val decodedPhotoWidth = composerVisualAttachmentWidth(160f / 100f)
        composeRule.waitUntil(5_000) {
            composeRule
                .onNodeWithTag("conversation.composer.attachment.0")
                .fetchSemanticsNode()
                .size.width == decodedPhotoWidth
        }
        composeRule
            .onNodeWithTag("conversation.composer.attachments")
            .captureRoboImage("src/test/snapshots/composer_after_native_cleanup_light.png")
        composeRule.runOnIdle { accessible.value = true }
        composeRule
            .onNodeWithTag("conversation.composer.attachments")
            .captureRoboImage("src/test/snapshots/composer_after_native_cleanup_dark_large_rtl.png")
    }

    /** Generic hydration and another chat's cleanup cannot restore attachments queued by this send. */
    @Test
    fun pendingMediaDoesNotReappearOnUnrelatedOrSupersededDraftChanges() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val attachment =
                    MessageDraftAttachmentFfi(
                        "native-photo", "photo.jpg", "image/jpeg", byteArrayOf(1, 2),
                        null, null, null, emptyList(),
                    )
                val gateway =
                    RestoreGateway(
                        MessageDraftFfi(group.groupIdHex, "caption", null, listOf(attachment), 1L, 1L),
                    )
                val app =
                    appForRestore(
                        context,
                        MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher),
                    )
                val owner =
                    ConversationMediaDraftState(
                        app, ConversationController(appState = app, initialGroup = group), context,
                        backgroundScope, PhotoEditorMessages("", "", "", ""),
                    )
                owner.updateInputs(
                    savedNativeSlots(requireNotNull(gateway.current).mediaAttachments),
                    emptyList(),
                    "account",
                )
                val restored = requireNotNull(owner.restorePersistedAttachments())
                val pendingClear = requireNotNull(app.captureDraftForSend("account", group.groupIdHex))
                owner.forgetAcceptedAttachments(restored.mediaSlots.map { it.id }.toSet(), emptySet())
                owner.updateInputs(emptyList(), emptyList(), "account")
                app.loadDraft("account", group.groupIdHex)
                val other = requireNotNull(app.captureDraftForSend("account", "bb".repeat(16)))
                app.clearDraftAfterSuccessfulSend(other)
                advanceUntilIdle()

                assertEquals(0L, app.nativeComposerCleanupRevision("account", group.groupIdHex))
                assertNull(owner.restorePersistedAttachments())
                assertTrue(owner.preparedAttachments().isEmpty())

                app.setDraft("account", group.groupIdHex, androidx.compose.ui.text.input.TextFieldValue("next message"))
                app.clearDraftAfterSuccessfulSend(pendingClear)
                advanceUntilIdle()
                assertEquals("next message", gateway.current?.content)
                assertEquals(0L, app.nativeComposerCleanupRevision("account", group.groupIdHex))
                assertNull(owner.restorePersistedAttachments())
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A failed authoritative read cannot erase restored bytes or newer picker input. */
    @Test
    fun failedNativeRefreshPreservesTheCurrentShelf() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val attachment =
                    MessageDraftAttachmentFfi(
                        "native-photo",
                        "photo.jpg",
                        "image/jpeg",
                        byteArrayOf(1, 2),
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                val gateway =
                    RestoreGateway(
                        MessageDraftFfi(group.groupIdHex, "caption", null, listOf(attachment), 1L, 1L),
                    )
                val app =
                    appForRestore(
                        context,
                        MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher),
                    )
                val owner =
                    ConversationMediaDraftState(
                        app,
                        ConversationController(appState = app, initialGroup = group),
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                owner.updateInputs(
                    savedNativeSlots(requireNotNull(gateway.current).mediaAttachments),
                    emptyList(),
                    "account",
                )
                val restored = requireNotNull(owner.restorePersistedAttachments())
                val newerPick =
                    PendingMediaSlot(
                        "new-picker",
                        Uri.parse("content://picker/new"),
                    )
                owner.updateInputs(restored.mediaSlots + newerPick, emptyList(), "account")
                val pendingClear = requireNotNull(app.captureDraftForSend("account", group.groupIdHex))
                app.clearDraftAfterSuccessfulSend(pendingClear)
                advanceUntilIdle()
                gateway.readFailure = IllegalStateException("native read unavailable")

                assertNull(owner.restorePersistedAttachments())
                assertEquals(setOf("native-photo"), owner.preparedAttachments().keys)
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Late native bytes from the previous account never populate the newly selected account. */
    @Test
    fun accountChangeDuringReadDiscardsTheOldSnapshot() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val attachment =
                    MessageDraftAttachmentFfi(
                        "native-photo",
                        "photo.jpg",
                        "image/jpeg",
                        byteArrayOf(1, 2),
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                val gateway =
                    RestoreGateway(
                        MessageDraftFfi(group.groupIdHex, "caption", null, listOf(attachment), 1L, 1L),
                    )
                val app =
                    appForRestore(
                        context,
                        MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher),
                    )
                val owner =
                    ConversationMediaDraftState(
                        app,
                        ConversationController(appState = app, initialGroup = group),
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                owner.updateInputs(
                    savedNativeSlots(requireNotNull(gateway.current).mediaAttachments),
                    emptyList(),
                    "account",
                )
                gateway.beforeReadReturns = { owner.updateInputs(emptyList(), emptyList(), "other-account") }

                assertNull(owner.restorePersistedAttachments())
                assertTrue(owner.preparedAttachments().isEmpty())
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Removing a saved URI during native restoration neither resurrects it nor retains its native bytes. */
    @Test
    fun removingSavedDocumentWhileDraftReadIsPendingRemovesNativeAttachment() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val group = conversationTimelineTestGroup()
                val uri = Uri.parse("content://picker/document/saved")
                val attachment =
                    MessageDraftAttachmentFfi(
                        stagedDocumentAttachmentId("account", group.groupIdHex, uri.toString()),
                        "document.pdf",
                        "application/pdf",
                        byteArrayOf(1, 2, 3),
                        null,
                        null,
                        null,
                        emptyList(),
                    )
                val gateway = RestoreGateway(MessageDraftFfi(group.groupIdHex, "", null, listOf(attachment), 1L, 1L))
                val repository = MessageDraftRepository(gateway, EditorSessionStore(EmptyStrings), dispatcher)
                val app = appForRestore(context, repository)
                val controller = ConversationController(appState = app, initialGroup = group)
                val owner =
                    ConversationMediaDraftState(
                        app,
                        controller,
                        context,
                        backgroundScope,
                        PhotoEditorMessages("", "", "", ""),
                    )
                owner.updateInputs(emptyList(), listOf(uri), "account")
                gateway.beforeReadReturns = {
                    owner.releasePreparedDocument(uri)
                    owner.updateInputs(emptyList(), emptyList(), "account")
                }

                val restored = owner.restorePersistedAttachments()
                advanceUntilIdle()

                assertTrue(requireNotNull(restored).documentUris.isEmpty())
                assertTrue(owner.preparedDocumentAttachments().isEmpty())
                assertNull(gateway.current)
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun appForRestore(
        context: Context,
        repository: MessageDraftRepository,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDrafts),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = "account",
            messageDraftRepository = repository,
        )

    /** Native-id slots model an already restored saveable shelf without relying on FileProvider shadows. */
    private fun savedNativeSlots(attachments: List<MessageDraftAttachmentFfi>): List<PendingMediaSlot> =
        attachments.map { PendingMediaSlot(it.id, Uri.parse("content://native.saved/${it.id}")) }

    /** Keeps editor bookkeeping isolated from the Android key store. */
    private object EmptyStrings : EditorStringStore {
        override fun readAll(): Map<String, String> = emptyMap()

        override fun replaceAll(values: Map<String, String>) = true

        override fun clear() = Unit
    }

    /** Keeps unrelated text draft storage entirely in memory. */
    private object EmptyDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}

/** Returns the captured native snapshot after a shelf action, then accepts exact draft cleanup. */
private class RestoreGateway(
    var current: MessageDraftFfi?,
) : MessageDraftGateway {
    var beforeReadReturns: (() -> Unit)? = null
    var readFailure: RuntimeException? = null

    /** Delivers one controlled stale read without replaying the removal callback during cleanup. */
    override fun read(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftFfi? {
        readFailure?.let { throw it }
        val snapshot = current?.takeIf { it.groupIdHex == groupIdHex }
        val action = beforeReadReturns
        beforeReadReturns = null
        action?.invoke()
        return snapshot
    }

    /** Preserves other native draft fields when only one attachment is removed. */
    override fun save(
        accountRef: String,
        groupIdHex: String,
        content: String,
        replyToMessageIdHex: String?,
        mediaAttachments: List<MessageDraftAttachmentFfi>,
    ): MessageDraftFfi =
        MessageDraftFfi(groupIdHex, content, replyToMessageIdHex, mediaAttachments, 1L, 1L)
            .also { current = it }

    /** Removes the empty native draft after its last attachment is explicitly discarded. */
    override fun delete(
        accountRef: String,
        groupIdHex: String,
    ) {
        if (current?.groupIdHex == groupIdHex) current = null
    }

    /** No chat-list background work is part of this restoration fixture. */
    override fun summaries(accountRef: String): List<MessageDraftSummaryFfi> = emptyList()
}
