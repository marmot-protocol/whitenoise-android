package dev.ipf.whitenoise.android.ui.conversation.share

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A received VCF must validate before only a user-triggered system intent can read it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedContactIntentsTest {
    /** The own-client VCF parses; malformed, oversized and multi-contact payloads fail closed. */
    @Test fun validatesOneBoundedContact() {
        val contact = SharedContact("Ada Example", "+123456789", "ada@example.org")
        val valid = vcardFile(buildVCard(contact))
        assertEquals(contact, validatedReceivedVCard(valid))
        assertNull(validatedReceivedVCard(vcardFile("BEGIN:VCARD\nFN:Ada\nEND:VCARD")))
        assertNull(validatedReceivedVCard(vcardFile(buildVCard(contact) + buildVCard(contact))))
        assertNull(validatedReceivedVCard(vcardFile("x".repeat(1_048_577))))
        val invalidUtf8 =
            File.createTempFile("received-contact-invalid-", ".vcf").apply {
                writeBytes(byteArrayOf(-1, -2))
            }
        assertNull(validatedReceivedVCard(invalidUtf8))
    }

    /** View passes the exact content URI as data and ClipData with a temporary read grant. */
    @Test fun viewIntentGrantsOnlyReadAccess() {
        val uri = Uri.parse("content://test.example/contact.vcf")
        val intent = viewReceivedContactIntent(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(VCARD_MIME_TYPE, intent.type)
        assertEquals(uri, intent.data)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    /** Add gives the editor validated fields and the VCF read grant without writing Contacts directly. */
    @Test fun addIntentOpensSystemEditor() {
        val uri = Uri.parse("content://test.example/contact.vcf")
        val contact = SharedContact("Ada", "+123", "ada@example.org")
        val intent = addReceivedContactIntent(uri, contact)
        assertEquals(Intent.ACTION_INSERT, intent.action)
        assertEquals(ContactsContract.Contacts.CONTENT_TYPE, intent.type)
        assertEquals("Ada", intent.getStringExtra(ContactsContract.Intents.Insert.NAME))
        assertEquals("+123", intent.getStringExtra(ContactsContract.Intents.Insert.PHONE))
        assertEquals("ada@example.org", intent.getStringExtra(ContactsContract.Intents.Insert.EMAIL))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    /** A missing contact handler leaves Save VCF available on the card. */
    @Test fun missingHandlerHasRecoverableResult() {
        val result = launchReceivedContactIntent(Intent(Intent.ACTION_VIEW)) { throw ActivityNotFoundException() }
        assertEquals(ContactLaunchResult.NoHandler, result)
    }

    /** Test file isolated to Robolectric's private cache. */
    private fun vcardFile(content: String): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return File.createTempFile("received-contact-", ".vcf", context.cacheDir).apply { writeText(content) }
    }
}
