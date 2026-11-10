@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import java.util.Locale

internal const val GLOBAL_SEARCH_BY_ME_TAG = "global.search.by_me"

/**
 * The "By me" quick filter (#3349): the active identity's account id and the label its sender chip shows. It is a
 * one-tap way to select the stable sender filter the Senders picker already offers as "You", so MDK's native sender
 * filter narrows global results and in-chat search steps through the account's own messages.
 */
internal data class GlobalSearchByMe(
    val selfIdHex: String,
    val label: String,
    val onToggle: () -> Unit,
)

/** Whether the sender filter currently includes the active identity [selfIdHex]. */
internal fun GlobalSearchState.byMeActive(selfIdHex: String): Boolean {
    val selfKey = selfIdHex.trim()
    return senderFilters.any { it.stableId.equals(selfKey, ignoreCase = true) }
}

/**
 * Adds the active identity to the sender filter, or removes it when present. The sender key is the same lowercase
 * account id the Senders picker uses, so the chip and the picker's "You" row are one selection; other selected
 * senders stay, combined with OR as in the picker.
 */
internal fun toggleGlobalSearchByMe(
    state: GlobalSearchState,
    selfIdHex: String,
    label: String,
): GlobalSearchState {
    val key = selfIdHex.trim().lowercase(Locale.ROOT)
    return if (state.byMeActive(key)) {
        state.copy(senderFilters = state.senderFilters.filterNot { it.stableId.equals(key, ignoreCase = true) }.toSet())
    } else {
        GlobalSearchTransitions.applySenderFilter(state, GlobalSearchSenderFilter(key, label))
    }
}

/** The quick filter chip; TalkBack reads it as a toggle named "Messages by me" with its selected state. */
@Composable
internal fun GlobalSearchByMeChip(
    byMe: GlobalSearchByMe,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.search_quick_filter_by_me_description)
    FilterChip(
        selected = selected,
        onClick = byMe.onToggle,
        label = { Text(stringResource(R.string.search_quick_filter_by_me)) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(18.dp))
        },
        modifier = modifier.testTag(GLOBAL_SEARCH_BY_ME_TAG).semantics { contentDescription = description },
    )
}
