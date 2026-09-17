package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long a new newest row takes to enter, matched by the rows above sliding to their new slots. */
internal const val CONVERSATION_TAIL_ENTRANCE_MILLIS = 180

/** Slack after the entrance so the entering id is never released mid-motion. */
private const val CONVERSATION_TAIL_ENTRANCE_SETTLE_MILLIS = 80L

/**
 * The one coordinated motion a new newest row gets while the reader follows
 * the tail. The list lays the row out in its slot at once, which shifts every
 * row above it up by the row's height in a single frame; this draws all of them
 * shifted back down by that same height in that frame and then eases the shift
 * to zero, so the rows above slide up to their new slots while the new row
 * rises from beneath its own, on one short curve. Layout is never animated, so
 * paging, keyboard tracking and history reading are untouched, and the shift is
 * exactly zero outside an entrance.
 */
@Stable
internal class ConversationTailEntrance internal constructor(
    private val scope: CoroutineScope,
    private val rowSpacingPx: Float,
) {
    /** The row currently entering, or null when no entrance is in flight. */
    var enteringItemId: String? by mutableStateOf(null)
        internal set

    private var startedForItemId: String? = null
    private val shift = Animatable(0f)

    /** How far below its laid-out slot every row is drawn right now, in pixels. */
    var shiftPx: Float by mutableFloatStateOf(0f)
        private set

    /** Starts the motion from the entering row's first measure, once per row. */
    fun onEnteringRowMeasured(
        itemId: String,
        heightPx: Int,
    ) {
        if (itemId != enteringItemId || startedForItemId == itemId) return
        startedForItemId = itemId
        // Written during layout and read only while drawing, so the very frame
        // that lays the row out already draws everything shifted, with no jump.
        val startPx = heightPx + rowSpacingPx
        shiftPx = startPx
        scope.launch {
            shift.snapTo(startPx)
            shift.animateTo(0f, tween(CONVERSATION_TAIL_ENTRANCE_MILLIS, easing = FastOutSlowInEasing)) {
                shiftPx = value
            }
            shiftPx = 0f
        }
    }
}

/**
 * Whether a newest-row change is an entrance the reader should see move: a
 * genuine append (the row followed before is still present) seen by a reader
 * who is following the tail of an anchored transcript.
 */
internal fun conversationTailEntranceArms(
    latestItemId: String?,
    lastFollowedLatestId: String?,
    previousStillPresent: Boolean,
    followingTail: Boolean,
    initialTimelineAnchored: Boolean,
): Boolean =
    initialTimelineAnchored &&
        followingTail &&
        latestItemId != null &&
        lastFollowedLatestId != null &&
        latestItemId != lastFollowedLatestId &&
        previousStillPresent

/**
 * Tracks which row is entering. The composition that inserts the row already
 * resolves it straight from [conversationTailEntranceArms], before any effect
 * runs, and the held id keeps it resolved until the motion has settled.
 */
@Composable
internal fun rememberConversationTailEntrance(
    latestItemId: String?,
    lastFollowedLatestId: String?,
    previousStillPresent: Boolean,
    followingTail: Boolean,
    initialTimelineAnchored: Boolean,
    rowSpacingPx: Float = with(LocalDensity.current) { CONVERSATION_TIMELINE_ROW_SPACING.toPx() },
): ConversationTailEntrance {
    val scope = rememberCoroutineScope()
    val entrance = remember(scope) { ConversationTailEntrance(scope, rowSpacingPx) }
    val candidate =
        latestItemId.takeIf {
            conversationTailEntranceArms(
                latestItemId = latestItemId,
                lastFollowedLatestId = lastFollowedLatestId,
                previousStillPresent = previousStillPresent,
                followingTail = followingTail,
                initialTimelineAnchored = initialTimelineAnchored,
            )
        }
    var heldItemId by remember { mutableStateOf<String?>(null) }
    SideEffect {
        if (candidate != null && heldItemId != candidate) heldItemId = candidate
    }
    LaunchedEffect(heldItemId) {
        if (heldItemId == null) return@LaunchedEffect
        delay(CONVERSATION_TAIL_ENTRANCE_MILLIS + CONVERSATION_TAIL_ENTRANCE_SETTLE_MILLIS)
        heldItemId = null
    }
    entrance.enteringItemId = candidate ?: heldItemId
    return entrance
}

/** Draws this row shifted by the entrance in flight and, for the entering row itself, starts that entrance. */
internal fun Modifier.conversationTailEntranceMotion(
    entrance: ConversationTailEntrance,
    itemId: String,
): Modifier {
    val shifted = graphicsLayer { translationY = entrance.shiftPx }
    return if (entrance.enteringItemId == itemId) {
        // Measured, not size-changed: the row's first measure is the frame that
        // moves everything, and a second pass is harmless once it has started.
        shifted.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            entrance.onEnteringRowMeasured(itemId, placeable.height)
            layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
        }
    } else {
        shifted
    }
}
