@file:Suppress("FunctionNaming") // Composable functions use framework naming.

package dev.ipf.whitenoise.android.ui.qr

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val SCANNER_PERMISSION_BACKGROUND = Color(0xFF202020)

internal const val QR_SCANNER_SHEET_CONTENT_TAG = "qr_scanner_sheet_content"

/** Render real scanner state; the preview slot is supplied by the production CameraX owner. */
@Composable
internal fun QrScannerSheetContent(
    permissionGranted: Boolean,
    scannerError: String?,
    onDismiss: () -> Unit,
    onRequestPermission: () -> Unit,
    cameraPreview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    permissionPending: Boolean = false,
    openSettings: Boolean = false,
    onOpenSettings: () -> Unit = {},
    @StringRes permissionDetailRes: Int = R.string.camera_access_required,
    hasFlashUnit: Boolean = false,
    torchEnabled: Boolean = false,
    torchPending: Boolean = false,
    onToggleTorch: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .fillMaxSize()
            .testTag(QR_SCANNER_SHEET_CONTENT_TAG)
            .background(if (permissionGranted) Color.Black else SCANNER_PERMISSION_BACKGROUND),
    ) {
        when {
            scannerError != null ->
                ScannerErrorDialog(
                    detail = scannerError,
                    onDismiss = onDismiss,
                    onRetry = onRetry,
                )
            permissionGranted -> {
                cameraPreview()
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val targetSize = minOf(maxWidth - 64.dp, maxHeight - 192.dp, 280.dp).coerceAtLeast(0.dp)
                    if (targetSize >= 64.dp) {
                        RoundedQrTarget(Modifier.size(targetSize).testTag("qr_scanner.target"))
                    }
                }
            }
            else ->
                ScannerPermissionArea { availableHeight ->
                    if (permissionPending) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.testTag("qr_scanner.permission_pending"),
                        )
                    } else {
                        ScannerRecoveryCard(
                            title = stringResource(R.string.qr_camera_access_needed),
                            detail = stringResource(permissionDetailRes),
                            action =
                                stringResource(
                                    if (openSettings) R.string.open_settings else R.string.allow_camera,
                                ),
                            onAction = if (openSettings) onOpenSettings else onRequestPermission,
                            verticalMargin = if (availableHeight < 192.dp) 0.dp else WhiteNoiseSpacing.Section,
                        )
                    }
                }
        }
        ScannerHeader(
            onDismiss = onDismiss,
            titleRes = R.string.scan_qr_code,
            hasFlashUnit = permissionGranted && scannerError == null && hasFlashUnit,
            torchEnabled = torchEnabled,
            torchPending = torchPending,
            onToggleTorch = onToggleTorch,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/** Preserve phone centering while giving short windows all the space below the actual 112dp header. */
@Composable
private fun ScannerPermissionArea(content: @Composable (Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableHeight = (maxHeight - 112.dp).coerceAtLeast(0.dp)
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = { content(availableHeight) },
        ) { measurables, constraints ->
            val header = 112.dp.roundToPx().coerceAtMost(constraints.maxHeight)
            val child =
                measurables.single().measure(
                    constraints.copy(minWidth = 0, minHeight = 0, maxHeight = constraints.maxHeight - header),
                )
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.placeRelative(
                    x = (constraints.maxWidth - child.width) / 2,
                    y = maxOf(header, (constraints.maxHeight - child.height) / 2),
                )
            }
        }
    }
}

/** Keep native error ownership in the scanner while using the prototype's retry/close dialog chrome. */
@Composable
private fun ScannerErrorDialog(
    detail: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    WhiteNoiseAlertDialog(
        modifier = Modifier.testTag("qr_scanner.error_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.camera_unavailable)) },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = onRetry, modifier = Modifier.testTag("qr_scanner.retry")) {
                Text(stringResource(R.string.retry))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("qr_scanner.error_close")) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/** Prototype permission card keeps its recovery action reachable at large font sizes. */
@Composable
private fun ScannerRecoveryCard(
    title: String,
    detail: String,
    action: String,
    onAction: () -> Unit,
    verticalMargin: Dp,
) {
    Box(Modifier.padding(horizontal = WhiteNoiseSpacing.Section, vertical = verticalMargin)) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(WhiteNoiseSpacing.Section),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(detail, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("qr_scanner.recovery")) {
                    Text(action)
                }
            }
        }
    }
}

