package dev.ipf.whitenoise.android.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.updates.AppUpdateInfo

private val AppUpdateEmblemBackground = Color(0xFF55D6C2)
private val AppUpdateEmblemForeground = Color(0xFF00382F)

/** Availability ignores the retired banner dismissal while keeping actual version and distribution policy. */
internal fun showAppUpdateEntry(
    info: AppUpdateInfo,
    selfUpdateEnabled: Boolean,
): Boolean = selfUpdateEnabled && info.isUpdateAvailable

/** Opens Settings for a real newer release; it never starts resolution, downloads or installation. */
@Composable
@Suppress("FunctionNaming")
internal fun AppUpdateIconButton(
    info: AppUpdateInfo,
    selfUpdateEnabled: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!showAppUpdateEntry(info, selfUpdateEnabled)) return
    val availability =
        stringResource(
            if (info.isFarBehind) R.string.app_update_persistent_title else R.string.app_update_available_title,
        )
    IconButton(
        onClick = onOpenSettings,
        modifier = modifier.testTag("appUpdate.openSettings").semantics { stateDescription = availability },
    ) {
        AppUpdateEmblem(stringResource(R.string.app_updates))
    }
}

/** The prototype's update-only teal accent uses the existing first-party nine-sided Material shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
@Suppress("FunctionNaming")
internal fun AppUpdateEmblem(contentDescription: String? = null) {
    Box(
        modifier = Modifier.size(32.dp).background(AppUpdateEmblemBackground, MaterialShapes.Cookie9Sided.toShape()),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_app_update_download),
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = AppUpdateEmblemForeground,
        )
    }
}
