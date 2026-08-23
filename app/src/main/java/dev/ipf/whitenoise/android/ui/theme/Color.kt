package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.graphics.Color

// Brand anchor — the single hue the whole app is built around. Every other
// role in the scheme (see Theme.kt) is a neutral or a cyan-derived tint chosen
// to sit alongside it, so the palette reads the same on every device rather
// than following the system wallpaper.
val Highlight = Color(0xFF06B6D4)
val OnHighlight = Color(0xFF001F28)

// AMOLED surfaces keep a pure-black fill. Warm, blue-free strokes restore
// object boundaries without driving the OLED panel's blue subpixels.
internal val AmoledSurfaceBorder = Color(0xFF665A00)
internal val AmoledEmphasizedSurfaceBorder = Color(0xFF665A00)
