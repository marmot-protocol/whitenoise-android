package dev.ipf.whitenoise.android.ui.theme

/**
 * Named alphas for dimming media under overlaid text or controls. One source
 * of truth for the "scrim over an image/video" pattern — the values are the
 * ones the call sites already shipped with, consolidated rather than redesigned.
 */
internal object ScrimAlpha {
    /** Faint full-bleed dim under transient media states (video buffering). */
    const val FAINT = 0.35f

    /** Backdrop for a small floating chip over media (bubble-footer timestamp). */
    const val CHIP = 0.4f

    /** Full-bleed dim while video transport controls are visible. */
    const val CONTROLS = 0.45f

    /** Grid-tile dim for overlaid attribution or label text. */
    const val TILE = 0.5f

    /** Circular affordance backdrop over media (play button, QR scan frame). */
    const val AFFORDANCE = 0.55f

    /** Heavy dim for edit-action overlays on avatars and cover images. */
    const val HEAVY = 0.62f
}
