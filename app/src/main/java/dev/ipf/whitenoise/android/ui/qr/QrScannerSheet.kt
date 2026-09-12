package dev.ipf.whitenoise.android.ui.qr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Near-full prototype scanner chrome around the production CameraX/ML Kit owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod") // One CameraX/lifecycle owner with declarative Compose content.
@Composable
internal fun QrScannerSheet(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
    @StringRes permissionDetailRes: Int = R.string.camera_access_required,
    cameraContent: @Composable ((String) -> Unit, (String) -> Unit, (Camera?) -> Unit) -> Unit = { scan, error, bound ->
        CameraQrScanner(onScan = scan, onError = error, onCameraBound = bound)
    },
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.scannerActivity() }
    var permissionGranted by remember { mutableStateOf(context.scannerCameraPermission()) }
    var permissionFinished by rememberSaveable { mutableStateOf(permissionGranted) }
    var scannerError by remember { mutableStateOf<String?>(null) }
    val torch = remember(context) { QrScannerTorch(ContextCompat.getMainExecutor(context)) }
    val currentScan by rememberUpdatedState(onScan)
    var active by remember { mutableStateOf(true) }
    val cameraUnavailable = stringResource(R.string.camera_unavailable)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
            permissionFinished = true
        }
    DisposableEffect(context, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) permissionGranted = context.scannerCameraPermission()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            active = false
            torch.bind(null)
        }
    }
    LaunchedEffect(permissionGranted, permissionFinished) {
        if (!permissionGranted && !permissionFinished) launcher.launch(Manifest.permission.CAMERA)
    }
    val height =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp() * 0.94f
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.Black,
        contentColor = Color.White,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = null,
    ) {
        QrScannerSheetContent(
            permissionGranted = permissionGranted,
            scannerError = scannerError,
            onDismiss = onDismiss,
            onRequestPermission = { permissionFinished = false },
            modifier = Modifier.height(height.coerceAtLeast(0.dp)),
            permissionPending = !permissionFinished,
            openSettings =
                permissionFinished &&
                    activity?.let {
                        !ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.CAMERA)
                    } == true,
            onOpenSettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { scannerError = cameraUnavailable }
            },
            permissionDetailRes = permissionDetailRes,
            hasFlashUnit = torch.available,
            torchEnabled = torch.enabled,
            torchPending = torch.pending,
            onToggleTorch = torch::toggle,
            onRetry = { scannerError = null },
            cameraPreview = {
                cameraContent(
                    { if (active) currentScan(it) },
                    { if (active) scannerError = it },
                    { if (active) torch.bind(it) },
                )
            },
        )
    }
}

/** Read Android's current grant again after returning from application settings. */
private fun Context.scannerCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** Find the host only for Android's permission-rationale decision; never substitute a fake grant. */
private tailrec fun Context.scannerActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.scannerActivity()
        else -> null
    }

