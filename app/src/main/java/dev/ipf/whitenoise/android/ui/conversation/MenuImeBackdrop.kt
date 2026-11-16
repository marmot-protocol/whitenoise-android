package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val MENU_IME_RESTORE_TIMEOUT_MILLIS = 1_500L

/** A closed keyboard creates no hold; a visible handoff may still end at zero. */
internal fun captureMenuImeBackdrop(
    displayedBottom: Int?,
    liveBottom: Int,
): Int? = displayedBottom ?: liveBottom.takeIf { it > 0 }

/** Hold menu geometry, then hand it back only at the same live IME height. */
@Composable
internal fun animatedMenuImeBackdrop(
    frozenBottom: Int?,
    returning: Boolean,
    liveBottom: Int,
    onReleased: () -> Unit,
): Int? {
    val bottom = remember(frozenBottom) { Animatable((frozenBottom ?: liveBottom).toFloat()) }
    LaunchedEffect(frozenBottom, returning, liveBottom) {
        if (frozenBottom != null && returning) {
            // A changed live inset cancels and retargets from the displayed value.
            bottom.animateTo(liveBottom.toFloat(), tween(durationMillis = 160))
            onReleased()
        }
    }
    // Null, rather than zero, identifies ownership: zero is a valid animated
    // endpoint when restoration fails or the keyboard closes during handoff.
    return frozenBottom?.let { if (returning) bottom.value.roundToInt() else it }
}

/** Give keyboard restoration a bounded chance before returning to its live inset. */
internal suspend fun awaitMenuImeRestore(
    readInset: () -> Int,
    readTargetInset: () -> Int,
    awaitFrame: suspend () -> Unit,
) {
    withTimeoutOrNull(MENU_IME_RESTORE_TIMEOUT_MILLIS) {
        snapshotFlow { readInset() > 0 || readTargetInset() > 0 }.first { it }
        awaitImeInsetAtTarget(
            readInset = readInset,
            readTargetInset = readTargetInset,
            awaitFrame = awaitFrame,
        )
    }
}
