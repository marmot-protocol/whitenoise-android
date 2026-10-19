package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
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
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun productionRootOwnsTheSavedRegistryAboveItsPrivacyGate() {
        val source =
            sequenceOf(
                File("src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
                File("app/src/main/java/dev/ipf/whitenoise/android/ui/WhiteNoiseApp.kt"),
            ).first(File::isFile).readText()
        assertTrue(
            source.indexOf("val protectedShell = rememberProtectedShellSavedState") <
                source.indexOf("if (appState.appLockScreenVisible) {"),
        )
        assertTrue(source.contains("protectedShell.holder.SaveableStateProvider(protectedShell.key)"))
    }

    @Test fun emptyDraftDisposalBeforeCameraWritesPreservesItsPendingOutput() {
        exercise(emptyComposer = true, writeAfterLock = true)
    }

    @Test fun coldRestoreReestablishesCaptureHoldWithAnEmptyDraft() {
        exercise(emptyComposer = true, restore = true, writeAfterLock = true)
    }

    @Test fun emptyDraftDisposalImmediatelyAfterResultPreservesItsAcceptedOutput() {
        exercise(emptyComposer = true, resultAndLockTogether = true)
    }

    @Test fun fullShelfRejectsTheCaptureWithoutLeakingItsOutputOrHold() = exercise(fullComposer = true)

    @Test fun cancelledCameraDiscardsTheUnusedOutput() = exercise(cancelCapture = true)

    @Test fun resultBeforeLockRetainsShelf() = exercise(resultBeforeLock = true)

    @Test fun resultWhileLockedWaitsForUnlockAndStagesOnce() = exercise()

    @Test fun cancelledAuthenticationCanRetryWithoutLosingCapture() = exercise(cancelAuthentication = true)

    @Test fun restoredProcessRecoversPendingCaptureAndExistingShelf() = exercise(restore = true)

    @Test fun callbackBeforeAccountDisposalRejectsAndCleansItsCapture() {
        exercise(resultBeforeLock = true, replaceAccount = true, replaceBeforeCallback = true)
    }

    @Test fun changingAccountWhileLockedRejectsTheOldResult() = exercise(replaceAccount = true)

    @Test fun changingRuntimeWhileLockedRejectsTheOldResult() = exercise(replaceRuntime = true)

    // Each parameter selects one lifecycle ordering in this focused matrix.
    @Suppress("LongMethod", "LongParameterList")
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
        val registry = Camera()
        var captures = PendingCameraCaptureFiles()
        val app =
            WhiteNoiseAppState(
                context,
                DraftStore(EmptyDrafts),
                { null },
                emptyList(),
                "capture-account",
            )
        val controller = ConversationController(appState = app, initialGroup = conversationTimelineTestGroup())
        var draftDisposals = 0
        val locked = mutableStateOf(false)
        val account = mutableStateOf("capture-account")
        val runtime = mutableStateOf(4)
        val existing = Uri.parse("content://fixture/existing")
        val initialUris =
            when {
                emptyComposer -> emptyList()
                fullComposer -> List(10) { Uri.parse("content://fixture/existing/$it") }
                else -> listOf(existing)
            }
        var launch: (() -> Unit)? = null
        var visibleUris = emptyList<Uri>()
        var composed = false
        var failures = 0
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registry,
                LocalPendingCameraCaptureFiles provides captures,
            ) {
                SideEffect { captures.retainOwner("${runtime.value}:${account.value}") }
                val state = rememberProtectedShellSavedState(account.value, runtime.value)
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
                            )
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
                        visibleUris = slots.map { it.uri }
                    }
                }
            }
        }
        composeRule.runOnIdle { checkNotNull(launch).invoke() }
        composeRule.waitForIdle()
        val captured = checkNotNull(registry.uri)

        fun writeCapture() {
            context.contentResolver.openOutputStream(captured)!!.use { it.write(byteArrayOf(3, 5, 7)) }
        }
        if (!writeAfterLock) writeCapture()
        val abandoned = File(context.cacheDir, "camera/abandoned.jpg").apply { writeText("abandoned") }
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
        composeRule.runOnIdle { locked.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(composed)
            assertEquals(0, failures)
            assertEquals(
                if (replaceAccount || replaceRuntime || cancelCapture || fullComposer) {
                    initialUris
                } else {
                    initialUris + captured
                },
                visibleUris,
            )
        }
        if (cancelCapture || replaceBeforeCallback || replaceAccount || replaceRuntime || fullComposer) {
            org.junit.Assert.assertThrows(java.io.FileNotFoundException::class.java) {
                context.contentResolver.openInputStream(captured)?.close()
            }
        }
        if (!replaceAccount && !replaceRuntime && !cancelCapture && !fullComposer) {
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
        composeRule.runOnIdle {
            locked.value = true
            captures.releaseAll()
        }
        composeRule.waitForIdle()
        controller.onCleared()
        app.mutationsScope.cancel()
        File(context.cacheDir, "camera/${captured.lastPathSegment}").delete()
    }

    private object EmptyDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

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

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            this.requestCode = requestCode
            uri = input as Uri
        }

        fun deliver(success: Boolean = true) {
            dispatchResult(requestCode, if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
        }
    }
}
