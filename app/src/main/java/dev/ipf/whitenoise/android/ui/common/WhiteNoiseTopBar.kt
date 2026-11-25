package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import dev.ipf.whitenoise.android.R

/** Locale-independent handle for the shared top bar's leading Back or Close action. */
internal const val WHITE_NOISE_TOP_BAR_BACK_TAG = "whitenoise.topbar.back"

/**
 * The leading control of a [WhiteNoiseTopBar].
 *
 * [Back] (arrow, "Back") leaves a page that was pushed onto the stack. [Close] (X, "Close") cancels a modal or
 * full-screen flow that returns to where it started, such as a picker that shares or forwards something.
 */
enum class WhiteNoiseTopBarNavigation { Back, Close }

/**
 * Destination top bar: start-aligned title, one leading [navigation] control, and the scaffold's shared scroll tint.
 *
 * [onBack] runs for either control. [navigationEnabled] disables it while the page commits work, and
 * [navigationAccessory] sits right after it, for a page that shows which account it acts as.
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
    navigation: WhiteNoiseTopBarNavigation = WhiteNoiseTopBarNavigation.Back,
    navigationEnabled: Boolean = true,
    navigationAccessory: (@Composable () -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = titleContent ?: { Text(title, style = titleStyle) },
        navigationIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    enabled = navigationEnabled,
                    modifier = Modifier.testTag(WHITE_NOISE_TOP_BAR_BACK_TAG),
                ) {
                    Icon(
                        painter =
                            painterResource(
                                when (navigation) {
                                    WhiteNoiseTopBarNavigation.Back -> R.drawable.ic_arrow_back
                                    WhiteNoiseTopBarNavigation.Close -> R.drawable.ic_close
                                },
                            ),
                        contentDescription =
                            stringResource(
                                when (navigation) {
                                    WhiteNoiseTopBarNavigation.Back -> R.string.back
                                    WhiteNoiseTopBarNavigation.Close -> R.string.close
                                },
                            ),
                    )
                }
                navigationAccessory?.invoke()
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
