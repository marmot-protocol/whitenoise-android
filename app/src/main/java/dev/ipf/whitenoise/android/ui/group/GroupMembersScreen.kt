package dev.ipf.whitenoise.android.ui.group

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.settings.SettingsScaffold

/**
 * Full roster destination: the Members title over a plain back arrow, with no top-bar actions and no
 * add-members affordance — adding members belongs to the group info screen alone. The caller retains
 * its native renderer and controller-owned selection and mutations.
 */
@Suppress("FunctionNaming")
@Composable
internal fun GroupMembersScreen(
    onBack: () -> Unit,
    scrollState: ScrollState,
    feedback: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.members), onBack = onBack, bottomBar = bottomBar) {
        Column(Modifier.fillMaxSize().testTag("chat_info.members_screen")) {
            feedback()
            Column(
                modifier = Modifier.weight(1f).whiteNoiseVerticalScroll(scrollState).padding(bottom = 24.dp),
            ) {
                content()
            }
        }
    }
}
