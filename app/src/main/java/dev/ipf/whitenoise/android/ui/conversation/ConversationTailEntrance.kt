package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

    /** One row claimed by its send flight for this entrance, including early retirement. */
    internal var claimedEnteringItemId: String? by mutableStateOf(null)
        private set

    /** Composition owns claims even when the hidden destination skips drawing. */
    internal fun claimPresentation(presentationId: String?) {
        if (claimedEnteringItemId != enteringItemId) claimedEnteringItemId = null
        if (motionEnabled && presentationId != null && presentationId == enteringItemId) {
            claimedEnteringItemId = presentationId
        }
    }

    /** Ends a settled entrance outside composition and drops its send claim, so neighbours keep final slots. */
    internal fun release(itemId: String) {
        if (enteringItemId != itemId) return
        enteringItemId = null
        if (claimedEnteringItemId == itemId) claimedEnteringItemId = null
    }

    private var startedForItemId: String? = null
    private val shift = Animatable(0f)
    private var animation: Job? = null
    var motionEnabled: Boolean = true
        internal set(value) {
            field = value
            if (!value) {
                animation?.cancel()
                shiftPx = 0f
                claimedEnteringItemId = null
            }
        }

    /** How far below its laid-out slot every row is drawn right now, in pixels. */
    var shiftPx: Float by mutableFloatStateOf(0f)
        private set

    /**
     * Whether the list has scrolled to the entering row. A reversed list lays the inserted row out under the
     * composer, in its bottom padding, a frame before the tail follow reaches it; starting there would draw every
     * row above a full row-height below its slot. Read while measuring, so reaching the tail remeasures the row.
     */
    internal var tailReached: () -> Boolean = { true }

    /** Starts the motion from the entering row's first measure at the tail, once per row. */
    fun onEnteringRowMeasured(
        itemId: String,
        heightPx: Int,
    ) {
        val pendingStart = motionEnabled && itemId == enteringItemId && startedForItemId != itemId
        if (!pendingStart || !tailReached()) return
        startedForItemId = itemId
        // Written during layout and read only while drawing, so the very frame
        // that lays the row out already draws everything shifted, with no jump.
        val startPx = heightPx + rowSpacingPx
        shiftPx = startPx
        animation?.cancel()
        animation =
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
 * Whether a newest-row change is an entrance the reader should see move: exactly
 * one row appended after the row followed before, seen by a reader who is
 * following the tail of an anchored transcript. A batch of arrivals is not
 * armed, since the shift is measured from the newest row alone; an older-page
 * trim that dropped the followed row is not an append at all.
 */
internal fun conversationTailEntranceArms(
    latestItemId: String?,
    lastFollowedLatestId: String?,
    previousIsNewestButOne: Boolean,
    followingTail: Boolean,
    initialTimelineAnchored: Boolean,
): Boolean =
    initialTimelineAnchored &&
        followingTail &&
        latestItemId != null &&
        lastFollowedLatestId != null &&
        latestItemId != lastFollowedLatestId &&
        previousIsNewestButOne

/**
 * Tracks which row is entering. The composition that inserts the row already
 * resolves it straight from [conversationTailEntranceArms], before any effect
 * runs, and the held id keeps it resolved until the motion has settled.
 */
@Composable
internal fun rememberConversationTailEntrance(
    latestItemId: String?,
    lastFollowedLatestId: String?,
    previousIsNewestButOne: Boolean,
    followingTail: Boolean,
    initialTimelineAnchored: Boolean,
    rowSpacingPx: Float = with(LocalDensity.current) { CONVERSATION_TIMELINE_ROW_SPACING.toPx() },
    motionEnabled: Boolean = rememberConversationMotionEnabled(),
    ownerKey: Any? = null,
    tailReached: () -> Boolean = { true },
): ConversationTailEntrance {
    val scope = rememberCoroutineScope()
    val entrance = remember(scope, ownerKey) { ConversationTailEntrance(scope, rowSpacingPx) }
    entrance.tailReached = tailReached
    DisposableEffect(entrance) { onDispose { entrance.motionEnabled = false } }
    val candidate =
        latestItemId.takeIf {
            conversationTailEntranceArms(
                latestItemId = latestItemId,
                lastFollowedLatestId = lastFollowedLatestId,
                previousIsNewestButOne = previousIsNewestButOne,
                followingTail = followingTail,
                initialTimelineAnchored = initialTimelineAnchored,
            )
        }
    val hold = remember(ownerKey) { TailEntranceHold() }
    SideEffect {
        if (candidate == null || hold.itemId == candidate) return@SideEffect
        hold.itemId = candidate
        hold.release?.cancel()
        hold.release =
            scope.launch {
                delay(CONVERSATION_TAIL_ENTRANCE_MILLIS + CONVERSATION_TAIL_ENTRANCE_SETTLE_MILLIS)
                hold.itemId = null
                // Rows read the entering id only while measuring, so releasing it here relayouts them
                // instead of recomposing the transcript.
                entrance.release(candidate)
            }
    }
    entrance.motionEnabled = motionEnabled
    entrance.enteringItemId = (candidate ?: hold.itemId).takeIf { motionEnabled }
    return entrance
}

/**
 * Claims the entering row for its send flight during composition, from this small scope of its own, so a flight
 * starting or retiring recomposes only this claim and not the transcript that owns the entrance. The scope also
 * observes the entering row, because a flight accepted before its row publishes must claim it when it arrives.
 */
@Composable
@Suppress("FunctionNaming")
internal fun TailEntranceSendClaim(
    entrance: ConversationTailEntrance,
    claimedPresentationId: () -> String?,
) {
    val claimed = claimedPresentationId()
    val entering = entrance.enteringItemId
    SideEffect { if (entering == entrance.enteringItemId) entrance.claimPresentation(claimed) }
}

/** Plain bookkeeping, so holding and releasing an entrance never invalidates the composition that owns it. */
private class TailEntranceHold {
    var itemId: String? = null
    var release: Job? = null
}

/** The claim source for rows that no send flight can claim. */
private val NoClaimedPresentation: () -> String? = { null }

/**
 * Draws this row shifted by the entrance in flight and, for the entering row itself, starts that entrance.
 *
 * The element compares equal for the same entrance, row and claim source, so recomposing the transcript does not
 * re-run unchanged rows. Entrance state is read only while measuring and drawing, never during composition, so an
 * entrance starting or settling invalidates layout and layers instead of recomposing every visible row. Callers pass
 * a remembered [claimedPresentationId] for the same reason.
 */
internal fun Modifier.conversationTailEntranceMotion(
    entrance: ConversationTailEntrance,
    itemId: String,
    claimedPresentationId: () -> String? = NoClaimedPresentation,
): Modifier = this then ConversationTailEntranceElement(entrance, itemId, claimedPresentationId)

private data class ConversationTailEntranceElement(
    val entrance: ConversationTailEntrance,
    val itemId: String,
    val claimedPresentationId: () -> String?,
) : ModifierNodeElement<ConversationTailEntranceNode>() {
    /** Attaches one row's entrance layer. */
    override fun create() = ConversationTailEntranceNode(entrance, itemId, claimedPresentationId)

    /** Retargets a reused row's measure and layer, so it starts and follows the current entrance. */
    override fun update(node: ConversationTailEntranceNode) = node.retarget(entrance, itemId, claimedPresentationId)

    /** Names the row and entrance when inspecting the transcript. */
    override fun InspectorInfo.inspectableProperties() {
        name = "conversationTailEntranceMotion"
        properties["itemId"] = itemId
    }
}

/** Starts the entrance from the entering row's measure and draws every row at the shared shift. */
private class ConversationTailEntranceNode(
    private var entrance: ConversationTailEntrance,
    private var itemId: String,
    private var claimedPresentationId: () -> String?,
) : Modifier.Node(),
    LayoutModifierNode {
    private var shiftLayer = shiftLayer(entrance, itemId, claimedPresentationId)

    /**
     * Points this node at new inputs. The layer block is rebuilt because a layer re-runs only for a new block
     * instance, and the old one would keep observing the previous entrance's shift.
     */
    fun retarget(
        entrance: ConversationTailEntrance,
        itemId: String,
        claimedPresentationId: () -> String?,
    ) {
        this.entrance = entrance
        this.itemId = itemId
        this.claimedPresentationId = claimedPresentationId
        shiftLayer = shiftLayer(entrance, itemId, claimedPresentationId)
        invalidateMeasurement()
    }

    /**
     * Measured, not size-changed: the entering row's first measure is the frame that moves everything, and a second
     * pass is harmless once it has started. Reading the entering id here remeasures rows when it changes.
     */
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        if (entrance.enteringItemId == itemId) entrance.onEnteringRowMeasured(itemId, placeable.height)
        return layout(placeable.width, placeable.height) {
            placeable.placeRelativeWithLayer(0, 0, layerBlock = shiftLayer)
        }
    }
}

/** Draws one row at the entrance shift, or in its own slot while its send flight claims or has just released it. */
private fun shiftLayer(
    entrance: ConversationTailEntrance,
    itemId: String,
    claimedPresentationId: () -> String?,
): GraphicsLayerScope.() -> Unit =
    {
        val currentClaim = claimedPresentationId()
        val entering = entrance.enteringItemId
        // Once visible accepted ink retires, its neighbours must share its final slots too.
        val claimedEntrance = entering != null && entrance.claimedEnteringItemId == entering
        val retiredClaim = claimedEntrance && currentClaim != entering
        val activeEnteringClaim = itemId == entering && claimedEntrance && currentClaim == entering
        translationY =
            if (retiredClaim || activeEnteringClaim) {
                0f
            } else {
                entrance.shiftPx
            }
    }
