package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.ui.common.MeasuredControlHandoff
import dev.ipf.whitenoise.android.ui.common.controlHandoffEndpoint

/** The shell connects only these two real controls; no composer or draft state is shared. */
internal enum class ComposerFabEndpoint { Composer, NewMessage }

internal val LocalComposerFabHandoff = staticCompositionLocalOf<MeasuredControlHandoff?> { null }

/** Registers geometry without replacing the endpoint's existing action or dictation obstacle. */
@Composable
internal fun Modifier.composerFabEndpoint(endpoint: ComposerFabEndpoint): Modifier =
    controlHandoffEndpoint(LocalComposerFabHandoff.current, endpoint)
