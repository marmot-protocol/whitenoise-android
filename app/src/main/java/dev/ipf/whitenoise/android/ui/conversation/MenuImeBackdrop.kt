package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.offset
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val MENU_IME_RESTORE_TIMEOUT_MILLIS = 1_500L

/** A closed keyboard creates no hold; a visible handoff may still end at zero. */
internal fun captureMenuImeBackdrop(
    displayedBottom: Int?,
    liveBottom: Int,
): Int? = displayedBottom ?: liveBottom.takeIf { it > 0 }

/**
 * [captureMenuImeBackdrop] for the held-inset reader, read once at the call. Call it from an event
 * handler: a read in composition would recompose the caller on every frame of the hand-back.
 */
internal fun captureMenuImeBackdrop(
    displayedReader: (() -> Int)?,
    liveBottom: Int,
): Int? = captureMenuImeBackdrop(displayedReader?.invoke(), liveBottom)

/**
 * The inset reader the conversation presents: a settings route's outgoing inset, which stays fixed, or
 * else the menu hand-back's reader. A reader, not a value, because the hand-back animates it on every
 * frame and only layout reads it.
 */
internal fun presentedMenuImeBottom(
    outgoingBottom: Int?,
    displayedBottom: (() -> Int)?,
): (() -> Int)? = outgoingBottom?.let { held -> { held } } ?: displayedBottom

/** The held inset in dp for the tabletop bottom bar, converted only when the bar reads it. */
internal fun Density.heldImeBottomDp(bottom: (() -> Int)?): () -> Dp = { (bottom?.invoke() ?: 0).toDp() }

/**
 * Hold menu geometry, then hand it back only at the same live IME height. Returns a reader for the held
 * inset, or null when the live IME owns it. Read it in layout or draw: reading it in composition would
 * recompose the caller on every frame of the hand-back. Only ownership, [frozenBottom], is read in
 * composition; [returning] is observed in the effect, so starting the hand-back does not recompose the caller.
 */
@Composable
internal fun animatedMenuImeBackdrop(
    frozenBottom: Int?,
    returning: () -> Boolean,
    liveBottom: () -> Int,
    onReleased: () -> Unit,
): (() -> Int)? {
    val readReturning by rememberUpdatedState(returning)
    val readLiveBottom by rememberUpdatedState(liveBottom)
    val release by rememberUpdatedState(onReleased)
    val bottom = remember(frozenBottom) { Animatable((frozenBottom ?: 0).toFloat()) }
    LaunchedEffect(frozenBottom) {
        if (frozenBottom == null) return@LaunchedEffect
        // Observe the return and its animation frames in the effect, not the conversation composition.
        // A cleared flag cancels the return where it stands: a menu reopened mid-return captures that displayed height.
        snapshotFlow { readReturning() }.collectLatest { isReturning ->
            if (isReturning) {
                snapshotFlow { readLiveBottom() }.collectLatest { live ->
                    // A changed live inset cancels and retargets from the displayed value.
                    bottom.animateTo(live.toFloat(), tween(durationMillis = 160))
                    release()
                }
            }
        }
    }
    // The held inset until the return starts, then each animation frame.
    val readHeld: () -> Int = remember(bottom) { { bottom.value.roundToInt() } }
    // Null, rather than zero, identifies ownership: zero is a valid animated
    // endpoint when restoration fails or the keyboard closes during handoff.
    return readHeld.takeIf { frozenBottom != null }
}

/**
 * Pads the bottom by the inset [bottom] reads, in pixels, like `Modifier.padding(bottom = …)`. The read
 * happens during layout, so an animating inset relayouts the content without recomposing the caller.
 */
internal fun Modifier.heldImeBottomPadding(bottom: () -> Int): Modifier =
    layout { measurable, constraints ->
        val inset = bottom().coerceAtLeast(0)
        val placeable = measurable.measure(constraints.offset(vertical = -inset))
        layout(placeable.width, constraints.constrainHeight(placeable.height + inset)) { placeable.place(0, 0) }
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
