package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseEmptyState

/** Empty Left describes native ended-membership history without claiming the user's ordinary Chats list is empty. */
@Composable
@Suppress("FunctionNaming") // Compose naming follows the framework convention.
internal fun EmptyLeftChats() {
    Box(Modifier.fillMaxSize().testTag("chats.empty.left"), contentAlignment = Alignment.Center) {
        WhiteNoiseEmptyState(stringResource(R.string.no_left_chats), stringResource(R.string.no_left_chats_detail))
    }
}
