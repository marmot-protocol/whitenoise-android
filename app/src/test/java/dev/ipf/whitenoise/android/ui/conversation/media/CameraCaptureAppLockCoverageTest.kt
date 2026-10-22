package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.ConversationMediaDraftState
import dev.ipf.whitenoise.android.ui.conversation.PhotoEditorMessages
import dev.ipf.whitenoise.android.ui.navigation.rememberProtectedShellSavedState
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Runs the real capture launcher and shell registry through deterministic lock/result orderings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CameraCaptureAppLockCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    @get:Rule val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** An empty draft disposed before the camera writes keeps the pending output for the later result. */
    @Test fun emptyDraftDisposalBeforeCameraWritesPreservesItsPendingOutput() {
        exercise(emptyComposer = true, writeAfterLock = true)
    }

    /** A cold restore re-holds the pending capture even when the draft is empty. */
    @Test fun coldRestoreReestablishesCaptureHoldWithAnEmptyDraft() {
        exercise(emptyComposer = true, restore = true, writeAfterLock = true)
    }

    /** A result landing in the same frame as lock disposal keeps its accepted output. */
    @Test fun emptyDraftDisposalImmediatelyAfterResultPreservesItsAcceptedOutput() {
        exercise(emptyComposer = true, resultAndLockTogether = true)
    }

    /** A full shelf refuses the capture with one actionable notice and leaks neither its output nor its hold. */
    @Test fun fullShelfRejectsTheCaptureWithoutLeakingItsOutputOrHold() = exercise(fullComposer = true)

    /** A cancelled camera discards its unused private output. */
    @Test fun cancelledCameraDiscardsTheUnusedOutput() = exercise(cancelCapture = true)

    /** A result accepted before lock disposal survives on the shelf after unlock. */
    @Test fun resultBeforeLockRetainsShelf() = exercise(resultBeforeLock = true)

    /** A result delivered while locked waits for unlock and stages exactly once. */
    @Test fun resultWhileLockedWaitsForUnlockAndStagesOnce() = exercise()

    /** A cancelled authentication keeps the capture for a successful retry. */
    @Test fun cancelledAuthenticationCanRetryWithoutLosingCapture() = exercise(cancelAuthentication = true)

    /** A restored process recovers the pending capture and the existing shelf. */
    @Test fun restoredProcessRecoversPendingCaptureAndExistingShelf() = exercise(restore = true)

    /** A callback after an account change but before disposal rejects and deletes its capture. */
    @Test fun callbackBeforeAccountDisposalRejectsAndCleansItsCapture() {
        exercise(resultBeforeLock = true, replaceAccount = true, replaceBeforeCallback = true)
    }

    /** An account change while locked rejects the old account's result. */
    @Test fun changingAccountWhileLockedRejectsTheOldResult() = exercise(replaceAccount = true)

    /** A runtime replacement while locked rejects the old runtime's result. */
    @Test fun changingRuntimeWhileLockedRejectsTheOldResult() = exercise(replaceRuntime = true)

    /** Runs one lifecycle ordering from the matrix — each parameter selects one ordering — through the fixture. */
    @Suppress("LongParameterList")
    private fun exercise(
        resultBeforeLock: Boolean = false,
        cancelCapture: Boolean = false,
        cancelAuthentication: Boolean = false,
        restore: Boolean = false,
        replaceAccount: Boolean = false,
        replaceRuntime: Boolean = false,
        replaceBeforeCallback: Boolean = false,
        emptyComposer: Boolean = false,
        fullComposer: Boolean = false,
        writeAfterLock: Boolean = false,
        resultAndLockTogether: Boolean = false,
    ) {
        CameraCaptureFixture(
            composeRule,
            context,
            CameraCaptureScenario(
                resultBeforeLock,
                cancelCapture,
                cancelAuthentication,
                restore,
                replaceAccount,
                replaceRuntime,
                replaceBeforeCallback,
                emptyComposer,
                fullComposer,
                writeAfterLock,
                resultAndLockTogether,
            ),
        ).exercise()
    }
}

private data class CameraCaptureScenario(
    val resultBeforeLock: Boolean,
    val cancelCapture: Boolean,
    val cancelAuthentication: Boolean,
    val restore: Boolean,
    val replaceAccount: Boolean,
    val replaceRuntime: Boolean,
    val replaceBeforeCallback: Boolean,
    val emptyComposer: Boolean,
    val fullComposer: Boolean,
    val writeAfterLock: Boolean,
    val resultAndLockTogether: Boolean,
)

