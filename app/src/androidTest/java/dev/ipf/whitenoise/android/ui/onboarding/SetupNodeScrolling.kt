package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performScrollTo

/** Scrolls a node into view when the scrolling content owns it; an action in a pinned bar is already on screen. */
internal fun SemanticsNodeInteraction.scrollToIfScrollable(): SemanticsNodeInteraction {
    var parent = fetchSemanticsNode().parent
    while (parent != null) {
        if (SemanticsActions.ScrollBy in parent.config) return performScrollTo()
        parent = parent.parent
    }
    return this
}
