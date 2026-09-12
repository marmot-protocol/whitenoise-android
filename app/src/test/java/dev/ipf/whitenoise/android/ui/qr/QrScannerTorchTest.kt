package dev.ipf.whitenoise.android.ui.qr

import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.TorchState
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Real camera-control interfaces expose deterministic hardware responses, without any camera or provider emulator. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class QrScannerTorchTest {
    private val direct = Executor { it.run() }

    /** A device without a flash unit cannot dispatch a torch request. */
    @Test fun missingHardwareCannotToggle() {
        val camera = CameraBoundary(hasFlash = false)
        val torch = QrScannerTorch(direct)
        torch.bind(camera.camera)
        torch.toggle()
        assertFalse(torch.available)
        assertEquals(0, camera.requests.size)
        torch.bind(null)
    }

    /** Successful futures clear busy state; only observed CameraX state marks the flashlight on. */
    @Test fun checkedStateRequiresActualCameraObservation() {
        val camera = CameraBoundary()
        val torch = QrScannerTorch(direct)
        torch.bind(camera.camera)
        torch.toggle()
        torch.toggle()
        assertEquals(listOf(true), camera.requests)
        assertTrue(torch.pending)
        assertFalse(torch.enabled)
        camera.future.complete()
        assertFalse(torch.pending)
        assertFalse(torch.enabled)
        camera.state.value = TorchState.ON
        assertTrue(torch.enabled)
        torch.bind(null)
    }

    /** A failed hardware request leaves the control off and permits an explicit retry. */
    @Test fun failureDoesNotClaimTorchOn() {
        val camera = CameraBoundary()
        val torch = QrScannerTorch(direct)
        torch.bind(camera.camera)
        torch.toggle()
        camera.future.complete(IllegalStateException("camera disconnected"))
        assertFalse(torch.pending)
        assertFalse(torch.enabled)
        torch.toggle()
        assertEquals(2, camera.requests.size)
        torch.bind(null)
    }

    /** An old binding's future cannot complete a newer binding's in-flight hardware request. */
    @Test fun staleCompletionCannotUnlockReplacementRequest() {
        val old = CameraBoundary()
        val replacement = CameraBoundary()
        val torch = QrScannerTorch(direct)
        torch.bind(old.camera)
        torch.toggle()
        torch.bind(replacement.camera)
        torch.toggle()
        old.future.complete()
        assertTrue(torch.pending)
        replacement.future.complete()
        assertFalse(torch.pending)
        torch.bind(null)
    }

    /** Dismissal removes the actual LiveData observer and ignores late hardware state. */
    @Test fun unbindRemovesObservationAndActions() {
        val camera = CameraBoundary()
        val torch = QrScannerTorch(direct)
        torch.bind(camera.camera)
        assertTrue(camera.state.hasObservers())
        torch.bind(null)
        camera.state.value = TorchState.ON
        torch.toggle()
        assertFalse(camera.state.hasObservers())
        assertFalse(torch.available)
        assertFalse(torch.enabled)
        assertEquals(0, camera.requests.size)
    }

    private class CameraBoundary(
        hasFlash: Boolean = true,
    ) {
        val state = MutableLiveData(TorchState.OFF)
        val requests = mutableListOf<Boolean>()
        var future = ControlledFuture()
        val camera: Camera

        init {
            val info =
                proxy<CameraInfo> { name, _ ->
                    when (name) {
                        "hasFlashUnit" -> hasFlash
                        "getTorchState" -> state
                        else -> null
                    }
                }
            val control =
                proxy<CameraControl> { name, args ->
                    if (name == "enableTorch") {
                        requests += args!![0] as Boolean
                        future = ControlledFuture()
                        future
                    } else {
                        null
                    }
                }
            camera =
                proxy { name, _ ->
                    when (name) {
                        "getCameraInfo" -> info
                        "getCameraControl" -> control
                        else -> null
                    }
                }
        }
    }

    /** Implement only the CameraX interfaces invoked by this owner; no app state is fabricated. */
    private companion object {
        inline fun <reified T> proxy(crossinline result: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { instance, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(instance)
                    "equals" -> instance === args?.get(0)
                    "toString" -> T::class.java.simpleName
                    else -> result(method.name, args)
                }
            } as T
    }

    private class ControlledFuture : ListenableFuture<Void> {
        private val listeners = mutableListOf<Pair<Runnable, Executor>>()
        private var done = false
        private var failure: Throwable? = null

        override fun addListener(
            listener: Runnable,
            executor: Executor,
        ) {
            if (done) executor.execute(listener) else listeners += listener to executor
        }

        /** Deliver one real Future completion to the CameraX-facing owner. */
        fun complete(error: Throwable? = null) {
            failure = error
            done = true
            listeners.toList().forEach { (runnable, executor) -> executor.execute(runnable) }
            listeners.clear()
        }

        override fun cancel(mayInterruptIfRunning: Boolean) = false

        override fun isCancelled() = false

        override fun isDone() = done

        override fun get(): Void? {
            check(done)
            failure?.let { throw ExecutionException(it) }
            return null
        }

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Void? = get()
    }
}
