@file:Suppress("FunctionNaming", "LongParameterList", "MatchingDeclarationName") // Compose state primitives.

package dev.ipf.whitenoise.android.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSizes
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** A button on a state page: [iconRes] leads the label and [testTag] marks it for tests. */
internal class StateAction(
    val label: String,
    val onClick: () -> Unit,
    @DrawableRes val iconRes: Int? = null,
    val testTag: String? = null,
)

/**
 * Full-screen state (loading, failure, locked, startup): content centred in a bounded column that scrolls when
 * large text outgrows the screen. Parts are optional and always appear in the same order: [icon], title,
 * message, [extra], progress with [progressLabel], then the [primary] and [secondary] actions, then [footer].
 */
@Composable
internal fun StatePage(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    title: String? = null,
    message: String? = null,
    selectableMessage: Boolean = false,
    messageModifier: Modifier = Modifier,
    progress: Boolean = false,
    progressLabel: String? = null,
    progressLabelModifier: Modifier = Modifier,
    primary: StateAction? = null,
    secondary: StateAction? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .whiteNoiseVerticalScroll(rememberScrollState())
                .padding(WhiteNoiseSpacing.CompactScreenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StateContent(
            modifier = Modifier.widthIn(max = WhiteNoiseSizes.StatePageMaxWidth).fillMaxWidth(),
            icon = icon,
            title = title,
            message = message,
            selectableMessage = selectableMessage,
            messageModifier = messageModifier,
            progress = progress,
            progressLabel = progressLabel,
            progressLabelModifier = progressLabelModifier,
            primary = primary,
            secondary = secondary,
            extra = extra,
            footer = footer,
        )
    }
}

/**
 * Full-screen progress (app and startup loaders): the same centred parts as [StatePage] without its scroll
 * container, so it costs nothing on cold start and stays safe inside a lazy list item measured with unbounded
 * height. Progress pages hold only an [icon], the indicator and a short [progressLabel], so they never need to
 * scroll.
 */
@Composable
internal fun StateProgressPage(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    progressLabel: String? = null,
    progressLabelModifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().padding(WhiteNoiseSpacing.CompactScreenMargin),
        contentAlignment = Alignment.Center,
    ) {
        StateContent(
            modifier = Modifier.widthIn(max = WhiteNoiseSizes.StatePageMaxWidth).fillMaxWidth(),
            icon = icon,
            progress = true,
            progressLabel = progressLabel,
            progressLabelModifier = progressLabelModifier,
        )
    }
}

/** The state page's parts without the full-screen frame, for a card or a step inside a larger screen. */
@Composable
internal fun StateContent(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    title: String? = null,
    message: String? = null,
    selectableMessage: Boolean = false,
    messageModifier: Modifier = Modifier,
    progress: Boolean = false,
    progressLabel: String? = null,
    progressLabelModifier: Modifier = Modifier,
    primary: StateAction? = null,
    secondary: StateAction? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
    ) {
        icon?.let {
            Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(WhiteNoiseSizes.StateIcon))
        }
        title?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        message?.let {
            val text = @Composable {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().then(messageModifier),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (selectableMessage) SelectionContainer { text() } else text()
        }
        extra()
        if (progress) CircularProgressIndicator()
        progressLabel?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                // The copy can arrive after the indicator is already on screen, so announce it.
                modifier = Modifier.then(progressLabelModifier).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        primary?.let { action ->
            WhiteNoiseButton(onClick = action.onClick, modifier = action.modifier()) { ActionLabel(action) }
        }
        secondary?.let { action ->
            WhiteNoiseOutlinedButton(onClick = action.onClick, modifier = action.modifier()) { ActionLabel(action) }
        }
        footer()
    }
}

/** Full-width action button, tagged with the action's [StateAction.testTag] when it has one. */
private fun StateAction.modifier(): Modifier {
    val width = Modifier.fillMaxWidth()
    return if (testTag != null) width.testTag(testTag) else width
}

/** The action's optional leading icon followed by its label. */
@Composable
private fun ActionLabel(action: StateAction) {
    action.iconRes?.let {
        Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(WhiteNoiseSpacing.Related))
    }
    Text(action.label)
}
