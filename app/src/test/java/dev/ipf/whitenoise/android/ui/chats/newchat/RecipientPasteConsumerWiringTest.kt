package dev.ipf.whitenoise.android.ui.chats.newchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the shared recipient boundary across its four intended surfaces only. */
class RecipientPasteConsumerWiringTest {
    @Test
    fun allRecipientSurfacesRouteThroughTheSharedField() {
        val newChat = source("ui/chats/newchat/NewChatFlow.kt")
        val contactPicker = source("ui/chats/newchat/ContactPickerScreen.kt")
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val groupDetails = source("ui/group/GroupDetailsScreen.kt")

        assertTrue(newChat.contains("RecipientSearchField("))
        assertTrue(newChat.contains("ContactPickerScreen("))
        assertTrue(contactPicker.contains("RecipientSearchField("))
        assertTrue(conversation.contains("ContactPickerScreen("))
        assertTrue(groupDetails.contains("ContactPickerScreen("))
    }

    @Test
    fun genericSearchFieldRemainsOutsideTheRecipientPastePolicy() {
        val genericField = source("ui/chats/newchat/ChatFlowComponents.kt")

        assertFalse(genericField.contains("RecipientPastePolicy"))
        assertFalse(genericField.contains("RecipientSearchField("))
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$relativePath"),
        ).first(File::isFile).readText()
}
