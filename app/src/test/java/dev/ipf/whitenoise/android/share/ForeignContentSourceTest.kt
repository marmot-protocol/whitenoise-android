package dev.ipf.whitenoise.android.share

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForeignContentSourceTest {
    private val ownAuthority = "dev.ipf.whitenoise.fileprovider"

    @Test
    fun providerUriDropsTheUserPrefixAndRejectsNonContentSources() {
        assertEquals(
            Uri.parse("content://$ownAuthority/shared/a.jpg"),
            ForeignContentSource.providerUri(Uri.parse("content://10@$ownAuthority/shared/a.jpg")),
        )
        assertNull(ForeignContentSource.providerUri(Uri.parse("file:///data/data/app/files/key")))
        assertNull(ForeignContentSource.providerUri(Uri.parse("content:///no-authority")))
        assertNull(ForeignContentSource.providerUri(Uri.parse("content://0@/blank")))
        assertNull(ForeignContentSource.providerUri(Uri.parse("https://example.com/a.jpg")))
    }

    @Test
    fun onlyAnotherAppsContentProviderIsForeign() {
        val ownsProvider = { uri: Uri -> uri.authority == ownAuthority }

        assertTrue(ForeignContentSource.isForeign(Uri.parse("content://media/external/images/1"), ownsProvider))
        assertFalse(ForeignContentSource.isForeign(Uri.parse("content://$ownAuthority/shared/a.jpg"), ownsProvider))
        assertFalse(ForeignContentSource.isForeign(Uri.parse("content://0@$ownAuthority/shared/a.jpg"), ownsProvider))
        assertFalse(ForeignContentSource.isForeign(Uri.parse("file:///sdcard/a.jpg"), ownsProvider))
    }

    /** A keyboard or clipboard app cannot make the composer stage our own private files. */
    @Test
    fun pasteRefusesFileAndAppOwnedSourcesBeforeReading() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val secret = File(context.filesDir, "private.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            assertNull(materializeReceiveContentImageUri(context, Uri.fromFile(secret), "image/jpeg"))
            assertNull(
                materializeReceiveContentImageUri(
                    context,
                    Uri.parse("content://$ownAuthority/shared/a.jpg"),
                    resolvedMime = "image/jpeg",
                    isAppOwnedProvider = { true },
                ),
            )
            assertTrue(File(context.cacheDir, "composer_paste").listFiles().isNullOrEmpty())
        } finally {
            secret.delete()
        }
    }
}
