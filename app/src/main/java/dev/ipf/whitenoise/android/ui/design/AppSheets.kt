package dev.ipf.whitenoise.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dialog
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

/**
 * Bottom-anchored sheet that leaves focus in its host window.
 *
 * Material's [androidx.compose.material3.ModalBottomSheet] opens a focusable dialog window. From a
 * focused conversation that steals window focus from the composer and Android closes the IME.
 * [KeyboardSafePopup] supplies the shared non-focusable popup, outside-tap consumption, and
 * overlay-priority Back dismissal. This wrapper adds the sheet styling and accessibility semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeyboardPreservingBottomSheet(
    paneTitle: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dismissLabel = stringResource(R.string.dismiss)
    KeyboardSafePopup(
        expanded = true,
        onDismissRequest = onDismissRequest,
        popupPositionProvider = BottomAnchoredPopupPositionProvider,
        scrimModifier =
            Modifier
                .background(BottomSheetDefaults.ScrimColor)
                .semantics {
                    contentDescription = dismissLabel
                    role = Role.Button
                    onClick(label = dismissLabel) {
                        onDismissRequest()
                        true
                    }
                },
    ) {
        Surface(
            modifier =
                Modifier
                    .then(modifier)
                    .widthIn(max = BottomSheetDefaults.SheetMaxWidth)
                    .fillMaxWidth()
                    .semantics {
                        isTraversalGroup = true
                        dialog()
                        this.paneTitle = paneTitle
                    }.pointerInput(Unit) {
                        detectTapGestures { /* Consume blank sheet taps without dismissing. */ }
                    },
            shape = BottomSheetDefaults.ExpandedShape,
            color = amoledSheetContainerColor(),
        ) {
            Column(
                modifier =
                    Modifier
                        .navigationBarsPadding()
                        .padding(top = 8.dp),
                content = content,
            )
        }
    }
}
