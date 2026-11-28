package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.EmojiLabel
import dev.ipf.whitenoise.android.ui.keyboard.HardwareShortcutBlocker
import dev.ipf.whitenoise.android.ui.testing.exposePerformanceTestTags
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Horizontal inset for sheet titles and hand-laid sheet content; list rows keep their own padding. */
val WhiteNoiseSheetInset = WhiteNoiseSpacing.Section

/**
 * Ordinary sheets only. Material owns the handle, shape, width, IME, motion and dismissal.
 * The container is `surfaceContainerLow`, which the AMOLED scheme already maps to black.
 * [dragHandle] is replaced only by the full-height camera sheet and the wipe progress sheet (none) and the
 * keyboard-compact report sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor: Color? = null,
    contentColor: Color? = null,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle(Modifier.testTag("sheet.dragHandle")) },
    contentWindowInsets: @Composable () -> WindowInsets = { WindowInsets.safeDrawing },
    interactionEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // The sheet owns input from its first composition, before its window takes focus (#4005).
    HardwareShortcutBlocker()
    CompositionLocalProvider(LocalWhiteNoiseTextFieldContainerColor provides scheme.surfaceContainerLowest) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier.exposePerformanceTestTags().testTag("sheet.surface"),
            sheetState = sheetState,
            sheetGesturesEnabled = interactionEnabled,
            properties =
                ModalBottomSheetProperties(
                    shouldDismissOnBackPress = interactionEnabled,
                    shouldDismissOnClickOutside = interactionEnabled,
                ),
            containerColor = containerColor ?: scheme.surfaceContainerLow,
            contentColor = contentColor ?: scheme.onSurface,
            contentWindowInsets = contentWindowInsets,
            dragHandle = dragHandle,
            content = content,
        )
    }
}

/**
 * Begins immediately after Material's drag-handle slot. No repeated status-bar or top padding.
 * [titleMaxLines] bounds long remote titles to an ellipsized run so they cannot push the content out of the sheet.
 */
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseSheetHeader(
    title: String,
    onClose: (() -> Unit)? = null,
    closeEnabled: Boolean = true,
    onBack: (() -> Unit)? = null,
    titleMaxLines: Int = Int.MAX_VALUE,
    action: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("sheet.header")
            .padding(
                start = if (onBack == null) WhiteNoiseSheetInset else Dimens.spaceXs,
                end = if (onClose == null) WhiteNoiseSheetInset else WhiteNoiseSpacing.Related,
                bottom = WhiteNoiseSpacing.Related,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
            }
            Spacer(Modifier.width(Dimens.spaceXs))
        }
        EmojiLabel(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = titleMaxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        action()
        if (onClose != null) {
            IconButton(onClick = onClose, enabled = closeEnabled) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close))
            }
        }
    }
}
