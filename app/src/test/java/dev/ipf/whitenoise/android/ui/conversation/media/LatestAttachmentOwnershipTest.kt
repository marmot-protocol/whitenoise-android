package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.drawable.ColorDrawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LatestAttachmentOwnershipTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun pageDisposalClosesTheLatestAssignedDrawableBeforeAnyImageComposes() {
        var mounted by mutableStateOf(true)
        val presentation = mutableStateOf<DecodedAttachmentPresentation?>(null)
        val drawable =
            object : ColorDrawable(android.graphics.Color.RED), java.io.Closeable {
                var closed = false

                override fun close() {
                    closed = true
                }
            }
        composeRule.setContent { if (mounted) OwnLatestAttachmentPresentation("page", presentation) }
        composeRule.runOnIdle {
            presentation.value = DecodedAttachmentPresentation.Animated(drawable)
            mounted = false
        }
        composeRule.waitForIdle()
        assertTrue(drawable.closed)
    }
}