/** Prototype centered scanner toolbar; torch appears only for the currently bound flash-capable camera. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod") // Declarative scanner drawing/layout.
@Composable
private fun ScannerHeader(
    onDismiss: () -> Unit,
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
    hasFlashUnit: Boolean = false,
    torchEnabled: Boolean = false,
    torchPending: Boolean = false,
    onToggleTorch: () -> Unit = {},
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .height(112.dp)
                .background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                Color.Black.copy(alpha = 0.68f),
                                Color.Black.copy(alpha = 0.32f),
                                Color.Transparent,
                            ),
                    ),
                ),
    ) {
        BottomSheetDefaults.DragHandle(
            modifier = Modifier.align(Alignment.TopCenter),
            color = Color.White.copy(alpha = 0.72f),
        )

        CenterAlignedTopAppBar(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
            title = {
                Text(
                    text = stringResource(titleRes),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("qr_scanner.close")) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            },
            actions = {
                if (hasFlashUnit) {
                    IconToggleButton(
                        checked = torchEnabled,
                        enabled = !torchPending,
                        modifier = Modifier.testTag("qr_scanner.torch"),
                        onCheckedChange = { onToggleTorch() },
                        colors =
                            IconButtonDefaults.iconToggleButtonColors(
                                contentColor = Color.White.copy(alpha = 0.72f),
                                checkedContentColor = Color.White,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.qr_ic_flash_on),
                            contentDescription =
                                stringResource(
                                    if (torchEnabled) {
                                        R.string.qr_turn_flashlight_off
                                    } else {
                                        R.string.qr_turn_flashlight_on
                                    },
                                ),
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(48.dp))
                }
            },
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
    }
}

/** Draw the pinned prototype rounded corner target without obstructing camera analysis. */
@Suppress("LongMethod") // Declarative scanner drawing/layout.
@Composable
private fun RoundedQrTarget(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (size.minDimension < 64.dp.toPx()) return@Canvas
        val inset = 4.dp.toPx()
        val cornerLength = 58.dp.toPx().coerceAtMost(size.minDimension * 0.28f)
        val cornerRadius = 24.dp.toPx().coerceAtMost(cornerLength)
        val right = size.width - inset
        val bottom = size.height - inset
        val paths =
            listOf(
                Path().apply {
                    moveTo(inset + cornerLength, inset)
                    lineTo(inset + cornerRadius, inset)
                    arcTo(
                        rect = Rect(inset, inset, inset + cornerRadius * 2f, inset + cornerRadius * 2f),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false,
                    )
                    lineTo(inset, inset + cornerLength)
                },
                Path().apply {
                    moveTo(right - cornerLength, inset)
                    lineTo(right - cornerRadius, inset)
                    arcTo(
                        rect =
                            Rect(
                                right - cornerRadius * 2f,
                                inset,
                                right,
                                inset + cornerRadius * 2f,
                            ),
                        startAngleDegrees = -90f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(right, inset + cornerLength)
                },
                Path().apply {
                    moveTo(inset, bottom - cornerLength)
                    lineTo(inset, bottom - cornerRadius)
                    arcTo(
                        rect =
                            Rect(
                                inset,
                                bottom - cornerRadius * 2f,
                                inset + cornerRadius * 2f,
                                bottom,
                            ),
                        startAngleDegrees = 180f,
                        sweepAngleDegrees = -90f,
                        forceMoveTo = false,
                    )
                    lineTo(inset + cornerLength, bottom)
                },
                Path().apply {
                    moveTo(right, bottom - cornerLength)
                    lineTo(right, bottom - cornerRadius)
                    arcTo(
                        rect =
                            Rect(
                                right - cornerRadius * 2f,
                                bottom - cornerRadius * 2f,
                                right,
                                bottom,
                            ),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = 90f,
                        forceMoveTo = false,
                    )
                    lineTo(right - cornerLength, bottom)
                },
            )
        val targetStroke =
            Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            )

        paths.forEach { path ->
            drawPath(path, color = Color.White, style = targetStroke)
        }
    }
}
