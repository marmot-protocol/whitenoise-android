// The file names its primary composable; its row/action model stays colocated.
@file:Suppress("MatchingDeclarationName")

package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold
import dev.ipf.whitenoise.android.ui.settings.SettingsSection

/** Existing picker capabilities, including selected-member review and profile long-press. */
internal data class NewGroupRecipientActions(
    val back: () -> Unit,
    val confirm: () -> Unit,
    val review: () -> Unit,
    val scan: () -> Unit,
    val pasteRejected: () -> Unit,
    val retry: () -> Unit,
    val toggle: (RecipientSearch.Candidate) -> Unit,
    val profile: (RecipientSearch.Candidate) -> Unit,
)

/** Search-first prototype member selection, with a pinned action including explicit solo-group creation. */
@Composable
@Suppress("FunctionNaming", "LongMethod") // Compose naming follows the framework convention.
internal fun NewGroupRecipientContent(
    query: TextFieldState,
    people: List<GroupCreationPerson>,
    selected: List<GroupCreationPerson>,
    searching: Boolean,
    failed: Boolean,
    incomplete: Boolean,
    actions: NewGroupRecipientActions,
) {
    SettingsScaffold(
        title = stringResource(R.string.new_group),
        onBack = actions.back,
        modifier = Modifier.imePadding().testTag("new_group.screen"),
        bottomBar = {
            GroupCreationBottomAction(
                stringResource(if (selected.isEmpty()) R.string.group_create_solo else R.string.group_continue),
                enabled = true,
                onClick = actions.confirm,
                tag = "new_group.continue",
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag("new_group.list"), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                NewGroupRecipientSearchField(
                    query,
                    actions.pasteRejected,
                    actions.scan,
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("new_group.search"),
                )
            }
            if (selected.isNotEmpty()) {
                item { SettingsSection(stringResource(R.string.group_selected_people)) }
                item {
                    LazyRow(
                        Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(selected, key = { it.candidate.accountIdHex }) { person ->
                            GroupSelectedPerson(person) { actions.toggle(person.candidate) }
                        }
                    }
                }
                item {
                    TextButton(actions.review, Modifier.padding(horizontal = 16.dp).testTag("new_group.review")) {
                        Text(stringResource(R.string.group_review_people))
                    }
                }
            }
            item { SettingsSection(stringResource(R.string.new_message_people)) }
            if (searching) item { UserSearchStatusRow(R.string.user_search_searching, showProgress = true) }
            if (failed || incomplete) {
                item {
                    UserSearchStatusRow(if (failed) R.string.user_search_failed else R.string.user_search_incomplete)
                    TextButton(actions.retry, Modifier.padding(horizontal = 16.dp).testTag("new_group.retry")) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            val searchComplete = !searching && !failed && !incomplete
            if (people.isEmpty() && searchComplete) {
                item {
                    WhiteNoiseEmptyState(
                        stringResource(R.string.no_matches),
                        stringResource(
                            if (query.text.isBlank()) {
                                R.string.recipient_search_empty_hint
                            } else {
                                R.string.search_people_hint
                            },
                        ),
                    )
                }
            }
            itemsIndexed(people, key = { _, person -> person.candidate.accountIdHex }) { index, person ->
                GroupCreationPersonRow(
                    person,
                    index,
                    people.size,
                    selected = selected.any { it.candidate.accountIdHex.equals(person.candidate.accountIdHex, true) },
                    onClick = { actions.toggle(person.candidate) },
                    onLongClick = { actions.profile(person.candidate) },
                )
            }
        }
    }
}
