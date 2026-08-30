package dev.ipf.whitenoise.android.share

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutboundShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun inviteUsesCanonicalLocalizedPlainTextAndExcludesWhiteNoise() {
        val message = context.getString(R.string.invite_message)
        assertTrue(message.contains("https://www.whitenoise.chat/download"))
        val send = inviteShareIntent(message)
        addExternalTarget(send)

        val chooser = outboundShareChooser(context, send, context.getString(R.string.invite_to_white_noise))
        val nested = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        val excluded =
            IntentCompat
                .getParcelableArrayExtra(
                    chooser,
                    Intent.EXTRA_EXCLUDE_COMPONENTS,
                    ComponentName::class.java,
                ).orEmpty()

        assertEquals(Intent.ACTION_SEND, nested?.action)
        assertEquals("text/plain", nested?.type)
        assertEquals(message, nested?.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(
            context.getString(R.string.invite_to_white_noise),
            chooser.getCharSequenceExtra(Intent.EXTRA_TITLE),
        )
        assertTrue(ComponentName(context, MainActivity::class.java) in excluded)
    }

    @Test
    fun chooserFailsClosedWhenWhiteNoiseIsTheOnlyHandler() {
        val failure =
            runCatching { outboundShareChooser(context, inviteShareIntent("Invite"), "Invite") }
                .exceptionOrNull()

        assertTrue(failure is ActivityNotFoundException)
    }

    @Test
    fun textAndSingleFileCarryTextStreamClipDataAndTemporaryReadGrant() {
        val uri = Uri.parse("content://${context.packageName}.fileprovider/shared_media/report.pdf")
        val intent = outboundShareIntent("Visible caption", listOf(OutboundShareStream(uri, "application/pdf")))

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals("Visible caption", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(uri, IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun multipleFilesUseSendMultipleWithoutDroppingStreams() {
        val first = Uri.parse("content://${context.packageName}.fileprovider/shared_media/one.png")
        val second = Uri.parse("content://${context.packageName}.fileprovider/shared_media/two.jpg")
        val intent =
            outboundShareIntent(
                text = null,
                streams = listOf(OutboundShareStream(first, "image/png"), OutboundShareStream(second, "image/jpeg")),
            )
        val streams = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("image/*", intent.type)
        assertEquals(listOf(first, second), streams)
        assertEquals(first, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(second, intent.clipData?.getItemAt(1)?.uri)
    }

    @Test
    fun mixedAttachmentFamiliesUseWildcardAndAttachmentOnlyDoesNotInventText() {
        val intent =
            outboundShareIntent(
                text = null,
                streams =
                    listOf(
                        OutboundShareStream(Uri.parse("content://test/photo"), "image/png"),
                        OutboundShareStream(Uri.parse("content://test/manual"), "application/pdf"),
                    ),
            )

        assertEquals("*/*", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun malformedRemoteMimeFallsBackToBinaryInsteadOfEscapingIntentType() {
        assertEquals("application/octet-stream", normalizedOutboundMediaType("not a mime"))
        assertEquals("application/octet-stream", normalizedOutboundMediaType("image/"))
        assertEquals("image/jpeg", normalizedOutboundMediaType(" Image/JPEG "))
    }

    private fun addExternalTarget(intent: Intent) {
        val resolveInfo =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = "example.share.target"
                        name = "example.share.target.ShareActivity"
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(intent, resolveInfo)
    }
}
