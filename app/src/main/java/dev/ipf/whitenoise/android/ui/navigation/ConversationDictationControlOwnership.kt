@file:Suppress("MatchingDeclarationName") // Route and owner types share one presentation policy.

package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.continueTarget
import dev.ipf.whitenoise.android.audio.requiresCompletionControls
import dev.ipf.whitenoise.android.ui.conversation.composer.DictationControlFacts
import dev.ipf.whitenoise.android.ui.conversation.composer.controlFacts
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
    recoveryHandedToComposer: Boolean = false,
): ConversationDictationControlOwner {
    if (appLockScreenVisible || state.target == null || !state.requiresCompletionControls(recoveryHandedToComposer)) {
        return ConversationDictationControlOwner.Hidden
    }
    val originComposerVisible = conversationDictationOriginComposerVisible(state, route)
    return when {
        shouldShowConversationDictationPersistentControl(
            state = state,
            originVisible = false,
            appLockScreenVisible = false,
        ) -> ConversationDictationControlOwner.Persistent
        originComposerVisible -> ConversationDictationControlOwner.Composer
        else -> ConversationDictationControlOwner.Hidden
    }
}

/** The measured launcher can seed entry only in the current session's exact rendered origin. */
internal fun conversationDictationOriginComposerVisible(
    state: ConversationDictationState,
    route: ConversationDictationComposerRoute,
): Boolean {
    val target = state.target ?: return false
    return route.navigationAccountStable &&
        route.composerVisible &&
        route.selectedChatId != null &&
        route.selectedChatId == route.renderedChatId &&
        route.renderedAccountRef.equals(target.accountRef, ignoreCase = true) &&
        route.selectedGroupIdHex.equals(target.groupIdHex, ignoreCase = true)
}

/**
 * Continue records into the active dictation's chat. While another chat's composer is on screen the
 * control leaves it out, and Dictate in that composer queues a recording for that chat instead.
 */
internal fun ConversationDictationController.controlFactsFor(route: ConversationDictationComposerRoute): DictationControlFacts {
    val facts = controlFacts
    val target = continueTarget ?: return facts
    val otherChatOnScreen =
        route.composerVisible &&
            route.selectedGroupIdHex != null &&
            !(
                route.renderedAccountRef.equals(target.accountRef, ignoreCase = true) &&
                    route.selectedGroupIdHex.equals(target.groupIdHex, ignoreCase = true)
            )
    return if (otherChatOnScreen) facts.copy(continueAvailable = false) else facts
}
