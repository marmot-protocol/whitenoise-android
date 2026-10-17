package dev.ipf.whitenoise.android.maestro

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Rect

private const val LEAVE_HORIZONTAL_INSET_PX = 2f
private const val LEAVE_VERTICAL_OFFSET_PX = 8f

/** Real Android mouse events exercise production pointer input without acquiring UiAutomation. */
internal class MaestroHoverPointer(
    private val root: View,
    private val fixture: MaestroPresentationFixture,
) {
    private var attached = true

    fun dispatch(
        bounds: Rect,
        exit: Boolean,
    ) {
        check(!bounds.isEmpty) { "Timestamp layout was not measured" }
        // A button callback runs inside touch dispatch. Wait until that dispatch
        // returns instead of sending a reentrant pointer event into Compose.
        root.post {
            if (!attached) return@post
            check(root.isAttachedToWindow) { "Timestamp pointer target detached" }
            val properties =
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_MOUSE
                }
            val coordinates =
                MotionEvent.PointerCoords().apply {
                    x = if (exit) bounds.left + LEAVE_HORIZONTAL_INSET_PX else bounds.center.x
                    y = if (exit) bounds.bottom + LEAVE_VERTICAL_OFFSET_PX else bounds.center.y
                }
            val now = SystemClock.uptimeMillis()
            val event =
                // Move away from the measured text inside the same window.
                // Production hit testing must clear the disclosure; the fixture
                // never changes the timestamp's hover state directly.
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_HOVER_MOVE,
                    1,
                    arrayOf(properties),
                    arrayOf(coordinates),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_MOUSE,
                    0,
                )
            try {
                // The return value is not UI proof: Maestro must observe the
                // actual tooltip appearing/disappearing after these events.
                root.dispatchGenericMotionEvent(event)
                fixture.record(if (exit) "hover-exit" else "hover-enter")
            } finally {
                event.recycle()
            }
        }
    }

    fun close() {
        attached = false
    }
}
