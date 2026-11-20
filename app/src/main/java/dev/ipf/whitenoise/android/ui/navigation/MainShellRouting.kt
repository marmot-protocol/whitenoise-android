package dev.ipf.whitenoise.android.ui.navigation

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.LayoutDirection.Ltr
import androidx.compose.ui.unit.LayoutDirection.Rtl
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.ui.chats.ChatListReturnHead
import dev.ipf.whitenoise.android.ui.conversation.ConversationSurfaceState
import dev.ipf.whitenoise.android.ui.conversation.NotificationLandingTarget
import dev.ipf.whitenoise.android.ui.settings.SettingsContour

internal data class ConversationOpenContext(
    val activationBounds: androidx.compose.ui.geometry.Rect? = null,
    val focusMessageId: String? = null,
    // Set only when focusMessageId is a search result opened from the chat list (#1945). Voice, playback and
    // attachment-speech destinations also name a message to focus, but land with their own rules and wording.
    val focusFromSearch: Boolean = false,
    val focusMessageRequestId: Long = 0L,
    val ttsFocusSessionId: Long? = null,
    val notificationOpenRequestId: Long = 0L,
    val notificationRouteTraceRequestId: Long? = null,
    // Message notification taps advance this cursor only after the destination
    // freezes its pre-read unread projection. Back commits it immediately when
    // the user leaves before that boundary becomes available (#1016/#2191).
    val notificationReadThroughMessageId: String? = null,
    // The tapped card's captured message with the account and group it was issued for. It is separate
    // from focusMessageId, which belongs to search hits, and the screen verifies the pairing against its
    // controller before it lands on the message's beginning (#3147).
    val notificationTarget: NotificationLandingTarget? = null,
    // Non-null only when a notification-routed conversation opened before its
    // account switch landed (#586). The conversation controller and scroll key
    // bind to this account — never the still-switching active account — so the
    // open renders the target account's local history immediately and survives
    // the active-ref flip without recreation.
    val pinnedAccountRef: String? = null,
)

/** A conversation open waiting for its prepared route, with the list head captured for the return snap. */
internal data class PendingConversationOpen(
    val requestId: Long,
    val accountRef: String?,
    val item: ChatListItem,
    val focusMessageId: String?,
    val justCreated: Boolean,
    val visibleActiveListHead: ChatListReturnHead?,
    val activationBounds: androidx.compose.ui.geometry.Rect? = null,
)

internal fun pendingConversationOpenBelongsToAccount(
    requestAccountRef: String?,
    activeAccountRef: String?,
): Boolean = requestAccountRef != null && requestAccountRef == activeAccountRef

internal fun mainShellAccountContentOwned(
    previousAccountRef: String?,
    activeAccountRef: String?,
): Boolean = previousAccountRef == activeAccountRef

internal data class ConversationTransitionContent(
    val chat: ChatListItem,
    val controller: ConversationController,
    val accountRef: String,
    val openContext: ConversationOpenContext,
    val justCreated: Boolean,
    val openedAsDmHint: Boolean,
    val surfaceState: ConversationSurfaceState? = null,
)

internal fun conversationControllerAccountRef(
    selectedPinnedAccountRef: String?,
    pendingAccountRef: String?,
    exitingAccountRef: String?,
    activeAccountRef: String?,
): String? = selectedPinnedAccountRef ?: pendingAccountRef ?: exitingAccountRef ?: activeAccountRef

internal fun retainedConversationContentBelongsToRoute(
    contentAccountRef: String,
    activeAccountRef: String?,
    pinnedAccountRef: String?,
    notificationRouteTraceRequestId: Long?,
    notificationEarlyOpenRequestId: Long,
): Boolean =
    contentAccountRef == activeAccountRef ||
        (
            pinnedAccountRef == contentAccountRef &&
                notificationEarlyOpenRequestId != 0L &&
                notificationRouteTraceRequestId == notificationEarlyOpenRequestId
        )

internal fun preparedConversationCanOpen(
    hasPublishedAuthoritativeTimeline: Boolean,
    hasPreparedInitialPresentation: Boolean,
    hasLoadError: Boolean,
    terminalConversationUnavailable: Boolean,
): Boolean =
    (hasPublishedAuthoritativeTimeline && hasPreparedInitialPresentation) ||
        hasLoadError ||
        terminalConversationUnavailable

