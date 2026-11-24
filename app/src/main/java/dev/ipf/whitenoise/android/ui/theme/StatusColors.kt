package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Status roles Material 3 has no slot for. Both read as text or icons on the theme's surfaces
 * (at least 4.5:1 on light, dark and AMOLED), so they carry meaning together with a label or icon shape.
 */
@Immutable
class StatusColors(
    val success: Color,
    val warning: Color,
)

internal val LightStatusColors = StatusColors(success = Color(0xFF146C2E), warning = Color(0xFF934B00))

/** Dark and AMOLED share the roles: AMOLED differs from dark only in surface fills. */
internal val DarkStatusColors = StatusColors(success = Color(0xFF81C995), warning = Color(0xFFFFB86B))

internal val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** `MaterialTheme.statusColors.success` and `.warning`, set by [WhiteNoiseTheme]. */
val MaterialTheme.statusColors: StatusColors
    @Composable
    @ReadOnlyComposable
    get() = LocalStatusColors.current
