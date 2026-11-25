package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.ipf.whitenoise.android.state.ConversationNoticeDestination
import dev.ipf.whitenoise.android.state.TransientNotice
import dev.ipf.whitenoise.android.state.TransientNoticeKind
import dev.ipf.whitenoise.android.state.isForConversation
import kotlinx.coroutines.delay

/** The region that holds the temporary banners of the slot that currently owns them. */
internal const val TOP_NOTICE_REGION_TAG = "top-notice-region"

/** The material snackbar durations, which the material host keeps private. */
internal const val SNACKBAR_SHORT_MILLIS = 4_000L
internal const val SNACKBAR_LONG_MILLIS = 10_000L

/** An app-wide confirmation shown in the owning top region. */
internal const val GLOBAL_TRANSIENT_NOTICE_TAG = "global-transient-notice"

/** A confirmation that belongs to one conversation, shown only inside that conversation's region. */
internal const val CONVERSATION_TRANSIENT_NOTICE_TAG = "conversation-transient-notice"

/** Share of the visible window height a temporary banner region may take before it scrolls internally. */
internal const val TOP_NOTICE_MAX_HEIGHT_FRACTION = 0.3f

/** A banner region never shrinks below one comfortable line, even in a very short window. */
internal val TOP_NOTICE_MIN_MAX_HEIGHT = 72.dp

/** One top slot that can present temporary banners; identity is what decides ownership. */
internal class TopNoticeClaim

/**
 * Chooses the single top slot that presents app-level temporary banners (#3312).
 *
 * Temporary confirmations and snackbars sit at the top of the screen that owns them, below its bars and in
 * the same region as the playback strip, so a visible composer never gives up height to them. Every screen
 * region that can host them claims a slot while composed, and the newest claim owns the banners: a destination
 * pushed over the shell, or a modal over a destination, takes them over and hands them back when it leaves.
 * Nothing renders while [presentationAllowed] is false, which is how app lock hides stale text and actions.
 * The host reads the app's temporary notice, retained partial result and snackbar queue. One slot owns their
 * presentation at a time.
 */
@Stable
internal class TopNoticeHost(
    val snackbarHostState: SnackbarHostState,
    private val notice: () -> TransientNotice?,
    val partial: () -> TransientNotice? = { null },
    val dismissNotice: (TransientNotice) -> Unit = {},
    private val presentationAllowed: () -> Boolean = { true },
    val present: (Int, TransientNoticeKind) -> Unit = { _, _ -> },
) {
    private val claims = mutableStateListOf<TopNoticeClaim>()

    /** The app's current temporary notice, global or conversation scoped. */
    val transientNotice: TransientNotice?
        get() = notice()

    /** True when [claim] is the newest live slot and presentation is allowed. */
    fun owns(claim: TopNoticeClaim): Boolean = presentationAllowed() && claims.lastOrNull() === claim

    /** Makes [claim] the newest slot, as a screen does when it enters composition. */
    fun claim(claim: TopNoticeClaim) {
        claims.remove(claim)
        claims += claim
    }

    /** Returns ownership to the previous slot when [claim]'s screen leaves composition. */
    fun release(claim: TopNoticeClaim) {
        claims.remove(claim)
    }
}

/** The app's top-notice owner, absent in previews and isolated tests. */
internal val LocalTopNoticeHost = staticCompositionLocalOf<TopNoticeHost?> { null }

/** The conversation a subtree belongs to, so a conversation-scoped notice renders only inside it. */
internal val LocalTopNoticeScope = staticCompositionLocalOf<ConversationNoticeDestination?> { null }

/**
 * Which notice a slot presents: a global notice in the owning slot, and a conversation-scoped notice only in the
 * owning slot of that same account and conversation, so it can never leak into another chat.
 */
internal fun topNoticeFor(
    notice: TransientNotice?,
    owner: Boolean,
    scope: ConversationNoticeDestination?,
): TransientNotice? {
    val destination = notice?.conversation
    val inScope = destination == null || scope?.let { notice.isForConversation(it.accountRef, it.groupIdHex) } == true
    return notice?.takeIf { owner && inScope }
}

/** One composed slot: its claim on the host and whether it currently owns the banners. */
@Stable
internal class TopNoticeSlotState(
    private val host: TopNoticeHost?,
    private val claim: TopNoticeClaim,
) {
    /** True while this slot presents the banners. Without a host, as in previews, every slot does. */
    val owns: Boolean
        get() = host?.owns(claim) ?: true

    /** The app snackbar queue this slot shows while it owns the banners. */
    val snackbarHostState: SnackbarHostState?
        get() = host?.snackbarHostState

    /** The app's current notice, or null without a host. */
    val hostNotice: TransientNotice?
        get() = host?.transientNotice

    fun partialNotice(scope: ConversationNoticeDestination?): TransientNotice? {
        val retained = host?.partial?.invoke()
        return topNoticeFor(retained, owner = true, scope = scope)
    }

    fun dismiss(notice: TransientNotice) {
        host?.dismissNotice?.invoke(notice)
    }

    /** True while this slot draws something, so a caller can hand it a system-bar inset it would otherwise own. */
    fun presents(
        notice: TransientNotice?,
        scope: ConversationNoticeDestination?,
    ): Boolean {
        val snackbar = snackbarHostState?.currentSnackbarData
        // Empty slots still claim ownership, but their callers do not depend on the claim order.
        val partial = host?.partial?.invoke()
        if (notice == null && partial == null && snackbar == null) return false
        val owner = owns
        return topNoticeFor(notice, owner, scope)?.kind?.let { it != TransientNoticeKind.Confirmation } == true ||
            topNoticeFor(partial, owner, scope) != null ||
            (owner && snackbar != null)
    }
}

