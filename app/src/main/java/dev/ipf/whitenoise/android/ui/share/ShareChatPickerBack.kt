@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.lerp
import dev.ipf.whitenoise.android.ui.common.rememberSystemAnimationsEnabled
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

private const val PREDICTIVE_BACK_MIN_SCALE = 0.9f
private const val PREDICTIVE_BACK_CANCEL_MILLIS = 200
private val PredictiveBackTransformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)

internal fun interface ShareChatPickerOverlayBackRegistrar {
    fun register(
        priority: Int,
        callback: OnBackAnimationCallback,
    ): () -> Unit
}

@Composable
internal fun ShareChatPickerBackAwareScreen(
    overlayBack: Boolean,
    onBackCommit: () -> Unit,
    overlayBackRegistrar: ShareChatPickerOverlayBackRegistrar? = null,
    content: @Composable () -> Unit,
) {
    // The scale follows the finger frame for frame; only a cancelled gesture eases back to rest.
    val predictiveBackProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val motion = rememberSystemAnimationsEnabled()
    val onProgress: (Float) -> Unit = { scope.launch { predictiveBackProgress.snapTo(it) } }
    val onCancel: () -> Unit = {
        scope.launch {
            predictiveBackProgress.animateTo(0f, if (motion) tween(PREDICTIVE_BACK_CANCEL_MILLIS) else snap())
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .shareChatPickerPredictiveBackScale { predictiveBackProgress.value },
    ) {
        if (overlayBack && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ShareChatPickerOverlayBackHandler(
                onProgress = onProgress,
                onCommit = onBackCommit,
                onCancel = onCancel,
                registrar = overlayBackRegistrar,
            )
        } else {
            ShareChatPickerPredictiveBackHandler(
                onProgress = onProgress,
                onCommit = onBackCommit,
                onCancel = onCancel,
            )
        }
        content()
    }
}

@Composable
private fun ShareChatPickerPredictiveBackHandler(
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    PredictiveBackHandler { progress ->
        try {
            progress.collect { event -> currentOnProgress(event.progress) }
            currentOnCommit()
        } catch (e: CancellationException) {
            currentOnCancel()
            throw e
        }
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun shareChatPickerOverlayBackAnimationCallback(
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
): OnBackAnimationCallback =
    object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
            onProgress(backEvent.progress)
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            onProgress(backEvent.progress)
        }

        override fun onBackCancelled() {
            onCancel()
        }

        override fun onBackInvoked() {
            onCommit()
        }
    }

@Composable
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private fun ShareChatPickerOverlayBackHandler(
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
    registrar: ShareChatPickerOverlayBackRegistrar?,
) {
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val backDispatcher = LocalView.current.findOnBackInvokedDispatcher()
    DisposableEffect(backDispatcher, registrar) {
        val callback =
            shareChatPickerOverlayBackAnimationCallback(
                onProgress = { currentOnProgress(it) },
                onCommit = { currentOnCommit() },
                onCancel = { currentOnCancel() },
            )
        val unregister =
            when {
                registrar != null ->
                    registrar.register(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
                backDispatcher != null -> {
                    backDispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback,
                    )
                    val unregisterCallback: () -> Unit = {
                        backDispatcher.unregisterOnBackInvokedCallback(callback)
                    }
                    unregisterCallback
                }
                else -> null
            }
        onDispose { unregister?.invoke() }
    }
    // Non-gesture dispatchers still need committed Back; platform gestures invoke the overlay first.
    BackHandler { currentOnCommit() }
}

// Read in the layer block so a gesture redraws the scale without recomposing the picker.
private fun Modifier.shareChatPickerPredictiveBackScale(progress: () -> Float): Modifier =
    graphicsLayer {
        val scale = lerp(1f, PREDICTIVE_BACK_MIN_SCALE, progress())
        scaleX = scale
        scaleY = scale
        transformOrigin = PredictiveBackTransformOrigin
    }
