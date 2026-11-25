package dev.ipf.whitenoise.android

import java.io.File

/** Reads an owner and its moved declarations together for source contract assertions. */
internal fun File.motionOwnerSource(): String {
    val parts =
        when (name) {
            "ProfileEditScreen.kt" -> listOf("ProfileImageDrafts.kt")
            "ConversationScreen.kt" ->
                listOf(
                    "ConversationNavigationState.kt",
                    "ConversationMessageNavigator.kt",
                    "ConversationMessageDragSelection.kt",
                    "ConversationReadAloudFollow.kt",
                    "ConversationBodyExpansionPosition.kt",
                )
            "ChatsScreen.kt" -> listOf("ChatListActions.kt", "ChatListDragSelection.kt")
            "ConversationMediaDraft.kt" -> listOf("ConversationMediaDraftPhotos.kt")
            "SettingsScreen.kt" -> listOf("SettingsAppUpdates.kt")
            "AccountKeysScreen.kt" -> listOf("ProfileKeyClipboard.kt")
            "DiagnosticsScreen.kt" -> listOf("DiagnosticsState.kt")
            "RelaysScreen.kt" -> listOf("RelayDescriptions.kt")
            "MediaFileBubble.kt" -> listOf("AttachmentLifecycle.kt")
            "TextAttachmentReader.kt" -> listOf("TextAttachmentSpeech.kt")
            "MediaViewer.kt" -> listOf("MediaViewerPage.kt")
            "SettingsPageMorph.kt" -> listOf("SettingsPageMorphGeometry.kt", "SettingsPageMorphRegistration.kt")
            else -> emptyList()
        }
    return (listOf(this) + parts.map { File(parentFile, it) }).joinToString("\n") { it.readText() }
}
