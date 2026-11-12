package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.AppMotionOpenEasing
import dev.ipf.whitenoise.android.ui.common.drawMeasuredPage
import dev.ipf.whitenoise.android.ui.common.measuredPageBounds
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.settings.settingsMorphBounds

/** Replacement page only: its source is already disposed, so Back never reverses into it. */
@Composable
// Compose naming; measurement, drawing, and input retirement share one replacement-page lifetime.
@Suppress("FunctionNaming", "CyclomaticComplexMethod")
internal fun ProfileGroupOpeningPage(
    origin: Rect?,
    owner: Any,
    content: @Composable () -> Unit,
) {
    val background = MaterialTheme.colorScheme.background
    val view = LocalView.current
    val motion = rememberSystemAnimationsEnabled()
    var host by remember(owner, origin) { mutableStateOf(Rect.Zero) }
    var retired by remember(owner, origin) { mutableStateOf(origin == null) }
    val progress = remember(owner, origin) { Animatable(if (motion && origin != null) 0f else 1f) }
    val animate = motion && !retired && !host.isEmpty
    LaunchedEffect(animate, host) {
        if (animate) {
            progress.animateTo(1f, tween(APP_MOTION_OPEN_MILLIS, easing = AppMotionOpenEasing))
        } else if (!motion || retired) {
            progress.snapTo(1f)
        }
    }
    val acceptsInput by remember(motion, retired, progress) {
        derivedStateOf {
            !motion ||
                retired ||
                progress.value >= 1f
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val next = it.settingsMorphBounds(view)
                if ((!host.isEmpty && host != next) || !validProfileGroupOrigin(origin, next)) retired = true
                host = next
            }.drawWithContent {
                // The previous route is disposed: cover the window even before measurement
                // and outside the expanding destination, using the active theme.
                drawRect(background)
                if (motion && !retired && host.isEmpty) {
                    // Wait for the real destination measurement; never flash a settled frame.
                } else if (!motion || retired || origin == null) {
                    drawContent()
                } else {
                    val bounds = measuredPageBounds(origin, host, progress.value).translate(-host.topLeft)
                    val destination = this
                    drawMeasuredPage(bounds, progress.value) { destination.drawContent() }
                }
            }.then(if (acceptsInput) Modifier else Modifier.clearAndSetSemantics {})
            .onPreviewKeyEvent { !acceptsInput && it.key != Key.Back }
            .pointerInput(acceptsInput) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (!acceptsInput) event.changes.forEach { it.consume() }
                    }
                }
            },
    ) { content() }
}

/** A clipped or stale launch control cannot define a trustworthy replacement transform. */
internal fun validProfileGroupOrigin(
    origin: Rect?,
    host: Rect,
): Boolean =
    origin != null &&
        !origin.isEmpty &&
        !host.isEmpty &&
        listOf(origin.left, origin.top, origin.right, origin.bottom, host.left, host.top, host.right, host.bottom)
            .all { it.isFinite() } &&
        origin.left >= host.left &&
        origin.top >= host.top &&
        origin.right <= host.right &&
        origin.bottom <= host.bottom
