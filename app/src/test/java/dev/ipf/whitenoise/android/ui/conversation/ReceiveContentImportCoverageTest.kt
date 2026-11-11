package dev.ipf.whitenoise.android.ui.conversation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * #3715: a composer image drop never blocks the main thread on the source app's provider, keeps the combined
 * attachment limit and never stages a result after its conversation lost ownership.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceiveContentImportCoverageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = BlockingImageProvider()
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val pasteDir = File(context.cacheDir, "composer_paste")
    private val discarded = Collections.synchronizedList(mutableListOf<Uri>())
    private val delivered = mutableListOf<ComposerImageDropResult>()
    private val dropImport =
        ComposerImageDropImport(
            scope = scope,
            resolveMime = { uri -> safeGetType(context.contentResolver, uri) },
            materialize = { uri, mime ->
                materializeReceiveContentImageUri(context, uri, mime, isAppOwnedProvider = { false })
            },
            discard = { copy ->
                discarded += copy
                pasteDir.listFiles { file -> file.name == copy.lastPathSegment }?.forEach(File::delete)
            },
        )

    init {
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
    }

    /** Every barrier opens and every import is cancelled, so a failed assertion cannot strand an IO thread. */
    @After
    fun releaseBarriers() {
        provider.typeGate.countDown()
        provider.openGate.countDown()
        scope.cancel()
        ShadowContentResolver.reset()
        pasteDir.deleteRecursively()
    }

    @Test
    fun providerTypeAndStreamRunOffMainWhileMainKeepsRunning() {
        provider.typeGate = CountDownLatch(1)

        importDrop(listOf(dropUri(1)))
        assertTrue("getType never started", provider.typeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))

        // The provider is still blocked, yet the main thread runs other work and the composer reports the import.
        var sentinelRan = false
        Handler(Looper.getMainLooper()).post { sentinelRan = true }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(sentinelRan)
        assertTrue(dropImport.isImporting)
        assertTrue(delivered.isEmpty())

        provider.typeGate.countDown()
        pumpMainUntil { !dropImport.isImporting }

        val mainThread = Looper.getMainLooper().thread
        assertNotEquals(mainThread, requireNotNull(provider.typeThread))
        assertNotEquals(mainThread, requireNotNull(provider.openThread))
        val result = delivered.single()
        assertEquals(1, result.localUris.size)
        assertEquals(0, result.unreadableCount)
        assertEquals(listOf(IMAGE_BYTES.toList()), pasteDir.listFiles().orEmpty().map { it.readBytes().toList() })
    }

    @Test
    fun lateResultAfterChatChangeIsDroppedAndItsCopyDeleted() {
        provider.openGate = CountDownLatch(1)
        var current = true

        importDrop(listOf(dropUri(1)), isCurrent = { current })
        assertTrue("copy never started", provider.openEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        current = false
        provider.openGate.countDown()
        pumpMainUntil { !dropImport.isImporting }

        assertTrue(delivered.isEmpty())
        assertEquals(1, discarded.size)
        assertTrue(pasteDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun cancellingWithTheScreenStagesNothing() {
        provider.typeGate = CountDownLatch(1)

        importDrop(listOf(dropUri(1)))
        assertTrue("getType never started", provider.typeEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        dropImport.cancel()
        provider.typeGate.countDown()
        pumpMainUntil { !dropImport.isImporting }

        assertTrue(delivered.isEmpty())
        assertNull(provider.openThread)
        assertTrue(pasteDir.listFiles().isNullOrEmpty())
    }

    @Test
    fun dropAdmitsOnlyWhatFitsTheCombinedLimit() {
        importDrop((1..3).map(::dropUri), maxItems = 3, stagedCount = { 2 })
        pumpMainUntil { !dropImport.isImporting }

        val result = delivered.single()
        assertEquals(listOf(dropUri(1)), result.admission.accepted)
        assertEquals(2, result.admission.droppedCount)
        assertEquals(1, result.localUris.size)
    }

    @Test
    fun concurrentDropsShareTheLimitThroughReservations() {
        provider.openGate = CountDownLatch(1)

        importDrop(listOf(dropUri(1), dropUri(2)), maxItems = 2)
        assertTrue("copy never started", provider.openEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        importDrop(listOf(dropUri(3)), maxItems = 2)
        pumpMainUntil { delivered.isNotEmpty() }

        // The second drop is refused while the first still holds both slots.
        val refused = delivered.single()
        assertTrue(refused.localUris.isEmpty())
        assertEquals(1, refused.admission.droppedCount)

        provider.openGate.countDown()
        pumpMainUntil { !dropImport.isImporting }
        assertEquals(2, delivered.sumOf { it.localUris.size })
    }

    @Test
    fun shelfFilledDuringCopyTrimsTheResultAndDeletesTheExtraCopy() {
        provider.openGate = CountDownLatch(1)
        var staged = 0

        importDrop(listOf(dropUri(1), dropUri(2)), maxItems = 2, stagedCount = { staged })
        assertTrue("copy never started", provider.openEntered.await(WAIT_SECONDS, TimeUnit.SECONDS))
        staged = 1
        provider.openGate.countDown()
        pumpMainUntil { !dropImport.isImporting }

        val result = delivered.single()
        assertEquals(1, result.localUris.size)
        assertEquals(1, result.admission.accepted.size)
        assertEquals(1, result.admission.droppedCount)
        assertEquals(1, discarded.size)
        assertEquals(1, pasteDir.listFiles().orEmpty().size)
    }

    /** The synchronous receive-content decision must not reach a provider; the screen must not copy inline. */
    @Test
    fun receiverAndScreenKeepProviderIpcOffTheCallback() {
        val pills =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/composer/ComposerPills.kt")
        val receiver = pills.functionBody("onReceive")
        listOf("safeGetType", "contentResolver", "openInputStream", "getType").forEach { call ->
            assertFalse("receive-content listener calls $call", call in receiver)
        }
        val screen = appModuleSource("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt")
        assertFalse(screen.contains("materializeReceiveContentImageUri"))
        assertTrue(screen.contains("imageDropImport.import("))
    }

    private fun importDrop(
        uris: List<Uri>,
        maxItems: Int = 10,
        stagedCount: () -> Int = { 0 },
        isCurrent: () -> Boolean = { true },
    ) = dropImport.import(uris, maxItems, stagedCount, isCurrent) { delivered += it }

    private fun pumpMainUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.nanoTime() < deadline) { "condition not reached within $WAIT_SECONDS s" }
            Thread.sleep(5)
        }
    }

    private fun dropUri(index: Int): Uri = Uri.parse("content://$AUTHORITY/images/$index")

    /** A source app's provider whose type lookup and stream open can be held at a bounded barrier. */
    private inner class BlockingImageProvider : ContentProvider() {
        @Volatile var typeGate = CountDownLatch(0)

        @Volatile var openGate = CountDownLatch(0)
        val typeEntered = CountDownLatch(1)
        val openEntered = CountDownLatch(1)

        @Volatile var typeThread: Thread? = null

        @Volatile var openThread: Thread? = null

        override fun onCreate() = true

        override fun getType(uri: Uri): String {
            typeThread = Thread.currentThread()
            typeEntered.countDown()
            typeGate.await(WAIT_SECONDS, TimeUnit.SECONDS)
            return "image/png"
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            openThread = Thread.currentThread()
            openEntered.countDown()
            openGate.await(WAIT_SECONDS, TimeUnit.SECONDS)
            val filesDir = checkNotNull(context).filesDir
            val source = File.createTempFile("drop_source", ".png", filesDir).apply { writeBytes(IMAGE_BYTES) }
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).also { source.delete() }
        }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0
    }

    private companion object {
        const val AUTHORITY = "dev.test.dropsource"
        const val WAIT_SECONDS = 5L
        val IMAGE_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    }
}
