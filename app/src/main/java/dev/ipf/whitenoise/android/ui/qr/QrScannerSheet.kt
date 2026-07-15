package dev.ipf.whitenoise.android.ui.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.theme.ScrimAlpha
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val QR_SCANNER_SHEET_CONTENT_TAG = "qr_scanner_sheet_content"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrScannerSheet(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    var scannerError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        QrScannerSheetContent(
            permissionGranted = permissionGranted,
            scannerError = scannerError,
            onDismiss = onDismiss,
            onRequestPermission = { launcher.launch(Manifest.permission.CAMERA) },
            cameraPreview = {
                CameraQrScanner(onScan = onScan, onError = { scannerError = it })
            },
        )
    }
}

@Composable
internal fun QrScannerSheetContent(
    permissionGranted: Boolean,
    scannerError: String?,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    cameraPreview: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .testTag(QR_SCANNER_SHEET_CONTENT_TAG)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.scan), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
            }
        }
        if (permissionGranted) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.BottomCenter,
            ) {
                cameraPreview()
                Text(
                    scannerError ?: stringResource(R.string.point_camera_at_profile_qr),
                    color = Color.White,
                    modifier =
                        Modifier
                            .padding(
                                16.dp,
                            ).background(Color.Black.copy(alpha = ScrimAlpha.Strong), RoundedCornerShape(24.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        } else {
            Text(stringResource(R.string.camera_access_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.allow_camera))
            }
        }
    }
}

@Composable
private fun CameraQrScanner(
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
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
            runCatching { providerRef.getAndSet(null)?.unbindAll() }
            runCatching { scannerRef.getAndSet(null)?.close() }
            runCatching { analyzerExecutor.shutdown() }
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
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
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

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
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider =
                runCatching { cameraProviderFuture.get() }.getOrElse {
                    onError(cameraUnavailable)
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
                        onError(it.message ?: it.javaClass.simpleName)
                    }.addOnCompleteListener {
                        analyzerBusy.set(false)
                        imageProxy.close()
                    }
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                // Failed before lifecycle binding could take over — the
                // composable's onDispose has nothing to unbind, so release
                // provider + scanner here instead of leaking them until the
                // sheet dismisses.
                runCatching { scannerRef.getAndSet(null)?.close() }
                runCatching { providerRef.getAndSet(null)?.unbindAll() }
                onError(it.message ?: it.javaClass.simpleName)
            }
        },
        executor,
    )
}
