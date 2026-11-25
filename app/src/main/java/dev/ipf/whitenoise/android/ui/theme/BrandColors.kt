package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.graphics.Color

/** Fixed identity colours for avatars without a picture; the same in every theme so a person keeps their colour. */
object AvatarPalette {
    val colors: List<Color> =
        listOf(
            Color(0xFF006A6A),
            Color(0xFF8C4A00),
            Color(0xFF5B5FC7),
            Color(0xFF006D3B),
            Color(0xFF9A4055),
        )
}

/** Brand colours of the app-update emblem; identical in light, dark and AMOLED. */
object AppUpdateEmblemColors {
    val background = Color(0xFF55D6C2)
    val foreground = Color(0xFF00382F)
}

/** Camera red for the record button and recording dot; it sits on the black camera preview in every theme. */
val CameraRecordRed = Color(0xFFE53935)
