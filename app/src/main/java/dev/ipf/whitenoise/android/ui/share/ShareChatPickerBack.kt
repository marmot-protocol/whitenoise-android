@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.util.lerp
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlin.coroutines.cancellation.CancellationException

private const val PREDICTIVE_BACK_MIN_SCALE = 0.9f
private val PredictiveBackTransformOrigin = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareChatPickerBackAwareSheet(
    sheetState: SheetState,
    overlayBack: Boolean,
    onDismissRequest: () -> Unit,
    onBackCommit: () -> Unit,
    content: @Composable () -> Unit,
) {
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    val animatedPredictiveBackProgress by animateFloatAsState(
        targetValue = predictiveBackProgress,
        label = "sharePickerPredictiveBack",
    )
    val onProgress: (Float) -> Unit = { predictiveBackProgress = it }
    val onCancel: () -> Unit = { predictiveBackProgress = 0f }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        if (overlayBack) {
            ShareChatPickerOverlayBackHandler(
                onProgress = onProgress,
                onCommit = onBackCommit,
                onCancel = onCancel,
            )
        } else {
            ShareChatPickerPredictiveBackHandler(
                onProgress = onProgress,
                onCommit = onBackCommit,
                onCancel = onCancel,
            )
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .shareChatPickerPredictiveBackScale(animatedPredictiveBackProgress),
        ) {
            content()
        }
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
private fun ShareChatPickerOverlayBackHandler(
    onProgress: (Float) -> Unit,
    onCommit: () -> Unit,
    onCancel: () -> Unit,
) {
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val backDispatcher = LocalView.current.findOnBackInvokedDispatcher()
    DisposableEffect(backDispatcher) {
        if (backDispatcher == null) return@DisposableEffect onDispose {}
        val callback =
            shareChatPickerOverlayBackAnimationCallback(
                onProgress = { currentOnProgress(it) },
                onCommit = { currentOnCommit() },
                onCancel = { currentOnCancel() },
            )
        backDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        )
        onDispose { backDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
    // Non-gesture dispatchers still need committed Back; platform gestures invoke the overlay first.
    BackHandler { currentOnCommit() }
}

private fun Modifier.shareChatPickerPredictiveBackScale(progress: Float): Modifier =
    graphicsLayer {
        val scale = lerp(1f, PREDICTIVE_BACK_MIN_SCALE, progress)
        scaleX = scale
        scaleY = scale
        transformOrigin = PredictiveBackTransformOrigin
    }
