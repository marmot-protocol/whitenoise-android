@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import java.util.Locale

/** Test tag of the By me quick filter chip. */
internal const val GLOBAL_SEARCH_BY_ME_TAG = "global.search.by_me"

/**
 * The "By me" quick filter (#3349): the active identity's account id, the label its sender chip shows, and what a
 * tap does. It is a one-tap way to select the stable sender filter the Senders picker already offers as "You", so
 * MDK's native sender filter narrows global results, in-chat search steps through the account's own messages, and
 * the attachment modes list the account's own files and media. A plain class: [rememberGlobalSearchByMe] keeps one
 * instance per identity so the chip row can skip recomposition.
 */
internal class GlobalSearchByMe(
    val selfIdHex: String,
    val label: String,
    val onToggle: () -> Unit,
)

/**
 * The By me filter for the active identity [selfIdHex], or null while no identity is bound. The instance is
 * remembered per identity and label, and [onToggle] is read at tap time, so a changing callback never replaces it.
 */
@Composable
internal fun rememberGlobalSearchByMe(
    selfIdHex: String?,
    label: String,
    onToggle: (selfIdHex: String) -> Unit,
): GlobalSearchByMe? {
    val latestToggle by rememberUpdatedState(onToggle)
    return remember(selfIdHex, label) {
        selfIdHex?.let { self -> GlobalSearchByMe(self, label) { latestToggle(self) } }
    }
}

/** Whether the sender filter currently includes the active identity, compared by its canonical lowercase key. */
internal fun GlobalSearchState.byMeActive(selfIdHex: String): Boolean {
    val selfKey = globalSearchSenderKey(selfIdHex)
    return senderFilters.any { it.stableId == selfKey }
}

/** The canonical sender key the Senders picker stores: trimmed, lowercase hex. */
internal fun globalSearchSenderKey(accountIdHex: String): String = accountIdHex.trim().lowercase(Locale.ROOT)

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
    val key = globalSearchSenderKey(selfIdHex)
    return if (state.byMeActive(key)) {
        state.copy(senderFilters = state.senderFilters.filterNot { it.stableId == key }.toSet())
    } else {
        GlobalSearchTransitions.applySenderFilter(state, GlobalSearchSenderFilter(key, label))
    }
}

/**
 * The quick filter chip. TalkBack reads its visible name, "By me", with the chip's own selected or not-selected
 * state, so no separate description is needed.
 */
@Composable
internal fun GlobalSearchByMeChip(
    byMe: GlobalSearchByMe,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = byMe.onToggle,
        label = { Text(stringResource(R.string.search_quick_filter_by_me)) },
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_person), contentDescription = null, modifier = Modifier.size(18.dp))
        },
        modifier = modifier.testTag(GLOBAL_SEARCH_BY_ME_TAG),
    )
}