private class CameraCaptureFixture(
    private val composeRule: ComposeContentTestRule,
    private val context: Context,
    private val scenario: CameraCaptureScenario,
) {
    private val resultBeforeLock get() = scenario.resultBeforeLock
    private val cancelCapture get() = scenario.cancelCapture
    private val cancelAuthentication get() = scenario.cancelAuthentication
    private val restore get() = scenario.restore
    private val replaceAccount get() = scenario.replaceAccount
    private val replaceRuntime get() = scenario.replaceRuntime
    private val replaceBeforeCallback get() = scenario.replaceBeforeCallback
    private val emptyComposer get() = scenario.emptyComposer
    private val fullComposer get() = scenario.fullComposer
    private val writeAfterLock get() = scenario.writeAfterLock
    private val resultAndLockTogether get() = scenario.resultAndLockTogether
    private val ownerReplaced get() = replaceAccount || replaceRuntime
    private val captureRejected get() = cancelCapture || fullComposer
    private val registry = Camera()
    private var captures = PendingCameraCaptureFiles()
    private val app =
        WhiteNoiseAppState(
            context,
            DraftStore(EmptyDrafts),
            { null },
            emptyList(),
            "capture-account",
        )
    private val controller = ConversationController(appState = app, initialGroup = conversationTimelineTestGroup())
    private var draftDisposals = 0
    private val locked = mutableStateOf(false)
    private val account = mutableStateOf("capture-account")
    private val runtime = mutableStateOf(4)
    private val existing = Uri.parse("content://fixture/existing")
    private val initialUris =
        when {
            emptyComposer -> emptyList()
            fullComposer -> List(10) { Uri.parse("content://fixture/existing/$it") }
            else -> listOf(existing)
        }
    private var launch: (() -> Unit)? = null
    private var visibleUris = emptyList<Uri>()
    private var composed = false
    private var failures = 0
    private var rejections = 0
    private val restoration = StateRestorationTester(composeRule)
    private lateinit var captured: Uri
    private lateinit var abandoned: File

    /** Runs the capture, lock and delivery sequence, then checks the shelf and output ownership. */
    fun exercise() {
        try {
            mount()
            captureAndSuspend()
            deliverWhileSuspended()
            assertVisibleShelf()
            assertOutputOwnership()
        } finally {
            cleanUp()
        }
    }

    /** Composes the composer shelf under the protected shell saved state with the fake camera registry. */
    private fun mount() {
        restoration.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registry,
                LocalPendingCameraCaptureFiles provides captures,
            ) {
                SideEffect { captures.retainOwner("${runtime.value}:${account.value}") }
                val state = rememberProtectedShellSavedState(account.value, runtime.value, suspended = locked.value)
                if (!locked.value) {
                    state.holder.SaveableStateProvider(state.key) {
                        DisposableEffect(Unit) {
                            composed = true
                            onDispose { composed = false }
                        }
                        var slots by rememberSaveable(account.value, stateSaver = PendingMediaSlotListSaver) {
                            mutableStateOf(appendPendingMediaSlots(emptyList(), initialUris, 10))
                        }
                        val captureAccount = account.value
                        val captureRuntime = runtime.value
                        launch =
                            rememberConversationCameraCapture(
                                "${account.value}:capture-group",
                                onCaptured = {
                                    val updated = appendPendingMediaSlots(slots, listOf(it), 10)
                                    val accepted = updated.size > slots.size
                                    slots = updated
                                    accepted
                                },
                                onFailure = { failures++ },
                                isCurrent = { account.value == captureAccount && runtime.value == captureRuntime },
                                onRejected = { rejections++ },
                            )
                        trackDraft(slots)
                        visibleUris = slots.map { it.uri }
                    }
                }
            }
        }
    }

    /** Keeps a media draft per account and runtime in sync with [slots], counting its disposals. */
    @Composable
    private fun trackDraft(slots: List<PendingMediaSlot>) {
        val scope = rememberCoroutineScope()
        val draft =
            remember(account.value, runtime.value) {
                ConversationMediaDraftState(
                    app,
                    controller,
                    context,
                    scope,
                    PhotoEditorMessages("", "", "", ""),
                    captures,
                )
            }
        SideEffect { draft.updateInputs(slots, emptyList(), account.value) }
        DisposableEffect(draft) {
            onDispose {
                draft.dispose()
                draftDisposals++
            }
        }
    }

    /** Writes fixture bytes into the camera output URI. */
    private fun writeCapture() {
        context.contentResolver.openOutputStream(captured)!!.use { it.write(byteArrayOf(3, 5, 7)) }
    }

    /** Launches the camera, records its output and locks the shell, optionally delivering the result first. */
    private fun captureAndSuspend() {
        composeRule.runOnIdle { checkNotNull(launch).invoke() }
        composeRule.waitForIdle()
        captured = checkNotNull(registry.uri)

        if (!writeAfterLock) writeCapture()
        abandoned = File(context.cacheDir, "camera/abandoned.jpg").apply { writeText("abandoned") }
        if (resultBeforeLock) {
            composeRule.runOnIdle {
                if (replaceBeforeCallback) account.value = "different-account"
                registry.deliver()
            }
            composeRule.waitForIdle()
        }
        composeRule.runOnIdle {
            if (resultAndLockTogether) registry.deliver()
            locked.value = true
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertFalse("protected content must be uncomposed", composed)
            assertTrue("production draft cleanup must execute", draftDisposals > 0)
            if (emptyComposer) assertFalse("unrelated abandoned camera files still clean up", abandoned.exists())
        }
    }

    /** Delivers the camera result while locked, covering failed authentication and process restoration. */
    private fun deliverWhileSuspended() {
        if (writeAfterLock) writeCapture()
        if (cancelAuthentication) {
            // A failed/cancelled prompt leaves the lock mounted; retry does not recreate its saved owner.
            composeRule.runOnIdle { registry.deliver() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertFalse(composed) }
        }
        if (restore) {
            // A real process death discards publication refcounts and the retained
            // shell object, while leaving the saved private output bytes intact.
            captures.release(File(context.cacheDir, "camera/${captured.lastPathSegment}"))
            captures = PendingCameraCaptureFiles()
            restoration.emulateSavedInstanceStateRestore()
        }
        composeRule.runOnIdle {
            if (replaceAccount) account.value = "different-account"
            if (replaceRuntime) runtime.value++
        }
        composeRule.waitForIdle()
        if (!resultBeforeLock && !cancelAuthentication && !resultAndLockTogether) {
            composeRule.runOnIdle { registry.deliver(success = !cancelCapture) }
        }
    }

    /** Unlocks and asserts which photos the shelf shows and how many rejections were reported. */
    private fun assertVisibleShelf() {
        composeRule.runOnIdle { locked.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(composed)
            assertEquals(0, failures)
            assertEquals("a full shelf must explain the refused photo once", if (fullComposer) 1 else 0, rejections)
            assertEquals(
                if (ownerReplaced || captureRejected) {
                    initialUris
                } else {
                    initialUris + captured
                },
                visibleUris,
            )
        }
    }

    /** Asserts that rejected or orphaned captures are deleted and accepted ones stay readable. */
    private fun assertOutputOwnership() {
        if (captureRejected || ownerReplaced || replaceBeforeCallback) {
            org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(captured)?.close()
            }
        }
        if (!ownerReplaced && !captureRejected) {
            composeRule.runOnIdle { registry.deliver() }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals(initialUris + captured, visibleUris) }
            context.contentResolver.openInputStream(captured)!!.use {
                assertTrue(it.readBytes().contentEquals(byteArrayOf(3, 5, 7)))
            }
        }
        if (fullComposer) {
            val reused = File(context.cacheDir, "camera/${captured.lastPathSegment}").apply { writeText("reused") }
            dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
                .clearUnheldFiles(reused.parentFile!!)
            assertFalse("a rejected result must release its exact cache hold", reused.exists())
        }
    }

    /** Locks, releases capture holds and tears down the controller and capture file. */
    private fun cleanUp() {
        composeRule.runOnIdle {
            locked.value = true
            captures.releaseAll()
        }
        composeRule.waitForIdle()
        controller.onCleared()
        app.mutationsScope.cancel()
        if (::captured.isInitialized) File(context.cacheDir, "camera/${captured.lastPathSegment}").delete()
    }
}

private object EmptyDrafts : DraftPersistence {
    /** Starts every run with no persisted drafts. */
    override fun read(): Map<String, String> = emptyMap()

    /** Discards draft writes, the test never reads them back. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

private class Camera :
    ActivityResultRegistry(),
    ActivityResultRegistryOwner {
    override val activityResultRegistry: ActivityResultRegistry get() = this
    private var requestCode = 0
    var uri: Uri? = null

    /** Records the launch instead of starting a camera, keeping the output URI for later delivery. */
    override fun <I, O> onLaunch(
        requestCode: Int,
        contract: ActivityResultContract<I, O>,
        input: I,
        options: ActivityOptionsCompat?,
    ) {
        this.requestCode = requestCode
        uri = input as Uri
    }

    /** Dispatches the camera result for the recorded launch, successful unless [success] is false. */
    fun deliver(success: Boolean = true) {
        dispatchResult(requestCode, if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
    }
}