/** Claims a top slot for as long as the calling screen stays composed. */
@Composable
internal fun rememberTopNoticeSlot(): TopNoticeSlotState {
    val host = LocalTopNoticeHost.current
    val claim = remember { TopNoticeClaim() }
    DisposableEffect(host, claim) {
        host?.claim(claim)
        onDispose { host?.release(claim) }
    }
    return remember(host, claim) { TopNoticeSlotState(host, claim) }
}

/** Measures actionable/status notices and layers a compact confirmation above the destination. */
@Composable
@Suppress("FunctionNaming")
internal fun TopNoticeRegion(
    slot: TopNoticeSlotState,
    modifier: Modifier = Modifier,
    notice: TransientNotice? = slot.hostNotice,
    scope: ConversationNoticeDestination? = LocalTopNoticeScope.current,
    confirmationNeedsStatusInset: Boolean = false,
) {
    if (!slot.owns) return
    val shown = topNoticeFor(notice, owner = true, scope = scope)
    val snackbarHostState = slot.snackbarHostState?.takeIf { it.currentSnackbarData != null }
    val inlineNotice = shown?.takeUnless { it.kind == TransientNoticeKind.Confirmation }
    val partialNotice = slot.partialNotice(scope)
    val hasMeasuredNotice = inlineNotice != null || partialNotice != null || snackbarHostState != null
    val bubbleNeedsInset = confirmationNeedsStatusInset && !hasMeasuredNotice
    Column(modifier.fillMaxWidth().zIndex(1f)) {
        if (hasMeasuredNotice) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .heightIn(max = topNoticeMaxHeight())
                    .fadingVerticalScroll(rememberScrollState())
                    .testTag(TOP_NOTICE_REGION_TAG),
            ) {
                listOfNotNull(partialNotice, inlineNotice).forEach {
                    val tag =
                        if (it.conversation == null) GLOBAL_TRANSIENT_NOTICE_TAG else CONVERSATION_TRANSIENT_NOTICE_TAG
                    InlineConfirmationNotice(
                        notice = it,
                        onDismiss = if (it.kind == TransientNoticeKind.Partial) ({ slot.dismiss(it) }) else null,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
                    )
                }
                snackbarHostState?.let { WhiteNoiseSnackbarHost(it) }
            }
        }
        // Anchor below measured feedback so the bubble never covers its actions.
        key(scope) {
            val inset = if (bubbleNeedsInset) Modifier.statusBarsPadding() else Modifier
            AnchoredConfirmationBubble(
                notice = shown?.takeIf { it.kind == TransientNoticeKind.Confirmation },
                modifier = inset.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            )
        }
    }
}

/** A slot and its region in one call, for screens whose region needs no inset coordination. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun TopNoticeSlot(
    modifier: Modifier = Modifier,
    notice: TransientNotice? = null,
    scope: ConversationNoticeDestination? = LocalTopNoticeScope.current,
) {
    val slot = rememberTopNoticeSlot()
    TopNoticeRegion(slot = slot, modifier = modifier, notice = notice ?: slot.hostNotice, scope = scope)
}

/** The tallest the banner region may grow: a share of the window height the keyboard leaves visible. */
@Composable
private fun topNoticeMaxHeight(): Dp {
    val density = LocalDensity.current
    val windowHeight = LocalConfiguration.current.screenHeightDp.dp
    val keyboard = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    return ((windowHeight - keyboard) * TOP_NOTICE_MAX_HEIGHT_FRACTION).coerceAtLeast(TOP_NOTICE_MIN_MAX_HEIGHT)
}

/**
 * Times the queued snackbar out independently of which slot is composed. The material host only counts down
 * while it is composed, so a short confirmation queued under app lock, or while a modal takes the banners over,
 * would otherwise show for its whole duration again once a slot picked it up. Run at the app root, this clock
 * starts when a snackbar reaches the head of the queue, as the material host's does, and runs on through lock,
 * slot changes and modals, so a confirmation is timed out once, and an indefinite error still stays until it is
 * dismissed (#3312).
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun TopNoticeSnackbarTimeoutEffect(hostState: SnackbarHostState) {
    val current = hostState.currentSnackbarData
    val accessibilityManager = LocalAccessibilityManager.current
    LaunchedEffect(current) {
        current ?: return@LaunchedEffect
        val timeout = snackbarTimeoutMillis(current.visuals, accessibilityManager)
        if (timeout == Long.MAX_VALUE) return@LaunchedEffect
        delay(timeout)
        current.dismiss()
    }
}

/**
 * How long a snackbar stays, as the material host decides it: the duration's fixed length, stretched to the
 * accessibility manager's recommended timeout for text with icons and, when it has an action, controls. An
 * indefinite snackbar never times out.
 */
internal fun snackbarTimeoutMillis(
    visuals: SnackbarVisuals,
    accessibilityManager: AccessibilityManager?,
): Long {
    val original =
        when (visuals.duration) {
            SnackbarDuration.Indefinite -> return Long.MAX_VALUE
            SnackbarDuration.Long -> SNACKBAR_LONG_MILLIS
            SnackbarDuration.Short -> SNACKBAR_SHORT_MILLIS
        }
    return accessibilityManager?.calculateRecommendedTimeoutMillis(
        original,
        containsIcons = true,
        containsText = true,
        containsControls = visuals.actionLabel != null,
    ) ?: original
}
