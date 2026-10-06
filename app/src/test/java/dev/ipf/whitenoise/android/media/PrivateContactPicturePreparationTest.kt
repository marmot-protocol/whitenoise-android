package dev.ipf.whitenoise.android.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** Selected local pixels are oriented, cropped, bounded and stripped without any upload adapter. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PrivateContactPicturePreparationTest {
    @Test fun cropSelectsTheChosenSquareAndBoundsTheSavedPng() =
        runBlocking {
            val image = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
            for (x in 0 until image.width) {
                for (y in 0 until image.height) {
                    image.setPixel(
                        x,
                        y,
                        if (x < 800) Color.RED else Color.BLUE,
                    )
                }
            }
            val output = ByteArrayOutputStream()
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
            val bytes = output.toByteArray()
            image.recycle()
            val prepared = preparePrivateContactPicture(bytes, IdentityImageCrop(focusX = 1f, zoom = 2f))
            val decoded = BitmapFactory.decodeByteArray(prepared, 0, prepared.size)
            assertTrue(decoded.width <= PRIVATE_CONTACT_PICTURE_EDGE)
            assertEquals(decoded.width, decoded.height)
            val center = decoded.getPixel(decoded.width / 2, decoded.height / 2)
            assertTrue(Color.blue(center) > 200 && Color.red(center) < 40)
            decoded.recycle()
        }

    @Test fun orientationIsAppliedAndExifLocationDoesNotReachTheSavedFile() =
        runBlocking {
            val image = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
            for (x in 0 until 80) for (y in 0 until 40) image.setPixel(x, y, if (x < 40) Color.RED else Color.BLUE)
            val file = File(RuntimeEnvironment.getApplication().cacheDir, "contact-exif-source.jpg")
            file.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            image.recycle()
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_MAKE, "Private device")
                setLatLong(9.0, 7.0)
                saveAttributes()
            }
            val prepared = preparePrivateContactPicture(file.readBytes(), IdentityImageCrop.Centered)
            val metadata = ExifInterface(ByteArrayInputStream(prepared))
            assertNull(metadata.getAttribute(ExifInterface.TAG_MAKE))
            assertNull(metadata.latLong)
            val orientation =
                metadata.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED,
                )
            assertTrue(
                orientation == ExifInterface.ORIENTATION_UNDEFINED || orientation == ExifInterface.ORIENTATION_NORMAL,
            )
            val pixels = BitmapFactory.decodeByteArray(prepared, 0, prepared.size)
            assertTrue(Color.red(pixels.getPixel(pixels.width / 2, 1)) > 200)
            assertTrue(Color.blue(pixels.getPixel(pixels.width / 2, pixels.height - 2)) > 200)
            pixels.recycle()
            assertTrue(file.delete())
        }

    @Test fun unsupportedAndOversizedInputCannotProduceASavedDraft() =
        runBlocking {
            assertTrue(
                runCatching { preparePrivateContactPicture(byteArrayOf(1), IdentityImageCrop.Centered) }.isFailure,
            )
            assertTrue(
                runCatching {
                    val bytes = ByteArray(IDENTITY_IMAGE_SOURCE_MAX_BYTES + 1)
                    preparePrivateContactPicture(bytes, IdentityImageCrop.Centered)
                }.isFailure,
            )
        }
}
