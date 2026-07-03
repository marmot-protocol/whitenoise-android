package dev.ipf.whitenoise.android.ui.theme

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

// Onboarding hero badge (see WhiteNoiseLogoLockup). Fixed brand tokens rather
// than scheme roles: the squircle echoes the shipped launcher icon (#1001), so
// its fill/mark must read the same on every device regardless of the active
// color scheme or a dynamic-color opt-in. The mockup is dark-mode — a muted
// slate-blue fill with the WN mark in a darker navy. The light pairing keeps
// the same hues but lifts the fill so the badge stays legible on the light-mode
// near-white background while preserving install→first-launch continuity.
internal val OnboardingBadgeBlueDark = Color(0xFF3E4E63)
internal val OnboardingBadgeMarkDark = Color(0xFF16202E)
internal val OnboardingBadgeBlueLight = Color(0xFF5A6B82)
internal val OnboardingBadgeMarkLight = Color(0xFF1B2636)
