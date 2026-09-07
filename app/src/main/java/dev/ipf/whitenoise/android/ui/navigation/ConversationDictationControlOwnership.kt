@file:Suppress("MatchingDeclarationName") // Route and owner types share one presentation policy.

package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.ui.shouldShowConversationDictationPersistentControl

/** Presentation ownership only; this must never become a dictation delivery destination. */
internal enum class ConversationDictationControlOwner {
    Hidden,
    Composer,
    Persistent,
}

/** The selected and rendered composer from one shell composition, not notification suppression. */
internal data class ConversationDictationComposerRoute(
    val selectedChatId: String?,
    val selectedGroupIdHex: String?,
    val renderedChatId: String?,
    val renderedAccountRef: String?,
    val navigationAccountStable: Boolean,
    val composerVisible: Boolean,
)

/** Transfers controls synchronously with the route while keeping the session target immutable. */
internal fun conversationDictationControlOwner(
    state: ConversationDictationState,
    route: ConversationDictationComposerRoute,
    appLockScreenVisible: Boolean,
): ConversationDictationControlOwner {
    val target = state.target
    if (appLockScreenVisible || target == null) return ConversationDictationControlOwner.Hidden
    val originComposerVisible =
        route.navigationAccountStable &&
            route.composerVisible &&
            route.selectedChatId != null &&
            route.selectedChatId == route.renderedChatId &&
            route.renderedAccountRef.equals(target.accountRef, ignoreCase = true) &&
            route.selectedGroupIdHex.equals(target.groupIdHex, ignoreCase = true)
    return when {
        originComposerVisible -> ConversationDictationControlOwner.Composer
        shouldShowConversationDictationPersistentControl(
            state = state,
            originVisible = false,
            appLockScreenVisible = false,
        ) -> ConversationDictationControlOwner.Persistent
        else -> ConversationDictationControlOwner.Hidden
    }
}
