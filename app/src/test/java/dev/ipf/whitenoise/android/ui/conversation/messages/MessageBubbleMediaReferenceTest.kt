package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.media.MediaReferenceParser
import org.junit.Assert.assertSame
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
    fun perMessageReferenceChangeRefreshesTheRememberedMedia() {
        val first = listOf(reference("first.jpg"))
        val second = listOf(reference("second.jpg"))
        val referencesByMessage = mutableStateOf(mapOf(MESSAGE_ID to first))
        var rendered: List<MediaAttachmentReferenceFfi>? = null

        composeRule.setContent {
            rendered =
                rememberMessageMediaReferences(
                    tags = emptyList(),
                    messageIdHex = MESSAGE_ID,
                    perMessageMediaReferences = referencesByMessage.value[MESSAGE_ID],
                )
        }

        composeRule.runOnIdle { assertSame(first, rendered) }
        composeRule.runOnIdle { referencesByMessage.value = mapOf(MESSAGE_ID to second) }
        composeRule.runOnIdle { assertSame(second, rendered) }
    }

    @Test
    fun unrelatedReferenceMapChangeDoesNotReparseTheStaleMessage() {
        val tags = listOf(MediaReferenceParser.toImetaTag(reference("fallback.jpg")))
        val referencesByMessage = mutableStateOf(emptyMap<String, List<MediaAttachmentReferenceFfi>>())
        var rendered: List<MediaAttachmentReferenceFfi>? = null

        composeRule.setContent {
            rendered =
                rememberMessageMediaReferences(
                    tags = tags,
                    messageIdHex = MESSAGE_ID,
                    perMessageMediaReferences = referencesByMessage.value[MESSAGE_ID],
                )
        }

        lateinit var firstParsed: List<MediaAttachmentReferenceFfi>
        composeRule.runOnIdle { firstParsed = requireNotNull(rendered) }
        composeRule.runOnIdle {
            referencesByMessage.value = mapOf("another-message" to listOf(reference("other.jpg")))
        }
        composeRule.runOnIdle { assertSame(firstParsed, rendered) }
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
