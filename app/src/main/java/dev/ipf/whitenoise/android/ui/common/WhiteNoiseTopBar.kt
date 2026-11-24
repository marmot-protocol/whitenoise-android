package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import dev.ipf.whitenoise.android.R

/** Locale-independent handle for the shared top bar's Back action. */
internal const val WHITE_NOISE_TOP_BAR_BACK_TAG = "whitenoise.topbar.back"

/** Locale-independent handle for the shared top bar's Close action. */
internal const val WHITE_NOISE_TOP_BAR_CLOSE_TAG = "whitenoise.topbar.close"

/**
 * The leading action of a [WhiteNoiseTopBar].
 *
 * Navigation icon rule: a page pushed onto the back stack, including a full-screen reader opened from a row, shows
 * the back arrow ([Back]). A modal flow that the user enters, completes or abandons, such as a share or forward
 * picker that leaves the current screen unchanged, shows the close cross ([Close]). Icon and label always agree.
 */
enum class WhiteNoiseTopBarNavigation { Back, Close }

/** One leading top-bar action whose icon, label and test tag follow [kind]. */
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseTopBarNavigationButton(
    kind: WhiteNoiseTopBarNavigation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val back = kind == WhiteNoiseTopBarNavigation.Back
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag(if (back) WHITE_NOISE_TOP_BAR_BACK_TAG else WHITE_NOISE_TOP_BAR_CLOSE_TAG),
    ) {
        Icon(
            painter = painterResource(if (back) R.drawable.ic_arrow_back else R.drawable.ic_close),
            contentDescription = stringResource(if (back) R.string.back else R.string.close),
        )
    }
}

/**
 * Destination top bar: start-aligned title, one leading action ([navigation], see the rule on
 * [WhiteNoiseTopBarNavigation]), and the scaffold's shared scroll tint. [titleContent] replaces the title text
 * (two-line title and subtitle, inline search); [navigationContent] replaces the leading action when it carries
 * more than the single button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongParameterList")
@Composable
fun WhiteNoiseTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    scrollBehavior: TopAppBarScrollBehavior? = LocalWhiteNoiseHeaderScroll.current,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    scrolledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    titleContent: (@Composable () -> Unit)? = null,
    navigation: WhiteNoiseTopBarNavigation = WhiteNoiseTopBarNavigation.Back,
    navigationEnabled: Boolean = true,
    navigationContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = titleContent ?: { Text(title, style = titleStyle) },
        navigationIcon = {
            if (navigationContent != null) {
                navigationContent()
            } else {
                WhiteNoiseTopBarNavigationButton(navigation, onBack, enabled = navigationEnabled)
            }
        },
        actions = actions,
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor,
            ),
        scrollBehavior = scrollBehavior,
    )
}
