package dev.ipf.whitenoise.android.ui.profile

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.ipf.whitenoise.android.share.QrShareCardSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.ByteArrayOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ProfileQrGallerySaveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val spec =
        QrShareCardSpec(
            "Message me on White Noise",
            "marmot://profile/npub1${"q".repeat(58)}?from=qr",
            "Ada",
        )

    @After
    fun resetResolver() = ShadowContentResolver.reset()

    /** Decode the actual MediaStore output, including an avatar, instead of checking a callback payload. */
    @Test
    fun publishedGalleryPngContainsTheProfileQrAndAvatar() =
        runBlocking {
            val provider = GalleryProvider(context)
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
            val avatar = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try {
                assertEquals(true, ProfileQrGallerySave().save(context, spec.copy(avatar = avatar), { true }))
                assertEquals(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, provider.collection)
                assertEquals("image/png", provider.values?.getAsString(MediaStore.Images.Media.MIME_TYPE))
                assertEquals(
                    "Pictures/White Noise",
                    provider.values?.getAsString(MediaStore.Images.Media.RELATIVE_PATH),
                )
                assertEquals(1, provider.values?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
                assertEquals(0, provider.completed?.getAsInteger(MediaStore.Images.Media.IS_PENDING))
                val bytes = provider.output.toByteArray()
                val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertEquals(1440, bitmap.width)
                    assertEquals(1620, bitmap.height)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                    assertEquals(spec.qrPayload, MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
                    assertTrue(pixels.any { it == Color.RED })
                    assertFalse(avatar.isRecycled)
                } finally {
                    bitmap.recycle()
                }
            } finally {
                avatar.recycle()
            }
        }

    /** Only the first tap owns the suspended render; losing the origin prevents gallery insertion. */
    @Test
    fun duplicateTapsAndReplacedOriginDoNotSave() =
        runTest {
            val pending = CompletableDeferred<ByteArray>()
            val action = ProfileQrGallerySave()
            var current = true
            var writes = 0
            val persist: suspend (ByteArray) -> Boolean = {
                writes++
                true
            }
            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    action.save(context, spec, { current }, { pending.await() }, persist)
                }
            assertTrue(action.inProgress)
            assertNull(action.save(context, spec, { current }, { error("duplicate render") }, persist))
            current = false
            pending.complete(byteArrayOf(1))
            assertNull(first.await())
            assertEquals(0, writes)
            assertFalse(action.inProgress)
        }

    /** Revocation while the storage dispatcher is queued must still prevent the irreversible insert. */
    @Test
    fun queuedStorageRechecksOwnershipBeforeInsertion() =
        runTest {
            val provider = GalleryProvider(context)
            ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
            val action = ProfileQrGallerySave(StandardTestDispatcher(testScheduler))
            var current = true
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    action.save(context, spec, { current }, { byteArrayOf(1) })
                }
            assertTrue(action.inProgress)
            current = false
            assertNull(result.await())
            assertNull(provider.collection)
            assertFalse(action.inProgress)
        }

    /** Cancelled rendering releases the busy guard and does not turn cancellation into save feedback. */
    @Test
    fun cancellationAndFailedWritesReleaseTheAction() =
        runTest {
            val pending = CompletableDeferred<ByteArray>()
            val action = ProfileQrGallerySave()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    action.save(context, spec, { true }, { pending.await() }, { error("cancelled save") })
                }
            job.cancelAndJoin()
            assertFalse(action.inProgress)
            assertEquals(false, action.save(context, spec, { true }, { byteArrayOf(1) }, { false }))
            assertFalse(action.inProgress)
            val failed =
                runCatching {
                    action.save(context, spec, { true }, { byteArrayOf(1) }, { throw IOException("full") })
                }
            assertTrue(failed.exceptionOrNull() is IOException)
            assertFalse(action.inProgress)
            assertEquals(true, action.save(context, spec, { true }, { byteArrayOf(1) }, { true }))
        }

    /** A completed write can survive dismissal, but its old sheet must not report completion in a new account. */
    @Test
    fun dismissedDuringStorageSuppressesStaleFeedback() =
        runTest {
            val action = ProfileQrGallerySave()
            var current = true
            assertNull(
                action.save(context, spec, { current }, { byteArrayOf(1) }, {
                    current = false
                    true
                }),
            )
            assertFalse(action.inProgress)
        }

    private class GalleryProvider(
        private val testContext: Context,
    ) : ContentProvider() {
        var collection: Uri? = null
        var values: ContentValues? = null
        var completed: ContentValues? = null
        val output = ByteArrayOutputStream()

        override fun onCreate() = true

        override fun getType(uri: Uri): String? = null

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
        ): Uri {
            collection = uri
            this.values = values?.let(::ContentValues)
            val destination = Uri.withAppendedPath(uri, "1")
            shadowOf(testContext.contentResolver).registerOutputStreamSupplier(destination) { output }
            return destination
        }

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            completed = values?.let(::ContentValues)
            return 1
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 1
    }
}
