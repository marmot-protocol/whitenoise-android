package dev.ipf.whitenoise.android.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageSearch
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.countUnreadIncoming
import dev.ipf.whitenoise.android.state.nextReadAnchor
import dev.ipf.whitenoise.android.state.unreadReceivedMentionIds
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchNavBar
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.chats.newchat.canInviteFromEmptyGroup
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.common.rememberConversationControllerCopy
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.conversationComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberConversationMentionPickerState
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldClearFocusOnResume
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldRestoreComposerFocusOnResume
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewSheet
import dev.ipf.whitenoise.android.ui.conversation.media.NullableFileSaver
import dev.ipf.whitenoise.android.ui.conversation.media.NullableUriSaver
import dev.ipf.whitenoise.android.ui.conversation.media.UriListSaver
import dev.ipf.whitenoise.android.ui.conversation.media.clearMediaTempFiles
import dev.ipf.whitenoise.android.ui.conversation.media.createImageCaptureFile
import dev.ipf.whitenoise.android.ui.conversation.media.fileProviderUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubble
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Maximum images per multi-pick. The Android Photo Picker enforces this
// cap on the system dialog side; 10 keeps the album payload bounded
// (10 * 1920px JPEG ≈ a few MB encrypted) without feeling artificially low.
// Approximate clearance the conversation composer occupies above the
// navigation bar. Used by ConversationScreen to push the global
// snackbar host above the composer so toasts don't intercept touches
// on the message input — see [LocalSnackbarBottomInset] + issue #122.
// 72.dp covers the single-line composer plus its vertical padding.
// Only a seed for the first frames: the bottom bar's measured height
// replaces it as soon as layout runs (#796), so multi-line composers,
// reply/edit banners, and the invite bar stay cleared exactly.
private val COMPOSER_SNACKBAR_INSET = 72.dp

private const val MEDIA_PICKER_MAX_ITEMS = 10

// Per-file ceiling for a document attachment. Matches the retained-uploads
// LRU cap so a single oversize pick can't OOM the picker pass before the
// retained store gets a chance to evict. Anything larger is dropped with a
// toast — the user can re-pick a smaller file or split the upload.
private const val MEDIA_ATTACHMENT_MAX_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

