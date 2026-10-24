package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel

/** Exercise the production accessible alternative, including when pull gestures are disabled. */
@OptIn(ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.performUnreadFilterAction(): SemanticsNodeInteraction =
    performCustomAccessibilityActionWithLabel(fetchSemanticsNode().config[SemanticsActions.CustomActions].single().label)
