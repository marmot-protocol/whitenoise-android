package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns queued UI-open notification cleanup across rapid conversation transitions. */
internal class ConversationOpenNotificationDismissalCoordinator(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val presenter: LocalNotificationPresenter,
) {
    private val lifetime = StalenessGuard()

    /** Invalidates cleanup queued by every earlier visible-conversation transition. */
    fun invalidate() {
        lifetime.advance()
    }

    /** Captures the opening boundary synchronously, then cancels its cards off main. */
    fun dismiss(
        accountRef: String?,
        groupIdHex: String?,
    ) {
        val target = conversationOpenDismissalTarget(accountRef, groupIdHex) ?: return
        val generation = lifetime.capture()
        val request = presenter.captureConversationDismissal(target.accountRef, target.groupIdHex) ?: return
        scope.launch(dispatcher) {
            presenter.dismissConversationMessages(request, dispatcher) { lifetime.isCurrent(generation) }
        }
    }
}
