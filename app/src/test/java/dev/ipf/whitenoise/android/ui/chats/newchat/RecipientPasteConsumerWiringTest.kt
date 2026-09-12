package dev.ipf.whitenoise.android.ui.chats.newchat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the shared recipient paste boundary across its intended surfaces. */
class RecipientPasteConsumerWiringTest {
    @Test
    fun allRecipientSurfacesRouteThroughTheSharedPastePolicy() {
        val newChat = source("ui/chats/newchat/NewChatFlow.kt")
        val newMessageContent = source("ui/chats/newchat/NewMessagePresentation.kt")
        val contactPicker = source("ui/chats/newchat/ContactPickerScreen.kt")
        val conversation = source("ui/conversation/ConversationScreen.kt")
        val groupDetails = source("ui/group/GroupDetailsScreen.kt")

        assertTrue(newChat.contains("NewMessageContent("))
        assertTrue(newMessageContent.contains("RecipientSearchField("))
        val groupFlow = source("ui/chats/newchat/NewGroupCreationFlow.kt")
        val groupPicker = source("ui/chats/newchat/NewGroupRecipientPickerScreen.kt")
        val groupContent = source("ui/chats/newchat/NewGroupRecipientContent.kt")
        val groupSearch = source("ui/chats/newchat/NewGroupRecipientSearchField.kt")
        assertTrue(groupFlow.contains("NewGroupRecipientPickerScreen("))
        assertTrue(groupPicker.contains("NewGroupRecipientContent("))
        assertTrue(groupContent.contains("NewGroupRecipientSearchField("))
        assertTrue(groupSearch.contains("dispatchRecipientPaste("))
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
