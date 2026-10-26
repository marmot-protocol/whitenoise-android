package dev.ipf.whitenoise.android.maestro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendComposerTarget
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotion
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendMotionHost
import dev.ipf.whitenoise.android.ui.conversation.ComposerSendViewportProjection
import dev.ipf.whitenoise.android.ui.conversation.reactions.localReactionOverlay
import dev.ipf.whitenoise.android.ui.conversation.reactions.localReactionTarget
import dev.ipf.whitenoise.android.ui.conversation.reactions.reactionSourceBounds
import dev.ipf.whitenoise.android.ui.conversation.reactions.rememberLocalReactionMotion
import kotlinx.coroutines.delay

/** Controlled acceptance with real measured endpoints; tags prove active ownership, not rendered pixels or timing. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroMotionPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario == "surface-motion-send") {
        SendMotionPresentation(fixture)
    } else {
        ReactionMotionPresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun SendMotionPresentation(fixture: MaestroPresentationFixture) {
    val motion = remember { ComposerSendMotion().apply { enabled = true } }
    motion.bindMeasuredStaticViewport()
    var started by remember { mutableStateOf(false) }
    var targetCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var targetLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    BindSendMotionDestination(motion, targetCoordinates, targetLayout)
    // Bounded real flights expose transient owners to accessibility polling; no clock or production duration override.
    LaunchedEffect(started) {
        if (started) {
            repeat(16) { iteration ->
                val source = checkNotNull(motion.capture("Measured send"))
                motion.composerTarget =
                    ComposerSendComposerTarget(
                        iteration.toLong(),
                        checkNotNull(motion.composerCoordinates).size.height.toFloat(),
                    )
                motion.accept(source, "fixture-$iteration", iteration.toLong())
                delay(500)
            }
        }
    }
    ComposerSendMotionHost(motion) {
        Column(Modifier.padding(24.dp)) {
            Text(
                "Measured send",
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.onGloballyPositioned { targetCoordinates = it },
                onTextLayout = { targetLayout = it },
            )
            Text(
                "Measured send",
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.padding(top = 120.dp).onGloballyPositioned {
                        motion.sourceSurface = it
                        motion.sourceEditor = it
                        motion.composerCoordinates = it
                    },
                onTextLayout = { motion.sourceLayout = it },
            )
            // Reading the identity keeps the measured target subscribed to each controlled acceptance.
            Text(motion.activePresentationId.orEmpty())
            Button(onClick = {
                fixture.record("motion-started")
                started = true
            }, enabled = !started) {
                Text("Start send motion")
            }
            Button(onClick = { fixture.finish("dismiss") }) { Text("Dismiss motion") }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun ReactionMotionPresentation(fixture: MaestroPresentationFixture) {
    val motion = rememberLocalReactionMotion(fixture, enabled = true)
    var source by remember { mutableStateOf(Rect.Zero) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(started) {
        if (started) {
            repeat(16) {
                // Targets are measured only after acceptance activates their observer.
                check(!source.isEmpty)
                motion.choose("👍", source)
                motion.accept("👍", true, motion.takeOrigin("👍"))
                delay(500)
            }
        }
    }
    Box(Modifier.fillMaxSize().localReactionOverlay(motion)) {
        Column(Modifier.padding(24.dp)) {
            Text("👍", Modifier.reactionSourceBounds { source = it })
            Text(
                "👍",
                Modifier.padding(top = 120.dp).localReactionTarget(motion, "👍", selected = true),
            )
            Button(onClick = {
                fixture.record("motion-started")
                started = true
            }, enabled = !started) {
                Text("Start reaction motion")
            }
            Button(onClick = { fixture.finish("dismiss") }) { Text("Dismiss motion") }
        }
    }
}

/** This fixture has no changing editor or transcript padding; its actual measured height is constant. */
private fun ComposerSendMotion.bindMeasuredStaticViewport() {
    readViewport = {
        composerCoordinates?.takeIf { it.isAttached }?.let { coordinates ->
            ComposerSendViewportProjection(
                coordinates.findRootCoordinates().boundsInWindow(clipBounds = false),
                coordinates.size.height.toFloat(),
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun BindSendMotionDestination(
    motion: ComposerSendMotion,
    coordinates: LayoutCoordinates?,
    textLayout: TextLayoutResult?,
) {
    val identity = motion.activePresentationId
    LaunchedEffect(identity, coordinates, textLayout) {
        val target = coordinates
        val layout = textLayout
        if (identity != null && target != null && layout != null) {
            motion.destinationFrame(
                identity,
                target,
                Color.Black,
                Color.Transparent,
                RoundRect(Rect.Zero, CornerRadius.Zero),
                0f,
            )
            motion.destinationText(identity, target)
            motion.destinationLayout(identity, layout)
        }
    }
}
