package dev.ipf.whitenoise.android.ui.conversation.composer

import dev.ipf.whitenoise.android.state.ConversationEmojiSource
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.installedEmojiFixture
import dev.ipf.whitenoise.android.ui.ConversationEmojiPicker
import dev.ipf.whitenoise.android.ui.CustomEmojiSet
import dev.ipf.whitenoise.android.ui.InstalledEmojiPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account and conversation artwork remains transient when configuring device-wide reactions. */
class EmojiPickerCatalogTest {
    /** Received and installed codes can be sent directly while persistent slots keep canonical built-ins. */
    @Test
    fun transientDefinitionsStayOutOfGlobalReactionSlots() {
        val source = ConversationEmojiSource("message", 0, reference(0))
        val received = ConversationEmojiPicker(mapOf(":remote:" to source, ":wn:" to source)) { _, _ -> null }
        val installed = InstalledEmojiPicker(mapOf(":installed:" to installedEmojiFixture("installed", "set"))) { null }
        val library = CustomEmojiSet(emptyList(), installed, received)

        assertEquals(listOf(":installed:", ":remote:", ":wn:"), emojiPickerShortcodes(library, EmojiPickerPurpose.USE))
        assertEquals(listOf(":wn:"), emojiPickerShortcodes(library, EmojiPickerPurpose.CONFIGURE_QUICK_REACTION))
        assertTrue(emojiPickerShortcodes(library, EmojiPickerPurpose.GROUP_IMAGE).isEmpty())
    }
}
