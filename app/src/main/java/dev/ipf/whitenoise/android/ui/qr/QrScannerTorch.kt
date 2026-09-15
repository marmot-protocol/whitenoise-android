package dev.ipf.whitenoise.android.ui.qr

import androidx.camera.core.Camera
import androidx.camera.core.TorchState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Observer
import java.util.concurrent.Executor

/** Ephemeral controls for the currently bound camera; checked state comes only from CameraX. */
internal class QrScannerTorch(
    private val executor: Executor,
) {
    var available by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    var pending by mutableStateOf(false)
        private set
    private var camera: Camera? = null
    private var generation = 0L
    private val observer = Observer<Int> { enabled = it == TorchState.ON }

    /** Detach the prior observer before accepting a camera from this scanner's live binding. */
    fun bind(next: Camera?) {
        generation++
        camera?.cameraInfo?.torchState?.removeObserver(observer)
        camera = next
        available = next?.cameraInfo?.hasFlashUnit() == true
        enabled = false
        pending = false
        if (available) next?.cameraInfo?.torchState?.observeForever(observer)
    }

    /** Request one hardware change; failures and stale completions cannot fabricate a checked state. */
    fun toggle() {
        val bound = camera?.takeIf { available && !pending } ?: return
        val operation = generation
        pending = true
        val future =
            runCatching { bound.cameraControl.enableTorch(!enabled) }.getOrElse {
                pending = false
                return
            }
        future.addListener(
            {
                runCatching { future.get() }
                if (operation == generation) pending = false
            },
            executor,
        )
    }
}
