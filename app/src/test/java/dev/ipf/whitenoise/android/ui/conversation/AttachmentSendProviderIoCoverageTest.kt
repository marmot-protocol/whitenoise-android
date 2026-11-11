package dev.ipf.whitenoise.android.ui.conversation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Looper
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.collectionLifecycleController
import dev.ipf.whitenoise.android.state.finishMediaFixture
import dev.ipf.whitenoise.android.state.mediaSendReconciliationAppState
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Staged-send preparation asks the picks' document provider for their MIME type (#3714). The send runs on Main, so
 * every provider getType it makes must run on a background thread while Main keeps running, through the real
 * [ConversationMediaSender.prepareStagedAttachments] seam that composer and collection sends share.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class AttachmentSendProviderIoCoverageTest {
    /** A prepared photo sends while a failed video pick picks the video notice; Main runs while getType is held. */
    @Test
    fun mixedAlbumClassifiesItsFailureOffMainWhileMainKeepsRunning() =
        providerFixture { sender ->
            BlockingTypeProvider.held += PHOTO
            val prepared = prepare(sender, listOf(slot(PHOTO), slot(VIDEO)), mapOf(PHOTO to photo()))

            awaitHeldGetType()
            var sentinelRan = false
            launch { sentinelRan = true }
            runCurrent()
            assertTrue("a Main sentinel runs while the provider is held", sentinelRan)
            assertTrue("the provider is still held when the sentinel runs", BlockingTypeProvider.inside.get())
            BlockingTypeProvider.release.countDown()

            val result = prepared.await()
            assertEquals(listOf("photo.jpg"), result.images.map { it.fileName })
            assertEquals(R.string.toast_couldnt_process_video, result.visualFailureToast)
            assertAllGetTypeOffMain()
        }

    /** A provider whose getType throws reads as an unknown type, so the image notice is chosen, still off Main. */
    @Test
    fun aThrowingProviderFallsBackToTheImageNoticeOffMain() =
        providerFixture { sender ->
            BlockingTypeProvider.failing += BROKEN
            val result = prepare(sender, listOf(slot(PHOTO), slot(BROKEN)), mapOf(PHOTO to photo())).await()

            assertEquals(listOf("photo.jpg"), result.images.map { it.fileName })
            assertEquals(R.string.toast_couldnt_decode_image, result.visualFailureToast)
            assertTrue(BlockingTypeProvider.callers.any { it.endsWith(BROKEN) })
            assertAllGetTypeOffMain()
        }

    /**
     * Cancelling the send while the provider is held ends preparation without a result once getType returns. The
     * failed video pick is what makes preparation classify, and so read the provider, at all.
     */
    @Test
    fun cancellingWhileTheProviderIsHeldEndsPreparation() =
        providerFixture { sender ->
            BlockingTypeProvider.held += PHOTO
            val prepared = prepare(sender, listOf(slot(PHOTO), slot(VIDEO)), mapOf(PHOTO to photo()))

            awaitHeldGetType()
            prepared.cancel()
            BlockingTypeProvider.release.countDown()
            prepared.join()

            assertTrue(prepared.isCancelled)
            assertAllGetTypeOffMain()
        }

    private fun TestScope.prepare(
        sender: ConversationMediaSender,
        slots: List<PendingMediaSlot>,
        prepared: Map<String, PendingAttachment>,
    ): Deferred<ConversationMediaSender.PreparedStagedAttachments> =
        async {
            sender.prepareStagedAttachments(
                imageSlots = slots,
                documentUris = emptyList(),
                preparedImageAttachments = prepared,
                preparedDocumentAttachments = emptyMap(),
                allowFileBacked = false,
                staging = FileBackedSendStaging(),
            )
        }

    /** Pumps Main until the held getType is entered; a getType on Main stalls here only for the bounded barrier. */
    private fun TestScope.awaitHeldGetType() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BARRIER_SECONDS)
        while (!BlockingTypeProvider.entered.await(POLL_MS, TimeUnit.MILLISECONDS)) {
            check(System.nanoTime() < deadline) { "the held getType was never reached" }
            runCurrent()
        }
    }

    private fun assertAllGetTypeOffMain() {
        val main = Looper.getMainLooper().thread.name
        assertTrue("preparation asked the provider for a type", BlockingTypeProvider.threads.isNotEmpty())
        assertFalse(
            "provider getType ran on Main: ${BlockingTypeProvider.threads}",
            BlockingTypeProvider.threads.any { it == main },
        )
    }

    private fun providerFixture(block: suspend TestScope.(ConversationMediaSender) -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            BlockingTypeProvider.reset()
            Robolectric.setupContentProvider(BlockingTypeProvider::class.java, AUTHORITY)
            val state = mediaSendReconciliationAppState()
            val controller = collectionLifecycleController(state)
            try {
                block(ConversationMediaSender(state, controller, state.appContext, fileBackedLimits = { null }) {})
            } finally {
                BlockingTypeProvider.release.countDown()
                finishMediaFixture(controller, state)
                Dispatchers.resetMain()
            }
        }

    private fun slot(name: String) = PendingMediaSlot(name, Uri.parse("content://$AUTHORITY/$name"))

    private fun photo() = PendingAttachment(ByteArray(PHOTO_BYTES) { 1 }, "image/jpeg", "photo.jpg")
}

/** A document provider whose getType can be held behind a bounded barrier or made to throw, recording its callers. */
class BlockingTypeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val name = uri.lastPathSegment.orEmpty()
        threads += Thread.currentThread().name
        callers += name
        if (name in failing) throw SecurityException("scripted provider failure")
        if (name in held) {
            inside.set(true)
            entered.countDown()
            release.await(BARRIER_SECONDS, TimeUnit.SECONDS)
            inside.set(false)
        }
        return if (name.endsWith(".mp4")) "video/mp4" else "image/jpeg"
    }

    /** Has no rows; the reader treats picks as unnamed and unsized. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    /** Read-only provider. */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    /** Read-only provider. */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** Read-only provider. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val held: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val failing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val callers: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val inside = AtomicBoolean()

        @Volatile var entered = CountDownLatch(1)

        @Volatile var release = CountDownLatch(1)

        fun reset() {
            held.clear()
            failing.clear()
            threads.clear()
            callers.clear()
            inside.set(false)
            entered = CountDownLatch(1)
            release = CountDownLatch(1)
        }
    }
}

private const val AUTHORITY = "dev.ipf.whitenoise.test.blockingtype"
private const val PHOTO = "photo.jpg"
private const val VIDEO = "clip.mp4"
private const val BROKEN = "broken.jpg"
private const val PHOTO_BYTES = 200
private const val BARRIER_SECONDS = 2L
private const val POLL_MS = 10L
