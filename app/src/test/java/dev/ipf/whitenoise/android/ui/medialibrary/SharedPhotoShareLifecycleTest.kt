package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * The gallery's share runs in its composition: leaving the gallery, leaving the app or losing ownership never opens a
 * late sheet, and a photo that cannot be shared leaves the selection so the rest can be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharedPhotoShareLifecycleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val beach = photo("message-1", 0, "beach.jpg")
    private val harbour = photo("message-2", 0, "harbour.jpg")
    private val failures = mutableListOf<Throwable>()
    private val secondRead = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var shown by mutableStateOf(true)
    private var owned = true
    private var current = listOf(beach, harbour)
    private var checks = 0
    private var ownershipLostOnFinalCheck = false
    private var harbourUnreadable = false
    private lateinit var share: SharedPhotoShare

    /** Mounts a gallery share whose availability checks count calls and can drop ownership on the final one. */
    @Before
    fun setUp() {
        File(context.cacheDir, MediaCacheDirs.SHARED).listFiles()?.forEach(File::delete)
        registerShareTarget()
        composeRule.setContent {
            if (shown) {
                share =
                    rememberSharedPhotoShare(
                        ownsShare = { owned },
                        accountRef = { "account" },
                        currentPhotos = {
                            checks += 1
                            if (checks == 2 && ownershipLostOnFinalCheck) owned = false
                            current
                        },
                        source = { photo ->
                            if (photo == harbour) {
                                secondRead.complete(Unit)
                                release.await()
                                if (harbourUnreadable) throw IOException("harbour.jpg download failed")
                            }
                            AttachmentPlaintext.Bytes(photo.messageIdHex.encodeToByteArray())
                        },
                        onFailure = { _, error -> failures += error },
                    )
            }
        }
        composeRule.runOnIdle {
            share.selection.toggle(beach)
            share.selection.toggle(harbour)
            share.start()
        }
        composeRule.waitUntil(TIMEOUT_MS) { secondRead.isCompleted }
        assertTrue(share.sharing)
        assertEquals(1, sharedFiles().size)
    }

    /** Closing the gallery mid-staging cancels it, deletes the staged photo and reports nothing. */
    @Test
    fun closingTheGalleryCancelsStaging() {
        composeRule.runOnIdle { shown = false }
        // The gallery has left composition before the paused read returns.
        composeRule.waitForIdle()
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { sharedFiles().isEmpty() }
        composeRule.waitForIdle()
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(failures.isEmpty())
    }

    /** An account or conversation switch mid-staging opens no sheet, deletes the set and reports nothing. */
    @Test
    fun losingOwnershipCancelsBeforeLaunch() {
        owned = false
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        assertTrue(failures.isEmpty())
    }

    /** Ownership lost while the final gallery read is in flight still prevents the sheet. */
    @Test
    fun losingOwnershipDuringTheFinalCheckCancels() {
        ownershipLostOnFinalCheck = true
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertEquals(2, checks)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        assertTrue(failures.isEmpty())
    }

    /** A photo removed mid-staging is reported while the gallery still owns the share. */
    @Test
    fun aRemovedPhotoIsReported() {
        current = listOf(beach)
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        assertEquals(listOf(harbour), (failures.single() as SharedPhotosUnavailableException).photos)
    }

    /** Leaving the app before the sheet opens keeps the selection, deletes the staged set and reports nothing. */
    @Test
    fun leavingTheAppDuringStagingKeepsTheSelection() {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        assertEquals(listOf(beach, harbour), share.selection.photos)
        assertTrue(failures.isEmpty())
    }

    /** A photo that fails while the app is in the background is not reported and keeps the whole selection. */
    @Test
    fun aFailureWhileTheAppIsAwayKeepsTheSelection() {
        harbourUnreadable = true
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        assertEquals(listOf(beach, harbour), share.selection.photos)
        assertTrue(failures.isEmpty())
    }

    /** A photo whose file cannot be read is named and deselected, so the next Share sends the others. */
    @Test
    fun anUnreadablePhotoIsDeselectedAndTheNextShareSendsTheRest() {
        harbourUnreadable = true
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(sharedFiles().isEmpty())
        val failure = failures.single() as SharedPhotosUnavailableException
        assertEquals(listOf(harbour), failure.photos)
        assertTrue(failure.unreadable)
        assertEquals(listOf(beach), share.selection.photos)

        composeRule.runOnIdle { share.start() }
        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertFalse(share.selection.active)
        assertEquals(1, failures.size)
    }

    /** Exiting selection mode cancels the share it was staging. */
    @Test
    fun exitingSelectionCancelsStaging() {
        composeRule.runOnIdle { share.exit() }
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { sharedFiles().isEmpty() }
        composeRule.waitForIdle()
        assertFalse(share.selection.active)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertTrue(failures.isEmpty())
    }

    /** A completed share opens one sheet and ends selection mode. */
    @Test
    fun aCompletedShareEndsSelection() {
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { !share.sharing }
        assertNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertFalse(share.selection.active)
        assertTrue(failures.isEmpty())
    }

    /** Registers an external app that accepts one or several shared JPEG images. */
    private fun registerShareTarget() {
        val application = ApplicationInfo().apply { packageName = SHARE_TARGET_PACKAGE }
        listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE).forEach { action ->
            shadowOf(context.packageManager).addResolveInfoForIntent(
                Intent(action).setType("image/jpeg"),
                ResolveInfo().apply {
                    activityInfo =
                        ActivityInfo().apply {
                            packageName = SHARE_TARGET_PACKAGE
                            name = "$SHARE_TARGET_PACKAGE.ShareActivity"
                            exported = true
                            applicationInfo = application
                        }
                },
            )
        }
    }

    private fun sharedFiles(): List<File> =
        File(context.cacheDir, MediaCacheDirs.SHARED)
            .listFiles()
            ?.filter(File::isFile)
            .orEmpty()

    private fun photo(
        messageIdHex: String,
        attachmentIndex: Int,
        fileName: String,
    ) = SharedMediaTile(
        messageIdHex = messageIdHex,
        attachmentIndex = attachmentIndex,
        reference =
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://cdn.example.test/$fileName")),
                ciphertextSha256 = "a".repeat(64),
                plaintextSha256 = "b".repeat(64),
                nonceHex = "c".repeat(48),
                fileName = fileName,
                mediaType = "image/jpeg",
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            ),
        mine = false,
        recordedAt = 1uL,
        sender = "sender",
        isVideo = false,
    )

    private companion object {
        const val SHARE_TARGET_PACKAGE = "com.example.photos"
        const val TIMEOUT_MS = 5_000L
    }
}
