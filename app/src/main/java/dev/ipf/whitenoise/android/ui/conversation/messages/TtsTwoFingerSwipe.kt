package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Two fingers dragged down over a message row.
 *
 * Read-aloud is otherwise reached through a long-press menu, which is three
 * deliberate actions away and needs the eyes on the screen. This is the quick
 * one — a gesture broad enough to hit without aiming, at the moment the reader
 * decides they would rather listen than read, and the same gesture again when
 * someone talks to them. What a committed gesture MEANS is a separate decision;
 * see [ttsQuickTransportActionFor].
 *
 * **Two fingers is what makes it unambiguous.** One finger already means scroll
 * on a conversation, and a two-finger drag has no other meaning on a message
 * row: the reply swipe is horizontal, selection is a long press, and there is
 * no pinch here. So the SECOND finger going down claims the gesture, and the
 * pointers are consumed from that moment — before the drag has committed to
 * anything. Waiting for the commit lets the list scroll a few pixels and then
 * stop, which reads as the app twitching rather than obeying.
 *
 * A single finger is never consumed, so ordinary scrolling is untouched, and a
 * gesture that drops back to one finger releases its claim and starts measuring
 * again if a second finger returns. Once committed the remaining pointers stay
 * consumed even down to one, so lifting one finger cannot hand the list a jump.
 *
 * **The node stays mounted whether or not it is listening**, and [enabled] is
 * read inside the loop rather than deciding whether the modifier exists. That
 * is not tidiness: dropping a pointer input out of the chain mid-gesture
 * rebuilds the row's pointer-input subtree, which cancels whatever gesture a
 * sibling is still resolving. Long-press-drag to select turns selection mode on
 * partway through exactly one such drag, so a structurally conditional detector
 * cancels the very gesture that disabled it - selection stopped after the first
 * row. Nothing is consumed while it is not listening, so an unmounted node and
 * a quiet one are indistinguishable from the outside.
 *
 * [onSwipe] is held in [rememberUpdatedState] and the input is keyed on nothing
 * else, for the same family of reason. Keying a pointer input on a callback is
 * a quiet way to break a gesture: a lambda is a new object on every
 * recomposition, and a conversation recomposes constantly while a message is
 * being read, so the node would be torn down and rebuilt mid-gesture and the
 * travel measured so far thrown away.
 */
@Composable
internal fun Modifier.twoFingerSwipeDown(
    enabled: Boolean,
    onSwipe: () -> Unit,
): Modifier {
    val swipe by rememberUpdatedState(onSwipe)
    val listening by rememberUpdatedState(enabled)
    return this.pointerInput(Unit) {
        val commitDistance = viewConfiguration.touchSlop * TWO_FINGER_COMMIT_SLOP
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val origins = mutableMapOf<PointerId, Float>()
            var committed = false
            while (true) {
                val pressed = awaitPointerEvent(pass = PointerEventPass.Initial).changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                when {
                    !listening -> Unit

                    committed -> pressed.forEach { it.consume() }

                    // One finger is a scroll, and a second finger arriving later
                    // measures from where it arrives rather than inheriting a
                    // stale origin from the finger that lifted.
                    pressed.size < TWO_FINGERS -> origins.clear()

                    else -> {
                        pressed.forEach { it.consume() }
                        pressed.forEach { change -> origins.getOrPut(change.id) { change.position.y } }
                        if (hasSwipedDown(pressed, origins, commitDistance)) {
                            committed = true
                            swipe()
                        }
                    }
                }
            }
        }
    }
}

/** Whether every pressed pointer has travelled far enough DOWN from where it was first seen. */
private fun hasSwipedDown(
    pressed: List<PointerInputChange>,
    origins: Map<PointerId, Float>,
    commitDistance: Float,
): Boolean {
    val travelled = pressed.mapNotNull { change -> origins[change.id]?.let { change.position.y - it } }
    return travelled.size >= TWO_FINGERS && travelled.all { it > commitDistance }
}

private const val TWO_FINGERS = 2

/**
 * Multiples of touch slop both fingers must travel down before this counts as a
 * command rather than a wobble. Small, deliberately: the pointers are already
 * consumed from the second finger down and nothing else on a message row wants
 * a two-finger drag, so the only cost of a short commit is a sloppier touch
 * counting, which is cheap next to a transport action that lags the intent.
 */
private const val TWO_FINGER_COMMIT_SLOP = 2f
