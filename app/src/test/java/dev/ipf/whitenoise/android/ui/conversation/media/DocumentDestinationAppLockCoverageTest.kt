package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.setAppLockScreenVisibleForTest
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.replaceActiveAccountForTest
import dev.ipf.whitenoise.android.ui.group.ConversationTranscriptSaveAction
import dev.ipf.whitenoise.android.ui.group.rememberConversationTranscriptSave
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Document destinations (attachment Save as and transcript export) across protected-shell lock disposal:
 * a live owner finishes its save, an interrupted one explains itself once and removes only an empty
 * created document, and an orphaned request never blocks the next save.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentDestinationAppLockCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = WhiteNoiseAppState(context, DraftStore(NoDrafts), { null }, emptyList(), "doc-account")
    private val registry = DestinationRegistry()
    private val destination = Uri.parse("content://$FIXTURE_DOCUMENTS/document/saved.txt")
    private val payload = byteArrayOf(2, 4, 6, 8)
    private lateinit var source: File
    private var fallback: DocumentSaveFallback? = null
    private var transcript: ConversationTranscriptSaveAction? = null
    private var scope: CoroutineScope? = null
    private var composed = false

    /** Installs the fixture documents provider and a readable private source file. */
    @Before
    fun setUp() {
        FixtureDocuments.directory = temporary.newFolder("documents")
        Robolectric.buildContentProvider(FixtureDocuments::class.java).create(FIXTURE_DOCUMENTS)
        source = temporary.newFile("source.bin").apply { writeBytes(payload) }
    }

    /** Stops app-owned coroutines so one test cannot leak into the next. */
    @After
    fun tearDown() {
        app.mutationsScope.cancel()
    }

    /** A destination chosen before lock disposal completes the live save with the source bytes. */
    @Test
    fun saveResultBeforeLockCompletesTheLiveCaller() {
        mountFallback()
        val save = startSave()
        createDocument(empty = true)
        deliverCreated()
        awaitIo { save.isCompleted }
        cycleLock()
        composeRule.runOnIdle {
            assertTrue("the live caller must finish", save.isCompleted && !save.isCancelled)
            assertArrayEquals(payload, documentFile().readBytes())
            assertNull("a completed save is not an interruption", app.toast)
        }
    }

    /** A destination returned while locked cancels the old caller, notices once and deletes the empty output. */
    @Test
    fun saveResultWhileLockedNoticesOnceAndRemovesTheEmptyDocument() {
        mountFallback()
        val save = startSave()
        setLocked(true)
        composeRule.runOnIdle { assertTrue("lock disposal must cancel the stale caller", save.isCancelled) }
        createDocument(empty = true)
        deliverCreated()
        setLocked(false)
        assertInterruptedOnce()
        awaitIo { !documentFile().exists() }
        assertNextSaveLaunches(expectedLaunches = 2)
    }

    /** A non-empty provider document is never deleted by interruption cleanup. */
    @Test
    fun saveResultWhileLockedKeepsANonEmptyDocument() {
        mountFallback()
        startSave()
        setLocked(true)
        createDocument(empty = false)
        deliverCreated()
        setLocked(false)
        assertInterruptedOnce()
        assertTrue("existing provider bytes must survive cleanup", documentFile().isFile)
    }

    /** A result for a replaced account is cleaned up silently and never written. */
    @Test
    fun saveResultAfterAccountChangeIsDiscardedWithoutNotice() {
        mountFallback()
        startSave()
        setLocked(true)
        createDocument(empty = true)
        composeRule.runOnIdle { app.replaceActiveAccountForTest("other-account") }
        deliverCreated()
        setLocked(false)
        awaitIo { !documentFile().exists() }
        composeRule.runOnIdle { assertNull("another account must not see this save's notice", app.toast) }
    }

    /** An orphaned request whose picker never answers does not block later saves. */
    @Test
    fun orphanedSaveRequestDoesNotBlockTheNextSave() {
        mountFallback()
        val orphaned = startSave()
        cycleLock()
        composeRule.runOnIdle { assertTrue(orphaned.isCancelled) }
        val next = startSave()
        composeRule.runOnIdle {
            assertEquals("the next save must reach the picker", 2, registry.launches)
            assertTrue("the next save must wait for its destination", next.isActive)
        }
        createDocument(empty = true)
        deliverCreated()
        awaitIo { next.isCompleted }
        composeRule.runOnIdle {
            assertTrue(next.isCompleted && !next.isCancelled)
            assertArrayEquals(payload, documentFile().readBytes())
        }
    }

    /** A transcript destination returned while locked notices once, removes the empty output and stays retryable. */
    @Test
    fun transcriptResultWhileLockedNoticesOnceAndStaysRetryable() {
        mountTranscript()
        composeRule.runOnIdle { checkNotNull(transcript).save() }
        setLocked(true)
        createDocument(empty = true)
        composeRule.runOnIdle { registry.dispatchResult(registry.requestCode, destination) }
        setLocked(false)
        assertInterruptedOnce()
        awaitIo { !documentFile().exists() }
        composeRule.runOnIdle {
            assertFalse(checkNotNull(transcript).busy)
            checkNotNull(transcript).save()
            assertEquals("the next export must reach the picker", 2, registry.launches)
        }
    }

    /** An orphaned transcript request whose picker never answers does not block the next export. */
    @Test
    fun orphanedTranscriptRequestDoesNotBlockTheNextExport() {
        mountTranscript()
        composeRule.runOnIdle { checkNotNull(transcript).save() }
        cycleLock()
        composeRule.runOnIdle {
            checkNotNull(transcript).save()
            assertEquals(2, registry.launches)
            assertTrue("the replacement export owns the picker", checkNotNull(transcript).busy)
        }
    }

    /** A transcript destination accepted before lock goes to the live export, not the interruption path. */
    @Test
    fun transcriptResultBeforeLockReachesTheLiveExport() {
        mountTranscript()
        composeRule.runOnIdle { checkNotNull(transcript).save() }
        createDocument(empty = true)
        composeRule.runOnIdle { registry.dispatchResult(registry.requestCode, destination) }
        awaitIo { checkNotNull(transcript).busy.not() }
        composeRule.runOnIdle {
            // The fixture has no native runtime, so the live export reports its own failure instead.
            assertEquals(AppText.Resource(R.string.toast_couldnt_export_transcript), app.toast?.title)
            app.clearToast()
        }
        cycleLock()
        composeRule.runOnIdle {
            assertFalse(checkNotNull(transcript).busy)
            checkNotNull(transcript).save()
            assertEquals(2, registry.launches)
        }
    }

    /** Composes the attachment Save as fallback beneath the protected-shell lock gate. */
    private fun mountFallback() = mount { fallback = rememberDocumentSaveFallback(app, "doc-group") }

    /** Composes the transcript export action beneath the protected-shell lock gate. */
    private fun mountTranscript() {
        val controller = ConversationController(appState = app, initialGroup = conversationTimelineTestGroup())
        mount { transcript = rememberConversationTranscriptSave(app, controller) }
    }

    /** Mounts [content] only while unlocked, under the same saved-state provider as the production shell. */
    private fun mount(content: @Composable () -> Unit) {
        composeRule.setContent {
            scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides registry) {
                val state = rememberProtectedShellSavedState("doc-account", 0, suspended = app.appLockScreenVisible)
                if (!app.appLockScreenVisible) {
                    state.holder.SaveableStateProvider(state.key) {
                        androidx.compose.runtime.DisposableEffect(Unit) {
                            composed = true
                            onDispose { composed = false }
                        }
                        content()
                    }
                }
            }
        }
    }

    /** Starts one Save as from a caller that outlives the protected subtree. */
    private fun startSave(): Job {
        var job: Job? = null
        composeRule.runOnIdle {
            val save = checkNotNull(fallback)
            job = checkNotNull(scope).launch { save(source, "saved.txt", "text/plain") }
        }
        composeRule.waitForIdle()
        return checkNotNull(job)
    }

    /** Answers the last CREATE_DOCUMENT request with the fixture destination. */
    private fun deliverCreated() {
        composeRule.runOnIdle {
            registry.dispatchResult(registry.requestCode, Activity.RESULT_OK, Intent().setData(destination))
        }
        composeRule.waitForIdle()
    }

    /** Waits for work the production code moved to Dispatchers.IO, which compose idling does not track. */
    private fun awaitIo(condition: () -> Boolean) {
        val deadline = System.nanoTime() + IO_TIMEOUT_MILLIS * 1_000_000L
        while (true) {
            // Let the IO thread finish and its continuation post back, then drain the main looper.
            composeRule.waitForIdle()
            var met = false
            composeRule.runOnIdle { met = condition() }
            if (met) return
            check(System.nanoTime() < deadline) { "IO work did not settle" }
            Thread.sleep(IO_POLL_MILLIS)
        }
    }

    /** Shows and hides the app-lock screen, as a successful authentication does. */
    private fun cycleLock() {
        setLocked(true)
        setLocked(false)
    }

    /** Shows or hides the app-lock screen and waits for the protected subtree to follow. */
    private fun setLocked(locked: Boolean) {
        composeRule.runOnIdle { app.setAppLockScreenVisibleForTest(locked) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(!locked, composed) }
    }

    /** Asserts one interruption notice, then that recomposition and relocking never repeat it. */
    private fun assertInterruptedOnce() {
        composeRule.runOnIdle {
            assertEquals(AppText.Resource(R.string.document_save_interrupted), app.toast?.title)
            app.clearToast()
        }
        cycleLock()
        composeRule.runOnIdle { assertNull("the interruption notice must appear once", app.toast) }
    }

    /** Asserts a new save reaches the platform picker instead of failing as already pending. */
    private fun assertNextSaveLaunches(expectedLaunches: Int) {
        val next = startSave()
        composeRule.runOnIdle {
            assertEquals(expectedLaunches, registry.launches)
            assertTrue(next.isActive)
        }
    }

    /** Creates the provider's CREATE_DOCUMENT output, either empty or holding unrelated bytes. */
    private fun createDocument(empty: Boolean) {
        documentFile().writeBytes(if (empty) ByteArray(0) else byteArrayOf(9, 9))
    }

    /** The fixture provider's backing file for [destination]. */
    private fun documentFile() = File(FixtureDocuments.directory, "saved.txt")
}

