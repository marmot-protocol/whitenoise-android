package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.ui.unit.Dp

/**
 * Semantic layer over the [Dimens] scale. Each name states where a gap is used and is an alias of one
 * scale step, so changing the rhythm means changing a [Dimens] step, never a second copy of the number.
 * Reach for these names first for custom White Noise compositions; use [Dimens] directly only when no
 * name carries the meaning. Material owns component internals.
 */
object WhiteNoiseSpacing {
    val CompactScreenMargin: Dp = Dimens.spaceLg
    val SettingsSectionInset: Dp = Dimens.spaceXxl
    val Related: Dp = Dimens.spaceSm
    val FormField: Dp = Dimens.spaceLg
    val Section: Dp = Dimens.spaceXl

    /**
     * Gap between stacked cards or groups that read as separate blocks. Today only the usage
     * diagnostics list uses it; the conversation timeline sets its own row spacing in
     * `ConversationTimelineLayout`.
     */
    val ConversationCluster: Dp = Dimens.spaceMd
    val PinnedActionInset: Dp = Dimens.spaceLg

    /** Horizontal inset of banners and inline notices (callouts, composer and conversation notices). */
    val NoticeHorizontalInset: Dp = Dimens.spaceMd

    /** Vertical inset of banners and inline notices. */
    val NoticeVerticalInset: Dp = Dimens.spaceSm

    /** Padding around the content of a modal bottom sheet. */
    val SheetContentInset: Dp = Dimens.spaceXl
}
