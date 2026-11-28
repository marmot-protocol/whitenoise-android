package dev.ipf.whitenoise.android.ui.devices

import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import dev.ipf.marmotkit.DeviceLinkEmojiFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.isOpen
import kotlinx.coroutines.delay

/** How long Link stays disabled after the comparison code first appears, so a tap already under way cannot land. */
internal const val DEVICE_LINK_ARM_DELAY_MS = 1_000L

/**
 * Whether Link may be tapped yet: only once the code has been on screen for [DEVICE_LINK_ARM_DELAY_MS] in this
 * showing of the request. The wait starts again whenever the request is shown again, after closing or a lock.
 */
@Composable
internal fun rememberLinkArmed(
    code: List<DeviceLinkEmojiFfi>,
    codeVisible: Boolean,
): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var armed by remember(code, started) { mutableStateOf(false) }
    LaunchedEffect(code, codeVisible, started) {
        if (code.isNotEmpty() && codeVisible && started) {
            delay(DEVICE_LINK_ARM_DELAY_MS)
            armed = true
        }
    }
    return armed
}

/** Whether a touch that went down at [downUptimeMs] began before the window was shown at [shownUptimeMs]. */
internal fun touchBeganBefore(
    downUptimeMs: Long,
    shownUptimeMs: Long,
): Boolean = downUptimeMs < shownUptimeMs

/** When this content was first composed, on the [SystemClock.uptimeMillis] clock that touch events carry. */
@Composable
internal fun rememberShownUptime(): Long = remember { SystemClock.uptimeMillis() }

/**
 * Consumes, before any child sees it, each gesture whose first touch went down before [shownUptimeMs], so a tap or
 * press begun on the screen below never reaches a button of the request that appeared under it. Later touches pass
 * through untouched.
 */
internal fun Modifier.ignoringTouchesBefore(shownUptimeMs: Long): Modifier =
    pointerInput(shownUptimeMs) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!touchBeganBefore(down.uptimeMillis, shownUptimeMs)) return@awaitEachGesture
            down.consume()
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * The step for a request whose profile had repeated requests: it warns that someone may have the key and offers no
 * Link and no code to compare. Don't link declines this request; Review devices opens the account's devices. A
 * request already being linked or settled keeps its own step, since its answer was already given.
 */
internal fun repeatedLinkRequestStep(state: DeviceLinkPromptStateFfi): DeviceLinkStep? =
    if (state.isOpen() && state != DeviceLinkPromptStateFfi.Linking) {
        DeviceLinkStep(
            title = R.string.link_request_repeated_title,
            detail = R.string.link_request_repeated_detail,
            primary = DeviceLinkAction.DontLink,
            secondary = DeviceLinkAction.ReviewDevices,
        )
    } else {
        null
    }