private const val IO_TIMEOUT_MILLIS = 5_000L
private const val IO_POLL_MILLIS = 20L
private const val FIXTURE_DOCUMENTS = "dev.ipf.whitenoise.android.test.documents"

/** A minimal document provider: file-backed reads/writes, a size column and DocumentsContract deletion. */
class FixtureDocuments : ContentProvider() {
    /** Accepts creation without extra setup. */
    override fun onCreate() = true

    /** Reports the backing file's size, which interruption cleanup uses to prove a document is empty. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = fileFor(uri).takeIf(File::isFile) ?: return null
        return MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(file.length())) }
    }

    /** Opens the backing file in the requested mode. */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor = ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.parseMode(mode))

    /** Implements DocumentsContract.deleteDocument by removing the backing file. */
    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method == "android:deleteDocument") {
            @Suppress("DEPRECATION")
            val uri = extras?.getParcelable<Uri>("uri")
            uri?.let { fileFor(it).delete() }
        }
        return null
    }

    /** Not used by document saves. */
    override fun getType(uri: Uri): String? = null

    /** Not used by document saves. */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    /** Not used by document saves. */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    /** Not used by document saves. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    /** Maps a document URI to its file in the per-test directory. */
    private fun fileFor(uri: Uri) = File(directory, checkNotNull(uri.lastPathSegment))

    companion object {
        lateinit var directory: File
    }
}

/** Records launches instead of starting an external picker Activity. */
private class DestinationRegistry :
    ActivityResultRegistry(),
    ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    var requestCode = 0
    var launches = 0

    /** Captures the request code so the test can answer it like the platform. */
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        this.requestCode = requestCode
        launches++
    }
}

/** No persisted drafts for this fixture. */
private object NoDrafts : DraftPersistence {
    /** Returns no saved drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Discards draft writes. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
