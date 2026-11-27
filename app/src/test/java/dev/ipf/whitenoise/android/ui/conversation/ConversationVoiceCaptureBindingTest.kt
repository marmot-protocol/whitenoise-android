package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.audio.VoiceNoteSessionOwner
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the binding an open conversation actually uses as group membership changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationVoiceCaptureBindingTest {
    @get:Rule val compose = createComposeRule()

    @get:Rule val folder = TemporaryFolder()

    /** The old recorder stays revoked, and rejoin can start a take without remounting the conversation. */
    @Test
    fun mountedConversationCanRecordAfterRejoin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = TestScope()
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val key = VoiceNoteKey("account", "ab".repeat(32))
        val canSend = mutableStateOf(true)
        lateinit var current: VoiceRecordingController
        val owner =
            VoiceNoteSessionOwner(
                store = VoiceDraftStore(folder.root),
                newController = { _, hooks ->
                    VoiceRecordingController(
                        context = context,
                        outputDirectory = hooks.outputDirectory,
                        scope = scope,
                        onPermissionRequest = hooks.onPermissionRequest,
                        onRecordingComplete = hooks.onRecordingComplete,
                        onError = { throw it },
                        mainDispatcher = dispatcher,
                        recorderDispatcher = dispatcher,
                    )
                },
                presentError = { throw it },
                presentElsewhere = { error("unexpected microphone owner") },
                hasMicrophonePermission = { true },
            )

        compose.setContent {
            val recorder = rememberVoiceRecorderBinding(owner, key) { canSend.value }
            SideEffect { current = recorder }
        }
        lateinit var old: VoiceRecordingController
        compose.runOnIdle {
            old = current
            canSend.value = false
            owner.onTargetRemoved(key.accountRef, key.groupIdHex)
        }

        compose.runOnIdle {
            assertNotSame(old, current)
            assertFalse(current.start())
            canSend.value = true
            assertFalse(old.start())
            assertTrue(current.start())
            current.release()
        }
    }
}
