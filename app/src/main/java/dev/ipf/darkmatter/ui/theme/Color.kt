package dev.ipf.darkmatter.ui.theme

import androidx.compose.ui.graphics.Color

// Brand anchor — the single hue the whole app is built around. Every other
// role in the scheme (see Theme.kt) is a neutral or a cyan-derived tint chosen
// to sit alongside it, so the palette reads the same on every device rather
// than following the system wallpaper.
val Highlight = Color(0xFF06B6D4)
val OnHighlight = Color(0xFF001F28)

// AMOLED surfaces keep a pure-black fill; this dim neutral stroke restores
// object boundaries without lifting the surface color off #000000.
internal val AmoledSurfaceBorder = Color(0xFF242424)
internal val AmoledEmphasizedSurfaceBorder = Color(0xFF2A2A2A)
