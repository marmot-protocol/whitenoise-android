package dev.ipf.whitenoise.android.media

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.core.ConversationTranscriptExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPlaintextWipeCoverageTest {
    @get:Rule
    val strategyCache = FileProviderStrategyCacheRule()

    private val application: Context = RuntimeEnvironment.getApplication()

    /** Wipe ends account-owned grants, cleans old unscoped files, and keeps another account's shares readable. */
    @Test
    fun removesOnlyOwnedAndLegacyPrivateCopies() =
        runBlocking {
            val context = RecordingRevocations(application)
            val owned = share(MediaCacheDirs.SHARED, sharedPlaintextPrefix("alice") + "message_1.pdf")
            val transcript = share(ConversationTranscriptExport.CacheDirName, sharedPlaintextPrefix("alice") + "export.json")
            val legacy = share(MediaCacheDirs.SHARED, "document_old.pdf")
            val other = share(MediaCacheDirs.SHARED, sharedPlaintextPrefix("bob") + "message_2.pdf")
            val profileCard = share(MediaCacheDirs.SHARED, "profile_card_1.png")
            val staleWriter = captureSharedPlaintextWrite()

            wipeSharedPlaintext(context, "alice") { _, error -> throw AssertionError(error) }

            assertEquals(setOf(owned.name, transcript.name, legacy.name), context.revoked.toSet())
            assertTrue(listOf(owned, transcript, legacy).none(File::exists))
            assertTrue(listOf(other, profileCard).all(File::exists))
            val stale = runCatching { withSharedPlaintextWrite<Unit>(staleWriter) { error("stale writer ran") } }
            assertTrue(stale.exceptionOrNull() is CancellationException)
        }

    /** Even if Android refuses a grant revocation, remove the app's bytes and preserve the failure for the report. */
    @Test
    fun revocationFailureStillDeletesPlaintext() =
        runBlocking {
            val context = RecordingRevocations(application, fail = true)
            val owned = share(MediaCacheDirs.SHARED, sharedPlaintextPrefix("alice") + "share_1.mp4")
            val failures = mutableListOf<String>()

            wipeSharedPlaintext(context, "alice") { name, _ -> failures += name }

            assertEquals(listOf(MediaCacheDirs.SHARED), failures)
            assertFalse(owned.exists())
        }

    private fun share(
        directory: String,
        name: String,
    ): File =
        File(File(application.cacheDir, directory).apply { mkdirs() }, name)
            .apply { writeText("test share") }

    private class RecordingRevocations(
        base: Context,
        private val fail: Boolean = false,
    ) : ContextWrapper(base) {
        val revoked = mutableListOf<String>()

        override fun revokeUriPermission(
            uri: Uri,
            modeFlags: Int,
        ) {
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION, modeFlags)
            revoked += requireNotNull(uri.lastPathSegment)
            if (fail) throw SecurityException("test revocation failure")
        }
    }
}
