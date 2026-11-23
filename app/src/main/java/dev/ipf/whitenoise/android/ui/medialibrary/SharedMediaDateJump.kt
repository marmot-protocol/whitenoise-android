package dev.ipf.whitenoise.android.ui.medialibrary

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.CivilDatePickerDialog
import java.time.LocalDate

/** Days with media loaded under the current filter, in the zone that buckets the grid's months. */
internal fun List<MediaMonthSection<SharedMediaTile>>.mediaDays(): Set<LocalDate> =
    flatMapTo(HashSet()) { section -> section.items.map { mediaDayForMedia(it.recordedAt) } }

/**
 * Grid index of the first loaded tile from [day] in [MediaTileGrid]'s header-then-tiles layout. A day
 * that opens its section resolves to the month header so the jump keeps the month's heading in view.
 */
internal fun List<MediaMonthSection<SharedMediaTile>>.dayIndex(day: LocalDate): Int? {
    var header = 0
    for (section in this) {
        val offset = section.items.indexOfFirst { mediaDayForMedia(it.recordedAt) == day }
        if (offset >= 0) return if (offset == 0) header else header + 1 + offset
        header += 1 + section.items.size
    }
    return null
}

/** Day of the tile at grid [index], or of the first tile under a header, so the picker opens where the grid is. */
internal fun List<MediaMonthSection<SharedMediaTile>>.dayAt(index: Int): LocalDate? {
    var header = 0
    for (section in this) {
        val end = header + 1 + section.items.size
        if (index < end) {
            val tile = section.items.getOrNull((index - header - 1).coerceAtLeast(0))
            return tile?.let { mediaDayForMedia(it.recordedAt) }
        }
        header = end
    }
    return null
}

/**
 * Opens the app's calendar on the day being viewed once media from two or more days is loaded. Only days
 * with loaded media can be chosen, and the year list offers only years that have them.
 */
@Suppress("FunctionNaming")
@Composable
internal fun MediaDateJumpAction(
    sections: List<MediaMonthSection<SharedMediaTile>>,
    gridState: LazyGridState?,
    ready: Boolean,
    onJump: (LocalDate) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val days = remember(sections) { sections.mediaDays() }
    // An open calendar stays composed while a refresh briefly empties the grid.
    if (days.size < 2 && !open) return
    IconButton(onClick = { open = true }, modifier = Modifier.testTag("shared.date.jump")) {
        Icon(
            painterResource(R.drawable.ic_calendar_month),
            contentDescription = stringResource(R.string.shared_media_jump_to_date),
        )
    }
    if (!open) return
    val viewing = remember { gridState?.let { sections.dayAt(it.firstVisibleItemIndex) } }
    // A refresh restores its pages one at a time; the open calendar keeps its days until the grid is ready.
    var shownDays by remember { mutableStateOf(days) }
    LaunchedEffect(days, ready) { if (ready && days.isNotEmpty()) shownDays = days }
    CivilDatePickerDialog(
        title = stringResource(R.string.shared_media_jump_to_date),
        initialDate = viewing ?: shownDays.maxOrNull(),
        confirmLabel = stringResource(R.string.show),
        confirmTag = "shared.date.show",
        onDismiss = { open = false },
        onConfirm = { day ->
            onJump(day)
            open = false
        },
        availableDays = shownDays,
    )
}

/**
 * Places the chosen day's first loaded tile at the top once the grid is showing. The position is
 * resolved against the sections current at that moment; a day they no longer contain is dropped.
 */
@Suppress("FunctionNaming")
@Composable
internal fun MediaDateJumpEffect(
    pendingDay: LocalDate?,
    sections: List<MediaMonthSection<SharedMediaTile>>,
    gridState: LazyGridState,
    ready: Boolean,
    onSettled: () -> Unit,
) {
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(pendingDay, sections, gridState, ready) {
        val day = pendingDay ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        sections.dayIndex(day)?.let { gridState.scrollToItem(it) }
        settled()
    }
}
