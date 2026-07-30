package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

internal class ChatFolderHandoffState {
    var pickerChatIds by mutableStateOf<List<String>?>(null)
    var editorChatIds by mutableStateOf<Set<String>?>(null)
    var editingFolderId by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberFolderHandoff(accountRef: String?) = remember(accountRef) { ChatFolderHandoffState() }
