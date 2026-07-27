package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleMediaReferenceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun projectedReferenceChangeRefreshesTheRememberedMedia() {
        val first = listOf(reference("first.jpg"))
        val second = listOf(reference("second.jpg"))
        val projectedMedia = mutableStateOf<List<MediaAttachmentReferenceFfi>?>(first)
        var rendered: List<MediaAttachmentReferenceFfi>? = null

        composeRule.setContent {
            rendered =
                rememberMessageMediaReferences(
                    tags = emptyList(),
                    messageIdHex = MESSAGE_ID,
                    sourceEpoch = null,
                    projectedMedia = projectedMedia.value,
                )
        }

        composeRule.runOnIdle { assertSame(first, rendered) }
        composeRule.runOnIdle { projectedMedia.value = second }
        composeRule.runOnIdle { assertSame(second, rendered) }
    }

    @Test
    fun emptyProjectedMediaIsAuthoritativeOverCompatibilityTags() {
        // Deliberately malformed: if the compatibility parser runs in a local
        // JVM test it would attempt to load the native UniFFI library. An empty
        // projected list must be returned directly instead.
        val tags = listOf(MessageTagFfi(listOf("imeta", "invalid")))
        var rendered: List<MediaAttachmentReferenceFfi>? = null

        composeRule.setContent {
            rendered =
                rememberMessageMediaReferences(
                    tags = tags,
                    messageIdHex = MESSAGE_ID,
                    sourceEpoch = 77uL,
                    projectedMedia = emptyList(),
                )
        }

        composeRule.runOnIdle { assertTrue(requireNotNull(rendered).isEmpty()) }
    }

    private fun reference(fileName: String) =
        MediaAttachmentReferenceFfi(
            locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://example.com/$fileName")),
            ciphertextSha256 = "a".repeat(64),
            plaintextSha256 = "b".repeat(64),
            nonceHex = "c".repeat(24),
            fileName = fileName,
            mediaType = "image/jpeg",
            version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1uL,
            dim = null,
            thumbhash = null,
        )

    private companion object {
        const val MESSAGE_ID = "message-a"
    }
}
