package dev.ipf.whitenoise.android.share

import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OutboundShareDeviceContractTest {
    @Test
    fun realChooserCarriesSentAndReceivedStreamsThenCancelsWithoutStateMutation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
        val sent = File(directory, "sent-fixture.pdf").apply { writeBytes("sent bytes".encodeToByteArray()) }
        val received =
            File(directory, "received-fixture.bin")
                .apply { writeBytes("received bytes".encodeToByteArray()) }
        val neighbor = File(directory, "neighbor-secret.txt").apply { writeBytes("not shared".encodeToByteArray()) }
        try {
            val sentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sent)
            val receivedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", received)
            val neighborUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", neighbor)
            val send =
                outboundShareIntent(
                    text = "Visible message caption",
                    streams =
                        listOf(
                            OutboundShareStream(sentUri, "application/pdf"),
                            OutboundShareStream(receivedUri, "application/octet-stream"),
                        ),
                )
            val chooser = outboundShareChooser(context, send, "Share")
            val nested = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            val streams =
                IntentCompat.getParcelableArrayListExtra(
                    nested,
                    Intent.EXTRA_STREAM,
                    android.net.Uri::class.java,
                )!!

            assertEquals(Intent.ACTION_SEND_MULTIPLE, nested.action)
            assertEquals("application/*", nested.type)
            assertEquals("Visible message caption", nested.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(listOf(sentUri, receivedUri), streams)
            assertEquals(
                listOf(sentUri, receivedUri),
                (0 until nested.clipData!!.itemCount).map { nested.clipData!!.getItemAt(it).uri },
            )
            assertTrue(nested.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(neighborUri in streams)

            instrumentation.runOnMainSync {
                context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            instrumentation.waitForIdleSync()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
        } finally {
            sent.delete()
            received.delete()
            neighbor.delete()
        }
    }
}
