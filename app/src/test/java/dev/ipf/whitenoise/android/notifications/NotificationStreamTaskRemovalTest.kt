package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.WhiteNoiseApplication
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = WhiteNoiseApplication::class)
class NotificationStreamTaskRemovalTest {
    @Test
    fun recentsSwipeClearsProcessOwnedConversationStateThroughTheServiceBoundary() {
        val application = RuntimeEnvironment.getApplication() as WhiteNoiseApplication
        val processState = application.mainShellProcessState
        processState.selectedChatJustCreated.value = true
        processState.selectedChatOpenedAsDmHint.value = true
        val serviceController =
            Robolectric
                .buildService(NotificationStreamForegroundService::class.java)
                .create()
        val service = serviceController.get()

        service.onTaskRemoved(null)

        assertFalse(processState.selectedChatJustCreated.value)
        assertFalse(processState.selectedChatOpenedAsDmHint.value)
        serviceController.destroy()
    }
}
