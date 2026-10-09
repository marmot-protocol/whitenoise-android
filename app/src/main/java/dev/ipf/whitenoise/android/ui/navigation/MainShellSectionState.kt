package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle

private const val SECTION_ACCOUNT_KEY = "main_shell_section_account"
private const val SECTION_RUNTIME_KEY = "main_shell_section_runtime"
private const val SECTION_NAME_KEY = "main_shell_section_name"
private const val SECTION_DETAIL_KEY = "main_shell_section_detail"

/** Retains only destination enum names while the protected shell is absent behind app lock. */
internal class MainShellSectionState(
    private val savedState: SavedStateHandle,
) {
    private var accountRef = savedState.get<Any>(SECTION_ACCOUNT_KEY) as? String
    private var runtimeGeneration = savedState.get<Any>(SECTION_RUNTIME_KEY) as? Int
    private val section = mutableStateOf(validSection(savedState.get<Any>(SECTION_NAME_KEY) as? String))
    private val detail = mutableStateOf(validDetail(savedState.get<Any>(SECTION_DETAIL_KEY) as? String))

    var sectionName: String
        get() = section.value
        set(value) {
            if (accountRef == null) return
            section.value = validSection(value)
            savedState[SECTION_NAME_KEY] = section.value
        }

    var settingsDetailName: String?
        get() = detail.value
        set(value) {
            if (accountRef == null) return
            detail.value = validDetail(value)
            savedState[SECTION_DETAIL_KEY] = detail.value
        }

    /** Bind before rendering; a replacement account/runtime cannot recover the previous destination. */
    fun bind(
        ownerAccountRef: String?,
        ownerRuntimeGeneration: Int,
    ) {
        if (ownerAccountRef == null) {
            clear()
        } else if (accountRef != ownerAccountRef || runtimeGeneration != ownerRuntimeGeneration) {
            clear()
            accountRef = ownerAccountRef
            runtimeGeneration = ownerRuntimeGeneration
            savedState[SECTION_ACCOUNT_KEY] = ownerAccountRef
            savedState[SECTION_RUNTIME_KEY] = ownerRuntimeGeneration
        }
    }

    /** Terminal navigation and explicitly released holders discard the lightweight saved route. */
    fun clear() {
        accountRef = null
        runtimeGeneration = null
        section.value = MainSection.Chats.name
        detail.value = null
        savedState.remove<String>(SECTION_ACCOUNT_KEY)
        savedState.remove<Int>(SECTION_RUNTIME_KEY)
        savedState.remove<String>(SECTION_NAME_KEY)
        savedState.remove<String>(SECTION_DETAIL_KEY)
    }

    private fun validSection(value: String?): String =
        MainSection.entries.firstOrNull { it.name == value }?.name ?: MainSection.Chats.name

    private fun validDetail(value: String?): String? = SettingsDetail.entries.firstOrNull { it.name == value }?.name
}
