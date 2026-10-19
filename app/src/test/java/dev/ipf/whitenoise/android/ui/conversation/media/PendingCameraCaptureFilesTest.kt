package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.navigation.MainShellProcessState
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Camera leases protect exactly the pending request, and every terminal owner transition releases it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingCameraCaptureFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun restoredHoldIsIdempotentAndTransfersOnlyToTheMatchingNativeSource() {
        val directory = temporary.newFolder("camera")
        val file = directory.resolve("capture.jpg").apply { writeText("capture") }
        val abandoned = directory.resolve("abandoned.jpg").apply { writeText("abandoned") }
        val uri = Uri.parse("content://capture/selected")
        val leases = PendingCameraCaptureFiles()
        leases.retainOwner("account:runtime")
        leases.hold(file, uri, "account:group")
        leases.hold(file, uri, "account:group")
        AttachmentPlaintextCache.clearUnheldFiles(directory)
        assertTrue(file.isFile)
        assertFalse(abandoned.exists())
        leases.releasePersisted(Uri.parse("content://capture/unrelated"))
        AttachmentPlaintextCache.clearUnheldFiles(directory)
        assertTrue(file.isFile)
        leases.releasePersisted(uri)
        AttachmentPlaintextCache.clearUnheldFiles(directory)
        assertFalse(file.exists())
    }

    @Test
    fun retiringAnOutgoingControllerCannotDeleteARetainedSameGroupCapture() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = WhiteNoiseAppState(context, DraftStore(EmptyDrafts), { null }, emptyList(), "account")
        val group = conversationTimelineTestGroup()
        val process = MainShellProcessState(app)
        process.conversationController("chat", "account", 0, 1) {
            ConversationController(appState = app, initialGroup = group)
        }
        val current =
            process.conversationController("chat", "account", 0, 2) {
                ConversationController(appState = app, initialGroup = group)
            }
        val file = temporary.newFile("capture.jpg").apply { writeText("capture") }
        process.pendingCameraCaptureFiles.hold(
            file,
            Uri.parse("content://capture/selected"),
            "account:${group.groupIdHex}",
        )
        process.retainConversationControllers(listOf(current))
        assertTrue(file.isFile)
        process.retainConversationControllers(emptyList())
        assertFalse(file.exists())
        process.release()
        app.mutationsScope.cancel()
    }

    @Test
    fun retiringAnOwnerDiscardsOnlyItsOwnPendingOutputAndReleasesTheHold() {
        val directory = temporary.newFolder("camera")
        val file = directory.resolve("capture.jpg").apply { writeText("capture") }
        val unrelated = directory.resolve("unrelated.jpg").apply { writeText("other") }
        val leases = PendingCameraCaptureFiles()
        leases.retainOwner("account:runtime")
        leases.hold(file, Uri.parse("content://capture/selected"), "account:group")
        leases.retainOwner("other-account:runtime")
        assertFalse(file.exists())
        assertTrue(unrelated.isFile)
        file.writeText("reused path")
        AttachmentPlaintextCache.clearUnheldFiles(directory)
        assertFalse("retirement must release the publication hold", file.exists())
    }

    private object EmptyDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
