package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterCategory
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterMenu
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterOptions
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFilterPicker
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchFolderOption
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchState
import dev.ipf.whitenoise.android.ui.common.WhiteNoisePickerItem

/** The real shell-owned filter presentation receives fixed options, without native search or preference writes. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroSearchPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("extra-search-menu-")) {
        SearchMenuPresentation(fixture)
        return
    }
    val folder = fixture.scenario.startsWith("extra-search-folder-")
    var state by remember(fixture) {
        mutableStateOf(
            GlobalSearchState(
                isOpen = true,
                openFilterCategory = if (folder) GlobalSearchFilterCategory.Folder else GlobalSearchFilterCategory.Chat,
            ),
        )
    }
    val empty = fixture.scenario.endsWith("empty")
    GlobalSearchFilterPicker(
        state = state,
        options =
            GlobalSearchFilterOptions(
                folders =
                    if (empty) emptyList() else listOf(GlobalSearchFolderOption("fixture-folder", "Fixture folder")),
                chats = if (empty) emptyList() else listOf(WhiteNoisePickerItem("fixture-chat", "Fixture chat")),
            ),
        onStateChange = { transition ->
            val next = transition(state)
            if (next.openFilterCategory == null) {
                fixture.finish("dismiss")
            } else {
                if (folder) {
                    check(next.folderFilters == setOf("fixture-folder"))
                } else {
                    check(next.chatFilters.single().stableId == "fixture-chat")
                }
                fixture.record("filter-selected")
            }
            state = next
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun SearchMenuPresentation(fixture: MaestroPresentationFixture) {
    Box(Modifier.size(48.dp)) {
        GlobalSearchFilterMenu(
            expanded = true,
            state = GlobalSearchState(isOpen = true, folderFilters = setOf("fixture-folder")),
            onDismiss = { fixture.finish("dismiss") },
            onCategory = {
                check(it == GlobalSearchFilterCategory.Chat)
                fixture.finish("category-selected")
            },
            onClearAll = { fixture.finish("clear-handoff") },
        )
    }
}