// Total bytes cap across one album send. Bound to the retained-uploads LRU
// cap (NOT independently doubled): exceeding that cap on insert would cause
// `ByteSizeLruCache` to evict the just-inserted RetainedMediaUpload during
// its own `put()` pass, breaking retry. Keep the picker ceiling honest with
// the actual heap budget rather than letting the user pick more than the
// controller can ever hold.
private const val MEDIA_ALBUM_MAX_TOTAL_BYTES = ConversationController.MEDIA_RETAINED_MAX_BYTES

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationScreen(
    appState: WhiteNoiseAppState,
    chat: ChatListItem,
    onBack: () -> Unit,
    // When opened from a chat-list message-body search hit (issue #290), the
    // matched message id to scroll to and briefly highlight once the timeline
    // has paged it in. Null for every normal open path.
    focusMessageId: String? = null,
    // Whether [focusMessageId] also gets the brief highlight flash. Search hits
    // do; a notification tap scrolls without the color flash.
    highlightFocusMessage: Boolean = true,
    // True when opened by tapping a message notification: receiving a message
    // implies current membership, so the composer shows immediately rather than
    // a placeholder while membership verification catches up after the switch.
    openedFromNotification: Boolean = false,
    // True only when this conversation was just created in the same navigation
    // step (issue #321) — drives a one-shot composer focus + keyboard raise so
    // the user can type the first message without an extra tap. False for row
    // taps, notification routing, and search hits.
    justCreated: Boolean = false,
    // True only when the opener knows this conversation is a newly-created DM.
    // The live roster can briefly report 0/1 members before the peer arrives;
    // keep that transient state in the DM presentation instead of falling into
    // the group subtitle branch (#998).
    openedAsDmHint: Boolean = false,
    // (chat, justCreated): navigate to another shared group when the user taps a
    // shared group / Message in the in-conversation profile sheet (issue #635).
    // Reuses the shell's existing open-group lambda so this path matches the
    // shell-level sheet's onOpenGroup exactly.
    onOpenProfileGroup: (ChatListItem, Boolean) -> Unit = { _, _ -> },
    // Scroll position captured when the user last left this chat while reading
    // history (issue #1107). Null when none was saved or they left near-bottom.
    restoredScrollSnapshot: ConversationScrollSnapshot? = null,
    onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit = {},
) {
    WindowSecureFlag(enabled = !appState.allowChatScreenshotsInChats)
    // Push the global snackbar host above the conversation composer so
    // a toast (e.g. the post-invite-accept confirmation) doesn't
    // overlap and intercept touches on the message input. Resets to
    // zero on dispose so other surfaces aren't affected. Issue #122.
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    // Keyed on chat.id so that a back-to-back conversation push (Compose
    // reusing the same node across nav) re-runs the effect: the
    // previous chat's onDispose may not have fired before the next
    // enters, leaving the inset at zero on a stale snackbar host.
    // Seeds the resting-composer estimate; the bottom bar's measured
    // height takes over on first layout (#796).
    DisposableEffect(chat.id) {
        snackbarBottomInset.value = COMPOSER_SNACKBAR_INSET
        onDispose { snackbarBottomInset.value = 0.dp }
    }
    val controllerCopy = rememberConversationControllerCopy()
    val controller =
        // Key on the active account too: chat.id is the groupIdHex, which is
        // shared across local accounts that belong to the same group. Without
        // the account in the key, switching accounts into the same conversation
        // (e.g. tapping another account's notification) reuses a controller
        // still bound to the previous account's timeline and read state.
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) {
            ConversationController(
                appState = appState,
                initialGroup = chat.group,
                initialMemberSnapshot =
                    chat.memberSnapshot
                        ?: appState.cachedGroupMemberSnapshot(appState.activeAccountRef, chat.group.groupIdHex),
                initialLastReadMessageId = chat.projection?.lastReadMessageIdHex,
                initialLastReadTimelineAt = chat.projection?.lastReadTimelineAt,
                copy = controllerCopy,
            )
        }
    val collapseLongMessages = appState.collapseLongMessagesInGroup(chat.group.groupIdHex)
    // When the developer streaming-debug toggle flips, re-publish the timeline.
    // Turning it off drops the transient QUIC debug rows so they don't linger.
    LaunchedEffect(controller, appState.streamingDebugEnabled) {
        controller.refreshStreamingDebugPresentation()
    }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on chat.id so opening a different conversation (e.g. via Message or
    // a shared-group tap from a profile opened on this group's details page)
    // lands on the conversation, not the new chat's details page.
    var showDetails by remember(chat.id) { mutableStateOf(false) }
    // Notification suppression must follow the visible *timeline*, not merely an
    // open chat. While group details/settings (and its sub-screens) are up, the
    // user can't see incoming messages, so those must notify — lift the
    // active-conversation suppression for the group while details are showing
    // and restore it on return to the timeline.
    LaunchedEffect(chat.group.groupIdHex, showDetails) {
        appState.setActiveConversation(if (showDetails) null else chat.group.groupIdHex)
    }
    var pendingTopBarLeaveAction by remember { mutableStateOf<LeaveAction?>(null) }
    // Sole-admin Leave gate: a sole admin with other members can't leave until
    // they hand admin to someone else. Instead of the old toast-only dead end,
    // the Leave action surfaces a "Transfer admin first" dialog that routes
    // into the group details transfer picker (#417, adversarial review).
    var showTransferAdminFirst by remember { mutableStateOf(false) }
    // Set when the user opts to transfer from that dialog: opens details with
    // the transfer picker auto-expanded. Keyed on chat.id so it doesn't leak
    // across a conversation switch.
    var openTransferOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Empty newly-created groups should route users into the existing member
    // invite flow instead of carrying a duplicate picker in the create sheet.
    var openAddMemberOnDetails by remember(chat.id) { mutableStateOf(false) }
    // Re-open after back-to-list should land where the reader left off, unless
    // this open path owns the anchor (search hit, just-created) or they left
    // at/near the bottom — then the existing unread/newest anchor runs.
    val scrollRestore =
        restoredScrollSnapshot?.takeIf {
            focusMessageId == null &&
                !justCreated
        }
    val positionalScrollRestore =
        scrollRestore?.takeIf {
            it.anchorItemId.isNullOrBlank() && it.anchorMessageIdHex.isNullOrBlank()
        }
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex = positionalScrollRestore?.firstVisibleItemIndex ?: 0,
            initialFirstVisibleItemScrollOffset = positionalScrollRestore?.firstVisibleItemScrollOffset ?: 0,
        )
    // Single conversation-level owner of which message's action menu is open, so
    // only one popover can be open at a time. With the keyboard up the menu is
    // non-focusable (#284), so long-pressing several bubbles would otherwise
    // stack several popovers; deriving each bubble's open state from this one id
    // makes opening one close any other.
    var openActionMenuId by remember(chat.id) { mutableStateOf<String?>(null) }
    var initialTimelineAnchored by remember(chat.id) { mutableStateOf(false) }
    // Id of the newest row the bottom-follow has reacted to. A real append
    // gives a new last id while the previous one stays in the list; an
    // older-page load trims the newest rows, so the previous id is gone and
    // no follow fires. Keyed on id (not recordedAt) to survive same-second tails.
    var lastFollowedLatestId by remember(chat.id) { mutableStateOf<String?>(null) }
    var initialTimelineLoadStarted by remember(chat.id) { mutableStateOf(false) }
    var highlightedMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    var navigateReplyJob by remember(chat.id) { mutableStateOf<Job?>(null) }
    // UI-only row-height cache for exact centered scrolls. LazyColumn can only
    // measure a target after it has been composed; keeping the measured height
    // by message id lets future off-screen jumps animate straight to the exact
    // centered offset, while never becoming protocol/data source-of-truth state.
    val timelineItemHeightsPx = remember(chat.id) { mutableStateMapOf<String, Int>() }
    // In-chat search (#292). Opening from the overflow menu swaps the top
    // bar into an inline search field; closing it restores the normal bar.
    // `searchPinnedMatchId` keeps the active match anchored to a concrete
    // message id so the N/M cursor follows that message as older pages load
    // and the match set grows. `searchJob` serializes scroll-jump coroutines
    // the same way `navigateReplyJob` does for reply navigation.
    var searchOpen by remember(chat.id) { mutableStateOf(false) }
    var searchQuery by remember(chat.id) { mutableStateOf("") }
    var searchPinnedMatchId by remember(chat.id) { mutableStateOf<String?>(null) }
    var searchJob by remember(chat.id) { mutableStateOf<Job?>(null) }
    // Scroll anchor captured the moment search opens, restored verbatim on
    // close so leaving search returns the list to where the reader was —
    // #292 requires "Closing search restores the normal top bar and scroll
    // position." Pair = (firstVisibleItemIndex, firstVisibleItemScrollOffset).
    var preSearchScrollAnchor by remember(chat.id) { mutableStateOf<Pair<Int, Int>?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    // Jump-to-newest plumbing.
    //
    // Badge = incoming messages newer than the highest-index timeline row the
    // user has ever had on screen during this composition. The high-water
    // mark only INCREASES, so scrolling back up past read messages doesn't
    // resurrect the badge.
    //
    //   HWM advances when the viewport reaches a new highest-visible row.
    //   New incoming arrivals (which extend the timeline beyond HWM) bump
    //   the badge. On chat re-entry, the auto-scroll's snap to the bottom
    //   immediately advances HWM to the last timeline index, so the badge
    //   shows 0 — matching the convention that an "open chat" is read up to
    //   the visible row, not the last delivered row.
    // Edits (kind-1009) are derived state, not chat — they mutate the
    // original message's body via [editsByTarget] and must not occupy a slot
    // in the lazy list. A naive `return@items` still reserves the slot, which
    // (combined with `Arrangement.spacedBy`) leaves a visible gap. Filter
    // them out up front and base every index/scroll calculation on the
    // filtered list so what we count matches what we render.
    val renderedTimeline =
        remember(controller.timeline) {
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        }
    val renderedSize = renderedTimeline.size
    val nearBottom by remember {
        derivedStateOf {
            // Match the rendered list size, otherwise bottomTimelineIndex
            // overshoots and nearBottom stays false at the physical bottom.
            isNearBottom(listState, renderedSize, controller.hasMoreBefore || controller.isLoadingOlder)
        }
    }
    // Read anchor stored as the message id of the deepest row the user has
    // settled on. Looked up live each time so load-older prepends shift both
    // the candidate and the anchor by the same offset — position comparisons
    // stay valid. Anchored on id (not recordedAt) to survive same-second
    // collisions: send() stamps with nowSeconds(), so multiple messages can
    // share a recordedAt and a strict-`>` filter would under-count.
    var readAnchorMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    val currentHighestVisibleTimelineIndex by remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf -1
            val olderHeader = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
            // LazyColumn layout: [Spacer][maybe older-loading][timeline items][Spacer]
            val firstTimelineListIndex = 1 + olderHeader
            // Visible rows index into the rendered (edit-filtered) list, so clamp
            // to its last index, not the longer unfiltered timeline's.
            (visible.last().index - firstTimelineListIndex)
                .coerceAtMost(renderedSize - 1)
        }
    }
    LaunchedEffect(currentHighestVisibleTimelineIndex) {
        val idx = currentHighestVisibleTimelineIndex
        if (idx < 0) return@LaunchedEffect
        // Monotonic advance only — scroll-up keeps the existing anchor so the
        // read pointer never moves backwards. See [nextReadAnchor]. Resolve the
        // visible row against the filtered (rendered) list it indexes into, not
        // the unfiltered timeline.
        readAnchorMessageId = nextReadAnchor(renderedTimeline, readAnchorMessageId, idx)
    }
    DisposableEffect(chat.id) {
        onDispose {
            val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
            val hasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
            val firstTimelineIndex =
                listState.firstVisibleItemIndex - 1 - (if (hasOlderHeader) 1 else 0)
            val anchor = rendered.getOrNull(firstTimelineIndex)
            onSaveScrollSnapshot(
                conversationScrollSnapshotOnLeave(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    nearBottom =
                        isNearBottom(
                            listState,
                            rendered.size,
                            hasOlderHeader,
                        ),
                    anchorItemId = anchor?.id,
                    anchorMessageIdHex = anchor?.record?.messageIdHex,
                ),
            )
        }
    }
    val unreadIncomingCount by remember {
        derivedStateOf {
            if (!initialTimelineAnchored) {
                0
            } else {
                countUnreadIncoming(controller.timeline, readAnchorMessageId)
            }
        }
    }
    // Unread messages (after the read anchor) that mention the active account,
    // oldest first — drives the in-conversation jump-to-mention chip. Mirrors
    // countUnreadIncoming's anchor logic; kind-9 only, matching the engine's
    // mention classification, reusing the #414 per-message detection.
    val selfAccountIdHexForMentions = appState.activeAccount?.accountIdHex
    val unreadMentionMessageIds by remember {
        derivedStateOf {
            // Anchor on the UI read high-water mark. It advances immediately when
            // the user visits a mention and when the visible row settles, so a
            // recreated controller cannot briefly resurrect already-read mentions.
            if (!initialTimelineAnchored || selfAccountIdHexForMentions.isNullOrBlank() || readAnchorMessageId == null) {
                emptyList()
            } else {
                unreadReceivedMentionIds(controller.timeline, readAnchorMessageId) { msg ->
                    documentMentionsAccount(
                        document = msg.record.contentTokens,
                        accountIdHex = selfAccountIdHexForMentions,
                        resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                    )
                }
            }
        }
    }
    // Reading the raw IME inset in the body would re-subscribe and recompose
    // this (very heavy) screen on every keyboard-animation frame. Capture the
    // ime WindowInsets (the @Composable read) once, then collapse to the
    // boolean edge inside derivedStateOf so only the open/close transition
    // triggers a recomposition. getBottom() reads the inset's snapshot state
    // inside the derived block. See #374.
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val imeIsOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    // #589: composer focus is hoisted here so the resume lifecycle observer
    // below can drive it. `composerFocus` is the requester wired into the
    // composer's BasicTextField; `composerFocused` mirrors the live focus
    // edge reported by `onFocusChanged`; `wasComposerFocusedOnPause` snapshots
    // that edge on ON_PAUSE so resume can restore the exact keyboard state the
    // user left with (Case B). Keyed on chat.id so a conversation switch
    // doesn't carry the previous chat's keyboard state across.
    val composerFocus = remember(chat.id) { FocusRequester() }
    var composerFocused by remember(chat.id) { mutableStateOf(false) }
    var wasComposerFocusedOnPause by remember(chat.id) { mutableStateOf(false) }
    // #589: used by the resume observer to clear focus and drop the keyboard
    // when the composer was NOT focused on pause (Case B), without poking the
    // composer's own focus requester.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // Seeded empty and populated off the Main thread: the first access to a
    // SharedPreferences file blocks on disk, and doing that inside composition
    // stalls the conversation screen's first frame. See #147.
    var recentReactionEmojis by remember(context) {
        mutableStateOf(emptyList<String>())
    }
    var quickReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiList.DefaultQuickChoices)
    }
    var quickReactionEmojisTouched by remember(context) { mutableStateOf(false) }
    LaunchedEffect(context) {
        val (loaded, quick) =
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.load(context) to RecentEmojiPreferences.loadQuickReactions(context)
            }
        // A pick made before this load lands has already merged the disk list
        // (recordPicked re-reads prefs), so a non-empty state is strictly newer
        // — don't clobber it with the stale read.
        if (recentReactionEmojis.isEmpty()) {
            recentReactionEmojis = loaded
        }
        if (!quickReactionEmojisTouched) {
            quickReactionEmojis = quick
        }
    }
    // Serialize the recents read-modify-write so rapid taps don't lose updates
    // (concurrent recordPicked() is last-writer-wins on the disk list). See #172.
    val recentEmojiWriteMutex = remember { Mutex() }
    // Selected-but-not-yet-sent image attachments. The preview sheet opens
    // when this or `pendingDocumentUris` is non-empty; the whole queue
    // ships as one kind:9 album via `controller.sendAttachments(list, caption)`.
    //
    // Persist the staging shelf across process death (issue #531): capturing
    // in landscape foregrounds the external camera app, which on low/medium
    // memory devices gets the backgrounded host process killed. On return the
    // activity is recreated and a plain `remember` would have wiped the shelf,
    // dropping the just-captured image even though `cameraOutputUri` survived.
    // The camera capture is a FileProvider URI over an app-owned cache file,
    // so it re-opens fine post-restore. Photo Picker / GET_CONTENT / document
    // URIs carry session-scoped read grants that DON'T survive process death;
    // if such a URI was staged when the process died it returns as a ghost
    // that fails to open — that degrades gracefully through the existing
    // decode-failure toast path in `sendStagedAttachments`, which is a better
    // outcome than silently losing the camera capture the user just accepted.
    // Keyed on chat.id so a conversation switch still flushes the staging
    // shelf — ConversationScreen is reused when `selectedChat` changes in
    // place, and an unkeyed state would otherwise carry URIs from chat A into
    // chat B (where a Send would attach them to the wrong recipient).
    var pendingMediaUris by rememberSaveable(chat.id, stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    var pendingDocumentUris by rememberSaveable(chat.id, stateSaver = UriListSaver) {
        mutableStateOf<List<android.net.Uri>>(emptyList())
    }
    // Survives process death while the camera app is foreground (the result
    // callback fires into a recreated activity, otherwise the capture is lost).
    var cameraOutputUri by rememberSaveable(stateSaver = NullableUriSaver) {
        mutableStateOf<android.net.Uri?>(null)
    }
    // Survives process death alongside `cameraOutputUri` so a capture
    // cancelled after a death-and-restore can still delete the empty temp
    // file instead of leaking it (issue #531).
    var cameraOutputFile by rememberSaveable(stateSaver = NullableFileSaver) {
        mutableStateOf<java.io.File?>(null)
    }

    // PickMultipleVisualMedia uses the system Photo Picker — no READ_MEDIA_IMAGES
    // permission needed (Android 13+ scopes the picker's own grant); on older
    // devices it falls back to GET_CONTENT with the same UX. The maxItems
    // cap comes from MEDIA_PICKER_MAX_ITEMS; picking a single image still works
    // (returns a one-element list).
    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(maxItems = MEDIA_PICKER_MAX_ITEMS),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append rather than replace so a follow-up "Add more" tile-pick
            // grows the staging shelf instead of clobbering whatever the user
            // already queued. Dedupe on Uri identity to keep a double-pick
            // from doubling the row, and cap on MEDIA_PICKER_MAX_ITEMS so the
            // shelf can't exceed what a fresh pick would have been allowed.
            val merged = (pendingMediaUris + uris).distinct().take(MEDIA_PICKER_MAX_ITEMS)
            pendingMediaUris = merged
        }
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val captured = cameraOutputUri
            if (success && captured != null) {
                // Append to whatever's already queued so an in-progress staging
                // shelf survives a camera capture.
                val merged = (pendingMediaUris + captured).distinct().take(MEDIA_PICKER_MAX_ITEMS)
                pendingMediaUris = merged
            } else {
                cameraOutputFile?.delete() // cancelled — don't leak the empty temp
            }
            cameraOutputUri = null
            cameraOutputFile = null
        }

    fun launchCameraCapture() {
        val file = createImageCaptureFile(context)
        if (file == null) {
            appState.present(R.string.toast_couldnt_decode_image, copyable = true)
            return
        }
        cameraOutputFile = file
        val uri = fileProviderUri(context, file)
        cameraOutputUri = uri
        cameraLauncher.launch(uri)
    }

    // TakePicture needs no permission of its own, but because CAMERA is declared
    // in the manifest (for the QR scanner) some OEMs require the runtime grant
    // before launching the capture intent — request it first if missing.
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (granted) launchCameraCapture() }

    // Voice-message recording surface — owned per ConversationScreen so a
    // backgrounded recording is dropped on dispose. The recorder writes
    // into a per-session temp dir; the file is consumed by `sendVoiceMessage`
    // below and then removed.
    val voiceOutputDir =
        remember(context) {
            java.io.File(context.cacheDir, "voice-recordings").apply { mkdirs() }
        }
    val micPermissionDeniedMsg = stringResource(R.string.voice_message_permission_denied)
    val voiceTooShortMsg = stringResource(R.string.voice_message_too_short)
    var voiceMicPermissionRequested by remember { mutableStateOf(false) }
    val voiceMicPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> if (!granted) appState.present(micPermissionDeniedMsg) }

    fun sendVoiceAttachment(
        file: java.io.File,
        durationMs: Long,
    ) {
        appState.launchMutation {
            val bytes =
                withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                }
            withContext(Dispatchers.IO) { runCatching { file.delete() } }
            if (bytes == null || bytes.isEmpty()) return@launchMutation
            val attachment =
                PendingAttachment(
                    plaintextBytes = bytes,
                    mediaType = dev.ipf.whitenoise.android.audio.VoiceRecorder.MIME_TYPE,
                    fileName = "voice-${durationMs}ms.${dev.ipf.whitenoise.android.audio.VoiceRecorder.FILE_EXTENSION}",
                )
            val seeded = controller.queueAttachments(listOf(attachment), null) ?: return@launchMutation
            scope.launch {
                val target = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                listState.animateScrollToItem(target)
            }
            controller.uploadQueued(seeded)
        }
    }

    fun readImageAttachment(
        uri: android.net.Uri,
        remainingBytes: Long,
    ): ImageAttachmentReadOutcome {
        val quality = appState.mediaQuality
        // Animated images (GIF, animated WebP) can't survive the static JPEG
        // recompress path — it flattens them to a single frame. Preserve them at
        // any quality; the quality knob only governs static-image downscaling.
        val animatedSource = MediaPipeline.isAnimatedImageSource(context.contentResolver, uri)
        if (quality.preservesOriginalImageBytes || animatedSource) {
            val cap = remainingBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            when (val original = MediaPipeline.readOriginalImageForUpload(context.contentResolver, uri, cap)) {
                is MediaPipeline.OriginalImageReadResult.Success ->
                    return ImageAttachmentReadOutcome(
                        PendingAttachment(
                            plaintextBytes = original.image.bytes,
                            mediaType = original.image.mediaType,
                            fileName = original.image.fileName,
                            dim = original.image.dim,
                            thumbhash = original.image.thumbhash,
                        ),
                    )
                MediaPipeline.OriginalImageReadResult.TooLarge -> return ImageAttachmentReadOutcome(null, overflowed = true)
                MediaPipeline.OriginalImageReadResult.Failed,
                MediaPipeline.OriginalImageReadResult.Unsupported,
                // Never flatten an animation to JPEG — fail the attachment instead
                // of silently dropping the animation. Static sources still fall
                // back to the JPEG re-encode so unsupported containers strip metadata.
                -> if (animatedSource) return ImageAttachmentReadOutcome(null) else Unit
            }
        }
        val jpeg =
            MediaPipeline.readDownscaledJpeg(
                context.contentResolver,
                uri,
                maxEdgePx = quality.imageMaxEdgePx,
                quality = quality.imageJpegQuality,
            ) ?: return ImageAttachmentReadOutcome(null)
        if (jpeg.bytes.size.toLong() > remainingBytes) {
            return ImageAttachmentReadOutcome(null, overflowed = true)
        }
        val sourceName = queryDisplayName(context.contentResolver, uri) ?: "image.jpg"
        val fileName = MediaPipeline.swapExtensionToJpg(sourceName)
        return ImageAttachmentReadOutcome(
            PendingAttachment(
                plaintextBytes = jpeg.bytes,
                mediaType = MediaPipeline.RECOMPRESSED_MIME,
                fileName = fileName,
                dim = "${jpeg.width}x${jpeg.height}",
                thumbhash = jpeg.thumbhash,
            ),
        )
    }

    val voiceRecordingController =
        // Re-key on every captured dependency: chat.id (basic), controller
        // (avoids dispatching through a stale ConversationController when
        // appState.runtimeGeneration changes), and voiceOutputDir (a fresh
        // File reference if context/cacheDir flips — also future-proofs an
        // account-scoped dir).
        remember(chat.id, controller, voiceOutputDir) {
            dev.ipf.whitenoise.android.audio.VoiceRecordingController(
                context = context,
                outputDirectory = voiceOutputDir,
                scope = scope,
                onPermissionRequest = {
                    val granted =
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    if (!granted && !voiceMicPermissionRequested) {
                        voiceMicPermissionRequested = true
                        voiceMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    granted
                },
                onRecordingComplete = { file, durationMs -> sendVoiceAttachment(file, durationMs) },
                onError = { throwable ->
                    if (throwable is IllegalStateException && throwable.message == "voice recording too short") {
                        appState.present(voiceTooShortMsg)
                    }
                },
                // Honor the user's media-quality ceiling for voice notes.
                // Read at record-start (the controller is not re-keyed on the
                // quality state) so a setting change applies to the next clip.
                bitrateProvider = { appState.mediaQuality.audioBitrateBps },
            )
        }
    DisposableEffect(voiceRecordingController) {
        onDispose { voiceRecordingController.release() }
    }

    // Auto-chain voice playback: when one clip ends, play the IMMEDIATE
    // next message iff it's also a voice attachment. Stops on any
    // non-voice neighbor (text, image, system) or end-of-timeline. We do
    // not skip past unrelated messages to find a later voice note — that
    // would jump the user past content they hadn't consumed.
    DisposableEffect(controller, chat.id) {
        val ownerKey = controller.group.groupIdHex
        val unregister =
            dev.ipf.whitenoise.android.audio.VoicePlaybackController.registerCompletionCallback(ownerKey) { completedKey ->
                val completedMsgId = completedKey.substringBefore('#')
                val completedIdx = controller.timeline.indexOfFirst { it.record.messageIdHex == completedMsgId }
                if (completedIdx >= 0) {
                    // Walk forward only as long as the next item is a derived-
                    // state row (edit / group system) — those are invisible to
                    // the user, so skipping them doesn't violate "immediate
                    // neighbor" semantics.
                    var nextIdx = completedIdx + 1
                    while (nextIdx < controller.timeline.size &&
                        (
                            MessageProjector.isEdit(controller.timeline[nextIdx].record) ||
                                MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)
                        )
                    ) {
                        nextIdx++
                    }
                    val nextMsg = controller.timeline.getOrNull(nextIdx)
                    val refs = nextMsg?.let { controller.mediaReferences[it.record.messageIdHex] }
                    val audioEntry =
                        refs?.withIndex()?.firstOrNull { (_, r) ->
                            r.mediaType.startsWith("audio/", ignoreCase = true)
                        }
                    if (nextMsg != null && audioEntry != null) {
                        val idx = audioEntry.index
                        val ref = audioEntry.value
                        scope.launch {
                            val mine = nextMsg.record.direction != "received"
                            val file =
                                runCatching {
                                    materializeVoiceAttachment(
                                        context = context,
                                        controller = controller,
                                        messageIdHex = nextMsg.record.messageIdHex,
                                        attachmentIndex = idx,
                                        reference = ref,
                                        mine = mine,
                                    )
                                }.getOrNull() ?: return@launch
                            dev.ipf.whitenoise.android.audio.VoicePlaybackController
                                .play("${nextMsg.record.messageIdHex}#$idx", file, ownerKey = ownerKey)
                        }
                    }
                }
            }
        onDispose {
            unregister()
        }
    }

    // Decode/compress each URI off the main thread, then hand the album to
    // the controller as a single `sendAttachments(list, caption)` call. One
    // kind:9 carries N imeta tags; the caption is shared across the whole
    // album. If any source fails to decode the rest still send (best
    // effort), but if NONE decode we bail without surfacing an empty send.
    fun sendPickedMedia(
        uris: List<android.net.Uri>,
        caption: String,
    ) {
        if (uris.isEmpty()) return
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            val result =
                withContext(Dispatchers.Default) {
                    val out = mutableListOf<PendingAttachment>()
                    var consumed = 0L
                    var overflowed = false
                    for (uri in uris) {
                        val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - consumed).coerceAtLeast(0L)
                        if (remaining <= 0L) {
                            overflowed = true
                            break
                        }
                        val mime = safeGetType(context.contentResolver, uri)
                        val attachment =
                            if (mime.startsWith("video/", ignoreCase = true)) {
                                when (val r = MediaPipeline.readVideoForUpload(context, uri, remaining)) {
                                    is MediaPipeline.VideoReadResult.Success ->
                                        PendingAttachment(
                                            plaintextBytes = r.video.bytes,
                                            mediaType = r.video.mediaType,
                                            fileName = r.video.fileName,
                                            dim = "${r.video.width}x${r.video.height}",
                                            thumbhash = r.video.thumbhash,
                                        )
                                    MediaPipeline.VideoReadResult.TooLarge -> {
                                        overflowed = true
                                        continue
                                    }
                                    MediaPipeline.VideoReadResult.Failed -> continue
                                }
                            } else {
                                val image = readImageAttachment(uri, remaining)
                                if (image.overflowed) {
                                    overflowed = true
                                    continue
                                }
                                image.attachment ?: continue
                            }
                        consumed += attachment.plaintextBytes.size.toLong()
                        out += attachment
                    }
                    out to overflowed
                }
            val attachments = result.first
            val albumOverflowed = result.second
            if (attachments.size < uris.size) {
                val anyVideoPicked =
                    uris.any {
                        safeGetType(context.contentResolver, it)
                            .startsWith("video/", ignoreCase = true)
                    }
                appState.present(
                    when {
                        albumOverflowed -> R.string.media_album_too_large
                        anyVideoPicked -> R.string.toast_couldnt_process_video
                        else -> R.string.toast_couldnt_decode_image
                    },
                    // Decode/process failures are diagnostic; the album size
                    // cap is a fixed validation limit (#796).
                    copyable = !albumOverflowed,
                )
                if (attachments.isEmpty()) return@launchMutation
            }
            controller.sendAttachments(attachments, trimmedCaption)
        }
    }

    // Read picked document URIs into attachments. Non-image documents are kept
    // as raw bytes; image/* picks from Files use the same media-quality and
    // metadata-stripping path as visual image picks before joining the document
    // send path. MIME comes from the content resolver; filename from
    // `OpenableColumns.DISPLAY_NAME`.
    //
    // Two-layer size guard:
    //   1. Per-attachment ceiling: skip any single pick that already declares
    //      a `OpenableColumns.SIZE` greater than [MEDIA_ATTACHMENT_MAX_BYTES],
    //      OR overruns the cap during a bounded streaming read (no fully-
    //      buffered `readBytes()` so a 500 MB pick can't OOM the JVM heap
    //      before the retained-uploads LRU has anything to evict).
    //   2. Album-total ceiling: stop accumulating once the cumulative payload
    //      crosses [MEDIA_ALBUM_MAX_TOTAL_BYTES]; remaining picks are dropped.
    //
    // Any reject surfaces a single user-visible toast; the rest of the album
    // continues. If NOTHING survives the gates we bail without an empty send.
    // Decoded outcome of the document read pass, surfaced so the unified
    // sendStagedAttachments path can blend its results with the image decode.
    data class DocumentReadOutcome(
        val attachments: List<PendingAttachment>,
        val rejected: Boolean,
        val albumOverflowed: Boolean,
        val totalBytes: Long,
    )

    suspend fun readPickedDocuments(
        uris: List<android.net.Uri>,
        bytesBudget: Long = MEDIA_ALBUM_MAX_TOTAL_BYTES,
    ): DocumentReadOutcome =
        withContext(Dispatchers.IO) {
            val accepted = mutableListOf<PendingAttachment>()
            var albumBytes = 0L
            var rejected = false
            var albumOverflowed = false
            for (uri in uris) {
                val resolvedMime =
                    safeGetType(context.contentResolver, uri)
                        .takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                val remainingAlbumBudget = (bytesBudget - albumBytes).coerceAtLeast(0L)
                if (remainingAlbumBudget <= 0L) {
                    albumOverflowed = true
                    break
                }
                if (resolvedMime.startsWith("image/", ignoreCase = true)) {
                    val image = readImageAttachment(uri, remainingAlbumBudget)
                    if (image.overflowed) {
                        albumOverflowed = true
                        continue
                    }
                    val attachment = image.attachment
                    if (attachment == null) {
                        rejected = true
                        continue
                    }
                    if (attachment.plaintextBytes.isEmpty()) continue
                    val nextAlbumBytes = albumBytes + attachment.plaintextBytes.size.toLong()
                    if (nextAlbumBytes > bytesBudget) {
                        albumOverflowed = true
                        continue
                    }
                    albumBytes = nextAlbumBytes
                    accepted += attachment
                    continue
                }
                val declaredSize = queryContentSize(context.contentResolver, uri)
                if (declaredSize > 0L && declaredSize > MEDIA_ATTACHMENT_MAX_BYTES) {
                    rejected = true
                    continue
                }
                val perFileCap =
                    minOf(MEDIA_ATTACHMENT_MAX_BYTES, remainingAlbumBudget)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                val bytes =
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            MediaPipeline.readBoundedBytes(stream, perFileCap)
                        }
                    }.getOrNull()
                if (bytes == null) {
                    rejected = true
                    continue
                }
                if (bytes.isEmpty()) continue
                if (albumBytes + bytes.size > bytesBudget) {
                    albumOverflowed = true
                    continue
                }
                albumBytes += bytes.size
                val name = queryDisplayName(context.contentResolver, uri) ?: "file"
                accepted +=
                    PendingAttachment(
                        plaintextBytes = bytes,
                        mediaType = resolvedMime,
                        fileName = name,
                        dim = null,
                    )
            }
            DocumentReadOutcome(accepted, rejected, albumOverflowed, albumBytes)
        }

    data class VisualReadOutcome(
        val attachments: List<PendingAttachment>,
        val albumOverflowed: Boolean,
    )

    suspend fun readPickedImages(uris: List<android.net.Uri>): VisualReadOutcome =
        withContext(Dispatchers.Default) {
            val out = mutableListOf<PendingAttachment>()
            var consumed = 0L
            var overflowed = false
            for (uri in uris) {
                val remaining = (MEDIA_ALBUM_MAX_TOTAL_BYTES - consumed).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    overflowed = true
                    break
                }
                val mime = safeGetType(context.contentResolver, uri)
                val attachment =
                    if (mime.startsWith("video/", ignoreCase = true)) {
                        // Thread the remaining album budget into the video read so a
                        // multi-video pick can't accumulate hundreds of MB in heap
                        // before the cap downstream would reject the tail.
                        when (val r = MediaPipeline.readVideoForUpload(context, uri, remaining)) {
                            is MediaPipeline.VideoReadResult.Success ->
                                PendingAttachment(
                                    plaintextBytes = r.video.bytes,
                                    mediaType = r.video.mediaType,
                                    fileName = r.video.fileName,
                                    dim = "${r.video.width}x${r.video.height}",
                                    thumbhash = r.video.thumbhash,
                                )
                            MediaPipeline.VideoReadResult.TooLarge -> {
                                overflowed = true
                                continue
                            }
                            MediaPipeline.VideoReadResult.Failed -> continue
                        }
                    } else {
                        val image = readImageAttachment(uri, remaining)
                        if (image.overflowed) {
                            overflowed = true
                            continue
                        }
                        image.attachment ?: continue
                    }
                consumed += attachment.plaintextBytes.size.toLong()
                out += attachment
            }
            VisualReadOutcome(out, overflowed)
        }

    // Single-path send used by the unified staging shelf: decodes images
    // (downscale + JPEG) and documents (raw bytes with cap) in parallel,
    // concatenates the attachments, and ships them as one kind-9 album.
    fun sendStagedAttachments(
        imageUris: List<android.net.Uri>,
        documentUris: List<android.net.Uri>,
        caption: String,
        onAfterSend: () -> Unit = {},
    ) {
        if (imageUris.isEmpty() && documentUris.isEmpty()) return
        val trimmedCaption = caption.trim().takeIf { it.isNotBlank() }
        appState.launchMutation {
            // Enforce the album byte cap on images first so a multi-large-photo
            // pick can't push the cumulative payload past
            // MEDIA_ALBUM_MAX_TOTAL_BYTES and evict the retained-uploads LRU
            // mid-flight (which would break retry). Drop the tail and surface
            // a single oversize toast.
            val rawImages = readPickedImages(imageUris)
            var imageBytes = 0L
            val acceptedImages = mutableListOf<PendingAttachment>()
            var imageAlbumOverflowed = rawImages.albumOverflowed
            for (attachment in rawImages.attachments) {
                val next = imageBytes + attachment.plaintextBytes.size
                if (next > MEDIA_ALBUM_MAX_TOTAL_BYTES) {
                    imageAlbumOverflowed = true
                    continue
                }
                imageBytes = next
                acceptedImages += attachment
            }
            val docBudget = (MEDIA_ALBUM_MAX_TOTAL_BYTES - imageBytes).coerceAtLeast(0L)
            val docOutcome =
                if (documentUris.isEmpty()) {
                    DocumentReadOutcome(emptyList(), rejected = false, albumOverflowed = false, totalBytes = 0L)
                } else {
                    readPickedDocuments(documentUris, docBudget)
                }
            val merged = acceptedImages + docOutcome.attachments
            val pickHasVideo =
                imageUris.any {
                    safeGetType(context.contentResolver, it)
                        .startsWith("video/", ignoreCase = true)
                }
            val visualFailureToast =
                if (pickHasVideo) R.string.toast_couldnt_process_video else R.string.toast_couldnt_decode_image
            if (merged.isEmpty()) {
                // Only surface the visual-decode toast when there were visual
                // picks to begin with — a document-only send that failed every
                // file should fall through to the document toasts below
                // rather than misreporting as an image decode error. And if
                // the album overflowed the byte budget, surface that
                // explicitly instead of "couldn't process".
                if (imageUris.isNotEmpty()) {
                    val toast =
                        if (imageAlbumOverflowed) R.string.media_album_too_large else visualFailureToast
                    appState.present(toast, copyable = !imageAlbumOverflowed)
                    return@launchMutation
                }
            }
            if (acceptedImages.size < imageUris.size && !imageAlbumOverflowed) {
                appState.present(visualFailureToast, copyable = true)
            }
            if (imageAlbumOverflowed || docOutcome.albumOverflowed) {
                appState.present(R.string.media_album_too_large)
            } else if (docOutcome.rejected) {
                appState.present(R.string.media_file_too_large)
            }
            if (merged.isEmpty()) return@launchMutation
            // Two-phase ship: SEED every send synchronously (so all the
            // optimistic bubbles appear in the same recomposition pass and
            // the user sees the queue light up at once), THEN run the
            // FFI upload+publish for each in pick order (so the post-
            // confirm timeline keeps the order the user picked).
            //
            // Image attachments ride one kind-9 album (the masonry layout
            // wants multiple tiles in one message). Non-image attachments
            // ship as their own kind-9 each, because each carries distinct
            // filename/MIME metadata that doesn't benefit from grid
            // composition. Caption sticks with images when present;
            // otherwise it attaches to the first file send.
            // Backfill thumbhash for any image-typed doc-picker attachments that
            // lack one. image/* document picks usually arrive here already
            // processed by the image privacy pipeline; this keeps the renderer
            // defensive for legacy/raw sources while staying off-main so the
            // staging-shelf dismiss animation doesn't stutter on multi-image
            // picks.
            val readyDocAttachments =
                if (docOutcome.attachments.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        docOutcome.attachments.map { attachment ->
                            if (!attachment.mediaType.startsWith("image/", ignoreCase = true) ||
                                attachment.thumbhash != null
                            ) {
                                attachment
                            } else {
                                val bitmap =
                                    MediaPipeline.decodeSampledBitmap(
                                        attachment.plaintextBytes,
                                        MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                                    )
                                val hash = bitmap?.let { Thumbhash.encodeFromBitmap(it) }
                                bitmap?.recycle()
                                attachment.copy(thumbhash = hash)
                            }
                        }
                    }
                }
            // The image picker's multi-select UI groups its picks as one
            // batch, so they ride one kind-9 album — preserves the masonry
            // grouping the user opted into. Doc-picker items, by contrast,
            // are picked one-by-one in order, so each ships as its own
            // kind-9 in pick position regardless of MIME. Image MIMEs from
            // the doc picker still render as image bubbles (single-image
            // variant of the album shape) — same surface, different framing.
            val seeded = mutableListOf<ConversationController.QueuedAttachmentSend>()
            if (acceptedImages.isNotEmpty()) {
                controller.queueAttachments(acceptedImages, trimmedCaption)?.let(seeded::add)
            }
            val captionConsumedByAlbum = acceptedImages.isNotEmpty()
            readyDocAttachments.forEachIndexed { index, attachment ->
                val perItemCaption =
                    if (!captionConsumedByAlbum && index == 0) trimmedCaption else null
                controller.queueAttachments(listOf(attachment), perItemCaption)?.let(seeded::add)
            }
            // Pull the user down to the just-seeded bubbles before the
            // upload loop suspends — same UX as text-send. Firing after
            // queueAttachments (the optimistic seed) and before
            // uploadQueued (the FFI publish) means the scroll lands in the
            // same frame the bubble appears, instead of waiting on the
            // relay round-trip.
            if (seeded.isNotEmpty()) onAfterSend()
            // Run uploads sequentially so the kind-9 publishes go out in
            // pick order. The optimistic bubbles are already on screen.
            for (slot in seeded) {
                controller.uploadQueued(slot)
            }
        }
    }

    // Documents take a separate launcher because `OpenMultipleDocuments`
    // accepts any MIME — the image picker can't surface PDFs, archives, etc.
    // Picked URIs accumulate in `pendingDocumentUris` so they can ride the
    // same staging shelf as image picks; one Send dispatches both sides
    // through one kind:9 album. Bytes pass through without recompression —
    // including picked/forwarded audio files. The send-quality audio bitrate
    // is intentionally scoped to recorded voice notes until this client grows
    // a general audio transcode path.
    val documentPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            // Append into the document side of the staging shelf rather than
            // sending immediately. The preview sheet renders both lists and
            // a single Send dispatches both decoders into one kind-9 album.
            val merged = (pendingDocumentUris + uris).distinct().take(MEDIA_PICKER_MAX_ITEMS)
            pendingDocumentUris = merged
        }

    // Wipe camera-capture temp files and retained outgoing attachment bytes
    // when leaving the conversation so plaintext media doesn't linger.
    // `shared_media` is deliberately NOT touched here — an external reader
    // (PDF viewer, etc.) may still be reading a granted FileProvider URI.
    // Those are reaped by the age-based `sweepStaleSharedMedia` janitor at
    // app start.
    DisposableEffect(Unit) {
        onDispose {
            clearMediaTempFiles(context)
            controller.clearRetainedUploads()
        }
    }

    // Scroll the lazy list so the item at [targetMessageId] sits roughly in the
    // vertical center of the message-list viewport, leaving context above and
    // below the target visible (#595, #794). Uses one animated scroll; if the
    // target was never measured before, any final exact-centering correction is
    // a non-animated snap rather than the visible bounce from #999.
    suspend fun centerTimelineItemAt(
        targetMessageId: String,
        fallbackTargetIndex: Int,
    ) {
        fun currentTargetIndex(): Int? {
            val timelineIndex =
                controller.timeline
                    .filterNot { MessageProjector.isEdit(it.record) }
                    .indexOfFirst { it.record.messageIdHex == targetMessageId }
                    .takeIf { it >= 0 }
                    ?: return null
            val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
            return 1 + olderMessagesHeaderCount + timelineIndex
        }

        val targetIndex = currentTargetIndex() ?: fallbackTargetIndex
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height
        if (viewportHeight <= 0) {
            // Layout not measured yet (rare on a fresh open): fall back to the
            // plain top-aligned jump rather than guessing an offset.
            listState.animateScrollToItem(targetIndex)
            return
        }
        val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        val firstTimelineListIndex = 1 + olderMessagesHeaderCount
        val renderedForHeightSample = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val lastTimelineListIndex = firstTimelineListIndex + renderedForHeightSample.size - 1
        val visibleTargetHeight = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size
        val visibleTimelineHeights =
            layoutInfo.visibleItemsInfo
                .filter { visibleItem ->
                    if (visibleItem.index !in firstTimelineListIndex..lastTimelineListIndex) return@filter false
                    val row = renderedForHeightSample.getOrNull(visibleItem.index - firstTimelineListIndex) ?: return@filter false
                    timelineRowKind(row.record, appState.streamingDebugEnabled) == TimelineRowKind.Bubble
                }.map { it.size }
        val itemHeight =
            ReplyNavigation.itemHeightForScrollPx(
                targetMessageId = targetMessageId,
                measuredItemHeightsByMessageId = timelineItemHeightsPx,
                visibleTargetHeightPx = visibleTargetHeight,
                visibleTimelineItemHeightsPx = visibleTimelineHeights,
            )
        // Compose clamps the resulting scroll at the list bounds, so this still
        // degrades as #595 asks: a near-top match can't scroll up past the
        // first row (top-aligned) and a near-bottom match can't scroll down
        // past the last row (bottom-aligned); only the middle case truly
        // centers.
        val animatedOffset = ReplyNavigation.centeredScrollOffset(viewportHeight, itemHeight)
        listState.animateScrollToItem(targetIndex, animatedOffset)

        // After an off-screen row is composed, use its real measured height for
        // exact final centering. This must remain non-animated: the old second
        // animateScrollToItem is what produced the visible overshoot/backtrack.
        withFrameNanos { }
        val resolvedTargetIndex = currentTargetIndex() ?: targetIndex
        val postScrollLayoutInfo = listState.layoutInfo
        val measuredItemHeight =
            postScrollLayoutInfo.visibleItemsInfo.firstOrNull { it.index == resolvedTargetIndex }?.size
                ?: timelineItemHeightsPx[targetMessageId]
        val measuredViewportHeight = postScrollLayoutInfo.viewportSize.height
        if (measuredViewportHeight > 0 && measuredItemHeight != null) {
            val measuredOffset = ReplyNavigation.centeredScrollOffset(measuredViewportHeight, measuredItemHeight)
            if (resolvedTargetIndex != targetIndex || measuredOffset != animatedOffset) {
                listState.scrollToItem(resolvedTargetIndex, measuredOffset)
            }
        }
    }

    fun recordReactionEmoji(emoji: String) {
        // The read-modify-write touches SharedPreferences (disk); keep it off
        // the Main thread, matching the off-Main load above. See #147.
        // Held under a mutex so rapid taps serialize instead of losing updates.
        scope.launch {
            recentReactionEmojis =
                recentEmojiWriteMutex.withLock {
                    withContext(Dispatchers.IO) { RecentEmojiPreferences.recordPicked(context, emoji) }
                }
        }
    }

    fun saveQuickReactionEmojis(choices: List<String>) {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.saveQuickReactions(context, choices) }
        }
    }

    fun resetQuickReactionEmojis() {
        quickReactionEmojisTouched = true
        scope.launch {
            quickReactionEmojis = withContext(Dispatchers.IO) { RecentEmojiPreferences.resetQuickReactions(context) }
        }
    }

    fun navigateToReplyTarget(item: TimelineMessage) {
        navigateReplyJob?.cancel()
        navigateReplyJob =
            scope.launch {
                val targetMessageId = controller.replyTargetMessageId(item)
                if (targetMessageId == null || !controller.loadUntilMessageAvailable(targetMessageId)) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                // Resolve the target in the rendered (edit-filtered) list the
                // LazyColumn shows — an unfiltered index is off by the edits above it.
                val timelineIndex =
                    controller.timeline
                        .filterNot { MessageProjector.isEdit(it.record) }
                        .indexOfFirst { it.record.messageIdHex == targetMessageId }
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                centerTimelineItemAt(targetMessageId, 1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
                delay(1_500L)
                if (highlightedMessageId == targetMessageId) {
                    highlightedMessageId = null
                }
            }
    }

    fun jumpToNextUnreadMention() {
        val targetMessageId = unreadMentionMessageIds.firstOrNull() ?: return
        navigateReplyJob?.cancel()
        navigateReplyJob =
            scope.launch {
                if (!controller.loadUntilMessageAvailable(targetMessageId)) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val timelineIndex =
                    controller.timeline
                        .filterNot { MessageProjector.isEdit(it.record) }
                        .indexOfFirst { it.record.messageIdHex == targetMessageId }
                if (timelineIndex < 0) {
                    appState.present(R.string.toast_original_message_unavailable)
                    return@launch
                }
                val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                centerTimelineItemAt(targetMessageId, 1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
                // Mark read up to the visited mention so the count — and the
                // chat-list @-badge — decrement in step; advance the local read
                // anchor so the chip's derived count updates immediately.
                readAnchorMessageId = targetMessageId
                controller.markReadUpTo(targetMessageId)
                delay(1_500L)
                if (highlightedMessageId == targetMessageId) {
                    highlightedMessageId = null
                }
            }
    }

    LaunchedEffect(controller) {
        initialTimelineLoadStarted = true
        controller.start()
    }
    LaunchedEffect(controller.group.pendingConfirmation, controller.group.groupIdHex) {
        if (controller.group.pendingConfirmation) {
            controller.dismissConversationNotifications()
        }
    }
    // inviteStreamScope outlives a single start() — acceptInvite() launches into
    // it from a separate mutation scope — so it's cancelled on controller
    // disposal here rather than in start()'s teardown (#279).
    DisposableEffect(controller) {
        appState.attachConversationController(controller)
        onDispose {
            appState.detachConversationController(controller)
            controller.onCleared()
        }
    }
    val latestTimelineItemId = renderedTimeline.lastOrNull()?.id
    val transcriptLocale = LocalConfiguration.current.locales[0]
    val olderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
    val bottomTimelineIndex = renderedTimeline.size + 1 + olderHeaderCount

    // Day label for the topmost visible message, surfaced by the sticky ribbon
    // overlay while scrolling. Hoisted into derivedStateOf and held as a State
    // (not read here) so the scroll-backed firstVisibleItemIndex read happens
    // inside the small ribbon child — not in the LazyColumn-hosting Box scope,
    // which would otherwise recompose the timeline container on every scroll
    // frame. Mirrors the nearBottom / currentHighestVisibleTimelineIndex
    // derived-state pattern above (#375).
    val stickyDayLabelState =
        remember(renderedTimeline, transcriptLocale, olderHeaderCount) {
            derivedStateOf {
                val i =
                    (listState.firstVisibleItemIndex - 1 - olderHeaderCount)
                        .coerceIn(0, (renderedTimeline.size - 1).coerceAtLeast(0))
                renderedTimeline
                    .getOrNull(i)
                    ?.record
                    ?.recordedAt
                    ?.let { messageDayLabel(it, transcriptLocale) }
                    .orEmpty()
            }
        }

    // In-chat search match set. Computed over the currently-loaded, rendered
    // (edit-filtered) timeline only — no relay fetch, no full-history preload.
    // Reactions / deletes / group-system / agent-stream rows carry no
    // user-typed body and are excluded by `MessageSearch.isSearchable`. As
    // older pages load, `renderedTimeline` grows and the match set expands
    // naturally. Keyed on `controller.timeline` (not just `renderedTimeline`'s
    // edges/size) so a kind-1009 edit — which changes the body returned by
    // `controller.displayedText(...)` without altering the rendered timeline's
    // first/last id or size — re-runs the derivation and keeps matches fresh.
    val searchMatchIds =
        remember(searchQuery, controller.timeline, renderedTimeline) {
            if (searchQuery.isBlank()) {
                emptyList()
            } else {
                // Restrict to rows that carry a user-typed body, then run the
                // shared substring matcher over those bodies and map the hit
                // indices back to message ids (timeline order preserved).
                // Snapshot the body once per row so the displayed (post-edit)
                // text used for matching is the same text used to map hits.
                val searchable =
                    renderedTimeline.mapNotNull { item ->
                        val body = controller.displayedText(item.record)
                        if (MessageSearch.isSearchable(item.record, body)) {
                            item.record.messageIdHex to body
                        } else {
                            null
                        }
                    }
                val bodies = searchable.map { it.second }
                MessageSearch
                    .matchIndices(bodies, searchQuery)
                    .map { searchable[it].first }
            }
        }
    // The active match ordinal, re-anchored to the pinned message id so it
    // tracks that message as the set grows. -1 when there are no matches.
    val searchActiveIndex = MessageSearch.resolveCursor(searchMatchIds, searchPinnedMatchId)
    // Keep the pin valid: if the resolved cursor fell back to the first match
    // (pin gone / unset) adopt that match id as the new pin so subsequent
    // steps move relative to a real anchor.
    LaunchedEffect(searchMatchIds, searchActiveIndex) {
        if (searchActiveIndex >= 0) {
            val resolvedId = searchMatchIds[searchActiveIndex]
            if (searchPinnedMatchId != resolvedId) searchPinnedMatchId = resolvedId
        }
    }

    fun scrollToSearchMatch(messageIdHex: String) {
        searchJob?.cancel()
        searchJob =
            scope.launch {
                // Local-only: the message is already in the loaded window
                // (matches are derived from it), so this resolves immediately;
                // the helper is reused for symmetry with reply navigation and
                // guards the rare case where a concurrent trim dropped the row.
                if (!controller.loadUntilMessageAvailable(messageIdHex)) return@launch
                val timelineIndex =
                    renderedTimeline.indexOfFirst { it.record.messageIdHex == messageIdHex }
                if (timelineIndex < 0) return@launch
                // Center the match so prior + subsequent context is visible (#595).
                centerTimelineItemAt(messageIdHex, 1 + olderHeaderCount + timelineIndex)
                highlightedMessageId = messageIdHex
                delay(1_500L)
                if (highlightedMessageId == messageIdHex) {
                    highlightedMessageId = null
                }
            }
    }

    // Step the cursor (next = forward/newer, previous = backward/older) with
    // wrap-around, pin the new match, and jump+highlight it.
    fun navigateToSearchMatch(forward: Boolean) {
        if (searchMatchIds.isEmpty()) return
        val next = MessageSearch.step(searchActiveIndex, searchMatchIds.size, forward)
        if (next < 0) return
        val targetId = searchMatchIds[next]
        searchPinnedMatchId = targetId
        scrollToSearchMatch(targetId)
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
        searchPinnedMatchId = null
        searchJob?.cancel()
        highlightedMessageId = null
        // Restore the scroll position captured when search opened (#292). The
        // cancel above stops any in-flight search scroll-jump, so this resolves
        // to the pre-search anchor without racing the search animation.
        preSearchScrollAnchor?.let { (index, offset) ->
            searchJob =
                scope.launch {
                    listState.scrollToItem(index, offset)
                }
        }
        preSearchScrollAnchor = null
    }

    // Back closes an open search first (restoring the normal bar) before it
    // leaves the conversation — matching the chat-list search affordance.
    BackHandler {
        if (searchOpen) closeSearch() else onBack()
    }

    // Auto-focus the field on open; clear transient highlight on close.
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            searchFocusRequester.requestFocus()
        }
    }
    // Jump to the first match as soon as one exists for the current query, so
    // typing immediately scrolls to (and highlights) the newest match without
    // requiring the user to tap an arrow first.
    LaunchedEffect(searchMatchIds.firstOrNull(), searchOpen) {
        if (searchOpen && searchMatchIds.isNotEmpty()) {
            val firstId = searchMatchIds[searchActiveIndex.coerceAtLeast(0)]
            scrollToSearchMatch(firstId)
        }
    }

    // Extend history a few rows before the reader reaches the top, while a
    // keyed message is still the anchor. Compose's keyed prepend then holds
    // those messages at the same offset in the same measure pass — the new
    // page lands above the fold and the reader scrolls up into it with no jump
    // or blink (no post-hoc scroll, which is what caused the flip).
    LaunchedEffect(listState, controller) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { firstIndex ->
                if (
                    initialTimelineAnchored &&
                    controller.hasMoreBefore &&
                    !controller.isLoadingOlder &&
                    firstIndex <= olderHeaderCount + OLDER_PAGE_PREFETCH_ROWS
                ) {
                    controller.loadOlder()
                }
            }
    }
    // Capture the unread boundary at chat open. Stays fixed for the lifetime
    // of this composable (per chat.id) so the "N unread messages" divider
    // doesn't keep moving as the user scrolls and marks messages as read.
    val entryUnreadCount = remember(chat.id) { chat.unreadCount.toInt().coerceAtLeast(0) }
    var entryFirstUnreadMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(chat.id, controller.timeline.size) {
        if (entryFirstUnreadMessageId == null && entryUnreadCount > 0) {
            val firstUnreadIndex = controller.firstUnreadTimelineIndex(entryUnreadCount)
            if (firstUnreadIndex >= 0) {
                // Controller-side unread helpers already skip derived-state
                // kinds, so this is always a kind-9 chat present in the
                // filtered renderedTimeline.
                entryFirstUnreadMessageId =
                    controller.timeline[firstUnreadIndex]
                        .record.messageIdHex
                        .takeIf { it.isNotBlank() }
            }
        }
    }
    // Boolean-edge key avoids per-frame coroutine cancellation. The IME open
    // animation takes ~200ms; the LazyColumn measures a smaller viewport on
    // each tick, so a single snap at frame 0 leaves the bubble below the
    // final viewport. The repeat loop re-snaps every frame for ~24 frames,
    // chasing the shrinking viewport to its settled bottom. Gated on
    // nearBottom so reading history isn't interrupted. imeIsOpen is derived
    // once above (boolean edge of WindowInsets.ime) to avoid recomposing the
    // body on every keyboard-animation frame. Keyed on initialTimelineAnchored
    // too so that when you open a chat with the keyboard already up (no
    // imeIsOpen edge), the chase still fires the moment the first-open anchor
    // settles — otherwise the anchor lands against the pre-IME viewport and the
    // newest message sits a few rows above the bottom until the keyboard closes.
    LaunchedEffect(imeIsOpen, chat.id, initialTimelineAnchored) {
        if (!imeIsOpen || !initialTimelineAnchored || !nearBottom) return@LaunchedEffect
        repeat(24) {
            withFrameNanos { }
            val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
            runCatching { listState.scrollToItem(last) }
        }
    }
    // #589: app-switch resume handling. Two bugs surfaced when returning to a
    // chat after backgrounding the app:
    //
    //   Case A (keyboard was OPEN on leave): the bottom scroll anchor was
    //   computed against the pre-resume viewport before the keyboard inset
    //   re-applied, so the latest bubble landed clipped behind the keyboard.
    //   The existing imeIsOpen chase above does NOT re-fire here because
    //   imeIsOpen never transitions (it was already true), so we add a
    //   resume-triggered re-anchor that waits for the IME inset to settle and
    //   then re-snaps to the newest row — reusing the 24-frame chase idiom.
    //
    //   Case B (keyboard was CLOSED on leave): Android/Compose restores the
    //   BasicTextField focus and IME visibility on its own, popping a keyboard
    //   the user never asked for. We snapshot the composer focus on ON_PAUSE
    //   and, on ON_RESUME, gate restoration through the pure
    //   [shouldRestoreComposerFocusOnResume] predicate: restore focus only if
    //   it was held on pause (or an edit/reply session is active); otherwise
    //   actively clear focus and hide the keyboard so it does not pop.
    //
    // Keyed on chat.id so a conversation switch rebinds the observer; resolved
    // through the existing Context.lifecycleOwner() idiom (no new Local import).
    val resumeLifecycleOwner = context.lifecycleOwner()
    DisposableEffect(chat.id, resumeLifecycleOwner) {
        if (resumeLifecycleOwner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            // Snapshot the keyboard state we're leaving with so
                            // resume can faithfully restore it (Case B).
                            wasComposerFocusedOnPause = composerFocused
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            val restoreFocus =
                                shouldRestoreComposerFocusOnResume(
                                    wasComposerFocusedOnPause = wasComposerFocusedOnPause,
                                    hasActiveEditOrReplySession =
                                        controller.editingMessageId != null ||
                                            controller.replyingTo != null,
                                )
                            scope.launch {
                                if (restoreFocus) {
                                    // Case B (was focused): re-raise the keyboard
                                    // exactly as it was before the switch.
                                    runCatching { composerFocus.requestFocus() }
                                    keyboardController?.show()
                                } else if (shouldClearFocusOnResume(
                                        restoringComposerFocus = restoreFocus,
                                        searchOpen = searchOpen,
                                    )
                                ) {
                                    // Case B (was NOT focused): the system tried
                                    // to restore focus/IME — undo it so the
                                    // keyboard does not pop unrequested.
                                    //
                                    // Guarded on !searchOpen: in-chat search
                                    // (#292) legitimately owns focus + IME while
                                    // open, and clearFocus(force = true) is
                                    // screen-wide. Clearing here would drop the
                                    // search field's focus and hide its keyboard,
                                    // and LaunchedEffect(searchOpen) does not
                                    // re-fire on resume to restore it — so we
                                    // leave focus untouched while search is open.
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                // Case A: re-anchor to the newest row AFTER the
                                // IME inset has settled, so the latest bubble
                                // sits above (not behind) the keyboard. Gated on
                                // nearBottom so a reader scrolled up into history
                                // is never yanked down. Poll the ime bottom inset
                                // across frames until it stops changing, then
                                // run the same 24-frame chase the IME-open path
                                // uses so the snap tracks the settling viewport.
                                if (initialTimelineAnchored && nearBottom) {
                                    var lastInset = -1
                                    var stableFrames = 0
                                    // Cap the settle wait so a keyboard that
                                    // never animates (Case B, staying closed)
                                    // can't spin here forever. Bail out early
                                    // once the inset holds steady for two frames.
                                    var settleFrame = 0
                                    while (settleFrame < 24 && stableFrames < 2) {
                                        withFrameNanos { }
                                        val current = imeInsets.getBottom(density)
                                        if (current == lastInset) {
                                            stableFrames++
                                        } else {
                                            stableFrames = 0
                                            lastInset = current
                                        }
                                        settleFrame++
                                    }
                                    if (nearBottom) {
                                        repeat(24) {
                                            withFrameNanos { }
                                            val last =
                                                (listState.layoutInfo.totalItemsCount - 1)
                                                    .coerceAtLeast(0)
                                            runCatching { listState.scrollToItem(last) }
                                        }
                                    }
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            resumeLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { resumeLifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    // Re-apply a saved scroll position once the timeline materializes (#1107).
    // Seeding rememberLazyListState alone is not enough: the list can clamp
    // while the window is still empty, and the first-open anchor would snap to
    // bottom before the reader's position is restored.
    LaunchedEffect(chat.id, scrollRestore) {
        val restore = scrollRestore ?: return@LaunchedEffect
        restore.anchorMessageIdHex
            ?.takeIf { it.isNotBlank() }
            ?.let { controller.loadUntilMessageAvailable(it) }
        val targetIndex =
            snapshotFlow {
                val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
                if (rendered.isEmpty()) {
                    null
                } else {
                    val liveOlderHeaderCount =
                        if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
                    val liveBottomTimelineIndex = rendered.size + 1 + liveOlderHeaderCount
                    conversationScrollRestoreListIndex(
                        snapshot = restore,
                        renderedItemIds = rendered.map { it.id },
                        renderedMessageIds = rendered.map { it.record.messageIdHex },
                        olderHeaderCount = liveOlderHeaderCount,
                    ).coerceAtMost(liveBottomTimelineIndex)
                }
            }.filterNotNull()
                .first()
        listState.scrollToItem(targetIndex, restore.firstVisibleItemScrollOffset)
        initialTimelineAnchored = true
        val restoredRendered =
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        lastFollowedLatestId = restoredRendered.lastOrNull()?.id
    }
    LaunchedEffect(latestTimelineItemId) {
        if (renderedTimeline.isNotEmpty()) {
            if (!initialTimelineAnchored) {
                if (scrollRestore != null) {
                    return@LaunchedEffect
                }
                // First-time anchor on chat open. If there are unread
                // messages, land at the first unread one so the user can
                // read forward from there; otherwise drop them at the
                // newest message. Re-resolve the index in renderedTimeline so
                // scrollToItem refers to the lazy-list slot order, not the
                // unfiltered controller timeline.
                val unreadId =
                    controller
                        .firstUnreadTimelineIndex(chat.unreadCount.toInt())
                        .takeIf { it >= 0 }
                        ?.let {
                            controller.timeline[it]
                                .record.messageIdHex
                                .takeIf { id -> id.isNotBlank() }
                        }
                val renderedUnreadIndex =
                    unreadId?.let { id -> renderedTimeline.indexOfFirst { it.record.messageIdHex == id } } ?: -1
                val targetIndex =
                    if (renderedUnreadIndex >= 0) {
                        1 + olderHeaderCount + renderedUnreadIndex
                    } else {
                        bottomTimelineIndex
                    }
                listState.scrollToItem(targetIndex)
                initialTimelineAnchored = true
                lastFollowedLatestId = renderedTimeline.lastOrNull()?.id
            } else {
                val latestId = renderedTimeline.lastOrNull()?.id
                val previousId = lastFollowedLatestId
                // A genuine append: the last id changed and the row we last
                // followed is still present (an older-page trim drops it, so
                // that path is excluded). Id-based, so same-second tails count.
                val isAppend =
                    previousId != null &&
                        latestId != null &&
                        latestId != previousId &&
                        renderedTimeline.any { it.id == previousId }
                lastFollowedLatestId = latestId ?: previousId
                if (isAppend && nearBottom) {
                    listState.scrollToItem(bottomTimelineIndex)
                }
            }
        }
    }

    // Reacting to the last message grows its bubble height (a reaction chip) but
    // doesn't change any timeline id, so the append-follow above never sees it
    // and the grown bubble pushes its own bottom up off the composer. When the
    // user is already at the bottom, re-assert the bottom so the bubble + chip
    // stay flush. Keyed on the last rendered message's reaction tally; mutating
    // an earlier (non-last) message leaves this key unchanged, so a react while
    // reading history never hijacks the scroll position.
    LaunchedEffect(
        renderedTimeline
            .lastOrNull()
            ?.record
            ?.messageIdHex
            ?.let { controller.reactions[it] },
    ) {
        if (initialTimelineAnchored && nearBottom && renderedTimeline.isNotEmpty()) {
            listState.scrollToItem(bottomTimelineIndex)
        }
    }

    fun reanchorNewestAfterBottomInputChange() {
        if (!initialTimelineAnchored || !nearBottom) return
        scope.launch {
            repeat(24) {
                withFrameNanos { }
                val last = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                runCatching { listState.scrollToItem(last) }
            }
        }
    }

    // Scroll-to-message for a chat-list message-body search hit (issue #290).
    // Waits for the first-open anchor to settle, then pages the local timeline
    // back until the matched message is materialized and scrolls to it with a
    // brief highlight — the same affordance the reply-jump uses. Fires once
    // per (chat.id, focusMessageId); a missing message (e.g. it was deleted
    // between the search and the tap) just toasts and leaves the user at the
    // normal anchor. Local-only: loadUntilMessageAvailable paginates the
    // already-persisted store, never a relay fetch.
    LaunchedEffect(chat.id, focusMessageId) {
        val focus = focusMessageId ?: return@LaunchedEffect
        // Let the initial unread/newest anchor run first so our scroll isn't
        // immediately overwritten by it.
        snapshotFlow { initialTimelineAnchored }.filter { it }.first()
        val target = controller.loadScrollNavigationTarget(focus)
        if (target == null) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == target }
        if (timelineIndex < 0) {
            appState.present(R.string.toast_original_message_unavailable)
            return@LaunchedEffect
        }
        val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        // Center the match so prior + subsequent context is visible (#595).
        centerTimelineItemAt(target, 1 + olderMessagesHeaderCount + timelineIndex)
        if (highlightFocusMessage) {
            highlightedMessageId = target
            delay(1_500L)
            if (highlightedMessageId == target) {
                highlightedMessageId = null
            }
        }
    }

    // Scroll-driven read pointer advance. Watches the shared read anchor
    // (`readAnchorMessageId`) so the FFI only sees IDs that strictly advance
    // the pointer — scroll-up cannot regress the count. Settle-gated
    // (`!isScrollInProgress`) avoids per-frame FFI hops while scrolling.
    LaunchedEffect(listState, chat.id) {
        snapshotFlow {
            if (!initialTimelineAnchored || listState.isScrollInProgress) {
                null
            } else {
                readAnchorMessageId
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect { messageId ->
                if (messageId.isNotBlank()) {
                    controller.markReadUpTo(messageId)
                }
            }
    }
    // In-conversation profile sheet (issue #635). Driven by the same
    // appState.pendingProfileNpub the shell-level sheet uses, but passed the live
    // ConversationController so it can offer group-admin actions. The shell-level
    // sheet is suppressed while a conversation is active (selectedChat != null in
    // the app shell), so the sheet renders exactly once here. Placed before the
    // showDetails early-return so it also overlays the members-list row profile
    // tap inside GroupDetailsScreen, which keeps the group context too.
    appState.pendingProfileNpub?.let { profileNpub ->
        ProfileSheet(
            appState = appState,
            npub = profileNpub,
            onOpenGroup = { item, justCreatedChat ->
                onOpenProfileGroup(item, justCreatedChat)
            },
            onDismiss = { appState.clearPresentedProfile() },
            adminController = controller,
            securePolicy =
                if (appState.allowChatScreenshotsInChats) {
                    SecureFlagPolicy.SecureOff
                } else {
                    SecureFlagPolicy.SecureOn
                },
        )
    }

    if (showDetails) {
        GroupDetailsScreen(
            appState = appState,
            controller = controller,
            onBack = {
                showDetails = false
                openTransferOnDetails = false
                openAddMemberOnDetails = false
            },
            onLeft = onBack,
            onJumpToMessage = { messageId ->
                showDetails = false
                openTransferOnDetails = false
                scrollToSearchMatch(messageId)
            },
            autoOpenTransferAdmin = openTransferOnDetails,
            autoOpenAddMember = openAddMemberOnDetails,
            onAutoOpenAddMemberConsumed = { openAddMemberOnDetails = false },
            onOpenSearch = {
                showDetails = false
                searchOpen = true
            },
        )
        return
    }

    val composerGate =
        conversationComposerGate(
            pendingInvite = controller.group.pendingConfirmation,
            membersVerified = controller.membersVerified,
            isSelfMember = controller.isSelfMember,
            seededSelfMember = controller.seededSelfMember,
            seededMembershipKnown = controller.seededMembershipKnown,
            assumeMemberUntilVerified = openedFromNotification,
        )
    val mentionPicker =
        rememberConversationMentionPickerState(
            controller = controller,
            appState = appState,
            requestProfiles = composerGate == ComposerGate.COMPOSER,
        )

    // #1206: one composer text state shared by the main composer and the
    // long-message reader's composer, so in-progress text never drifts between
    // them. Created at screen scope so both the bottom-bar composer and the
    // per-message reader can receive the same instance.
    val composerTextState =
        rememberComposerTextState(
            draftKey = controller.group.groupIdHex,
            initialDraft = appState.draftFor(controller.group.groupIdHex).orEmpty(),
        )

    val openDetailsDescription = stringResource(R.string.details)
    Scaffold(
        // The transcript consumes IME insets; the composer bottom bar is the sole
        // owner of keyboard padding so the reply-preview chip and input row move
        // as one cluster (#895, #1109).
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            if (searchOpen) {
                ConversationSearchTopBar(
                    query = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        // Re-anchor the cursor to the new query's match set on
                        // the next derivation; clearing the pin makes it land
                        // on the first match again.
                        searchPinnedMatchId = null
                    },
                    onClear = {
                        searchQuery = ""
                        searchPinnedMatchId = null
                    },
                    onClose = { closeSearch() },
                    onSearchAction = { navigateToSearchMatch(forward = true) },
                    focusRequester = searchFocusRequester,
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier
                                    // Fill the title slot so the whole strip between
                                    // the back arrow and the overflow menu opens
                                    // details, not just the avatar/name. Those two
                                    // live in their own slots and keep their taps.
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { showDetails = true }
                                    .semantics { contentDescription = openDetailsDescription },
                        ) {
                            Avatar(
                                title = controller.title(groupTitleCopy),
                                // For a 1:1 DM the seed must match the peer-derived
                                // avatar so the initials fallback stays stable, just
                                // like the chat-list row (#837).
                                seed = controller.avatarAccount ?: controller.group.groupIdHex,
                                size = 36.dp,
                                pictureUrl = controller.avatarUrl,
                            )
                            Column {
                                Text(
                                    controller.title(groupTitleCopy),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // Subtitle line: members count (groups) and the
                                // disappearing-timer indicator inline on ONE row,
                                // not stacked. The one-time tooltip anchors to the
                                // whole line when the timer is on.
                                val membersSubtitle =
                                    if (
                                        shouldShowConversationMembersSubtitle(
                                            membersLoaded = controller.membersLoaded,
                                            openedAsDmHint = openedAsDmHint,
                                            groupName = controller.group.name,
                                            memberCount = controller.members.size,
                                        )
                                    ) {
                                        controller.subtitle(
                                            justYou = stringResource(R.string.just_you),
                                            oneMember = stringResource(R.string.one_member),
                                            membersFormat = stringResource(R.string.members_count),
                                        )
                                    } else {
                                        null
                                    }
                                val disappearingSecs = controller.group.disappearingMessageSecs.toLong()
                                val showTimer = disappearingSecs > 0L
                                if (membersSubtitle != null || showTimer) {
                                    val labelStyle = MaterialTheme.typography.labelSmall
                                    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    val subtitleRow: @Composable () -> Unit = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            if (membersSubtitle != null) {
                                                Text(membersSubtitle, style = labelStyle, color = labelColor)
                                            }
                                            if (showTimer) {
                                                if (membersSubtitle != null) {
                                                    Text("·", style = labelStyle, color = labelColor)
                                                }
                                                Icon(
                                                    Icons.Default.Schedule,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(13.dp),
                                                    tint = labelColor,
                                                )
                                                Text(disappearingMessagesLabel(disappearingSecs), style = labelStyle, color = labelColor)
                                            }
                                        }
                                    }
                                    if (showTimer) {
                                        val timerTooltipState = rememberTooltipState(isPersistent = true)
                                        val timerTooltipText = stringResource(R.string.disappearing_tooltip_text)
                                        // Snapshot the one-time decision so marking the flag
                                        // (which we do first, to persist before a quick exit
                                        // can re-arm it) doesn't recompose this branch away and
                                        // cancel the still-suspended show().
                                        val showTooltipOnce =
                                            remember(controller.group.groupIdHex) {
                                                !appState.disappearingTooltipShown
                                            }
                                        if (showTooltipOnce) {
                                            LaunchedEffect(controller.group.groupIdHex) {
                                                appState.markDisappearingTooltipShown()
                                                timerTooltipState.show()
                                            }
                                        }
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberRichTooltipPositionProvider(),
                                            tooltip = { RichTooltip { Text(timerTooltipText) } },
                                            state = timerTooltipState,
                                            content = subtitleRow,
                                        )
                                    } else {
                                        subtitleRow()
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_actions))
                        }
                        KeyboardPreservingDropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            shape = RoundedCornerShape(20.dp),
                            // Inset the panel from the right screen edge instead
                            // of letting it sit flush against it.
                            offset = DpOffset(x = (-8).dp, y = 0.dp),
                            modifier = Modifier.widthIn(min = 232.dp),
                        ) {
                            // Iconless, roomier rows: each entry reads as a
                            // full-width tappable line of body-large text rather
                            // than a compact icon+label cell.
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.conversation_search_open),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                onClick = {
                                    menuOpen = false
                                    // Snapshot the current scroll position before the
                                    // search auto-scroll effect can move the list, so
                                    // closing search can restore it (#292).
                                    preSearchScrollAnchor =
                                        listState.firstVisibleItemIndex to
                                        listState.firstVisibleItemScrollOffset
                                    searchOpen = true
                                },
                            )
                            if (!controller.group.pendingConfirmation) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(if (controller.group.archived) R.string.unarchive else R.string.archive),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                    },
                                    contentPadding = conversationMenuItemPadding,
                                    enabled = !controller.mutationInFlight,
                                    onClick = {
                                        menuOpen = false
                                        appState.launchMutation { controller.setArchived(!controller.group.archived) }
                                    },
                                )
                                if (controller.isSelfMember) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(R.string.leave),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        },
                                        contentPadding = conversationMenuItemPadding,
                                        // Gate on membersLoaded: the sole-admin routing
                                        // below reads the roster, and an empty (unloaded)
                                        // roster would misroute to a plain leave.
                                        enabled = !controller.mutationInFlight && controller.membersLoaded,
                                        onClick = {
                                            menuOpen = false
                                            // A sole admin with other members can't
                                            // leave until they transfer admin; route
                                            // them to the transfer flow instead of
                                            // the old leaveGroup() toast dead end.
                                            appState.launchMutation {
                                                when (val leaveAction = controller.leaveAction()) {
                                                    LeaveAction.SoleAdminMustTransfer -> showTransferAdminFirst = true
                                                    LeaveAction.SoleMemberDeletesGroup,
                                                    LeaveAction.Standard,
                                                    -> pendingTopBarLeaveAction = leaveAction
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            // Measure the real bottom chrome (composer with reply/edit banners and
            // grown multi-line input, search nav bar, invite bar, or notice) and lift
            // the global toast host by that amount, instead of assuming the resting
            // single-line composer height (#122, #796). Nav/IME insets are subtracted
            // because WhiteNoiseSnackbarHost pads for those itself.
            val chromeInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
            Box(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        val chromeBottom = chromeInsets.getBottom(density)
                        snackbarBottomInset.value =
                            with(density) { (size.height - chromeBottom).coerceAtLeast(0).toDp() }
                    },
            ) {
                when {
                    // While search is open the composer steps aside for the match
                    // navigation bar pinned above the keyboard.
                    searchOpen ->
                        ConversationSearchNavBar(
                            matchCount = searchMatchIds.size,
                            activeIndex = searchActiveIndex,
                            hasQuery = searchQuery.isNotBlank(),
                            onPrev = { navigateToSearchMatch(forward = false) },
                            onNext = { navigateToSearchMatch(forward = true) },
                        )
                    controller.error != null -> Unit
                    // Membership-display gate (issues #545 and #623). During the
                    // brief window before refreshMembers() confirms the roster we
                    // must not flash a state we don't actually know: a left group
                    // flashing the active composer (#545) OR a member's group —
                    // especially an admin re-entering their own group — flashing the
                    // "no longer a member" notice (#623, the inverse). The gate
                    // paints the composer only for a (believed) member, the notice
                    // only for a known not-member, and NOTHING while membership is
                    // genuinely unknown (cold open with no seeding snapshot), where
                    // it upgrades on the confirmed result. The controller's
                    // `canSendMessages` guard still keeps any actual mutation safe
                    // until membership is verified.
                    else ->
                        when (composerGate) {
                            // Reserve the composer's resting height while membership
                            // is still unknown (e.g. right after an account switch),
                            // so the bottom inset is stable and the composer doesn't
                            // pop in over the last message once it resolves. Matches
                            // ComposerBar's resting height (the 44.dp pill plus its
                            // Column's 10.dp vertical padding top and bottom). Kept
                            // transparent so no surface colour flashes before the
                            // composer or notice resolves.
                            ComposerGate.PENDING ->
                                Spacer(
                                    Modifier
                                        .fillMaxWidth()
                                        .navigationBarsPadding()
                                        .imePadding()
                                        .height(64.dp),
                                )
                            ComposerGate.NOTICE -> RemovedMemberComposerNotice()
                            ComposerGate.INVITE ->
                                InvitePreviewActionBar(
                                    mutationInFlight = controller.mutationInFlight,
                                    onJoin = { appState.launchMutation { controller.acceptInvite() } },
                                    onDecline = {
                                        appState.launchMutation {
                                            if (controller.declineInvite()) onBack()
                                        }
                                    },
                                )
                            ComposerGate.COMPOSER -> {
                                val groupIdHex = controller.group.groupIdHex
                                val editingRecord =
                                    controller.editingMessageId?.let { id ->
                                        controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                                    }
                                ComposerBar(
                                    replyingTo = controller.replyingTo,
                                    messageTextCopy = messageTextCopy,
                                    onCancelReply = { controller.replyingTo = null },
                                    onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                                    initialDraft = appState.draftFor(groupIdHex).orEmpty(),
                                    onDraftChange = { appState.setDraft(groupIdHex, it) },
                                    draftKey = groupIdHex,
                                    textState = composerTextState,
                                    editingMessageId = controller.editingMessageId,
                                    editingInitialText = editingRecord?.let { controller.displayedText(it) },
                                    onCancelEdit = { controller.editingMessageId = null },
                                    onAfterSend = {
                                        // Always pull the user down to see their just-sent
                                        // bubble, even if they were reading older history.
                                        // Matches standard chat-app behavior.
                                        scope.launch {
                                            val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                            listState.animateScrollToItem(lastIndex)
                                        }
                                    },
                                    onPickFromGallery = {
                                        imagePickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    },
                                    onCaptureFromCamera = {
                                        val granted =
                                            ContextCompat.checkSelfPermission(
                                                context,
                                                Manifest.permission.CAMERA,
                                            ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            launchCameraCapture()
                                        } else {
                                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    onPickDocument = {
                                        // `*/*` lets the system file picker surface every
                                        // installed provider (Drive, Downloads, Files…)
                                        // without restricting by MIME. Bytes upload as-is.
                                        documentPickerLauncher.launch(arrayOf("*/*"))
                                    },
                                    onPasteImageUris = { uris ->
                                        // Receive-content URI grants are scoped to the
                                        // paste callback. Copy the bytes into app-owned
                                        // cache before returning, then stage those local
                                        // FileProvider URIs through the same shelf as the
                                        // photo picker/camera path.
                                        val openSlots = (MEDIA_PICKER_MAX_ITEMS - pendingMediaUris.size).coerceAtLeast(0)
                                        val pasteCandidates = uris.distinct().take(openSlots)
                                        val localUris =
                                            pasteCandidates.mapNotNull { uri ->
                                                materializeReceiveContentImageUri(context, uri)
                                            }
                                        if (localUris.size < pasteCandidates.size) {
                                            appState.present(R.string.toast_couldnt_decode_image, copyable = true)
                                        }
                                        if (localUris.isEmpty()) return@ComposerBar
                                        pendingMediaUris =
                                            (pendingMediaUris + localUris)
                                                .distinct()
                                                .take(MEDIA_PICKER_MAX_ITEMS)
                                    },
                                    voiceRecordingController = voiceRecordingController,
                                    appState = appState,
                                    mentionCandidates = mentionPicker.candidates,
                                    mentionPickerEnabled = mentionPicker.enabled,
                                    autoFocusOnEnter = justCreated,
                                    enterKeyBehavior = appState.enterKeyBehavior,
                                    // #589: hoisted focus plumbing — the requester lets the
                                    // resume observer restore focus, and the callback keeps
                                    // `composerFocused` tracking the live keyboard state.
                                    composerFocus = composerFocus,
                                    onComposerFocusChanged = { composerFocused = it },
                                    onBottomInputChanged = ::reanchorNewestAfterBottomInputChange,
                                )
                            }
                        }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // The composer bottomBar owns IME padding; consume here so the
                // transcript does not count the keyboard a second time (#895).
                .consumeWindowInsets(WindowInsets.ime),
        ) {
            when {
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_conversation), controller.error.orEmpty())
                controller.group.pendingConfirmation && renderedTimeline.isEmpty() && !controller.isLoading && initialTimelineLoadStarted ->
                    InvitePreviewPlaceholder(
                        inviterName = controller.inviteAccount?.let { appState.chatMemberTitle(it) },
                    )
                controller.group.pendingConfirmation && renderedTimeline.isEmpty() -> LoadingScreen()
                controller.timeline.isEmpty() && !controller.isLoading && initialTimelineLoadStarted -> {
                    if (
                        canInviteFromEmptyGroup(
                            isSelfMember = controller.isSelfMember,
                            isSelfAdmin = controller.isSelfAdmin,
                            membersLoaded = controller.membersLoaded,
                            memberCount = controller.members.size,
                        )
                    ) {
                        EmptyGroupConversation(
                            onAddMembers = {
                                openAddMemberOnDetails = true
                                showDetails = true
                            },
                        )
                    } else {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.no_messages_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                else ->
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                                    .alpha(if (initialTimelineAnchored) 1f else 0f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 8.dp),
                        ) {
                            item(key = "top-spacer") { Spacer(Modifier.height(4.dp)) }
                            if (controller.hasMoreBefore || controller.isLoadingOlder) {
                                item(key = "older-messages-loading") {
                                    Box(
                                        Modifier.fillMaxWidth().height(40.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (controller.isLoadingOlder) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        } else {
                                            IconButton(onClick = { scope.launch { controller.loadOlder() } }) {
                                                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                                            }
                                        }
                                    }
                                }
                            }
                            itemsIndexed(
                                renderedTimeline,
                                key = { _, item -> item.id },
                                // Pool layouts by category so Compose can reuse
                                // the heavier MessageBubble slot across scroll
                                // without recreating layout nodes for the
                                // simpler centered group-system rows.
                                contentType = { _, item ->
                                    if (MessageProjector.isGroupSystem(item.record)) "groupSystem" else "message"
                                },
                            ) { index, item ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .onSizeChanged { size ->
                                            if (size.height > 0 && timelineItemHeightsPx[item.record.messageIdHex] != size.height) {
                                                timelineItemHeightsPx[item.record.messageIdHex] = size.height
                                            }
                                        },
                                ) {
                                    // Rendered inside the slot, not as its own item, so
                                    // the anchor index math stays intact.
                                    val older = renderedTimeline.getOrNull(index - 1)
                                    val daySeparatorLabel =
                                        remember(older?.record?.recordedAt, item.record.recordedAt, transcriptLocale) {
                                            if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                                                messageDayLabel(item.record.recordedAt, transcriptLocale)
                                            } else {
                                                null
                                            }
                                        }
                                    if (daySeparatorLabel != null) {
                                        DaySeparator(daySeparatorLabel)
                                    }
                                    if (entryUnreadCount > 0 && item.record.messageIdHex == entryFirstUnreadMessageId) {
                                        UnreadMessagesDivider(count = entryUnreadCount)
                                    }
                                    // Synthetic `dbg:stream:` rows must never fall
                                    // through to normal message rendering — not even in
                                    // the window between the toggle flipping off and the
                                    // republish that drops them. Draw the debug row only
                                    // when enabled; otherwise suppress the row entirely.
                                    if (item.id.startsWith(ConversationController.STREAM_DEBUG_ID_PREFIX)) {
                                        if (appState.streamingDebugEnabled) {
                                            StreamDebugEventRow(record = item.record)
                                        }
                                        return@Column
                                    }
                                    // One decision point for which row a record renders
                                    // as. Group-system (kind 1210) rows are derived state
                                    // facts, not chat: they render the centered one-line
                                    // summary, never a raw-JSON bubble — and that summary
                                    // stays the default even in developer mode, with the
                                    // MLS dump reachable per-row behind a tap (#857). The
                                    // debug-row path covers the other non-user-visible
                                    // signaling kinds only when streaming debug is on, so
                                    // the timeline is byte-identical to today when off.
                                    when (timelineRowKind(item.record, appState.streamingDebugEnabled)) {
                                        TimelineRowKind.GroupSystem -> {
                                            GroupSystemRow(
                                                record = item.record,
                                                appState = appState,
                                                groupSystem = item.projected?.groupSystem,
                                                onDeleteForMe = {
                                                    controller.hideMessageForMe(item.record.messageIdHex)
                                                },
                                            )
                                            return@Column
                                        }
                                        TimelineRowKind.DebugRow -> {
                                            MessageDebugRow(
                                                style = MessageDebugClassifier.debugStyle(item.record),
                                                record = item.record,
                                            )
                                            return@Column
                                        }
                                        TimelineRowKind.Bubble -> Unit
                                    }
                                    MessageBubble(
                                        item = item,
                                        controller = controller,
                                        appState = appState,
                                        composerTextState = composerTextState,
                                        highlighted = item.record.messageIdHex == highlightedMessageId,
                                        quickReactionEmojis = quickReactionEmojis,
                                        isActionMenuOpen = openActionMenuId == item.record.messageIdHex,
                                        onActionMenuOpenChange = { open ->
                                            openActionMenuId = if (open) item.record.messageIdHex else null
                                        },
                                        // Lambdas, not method references: the Compose
                                        // compiler memoizes lambdas but allocates a fresh
                                        // function reference per recomposition, which made
                                        // every visible bubble recompose on any timeline
                                        // change. See #110.
                                        onReactionEmojiPicked = { recordReactionEmoji(it) },
                                        onQuickReactionsSave = { saveQuickReactionEmojis(it) },
                                        onQuickReactionsReset = { resetQuickReactionEmojis() },
                                        onReplyPreviewClick = { navigateToReplyTarget(it) },
                                        composerGate = composerGate,
                                        inviteMutationInFlight = controller.mutationInFlight,
                                        onJoinInvite = { appState.launchMutation { controller.acceptInvite() } },
                                        onDeclineInvite = {
                                            appState.launchMutation {
                                                if (controller.declineInvite()) onBack()
                                            }
                                        },
                                        mentionCandidates = mentionPicker.candidates,
                                        mentionPickerEnabled = mentionPicker.enabled,
                                        collapseLongMessages = collapseLongMessages,
                                        readOnly = controller.group.pendingConfirmation,
                                    )
                                }
                            }
                            // Kept minimal (matches the top-spacer) so the last
                            // bubble sits a tight breathing-room above the
                            // composer rather than orphaned in mid-screen; the
                            // 8dp item spacing + 8dp content padding already
                            // supply the gap. Retained (not removed) so the
                            // bottom-anchor index math stays stable.
                            item(key = "bottom-spacer") { Spacer(Modifier.height(4.dp)) }
                        }
                        // Day of the topmost visible message, shown only while
                        // scrolling — the inline separators carry it at rest.
                        // Confined to its own child so the scroll-backed reads
                        // (label + isScrollInProgress) recompose only the ribbon,
                        // not this LazyColumn-hosting Box scope (#375).
                        if (initialTimelineAnchored) {
                            StickyDayRibbon(
                                listState = listState,
                                labelState = stickyDayLabelState,
                            )
                        }
                        if (!initialTimelineAnchored) {
                            LoadingScreen()
                        }
                        if (initialTimelineAnchored) {
                            Column(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp),
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Jump-to-mention chip: tap visits the oldest unread
                                // mention and marks it read, so the count steps down.
                                val mentionCount = unreadMentionMessageIds.size
                                if (mentionCount > 0) {
                                    val jumpToMentionLabel = stringResource(R.string.conversation_jump_to_mention)
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                        shadowElevation = 2.dp,
                                        modifier =
                                            Modifier
                                                .height(34.dp)
                                                .semantics { contentDescription = jumpToMentionLabel }
                                                .clickable { jumpToNextUnreadMention() },
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.padding(horizontal = 12.dp),
                                        ) {
                                            Text("@", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                if (mentionCount > 99) "99+" else mentionCount.toString(),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                                if (!nearBottom) {
                                    val jumpToNewestLabel = stringResource(R.string.jump_to_newest)
                                    Box(
                                        modifier =
                                            Modifier
                                                .size(42.dp)
                                                .semantics { contentDescription = jumpToNewestLabel }
                                                .clickable {
                                                    scope.launch {
                                                        val targetIndex =
                                                            conversationJumpToNewestTargetListIndex(
                                                                unreadIncomingCount = unreadIncomingCount,
                                                                readAnchorMessageId = readAnchorMessageId,
                                                                renderedMessageIds = renderedTimeline.map { it.record.messageIdHex },
                                                                visibleListIndices =
                                                                    listState.layoutInfo.visibleItemsInfo
                                                                        .map { it.index }
                                                                        .toSet(),
                                                                olderHeaderCount = olderHeaderCount,
                                                                bottomTimelineIndex = bottomTimelineIndex,
                                                            )
                                                        listState.animateScrollToItem(targetIndex)
                                                    }
                                                },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                            shadowElevation = 2.dp,
                                            modifier = Modifier.size(34.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.ArrowDownward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                        if (unreadIncomingCount > 0) {
                                            Badge(modifier = Modifier.align(Alignment.TopEnd)) {
                                                Text(
                                                    if (unreadIncomingCount > 99) "99+" else unreadIncomingCount.toString(),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    pendingTopBarLeaveAction?.let { leaveAction ->
        val soleMember = leaveAction == LeaveAction.SoleMemberDeletesGroup
        val topBarGroupName = controller.title(groupTitleCopy)
        ConfirmDialog(
            title =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_title, topBarGroupName)
                } else {
                    stringResource(R.string.confirm_leave_title)
                },
            message =
                if (soleMember) {
                    stringResource(R.string.confirm_leave_sole_member_message)
                } else {
                    stringResource(R.string.confirm_leave_message)
                },
            confirmLabel = stringResource(R.string.leave),
            onConfirm = {
                pendingTopBarLeaveAction = null
                appState.launchMutation {
                    if (controller.leaveGroup()) onBack()
                }
            },
            onDismiss = { pendingTopBarLeaveAction = null },
            destructive = soleMember,
        )
    }

    if (showTransferAdminFirst) {
        ConfirmDialog(
            title = stringResource(R.string.sole_admin_leave_blocked_title),
            message = stringResource(R.string.sole_admin_leave_blocked_message),
            confirmLabel = stringResource(R.string.transfer_admin),
            onConfirm = {
                showTransferAdminFirst = false
                openTransferOnDetails = true
                showDetails = true
            },
            onDismiss = { showTransferAdminFirst = false },
        )
    }

    if (pendingMediaUris.isNotEmpty() || pendingDocumentUris.isNotEmpty()) {
        val imageUris = pendingMediaUris
        val documentUris = pendingDocumentUris
        MediaPreviewSheet(
            uris = imageUris,
            documentUris = documentUris,
            onDismiss = {
                pendingMediaUris = emptyList()
                pendingDocumentUris = emptyList()
            },
            onSend = { caption ->
                pendingMediaUris = emptyList()
                pendingDocumentUris = emptyList()
                sendStagedAttachments(
                    imageUris,
                    documentUris,
                    caption,
                    onAfterSend = {
                        // Pull the user down to the just-seeded bubble.
                        // `bottomTimelineIndex` reads from
                        // [renderedTimeline.size] (the snapshot-backed
                        // controller list) instead of
                        // [LazyListState.layoutInfo.totalItemsCount], which
                        // is stale until the next recompose — for a
                        // multi-file send that staleness leaves the user
                        // one-or-more rows above the new bubble.
                        scope.launch { listState.animateScrollToItem(bottomTimelineIndex) }
                    },
                )
            },
            onRemoveAt = { index ->
                pendingMediaUris =
                    pendingMediaUris.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
            },
            onRemoveDocumentAt = { index ->
                pendingDocumentUris =
                    pendingDocumentUris.toMutableList().apply {
                        if (index in indices) removeAt(index)
                    }
            },
            onAddPhotos = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onAddDocuments = { documentPickerLauncher.launch(arrayOf("*/*")) },
        )
    }
}