/** Keep the existing analysis executor and explicit provider/scanner teardown with the sheet lifecycle. */
@Composable
private fun CameraQrScanner(
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
    onCameraBound: (Camera?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context.lifecycleOwner()
    val cameraLifecycleUnavailable = stringResource(R.string.camera_lifecycle_unavailable)
    val cameraUnavailable = stringResource(R.string.camera_unavailable)

    if (lifecycleOwner == null) {
        // Emit the error as a post-composition effect, not a side effect during
        // composition (which violates Compose's rules and can fire on every
        // recomposition). See #23.
        LaunchedEffect(cameraLifecycleUnavailable) {
            onError(cameraLifecycleUnavailable)
        }
        return
    }

    // Track the CameraX provider and ML Kit scanner so we can release them when
    // the QR sheet is dismissed. CameraX binds use cases to the host activity's
    // lifecycle, so without an explicit unbind the camera keeps streaming
    // (and the OS in-use indicator stays lit) until the activity stops. The
    // BarcodeScanner is Closeable and leaks native resources otherwise.
    //
    // `disposedRef` is a separate teardown signal so a late
    // ProcessCameraProvider.getInstance() callback (fired after the sheet
    // dismissed) can bail and clean up instead of binding into refs we just
    // nulled out. Using `null` to mean both "not yet set" and "torn down"
    // would let `compareAndSet(null, …)` succeed after onDispose, leaking the
    // camera again.
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val scannerRef = remember { AtomicReference<BarcodeScanner?>(null) }
    val disposedRef = remember { AtomicBoolean(false) }
    // Per-frame ML Kit analysis runs here, off the main thread, for as long as
    // the scanner is open; shut down on dispose. The provider/bind callbacks
    // still use the main executor (they touch the lifecycle and preview view).
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            disposedRef.set(true)
            onCameraBound(null)
            runCatching { providerRef.getAndSet(null)?.unbindAll() }
            runCatching { scannerRef.getAndSet(null)?.close() }
            runCatching { analyzerExecutor.shutdown() }
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
                previewView.importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                bindQrScannerCamera(
                    context,
                    lifecycleOwner,
                    previewView,
                    cameraUnavailable,
                    providerRef,
                    scannerRef,
                    disposedRef,
                    analyzerExecutor,
                    onScan,
                    onError,
                    onCameraBound,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Bind the production latest-frame QR analyzer; rotation, one-result and late-disposal guards stay authoritative. */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindQrScannerCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraUnavailable: String,
    providerRef: AtomicReference<ProcessCameraProvider?>,
    scannerRef: AtomicReference<BarcodeScanner?>,
    disposedRef: AtomicBoolean,
    analyzerExecutor: Executor,
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
    onCameraBound: (Camera?) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider =
                runCatching { cameraProviderFuture.get() }.getOrElse {
                    if (!disposedRef.get()) onError(cameraUnavailable)
                    return@addListener
                }
            // If the sheet dismissed before this listener fired, the caller's
            // onDispose already ran and nulled the refs. Without disposedRef,
            // compareAndSet(null, provider) would succeed here and bind a
            // camera that nothing will ever unbind. Bail and clean up locally.
            if (disposedRef.get() || !providerRef.compareAndSet(null, provider)) {
                runCatching { provider.unbindAll() }
                return@addListener
            }
            val preview =
                Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val scanner =
                BarcodeScanning.getClient(
                    BarcodeScannerOptions
                        .Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build(),
                )
            if (disposedRef.get() || !scannerRef.compareAndSet(null, scanner)) {
                runCatching { scanner.close() }
                runCatching { provider.unbindAll() }
                providerRef.set(null)
                return@addListener
            }
            val didScan = AtomicBoolean(false)
            val analyzerBusy = AtomicBoolean(false)
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

            analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                if (!analyzerBusy.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    analyzerBusy.set(false)
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner
                    .process(image)
                    .addOnSuccessListener { codes ->
                        // process() can resolve after the sheet is dismissed; don't
                        // call back into torn-down UI state.
                        if (disposedRef.get()) return@addOnSuccessListener
                        val raw = codes.firstOrNull { it.rawValue != null }?.rawValue
                        if (raw != null && didScan.compareAndSet(false, true)) onScan(raw)
                    }.addOnFailureListener {
                        if (disposedRef.get()) return@addOnFailureListener
                        onError(cameraUnavailable)
                    }.addOnCompleteListener {
                        analyzerBusy.set(false)
                        imageProxy.close()
                    }
            }

            runCatching {
                provider.unbindAll()
                val camera =
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                onCameraBound(camera)
            }.onFailure {
                // Failed before lifecycle binding could take over — the
                // composable's onDispose has nothing to unbind, so release
                // provider + scanner here instead of leaking them until the
                // sheet dismisses.
                runCatching { scannerRef.getAndSet(null)?.close() }
                runCatching { providerRef.getAndSet(null)?.unbindAll() }
                onError(cameraUnavailable)
            }
        },
        executor,
    )
}
