package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Exercise the production accessible alternative on the same thread as pointer and click actions. */
internal fun SemanticsNodeInteraction.performUnreadFilterAction(rule: ComposeTestRule): SemanticsNodeInteraction {
    val action = fetchSemanticsNode().config[SemanticsActions.CustomActions].single().action
    rule.runOnUiThread { check(action()) }
    return this
}
