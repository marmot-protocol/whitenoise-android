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
import org.junit.Assert.assertNotEquals
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
            val alice = sharedPlaintextPrefix(application, "alice")
            val owned = share(MediaCacheDirs.SHARED, alice + "message_1.pdf")
            val transcript = share(ConversationTranscriptExport.CacheDirName, alice + "export.json")
            val legacy = share(MediaCacheDirs.SHARED, "document_old.pdf")
            val oldScoped = share(MediaCacheDirs.SHARED, legacySharedPlaintextPrefix("alice") + "share_fixture.pdf")
            val other = share(MediaCacheDirs.SHARED, sharedPlaintextPrefix(application, "bob") + "message_2.pdf")
            val profileCard = share(MediaCacheDirs.SHARED, "profile_card_1.png")
            val staleWriter = captureSharedPlaintextWrite()

            wipeSharedPlaintext(context, "alice") { _, error -> throw AssertionError(error) }

            assertEquals(setOf(owned.name, transcript.name, legacy.name, oldScoped.name), context.revoked.toSet())
            assertTrue(listOf(owned, transcript, legacy, oldScoped).none(File::exists))
            assertTrue(listOf(other, profileCard).all(File::exists))
            val stale = runCatching { withSharedPlaintextWrite<Unit>(staleWriter) { error("stale writer ran") } }
            assertTrue(stale.exceptionOrNull() is CancellationException)
        }

    /** Even if Android refuses a grant revocation, remove the app's bytes and preserve the failure for the report. */
    @Test
    fun revocationFailureStillDeletesPlaintextAndRetriesTheGrant() =
        runBlocking {
            val context = RecordingRevocations(application, fail = true)
            val owned = share(MediaCacheDirs.SHARED, sharedPlaintextPrefix(application, "alice") + "share_1.mp4")
            val failures = mutableListOf<String>()

            wipeSharedPlaintext(context, "alice") { name, _ -> failures += name }

            assertEquals(listOf(MediaCacheDirs.SHARED), failures)
            assertFalse(owned.exists())
            val recovered = RecordingRevocations(application)
            recoverSharedPlaintextWipes(recovered) { _, error -> throw AssertionError(error) }
            assertEquals(listOf(owned.name), recovered.revoked)
            recoverSharedPlaintextWipes(recovered) { _, error -> throw AssertionError(error) }
            assertEquals(listOf(owned.name), recovered.revoked)
        }

    /** A filename prefix is stable within an install but differs for another install or account. */
    @Test
    fun prefixesCannotBeDerivedFromTheAccountRefAlone() {
        val otherKeyDir = File(application.cacheDir, "different-share-key").apply { mkdirs() }
        val otherInstall =
            object : ContextWrapper(application) {
                override fun getNoBackupFilesDir(): File = otherKeyDir
            }
        val prefix = sharedPlaintextPrefix(application, "alice")

        assertEquals(prefix, sharedPlaintextPrefix(ContextWrapper(application), "alice"))
        assertNotEquals(prefix, sharedPlaintextPrefix(application, "bob"))
        assertNotEquals(prefix, sharedPlaintextPrefix(otherInstall, "alice"))
        assertNotEquals(prefix, legacySharedPlaintextPrefix("alice"))
    }

    /** Live ownership protects the journal until native teardown, and a failed cleanup can be retried. */
    @Test
    fun replaysJournalAfterUnfinishedCleanup() =
        runBlocking {
            val context = RecordingRevocations(application)
            val account = "interrupted"
            val prefix = sharedPlaintextPrefix(application, account)
            val blocked = share(MediaCacheDirs.SHARED, prefix + "document_fixture.pdf")
            assertTrue(blocked.delete())
            assertTrue(blocked.mkdir())
            val child = File(blocked, "child").apply { writeText("test") }
            assertTrue(recordSharedPlaintextWipe(context, account))

            recoverSharedPlaintextWipes(context) { _, error -> throw AssertionError(error) }
            assertTrue(blocked.exists())
            assertTrue(context.revoked.isEmpty())
            val failures = mutableListOf<String>()
            wipeSharedPlaintext(context, account) { area, _ -> failures += area }
            assertTrue(MediaCacheDirs.SHARED in failures)

            assertTrue(child.delete())
            recoverSharedPlaintextWipes(context) { _, error -> throw AssertionError(error) }
            assertFalse(blocked.exists())
            assertEquals(blocked.name, context.revoked.last())
        }

    /** A native rejection leaves the account and its staged shares available after startup recovery. */
    @Test
    fun rejectedWipeDisarmsStartupRecovery() =
        runBlocking {
            val context = RecordingRevocations(application)
            val account = "retained"
            val prefix = sharedPlaintextPrefix(application, account)
            val file = share(MediaCacheDirs.SHARED, prefix + "document_fixture.pdf")
            assertTrue(recordSharedPlaintextWipe(context, account))
            assertTrue(discardSharedPlaintextWipe(context, account))

            recoverSharedPlaintextWipes(context) { _, error -> throw AssertionError(error) }

            assertTrue(file.exists())
            assertTrue(context.revoked.isEmpty())
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