internal fun conversationRouteForwardDirection(layoutDirection: LayoutDirection): Int =
    when (layoutDirection) {
        Ltr -> 1
        Rtl -> -1
    }

/**
 * One staged share's post-account-switch chat open, bound to the share request that produced it.
 * [stagedAtElapsedMillis] is [SystemClock.elapsedRealtime] at staging, so a token restored long after
 * the share (or across a reboot) is discarded instead of reopening a chat out of context.
 */
internal data class PendingStagedShareOpen(
    val requestId: String,
    val accountRef: String,
    val groupIdHex: String,
    val otherChatCount: Int,
    val stagedAtElapsedMillis: Long = SystemClock.elapsedRealtime(),
) {
    /** True while the account-switch handoff is still recent enough to follow the user's share. */
    fun isFresh(nowElapsedMillis: Long = SystemClock.elapsedRealtime()): Boolean =
        nowElapsedMillis - stagedAtElapsedMillis in 0L..STAGED_SHARE_OPEN_MAX_AGE_MILLIS
}

/** Covers a slow account switch and chat-list load, but not a later unrelated app return. */
internal const val STAGED_SHARE_OPEN_MAX_AGE_MILLIS = 5L * 60L * 1_000L

internal sealed interface ProfileForegroundRoute {
    data object None : ProfileForegroundRoute

    data class ShellProfile(
        val npub: String,
    ) : ProfileForegroundRoute

    data class ConversationProfile(
        val npub: String,
    ) : ProfileForegroundRoute

    data class NewGroup(
        val initialMember: RecipientSearch.Candidate,
    ) : ProfileForegroundRoute
}

internal class ProfileGroupForegroundState {
    var initialMember by mutableStateOf<RecipientSearch.Candidate?>(null)
        private set

    var launchContour by mutableStateOf<SettingsContour?>(null)
        private set

    var launchOrigin by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        private set

    fun open(
        member: RecipientSearch.Candidate,
        origin: androidx.compose.ui.geometry.Rect? = null,
        contour: SettingsContour? = null,
    ) {
        launchContour = contour
        launchOrigin = origin
        initialMember = member
    }

    fun close() {
        launchContour = null
        launchOrigin = null
        initialMember = null
    }
}

internal fun profileForegroundRoute(
    pendingProfileNpub: String?,
    startGroupMember: RecipientSearch.Candidate?,
    conversationOpen: Boolean,
): ProfileForegroundRoute =
    when {
        startGroupMember != null -> ProfileForegroundRoute.NewGroup(startGroupMember)
        pendingProfileNpub == null -> ProfileForegroundRoute.None
        conversationOpen -> ProfileForegroundRoute.ConversationProfile(pendingProfileNpub)
        else -> ProfileForegroundRoute.ShellProfile(pendingProfileNpub)
    }

/** Builds the next notification open context, with a fresh request id and the card's captured target. */
internal fun nextNotificationConversationOpenContext(
    current: ConversationOpenContext,
    notificationRouteTraceRequestId: Long? = null,
    pinnedAccountRef: String? = null,
    notificationReadThroughMessageId: String? = null,
    notificationTarget: NotificationLandingTarget? = null,
): ConversationOpenContext =
    ConversationOpenContext(
        notificationOpenRequestId = current.notificationOpenRequestId + 1L,
        notificationRouteTraceRequestId = notificationRouteTraceRequestId,
        notificationReadThroughMessageId = notificationReadThroughMessageId,
        notificationTarget = notificationTarget,
        pinnedAccountRef = pinnedAccountRef,
    )

internal enum class MainShellContentRoute {
    Conversation,
    NotificationLoading,
    TtsReturnTransition,
    Main,
}

/** Selects the single shell surface that owns the current navigation frame. */
internal fun resolveMainShellContentRoute(
    conversationOpen: Boolean,
    routingNotification: Boolean,
    routingTtsReturn: Boolean,
): MainShellContentRoute =
    when {
        routingNotification -> MainShellContentRoute.NotificationLoading
        routingTtsReturn -> MainShellContentRoute.TtsReturnTransition
        conversationOpen -> MainShellContentRoute.Conversation
        else -> MainShellContentRoute.Main
    }
