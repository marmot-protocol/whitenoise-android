package dev.ipf.whitenoise.android.ui.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.imeAnimationTarget
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.tts.TTS_AUTO_READ_MAX_MESSAGES
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.AgentOperationProjector
import dev.ipf.whitenoise.android.core.ConversationSearchMatch
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.LeaveAction
import dev.ipf.whitenoise.android.core.MessageDebugClassifier
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageSearch
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.core.ReplyNavigation
import dev.ipf.whitenoise.android.core.TimelineRowKind
import dev.ipf.whitenoise.android.core.timelineRowKind
import dev.ipf.whitenoise.android.core.usesPersistedFailurePresentation
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.media.Thumbhash
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ChatCreateOpenConversationTimingEvent
import dev.ipf.whitenoise.android.state.ChatCreateOpenConversationTimingState
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.advanceConversationReadAnchor
import dev.ipf.whitenoise.android.state.chatCreateOpenConversationTimingStage
import dev.ipf.whitenoise.android.state.countUnreadIncoming
import dev.ipf.whitenoise.android.state.logUnreadCountDivergence
import dev.ipf.whitenoise.android.state.parseMarkdownOrEmpty
import dev.ipf.whitenoise.android.state.reduceChatCreateOpenConversationTiming
import dev.ipf.whitenoise.android.state.shouldFocusComposerOnDraftRestore
import dev.ipf.whitenoise.android.state.unreadCountDivergenceReport
import dev.ipf.whitenoise.android.state.unreadReceivedMentionIds
import dev.ipf.whitenoise.android.ui.MentionDetectionCache
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchNavBar
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchTopBar
import dev.ipf.whitenoise.android.ui.chats.newchat.ContactPickerScreen
import dev.ipf.whitenoise.android.ui.chats.newchat.canInviteFromEmptyGroup
import dev.ipf.whitenoise.android.ui.common.ConfirmDialog
import dev.ipf.whitenoise.android.ui.common.DragSelectionVisibleItem
import dev.ipf.whitenoise.android.ui.common.ErrorContent
import dev.ipf.whitenoise.android.ui.common.GroupAvatar
import dev.ipf.whitenoise.android.ui.common.LoadingScreen
import dev.ipf.whitenoise.android.ui.common.LocalSnackbarBottomInset
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.anchoredDragSelection
import dev.ipf.whitenoise.android.ui.common.dragSelectionAutoScrollDelta
import dev.ipf.whitenoise.android.ui.common.dragSelectionEndpoint
import dev.ipf.whitenoise.android.ui.common.lifecycleOwner
import dev.ipf.whitenoise.android.ui.common.rememberGroupTitleCopy
import dev.ipf.whitenoise.android.ui.common.rememberMessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.DisbandedGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.FrozenGroupComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.RemovedMemberComposerNotice
import dev.ipf.whitenoise.android.ui.conversation.composer.conversationComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerAttachmentSheetState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerShareRevision
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.rememberConversationMentionPickerState
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldClearFocusOnResume
import dev.ipf.whitenoise.android.ui.conversation.composer.shouldRestoreComposerFocusOnResume
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.media.NullableFileSaver
import dev.ipf.whitenoise.android.ui.conversation.media.NullableUriSaver
import dev.ipf.whitenoise.android.ui.conversation.media.UriListSaver
import dev.ipf.whitenoise.android.ui.conversation.media.clearMediaTempFiles
import dev.ipf.whitenoise.android.ui.conversation.media.createImageCaptureFile
import dev.ipf.whitenoise.android.ui.conversation.media.documentPickTreatAsImage
import dev.ipf.whitenoise.android.ui.conversation.media.fileProviderUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeReceiveContentImageUri
import dev.ipf.whitenoise.android.ui.conversation.media.materializeVoiceAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.queryContentSize
import dev.ipf.whitenoise.android.ui.conversation.media.queryDisplayName
import dev.ipf.whitenoise.android.ui.conversation.media.safeGetType
import dev.ipf.whitenoise.android.ui.conversation.media.voicePlaybackKey
import dev.ipf.whitenoise.android.ui.conversation.messages.BatchMessageDeleteDialog
import dev.ipf.whitenoise.android.ui.conversation.messages.ForwardMessageSheet
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubble
import dev.ipf.whitenoise.android.ui.conversation.messages.dismissTextSelectionOnOutsideTap
import dev.ipf.whitenoise.android.ui.conversation.share.ContactPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.share.LocationPickerScreen
import dev.ipf.whitenoise.android.ui.conversation.share.PickContactPhoneRow
import dev.ipf.whitenoise.android.ui.conversation.share.SharedContact
import dev.ipf.whitenoise.android.ui.conversation.share.VCARD_MIME_TYPE
import dev.ipf.whitenoise.android.ui.conversation.share.buildVCard
import dev.ipf.whitenoise.android.ui.conversation.share.contactVCardFileName
import dev.ipf.whitenoise.android.ui.conversation.share.formatContactShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatLocationShareText
import dev.ipf.whitenoise.android.ui.conversation.share.formatUserShareText
import dev.ipf.whitenoise.android.ui.conversation.share.locationGrantAllowsSharing
import dev.ipf.whitenoise.android.ui.conversation.share.readSharedContact
import dev.ipf.whitenoise.android.ui.design.KeyboardPreservingDropdownMenu
import dev.ipf.whitenoise.android.ui.design.conversationMenuItemPadding
import dev.ipf.whitenoise.android.ui.documentMentionsAccount
import dev.ipf.whitenoise.android.ui.group.GroupDetailsScreen
import dev.ipf.whitenoise.android.ui.group.disappearingMessagesLabel
import dev.ipf.whitenoise.android.ui.rememberRecentEmojiRecentsOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private data class ConversationSearchScrollAnchor(
    val bookmark: ConversationScrollBookmark,
    val match: ConversationSearchMatch?,
)

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

// Foreground catch-up normally materializes almost immediately. Keep the
// background-arrival listener bounded so a later, genuinely foreground
// message does not unexpectedly start an otherwise idle auto-reader.
private const val TTS_AUTO_READ_RESUME_SYNC_TIMEOUT_MS = 10_000L

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
    controller: ConversationController,
    onBack: () -> Unit,
    // When opened from a chat-list message-body search hit (issue #290), the
    // matched message id to scroll to and briefly highlight once the timeline
    // has paged it in. Null for every normal open path.
    focusMessageId: String? = null,
    // Non-zero when opened by tapping a message notification. Each tap gets a
    // fresh id so an already-mounted conversation re-runs its first-unread
    // anchor; it also implies current membership while verification catches up.
    notificationOpenRequestId: Long = 0L,
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
    // Scroll position captured when the user last left this chat while reading
    // history (issue #1107). Null when none was saved or they left near-bottom.
    restoredScrollSnapshot: ConversationScrollSnapshot? = null,
    onSaveScrollSnapshot: (ConversationScrollSnapshot?) -> Unit = {},
    onOpenConversation: (ChatListItem, Boolean) -> Unit = { _, _ -> },
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
    // Capture the unread boundary at chat open. Stays fixed for this controller
    // so the divider doesn't move as messages are marked read, but resets when
    // an account/runtime switch creates a new controller for the same group.
    val projectedEntryUnreadCount = chat.unreadCount.toInt().coerceAtLeast(0)
    val entryUnreadSnapshot =
        rememberConversationEntryUnreadSnapshot(
            controllerIdentity = controller,
            projectionUnread = projectedEntryUnreadCount,
            timeline = controller.timeline,
            readAnchorMessageId = chat.projection?.lastReadMessageIdHex,
        )
    val entryUnreadCount = entryUnreadSnapshot.count
    val entryFirstUnreadMessageId = entryUnreadSnapshot.firstUnreadMessageId
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
    // Re-open after back-to-list should land where the reader left off only
    // when fully read. Unread, search-hit, just-created, and notification opens
    // let their dedicated unread/newest/focus anchor own the position.
    val scrollRestore =
        restoredScrollSnapshot?.takeIf {
            shouldRestoreConversationScrollSnapshot(
                focusMessageId = focusMessageId,
                justCreated = justCreated,
                notificationOpenRequestId = notificationOpenRequestId,
                entryUnreadCount = entryUnreadCount,
            )
        }
    val positionalScrollRestore =
        scrollRestore?.takeIf {
            it.anchorItemId.isNullOrBlank() && it.anchorMessageIdHex.isNullOrBlank()
        }
    val listState =
        key(controller) {
            rememberLazyListState(
                initialFirstVisibleItemIndex = positionalScrollRestore?.firstVisibleItemIndex ?: 0,
                initialFirstVisibleItemScrollOffset = positionalScrollRestore?.firstVisibleItemScrollOffset ?: 0,
            )
        }
    val scrollCoordinator =
        remember(controller, listState) {
            ConversationScrollCoordinator(
                writer = LazyListConversationScrollWriter(listState),
                initialMode =
                    if (scrollRestore != null) {
                        ConversationScrollMode.ReadingHistory(
                            anchorMessageId = scrollRestore.anchorMessageIdHex,
                            pixelOffset = scrollRestore.firstVisibleItemScrollOffset,
                        )
                    } else {
                        ConversationScrollMode.FollowingTail
                    },
            )
        }
    val bottomChromeHeightObserver =
        remember(chat.id) {
            ConversationBottomChromeHeightObserver()
        }
    // Single conversation-level owner of which message's action menu is open, so
    // only one popover can be open at a time. With the keyboard up the menu is
    // non-focusable (#284), so long-pressing several bubbles would otherwise
    // stack several popovers; deriving each bubble's open state from this one id
    // makes opening one close any other.
    var openActionMenuId by remember(chat.id) { mutableStateOf<String?>(null) }
    DismissMessageActionMenuOnScroll(listState) {
        openActionMenuId = null
    }
    // Partial text selection is independent from batch message selection. Only
    // one bubble can own the native SelectionContainer at a time.
    var textSelectionMessageId by remember(chat.id) { mutableStateOf<String?>(null) }
    var textSelectionBubbleBounds by remember(chat.id) { mutableStateOf<Rect?>(null) }

    fun clearTextSelection() {
        textSelectionMessageId = null
        textSelectionBubbleBounds = null
    }
    // Selection is conversation-owned because the contextual top bar, back
    // handling, forwarding sheet, and rows all consume the same stable ids.
    // Each value snapshots the record/action projection so cap-trimmed rows stay
    // selected while the user scrolls deeper into history. This remains transient
    // composition state deliberately: serializing decrypted message snapshots into
    // Android saved state would extend their lifetime and privacy footprint.
    val selectedMessages =
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) {
            mutableStateMapOf<String, BatchMessageSelection>()
        }
    var batchForwardSheetOpen by
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var showBatchDeleteConfirm by
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var batchDeleteInFlight by
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(false) }
    var initialTimelineAnchored by
        remember(controller, notificationOpenRequestId) { mutableStateOf(false) }

    suspend fun ttsEntry(record: AppMessageRecordFfi): TtsSpeakableEntry? =
        projectTtsSpeakableEntry(
            message = record,
            editedText = controller.editsByTarget[record.messageIdHex]?.latestText,
            senderDisplayName = appState.displayName(record.sender),
            parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
            mentionDisplayName = appState::mentionDisplayName,
            isGroupMember =
                if (controller.membersLoaded) {
                    { bech32 -> appState.isRosterMember(bech32, controller.members) }
                } else {
                    null
                },
        )

    // The current unread backlog as speakable entries, oldest-first and bounded
    // so an inflated unread count can't narrate ancient history. Anchored on
    // the unread cursor — the last-read position — so it never skips
    // loaded-but-unspoken messages; shared by the open-time and
    // return-from-background paths.
    suspend fun autoReadBacklogEntries(): List<TtsSpeakableEntry> {
        val ready =
            appState.ttsHasUsableEngine &&
                appState.isConversationAutoRead(controller.group.groupIdHex) &&
                entryUnreadCount > 0
        val start = if (ready) controller.firstUnreadTimelineIndex(entryUnreadCount) else -1
        return if (start < 0) {
            emptyList()
        } else {
            controller.timeline
                .drop(start)
                // Bound BEFORE mapping so the cost scales with the speak cap,
                // not the unread count; 2x slack absorbs filtered-out entries.
                .take(TTS_AUTO_READ_MAX_MESSAGES * 2)
                .mapNotNull { message -> ttsEntry(message.record) }
        }
    }
    // Auto-read (#1483): once the timeline is anchored, read the unread backlog.
    LaunchedEffect(controller, chat.id, initialTimelineAnchored) {
        if (!initialTimelineAnchored) return@LaunchedEffect
        val entries = autoReadBacklogEntries()
        if (entries.isNotEmpty()) {
            appState.speakAloudAutoRead(
                controller.group.groupIdHex,
                entries,
                Locale.getDefault(),
            )
        }
    }
    // Live continuation: a speakable message arriving while speech is active
    // appends to the queue; while speech sits idle it stays quiet, so
    // auto-read never becomes an always-on announcer for an open chat.
    // Keyed on the controller too: an account switch swaps it under the
    // same chat id, and the stale collector must not keep appending.
    LaunchedEffect(controller, chat.id) {
        var seededLastId = false
        snapshotFlow {
            controller.timeline
                .lastOrNull()
                ?.record
                ?.messageIdHex
        }.distinctUntilChanged()
            .collect { lastId ->
                if (lastId == null) return@collect
                if (!seededLastId) {
                    seededLastId = true
                    return@collect
                }
                // Only the conversation that owns the active auto-read
                // session may extend it: manual speech and other chats'
                // sessions must never be appended to by this chat's arrivals.
                if (!appState.ownsTtsAutoReadSession(controller.group.groupIdHex)) return@collect
                val ttsState = appState.ttsController.state.value
                if (ttsState !is TtsState.Speaking && ttsState !is TtsState.Paused) return@collect
                val record = controller.timeline.lastOrNull()?.record ?: return@collect
                if (record.messageIdHex != lastId) return@collect
                val entry = ttsEntry(record) ?: return@collect
                appState.appendSpeech(entry, Locale.getDefault())
            }
    }
    // Auto-read return-from-background: capture the actual timeline tail when
    // foreground-only speech is stopped, then narrate rows that materialize
    // after that cursor. Do not use unread state here: an open conversation can
    // advance its read watermark before delayed background arrivals sync.
    var autoReadResumeCursor by
        remember(controller, chat.id) {
            mutableStateOf(conversationAutoReadCursor(controller.timeline))
        }
    var autoReadResumeGeneration by remember(controller, chat.id) { mutableStateOf(0L) }
    val autoReadLifecycleOwner = LocalContext.current.lifecycleOwner()
    DisposableEffect(controller, chat.id, autoReadLifecycleOwner) {
        if (autoReadLifecycleOwner == null) {
            onDispose { }
        } else {
            // Arm only on a real return — a preceding pause — so a cold open's
            // ON_RESUME does not double-fire alongside the open-time backlog.
            var hadPaused = false
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            autoReadResumeCursor = conversationAutoReadCursor(controller.timeline)
                            hadPaused = true
                        }
                        Lifecycle.Event.ON_RESUME ->
                            if (hadPaused) {
                                hadPaused = false
                                autoReadResumeGeneration += 1L
                            }
                        else -> Unit
                    }
                }
            autoReadLifecycleOwner.lifecycle.addObserver(observer)
            onDispose { autoReadLifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(controller, chat.id, autoReadResumeGeneration) {
        if (autoReadResumeGeneration == 0L) return@LaunchedEffect
        if (!appState.ttsHasUsableEngine) return@LaunchedEffect
        if (!appState.isConversationAutoRead(controller.group.groupIdHex)) return@LaunchedEffect
        val cursor = autoReadResumeCursor
        val entries =
            withTimeoutOrNull(TTS_AUTO_READ_RESUME_SYNC_TIMEOUT_MS) {
                snapshotFlow {
                    conversationMessagesAfterAutoReadCursor(controller.timeline, cursor)
                        // Bound before projecting so a delayed bulk sync cannot
                        // make the resume pass scale with the whole timeline.
                        .take(TTS_AUTO_READ_MAX_MESSAGES * 2)
                }.map { messages ->
                    messages.mapNotNull { message -> ttsEntry(message.record) }
                }.first { it.isNotEmpty() }
            } ?: return@LaunchedEffect
        // A newly started/manual session owns the transport; never replace it
        // with delayed foreground catch-up speech.
        val ttsState = appState.ttsController.state.value
        if (ttsState is TtsState.Speaking || ttsState is TtsState.Paused) return@LaunchedEffect
        if (!appState.isConversationAutoRead(controller.group.groupIdHex)) return@LaunchedEffect
        appState.speakAloudAutoRead(
            controller.group.groupIdHex,
            entries,
            Locale.getDefault(),
        )
    }
    // Id of the newest row the bottom-follow has reacted to. A real append
    // gives a new last id while the previous one stays in the list; an
    // older-page load trims the newest rows, so the previous id is gone and
    // no follow fires. Keyed on id (not recordedAt) to survive same-second tails.
    var lastFollowedLatestId by remember(controller) { mutableStateOf<String?>(null) }
    var initialTimelineLoadStarted by remember(controller) { mutableStateOf(false) }
    var highlightedMessageId by remember(controller) { mutableStateOf<String?>(null) }
    var navigateReplyJob by remember(controller) { mutableStateOf<Job?>(null) }
    // UI-only row-height cache for exact centered scrolls. LazyColumn can only
    // measure a target after it has been composed; keeping the measured height
    // by message id lets future off-screen jumps animate straight to the exact
    // centered offset, while never becoming protocol/data source-of-truth state.
    val timelineItemHeightsPx = remember(controller) { mutableStateMapOf<String, Int>() }
    // In-chat search (#292). Opening from the overflow menu swaps the top
    // bar into an inline search field; closing it restores the normal bar.
    // `searchPinnedMatchId` keeps the active match anchored to a concrete
    // message id so the N/M cursor follows that message as older pages load
    // and the match set grows. `searchJob` serializes scroll-jump coroutines
    // the same way `navigateReplyJob` does for reply navigation.
    var searchOpen by remember(controller) { mutableStateOf(false) }
    var searchQuery by remember(controller) { mutableStateOf("") }
    var searchPinnedMatchId by remember(controller) { mutableStateOf<String?>(null) }
    var searchJob by remember(controller) { mutableStateOf<Job?>(null) }
    // The durable local message position lets close-search move the bounded
    // subscription window back before the coordinator restores the exact
    // logical bookmark and viewport offset.
    var preSearchScrollAnchor by remember(controller) { mutableStateOf<ConversationSearchScrollAnchor?>(null) }
    DisposableEffect(controller) {
        onDispose {
            searchJob?.cancel()
            navigateReplyJob?.cancel()
        }
    }
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
    val selectableMessageProjections =
        remember(
            renderedTimeline,
            controller.deletedMessageIds,
            controller.editsByTarget,
            appState.activeAccount?.accountIdHex,
            // Moderation capability rides on these; re-snapshot when they move
            // so a promotion/demotion or roster verification is reflected.
            controller.isSelfAdmin,
            controller.canSendMessages,
        ) {
            renderedTimeline
                .mapNotNull { item ->
                    val record = item.record
                    val messageId = record.messageIdHex
                    if (
                        !isBatchSelectableMessage(
                            messageId = messageId,
                            userVisibleMessage = MessageProjector.isChatKind(record.kind),
                            committedMessage = item.status == MessageStatus.Received || item.status == MessageStatus.Sent,
                            projectedDeleted = item.projected?.deleted == true,
                            deletedMessageIds = controller.deletedMessageIds,
                        )
                    ) {
                        return@mapNotNull null
                    }
                    val invalidated = item.projected?.invalidationStatus != null
                    val persistedFailure =
                        item.projected?.let(::usesPersistedFailurePresentation) == true
                    val editedText =
                        controller.editsByTarget[messageId]
                            ?.latestText
                            ?.takeIf { record.kind == 9uL }
                    BatchMessageSelection(
                        action =
                            BatchMessageActionItem(
                                messageId = messageId,
                                senderId = record.sender,
                                senderDisplayName = record.sender,
                                copyableText =
                                    if (persistedFailure) {
                                        null
                                    } else {
                                        MessageProjector.copyableText(record, editedText)
                                    },
                                forwardableText = if (invalidated) null else MessageProjector.forwardableText(record, editedText),
                                // Same authoritative accessor the single-message
                                // surface and the mutation guard use, so bulk
                                // routing never diverges from per-message policy.
                                canDeleteForEveryone = controller.deleteCapabilityFor(record).canDeleteForEveryone,
                            ),
                        record = record,
                        timelineOrder = item.timelineOrder,
                    )
                }.associateBy { it.action.messageId }
        }
    // Display names are deliberately NOT resolved here: this map spans the
    // whole loaded timeline and profileRevisionForCompose bumps on any profile
    // resolution anywhere, so an eager per-entry displayName() re-ran an O(n)
    // pass (plus the downstream invalid-ids pass and reconcile effect) on
    // every bump. Names are only shown for the selected few — resolved below.
    val selectableMessages = selectableMessageProjections
    val orderedTimelineIds = remember(renderedTimeline) { renderedTimeline.map { it.id } }
    val timelineSelectionById =
        remember(renderedTimeline, selectableMessages) {
            renderedTimeline
                .mapNotNull { item ->
                    selectableMessages[item.record.messageIdHex]?.let { item.id to it }
                }.toMap()
        }
    val timelineIdSet = remember(orderedTimelineIds) { orderedTimelineIds.toSet() }
    val selectableTimelineIds = remember(timelineSelectionById) { timelineSelectionById.keys }
    val dragSelectionDensity = LocalDensity.current
    val dragEdgeThresholdPx = with(dragSelectionDensity) { 56.dp.toPx() }
    val dragMaxScrollStepPx = with(dragSelectionDensity) { 18.dp.toPx() }
    var transcriptWindowTop by
        remember(controller, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(0f) }
    var transcriptHeightPx by
        remember(controller, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf(0f) }
    var dragAnchorTimelineId by
        remember(controller, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf<String?>(null) }
    var dragPointerWindowY by
        remember(controller, appState.activeAccountRef, appState.runtimeGeneration) { mutableStateOf<Float?>(null) }

    fun currentScrollAnchor(): ConversationScrollAnchor {
        val liveRenderedTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val liveHasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
        return conversationScrollAnchor(
            listState = listState,
            renderedItemIds = liveRenderedTimeline.map { it.id },
            renderedMessageIds = liveRenderedTimeline.map { it.record.messageIdHex },
            hasOlderHeader = liveHasOlderHeader,
        )
    }

    @Suppress("ReturnCount") // Guard clauses keep invalid live-timeline gesture state explicit.
    fun updateMessageDragSelection(pointerWindowY: Float): Boolean {
        val anchorId = dragAnchorTimelineId ?: return false
        val endpointId =
            dragSelectionEndpoint(
                listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
                    val id = visible.key as? String
                    id
                        ?.takeIf(timelineIdSet::contains)
                        ?.let {
                            DragSelectionVisibleItem(
                                key = it,
                                start = visible.offset.toFloat(),
                                end = (visible.offset + visible.size).toFloat(),
                            )
                        }
                },
                pointerY = pointerWindowY - transcriptWindowTop,
            ) ?: return false
        if (endpointId == anchorId && selectedMessages.isEmpty()) return false
        val nextTimelineIds =
            anchoredDragSelection(
                orderedIds = orderedTimelineIds,
                eligibleIds = selectableTimelineIds,
                anchorId = anchorId,
                endpointId = endpointId,
            )
        selectedMessages.clear()
        nextTimelineIds.forEach { timelineId ->
            timelineSelectionById[timelineId]?.let { selection ->
                selectedMessages[selection.action.messageId] = selection
            }
        }
        return true
    }

    fun finishMessageDrag(clearSelection: Boolean) {
        val hadActiveDrag = dragAnchorTimelineId != null
        dragAnchorTimelineId = null
        dragPointerWindowY = null
        if (clearSelection) selectedMessages.clear()
        if (hadActiveDrag) scrollCoordinator.settleReadingAt(currentScrollAnchor())
    }

    LaunchedEffect(orderedTimelineIds, selectableTimelineIds) {
        val anchorId = dragAnchorTimelineId ?: return@LaunchedEffect
        if (anchorId !in orderedTimelineIds || anchorId !in selectableTimelineIds) {
            finishMessageDrag(clearSelection = true)
        }
    }

    LaunchedEffect(dragAnchorTimelineId, listState) {
        while (dragAnchorTimelineId != null) {
            withFrameNanos { }
            val pointerWindowY = dragPointerWindowY ?: continue
            val pointerY = pointerWindowY - transcriptWindowTop
            val scrollDelta =
                dragSelectionAutoScrollDelta(
                    pointerY = pointerY,
                    viewportStart = 0f,
                    viewportEnd = transcriptHeightPx,
                    edgeThreshold = dragEdgeThresholdPx,
                    maxStep = dragMaxScrollStepPx,
                )
            if (scrollDelta != 0f) {
                listState.scrollBy(scrollDelta)
                updateMessageDragSelection(pointerWindowY)
            }
        }
    }
    val invalidVisibleMessageIds =
        remember(renderedTimeline, selectableMessages) {
            renderedTimeline
                .asSequence()
                .map { it.record.messageIdHex }
                .filter { it.isNotBlank() && it !in selectableMessages }
                .toSet()
        }
    LaunchedEffect(
        selectableMessages,
        invalidVisibleMessageIds,
        controller.deletedMessageIds,
        controller.pendingTimelineRemovedMessageIds,
    ) {
        val pendingTimelineRemovals = controller.pendingTimelineRemovedMessageIds
        val reconciled =
            reconcileBatchSelections(
                selected = selectedMessages,
                selectableVisible = selectableMessages,
                deletedMessageIds = controller.deletedMessageIds + pendingTimelineRemovals,
                invalidVisibleMessageIds = invalidVisibleMessageIds,
            )
        selectedMessages.keys
            .toList()
            .filterNot(reconciled::containsKey)
            .forEach(selectedMessages::remove)
        reconciled.forEach { (messageId, selection) ->
            if (selectedMessages[messageId] != selection) selectedMessages[messageId] = selection
        }
        controller.acknowledgeTimelineRemovals(pendingTimelineRemovals)
        if (selectedMessages.isEmpty()) {
            batchForwardSheetOpen = false
            showBatchDeleteConfirm = false
        }
    }
    val selectionMode = selectedMessages.isNotEmpty()
    LaunchedEffect(selectionMode) {
        if (selectionMode) clearTextSelection()
    }
    val selectedSelections by
        remember(chat.id, appState.activeAccountRef, appState.runtimeGeneration) {
            derivedStateOf { orderedBatchSelections(selectedMessages.values) }
        }
    val selectedActionItems =
        remember(selectedSelections, appState.profileRevisionForCompose) {
            selectedSelections.map { selection ->
                selection.action.copy(senderDisplayName = appState.displayName(selection.action.senderId))
            }
        }
    val selectedCopyText = remember(selectedActionItems) { batchCopyText(selectedActionItems) }
    val selectedForwardBodies = remember(selectedActionItems) { batchForwardBodies(selectedActionItems) }
    val selectedDeleteBreakdown = remember(selectedActionItems) { batchDeleteBreakdown(selectedActionItems) }
    val renderedSize = renderedTimeline.size
    val hasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
    val nearBottom =
        rememberConversationNearBottom(
            listState = listState,
            renderedTimelineSize = renderedSize,
            hasOlderHeader = hasOlderHeader,
        )

    fun resolveScrollAnchorIndex(anchor: ConversationScrollAnchor): Int? {
        val liveRenderedTimeline = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val liveHasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
        val timelineIndex =
            anchor.messageId
                ?.takeIf { it.isNotBlank() }
                ?.let { messageId -> liveRenderedTimeline.indexOfFirst { it.record.messageIdHex == messageId } }
                ?.takeIf { it >= 0 }
                ?: anchor.itemId
                    ?.let { itemId -> liveRenderedTimeline.indexOfFirst { it.id == itemId } }
                    ?.takeIf { it >= 0 }
                ?: return null
        return 1 + (if (liveHasOlderHeader) 1 else 0) + timelineIndex
    }

    // Drag interactions are the authority for user intent. Programmatic list
    // movement never emits these, so it cannot accidentally downgrade a tail
    // follower or overwrite a history anchor.
    LaunchedEffect(listState, scrollCoordinator) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> scrollCoordinator.onUserGestureStarted(currentScrollAnchor())
                is DragInteraction.Stop,
                is DragInteraction.Cancel,
                -> {
                    snapshotFlow { listState.isScrollInProgress }.filter { !it }.first()
                    val liveRenderedSize = controller.timeline.count { !MessageProjector.isEdit(it.record) }
                    val liveHasOlderHeader = controller.hasMoreBefore || controller.isLoadingOlder
                    scrollCoordinator.onUserGestureSettled(
                        currentScrollAnchor(),
                        isNearBottom(listState, liveRenderedSize, liveHasOlderHeader),
                    )
                }
            }
        }
    }
    // Read anchor stored as the message id of the deepest row the user has
    // settled on. Looked up live each time so load-older prepends shift both
    // the candidate and the anchor by the same offset — position comparisons
    // stay valid. Anchored on id (not recordedAt) to survive same-second
    // collisions: send() stamps with nowSeconds(), so multiple messages can
    // share a recordedAt and a strict-`>` filter would under-count.
    var readAnchorMessageId by remember(controller) { mutableStateOf(controller.lastReadMessageId) }
    val currentHighestVisibleTimelineIndex by remember(renderedSize, hasOlderHeader) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf -1
            val olderHeader = if (hasOlderHeader) 1 else 0
            // LazyColumn layout: [Spacer][maybe older-loading][timeline items][Spacer]
            val firstTimelineListIndex = 1 + olderHeader
            // Visible rows index into the rendered (edit-filtered) list, so clamp
            // to its last index, not the longer unfiltered timeline's.
            (visible.last().index - firstTimelineListIndex)
                .coerceAtMost(renderedSize - 1)
        }
    }
    LaunchedEffect(controller, currentHighestVisibleTimelineIndex, controller.lastReadMessageId) {
        val idx = currentHighestVisibleTimelineIndex
        if (idx < 0) return@LaunchedEffect
        // Monotonic advance only — scroll-up keeps the existing anchor so the
        // read pointer never moves backwards. See [nextReadAnchor]. Resolve the
        // visible row against the filtered (rendered) list it indexes into, not
        // the unfiltered timeline.
        readAnchorMessageId =
            advanceConversationReadAnchor(
                timeline = renderedTimeline,
                currentUiAnchorId = readAnchorMessageId,
                durableAnchorId = controller.lastReadMessageId,
                candidateIndex = idx,
            )
    }
    DisposableEffect(controller) {
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
                    nearBottom = scrollCoordinator.isFollowingTail,
                    anchorItemId = anchor?.id,
                    anchorMessageIdHex = anchor?.record?.messageIdHex,
                ),
            )
        }
    }
    val unreadIncomingCount by remember(controller, chat.id) {
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
    val selfAccountIdHex = appState.activeAccount?.accountIdHex
    val mentionDetectionCache = remember(controller, chat.id, selfAccountIdHex) { MentionDetectionCache() }
    val unreadMentionMessageIds by remember(controller, chat.id, selfAccountIdHex, mentionDetectionCache) {
        derivedStateOf {
            // Anchor on the UI read high-water mark. It advances immediately when
            // the user visits a mention and when the visible row settles, so a
            // recreated controller cannot briefly resurrect already-read mentions.
            if (!initialTimelineAnchored || selfAccountIdHex.isNullOrBlank() || readAnchorMessageId == null) {
                emptyList()
            } else {
                unreadReceivedMentionIds(controller.timeline, readAnchorMessageId) { msg ->
                    mentionDetectionCache.getOrCompute(msg.record.messageIdHex, msg.record.contentTokens) {
                        documentMentionsAccount(
                            document = msg.record.contentTokens,
                            accountIdHex = selfAccountIdHex,
                            resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                        )
                    }
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

    @OptIn(ExperimentalLayoutApi::class)
    val imeTargetInsets = WindowInsets.imeAnimationTarget
    val density = LocalDensity.current
    val imeIsOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    val imeTargetIsOpen by remember(imeTargetInsets, density) {
        derivedStateOf { imeTargetInsets.getBottom(density) > 0 }
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
    var imeTransitionBookmark by remember(chat.id) { mutableStateOf<ConversationScrollBookmark?>(null) }
    var pauseScrollBookmark by remember(chat.id) { mutableStateOf<ConversationScrollBookmark?>(null) }
    val suppressNextImeOpenReanchor = remember(chat.id) { AtomicBoolean(false) }
    var wasComposerFocusedOnPause by remember(chat.id) { mutableStateOf(false) }
    val resumeScrollRestoreCoordinator = remember(controller) { ResumeScrollRestoreCoordinator() }
    // #589: used by the resume observer to clear focus and drop the keyboard
    // when the composer was NOT focused on pause (Case B), without poking the
    // composer's own focus requester.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val resumeLifecycleOwner = LocalContext.current.lifecycleOwner()
    var previousImeTargetOpen by remember(chat.id) { mutableStateOf(imeTargetIsOpen) }
    LaunchedEffect(imeTargetIsOpen, composerFocused, resumeLifecycleOwner) {
        val wasImeTargetOpen = previousImeTargetOpen
        previousImeTargetOpen = imeTargetIsOpen
        if (
            shouldClearComposerFocusAfterImeDismissal(
                wasImeTargetOpen = wasImeTargetOpen,
                imeTargetIsOpen = imeTargetIsOpen,
                composerFocused = composerFocused,
                lifecycleResumed = resumeLifecycleOwner?.lifecycle?.currentState == Lifecycle.State.RESUMED,
            )
        ) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    val scope = rememberCoroutineScope()

    fun revealSentMessage(targetIndex: Int? = null) {
        scope.launch {
            scrollCoordinator.programmaticJump(
                targetMessageId = null,
                reason = ConversationScrollReason.Send,
                resultingMode = ConversationScrollMode.FollowingTail,
            ) {
                val target = targetIndex ?: (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                animateScrollToItem(target)
            }
        }
    }

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // Seeded empty and populated off the Main thread: the first access to a
    // SharedPreferences file blocks on disk, and doing that inside composition
    // stalls the conversation screen's first frame. See #147.
    val recentEmojiRecentsOwner = rememberRecentEmojiRecentsOwner(context)
    var quickReactionEmojis by remember(context) {
        mutableStateOf(RecentEmojiList.DefaultQuickChoices)
    }
    var quickReactionEmojisTouched by remember(context) { mutableStateOf(false) }
    LaunchedEffect(context) {
        val quick =
            withContext(Dispatchers.IO) {
                RecentEmojiPreferences.loadQuickReactions(context)
            }
        if (!quickReactionEmojisTouched) {
            quickReactionEmojis = quick
        }
    }
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
    LaunchedEffect(chat.id, appState.inboundShareRevision, pendingMediaUris.size, pendingDocumentUris.size) {
        val capped =
            appState.consumeInboundShareStreamsCapped(
                groupIdHex = chat.group.groupIdHex,
                existingMediaCount = pendingMediaUris.size,
                existingDocumentCount = pendingDocumentUris.size,
                maxItems = MEDIA_PICKER_MAX_ITEMS,
            ) ?: return@LaunchedEffect
        val staged = capped.accepted
        if (staged.mediaUris.isNotEmpty()) {
            pendingMediaUris = (pendingMediaUris + staged.mediaUris).distinct()
        }
        if (staged.documentUris.isNotEmpty()) {
            pendingDocumentUris = (pendingDocumentUris + staged.documentUris).distinct()
        }
        if (capped.droppedCount > 0) {
            val message =
                context.resources.getQuantityString(
                    R.plurals.toast_share_attachments_dropped,
                    capped.droppedCount,
                    capped.droppedCount,
                )
            appState.presentText(AppText.Plain(message))
        }
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

    // Contact share (attachment sheet): the phone-row picker returns a data
    // URI whose temporary read grant covers the chosen entry's name + number
    // directly, so no READ_CONTACTS permission is requested and nothing
    // beyond that one picked row is read — never the address book.
    var pendingContactShare by remember(chat.id) { mutableStateOf<SharedContact?>(null) }
    // The picked point lives only here until the user sends or cancels; the
    // keyless OSM picker is the single confirmation surface.
    var locationPickerOpen by remember(chat.id) { mutableStateOf(false) }
    // "Share user" (npub) — the identity-native counterpart to a phone contact.
    // Reuses the recipient picker; the selection sends a `nostr:npub…` reference
    // the recipient can tap to open that profile.
    var shareUserPickerOpen by remember(chat.id) { mutableStateOf(false) }
    val shareUserSelection = remember(chat.id) { mutableStateListOf<RecipientSearch.Candidate>() }
    val contactPickerLauncher =
        rememberLauncherForActivityResult(PickContactPhoneRow()) { contactUri ->
            if (contactUri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val contact =
                    withContext(Dispatchers.IO) {
                        readSharedContact(context.contentResolver, contactUri)
                    }
                if (contact == null || contact.isEmpty) {
                    appState.present(R.string.contact_read_failed)
                } else {
                    pendingContactShare = contact
                }
            }
        }

    fun hasLocationGrant(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            if (locationGrantAllowsSharing(grants)) {
                locationPickerOpen = true
            } else {
                appState.present(R.string.location_permission_denied)
            }
        }

    fun sendSharedUser(candidate: RecipientSearch.Candidate) {
        val body = formatUserShareText(candidate.displayName, candidate.npub)
        appState.launchMutation {
            controller.send(body) {
                revealSentMessage()
            }
        }
    }

    fun sendSharedContact(contact: SharedContact) {
        appState.launchMutation {
            val vcardBytes =
                withContext(Dispatchers.IO) {
                    buildVCard(contact).toByteArray(Charsets.UTF_8)
                }
            // The vCard rides the existing media pipeline as a text/vcard
            // attachment (portable — any client can save it), and the caption
            // carries the human-readable name/phone so a peer with no contact
            // renderer still reads it, and our own bubble draws a card from it.
            val attachment =
                PendingAttachment(
                    plaintextBytes = vcardBytes,
                    mediaType = VCARD_MIME_TYPE,
                    fileName = contactVCardFileName(contact),
                )
            val caption = formatContactShareText(contact).ifBlank { null }
            val seeded = controller.queueAttachments(listOf(attachment), caption) ?: return@launchMutation
            revealSentMessage()
            controller.uploadQueued(seeded)
        }
    }

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
            revealSentMessage()
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
                    val refs = nextMsg?.let(controller::mediaReferencesFor)
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
                                .play(
                                    voicePlaybackKey(nextMsg.record.messageIdHex, idx, ref.sourceEpoch),
                                    file,
                                    ownerKey = ownerKey,
                                )
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
                val reportedMime = safeGetType(context.contentResolver, uri)
                val resolvedMime =
                    reportedMime.takeIf { it.isNotBlank() }
                        ?: "application/octet-stream"
                val remainingAlbumBudget = (bytesBudget - albumBytes).coerceAtLeast(0L)
                if (remainingAlbumBudget <= 0L) {
                    albumOverflowed = true
                    break
                }
                val sniffedImageMime =
                    if (reportedMime.isBlank() || reportedMime.equals("application/octet-stream", ignoreCase = true)) {
                        MediaPipeline.sniffImageMediaType(context.contentResolver, uri)
                    } else {
                        null
                    }
                if (documentPickTreatAsImage(reportedMime, sniffedImageMime)) {
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
        onAccepted: () -> Unit = {},
        onRejected: () -> Unit = {},
        onAfterSend: () -> Unit = {},
    ) {
        if (imageUris.isEmpty() && documentUris.isEmpty()) {
            onRejected()
            return
        }
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
                    onRejected()
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
            if (merged.isEmpty()) {
                onRejected()
                return@launchMutation
            }
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
            if (seeded.isEmpty()) {
                onRejected()
                return@launchMutation
            }
            // Pull the user down to the just-seeded bubbles before the
            // upload loop suspends — same UX as text-send. Firing after
            // queueAttachments (the optimistic seed) and before
            // uploadQueued (the FFI publish) means the scroll lands in the
            // same frame the bubble appears, instead of waiting on the
            // relay round-trip.
            onAccepted()
            onAfterSend()
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

    fun currentTimelineListIndex(messageId: String): Int? {
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == messageId }
                .takeIf { it >= 0 }
                ?: return null
        val olderMessagesHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        return 1 + olderMessagesHeaderCount + timelineIndex
    }

    // Scroll the lazy list so the item at [targetMessageId] sits roughly in the
    // vertical center of the message-list viewport, leaving context above and
    // below the target visible (#595, #794). Uses one animated scroll; if the
    // target was never measured before, any final exact-centering correction is
    // a non-animated snap rather than the visible bounce from #999.
    suspend fun centerTimelineItemAt(
        targetMessageId: String,
        fallbackTargetIndex: Int,
        reason: ConversationScrollReason,
    ) {
        val completed =
            scrollCoordinator.programmaticJump(
                targetMessageId = targetMessageId,
                reason = reason,
            ) {
                val targetIndex = currentTimelineListIndex(targetMessageId) ?: fallbackTargetIndex
                val layoutInfo = listState.layoutInfo
                val viewportHeight = layoutInfo.viewportSize.height
                if (viewportHeight <= 0) {
                    // Layout not measured yet (rare on a fresh open): fall back to the
                    // plain top-aligned jump rather than guessing an offset.
                    animateScrollToItem(targetIndex)
                    return@programmaticJump
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
                val animatedOffset = ReplyNavigation.centeredScrollOffset(viewportHeight, itemHeight)
                animateScrollToItem(targetIndex, animatedOffset)

                // Keep the measured correction in the same coordinator command. A
                // newer drag/jump cancels this whole block before it can snap back.
                withFrameNanos { }
                val resolvedTargetIndex = currentTimelineListIndex(targetMessageId) ?: targetIndex
                val postScrollLayoutInfo = listState.layoutInfo
                val measuredItemHeight =
                    postScrollLayoutInfo.visibleItemsInfo.firstOrNull { it.index == resolvedTargetIndex }?.size
                        ?: timelineItemHeightsPx[targetMessageId]
                val measuredViewportHeight = postScrollLayoutInfo.viewportSize.height
                if (measuredViewportHeight > 0 && measuredItemHeight != null) {
                    val measuredOffset =
                        ReplyNavigation.centeredScrollOffset(measuredViewportHeight, measuredItemHeight)
                    if (resolvedTargetIndex != targetIndex || measuredOffset != animatedOffset) {
                        scrollToItem(resolvedTargetIndex, measuredOffset)
                    }
                }
            }
        if (completed) scrollCoordinator.settleReadingAt(currentScrollAnchor())
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
                centerTimelineItemAt(
                    targetMessageId,
                    1 + olderMessagesHeaderCount + timelineIndex,
                    ConversationScrollReason.Reply,
                )
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
                centerTimelineItemAt(
                    targetMessageId,
                    1 + olderMessagesHeaderCount + timelineIndex,
                    ConversationScrollReason.Mention,
                )
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
    }
    LaunchedEffect(controller.group.pendingConfirmation, controller.group.groupIdHex) {
        if (controller.group.pendingConfirmation) {
            controller.dismissConversationNotifications()
        }
    }
    val latestTimelineItemId = renderedTimeline.lastOrNull()?.id
    val transcriptLocale = LocalConfiguration.current.locales[0]
    val olderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
    val bottomTimelineIndex = renderedTimeline.size + 1 + olderHeaderCount
    val currentTailIndex by rememberUpdatedState(newValue = bottomTimelineIndex)

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
    val searchWindowMatches =
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
                            ConversationSearchMatch(
                                messageIdHex = item.record.messageIdHex,
                                timelineAt = item.projected?.timelineAt ?: item.record.recordedAt,
                            ) to body
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
    // Full local-store matches: the loaded-window derivation above is instant
    // feedback while typing; the exhaustive history scan is the authority once
    // it lands, so a result cannot depend on incidental scroll history. The
    // effect restarting on each keystroke cancels a superseded scan, and the
    // debounce keeps typing from firing one scan per character.
    var historySearchMatches by remember(controller) { mutableStateOf<List<ConversationSearchMatch>?>(null) }
    LaunchedEffect(searchQuery, chat.id, controller) {
        historySearchMatches = null
        if (searchQuery.isBlank()) return@LaunchedEffect
        delay(HISTORY_SEARCH_DEBOUNCE_MILLIS)
        val launchedForQuery = searchQuery
        val scan = searchConversationHistoryMatches(appState, controller.group.groupIdHex, launchedForQuery)
        // Only publish if this is still the current query. Cancellation already
        // propagates from the scan, so this only guards a scan that completed
        // in the gap before the effect restarted for a newer keystroke.
        if (searchQuery == launchedForQuery) historySearchMatches = scan
    }
    val effectiveSearchMatches =
        remember(searchWindowMatches, historySearchMatches, renderedTimeline) {
            val scan = historySearchMatches
            if (scan == null) {
                searchWindowMatches
            } else {
                MessageSearch
                    .mergeWithHistoryScan(
                        windowMatches = searchWindowMatches,
                        loadedWindowIds = renderedTimeline.mapTo(HashSet()) { it.record.messageIdHex },
                        scanMatchesOldestFirst = scan,
                    )
            }
        }
    val effectiveSearchMatchIds = effectiveSearchMatches.map { it.messageIdHex }
    // The active match ordinal, re-anchored to the pinned message id so it
    // tracks that message as the set grows. -1 when there are no matches.
    val searchActiveIndex = MessageSearch.resolveCursor(effectiveSearchMatchIds, searchPinnedMatchId)
    // Keep the pin valid: if the resolved cursor fell back to the first match
    // (pin gone / unset) adopt that match id as the new pin so subsequent
    // steps move relative to a real anchor.
    LaunchedEffect(effectiveSearchMatchIds, searchActiveIndex) {
        if (searchActiveIndex >= 0) {
            val resolvedId = effectiveSearchMatchIds[searchActiveIndex]
            if (searchPinnedMatchId != resolvedId) searchPinnedMatchId = resolvedId
        }
    }

    suspend fun centerLoadedSearchMessage(messageIdHex: String) {
        val timelineIndex =
            controller.timeline
                .filterNot { MessageProjector.isEdit(it.record) }
                .indexOfFirst { it.record.messageIdHex == messageIdHex }
        if (timelineIndex < 0) return
        val liveOlderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        centerTimelineItemAt(
            messageIdHex,
            1 + liveOlderHeaderCount + timelineIndex,
            ConversationScrollReason.Search,
        )
        highlightedMessageId = messageIdHex
        delay(1_500L)
        if (highlightedMessageId == messageIdHex) {
            highlightedMessageId = null
        }
    }

    fun scrollToSearchMatch(match: ConversationSearchMatch) {
        val previousSearchJob = searchJob
        previousSearchJob?.cancel()
        highlightedMessageId = null
        searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!controller.loadSearchResultMessageAvailable(match)) return@launch
                centerLoadedSearchMessage(match.messageIdHex)
            }
    }

    // Group-details search can jump to a known id without exhaustive-search
    // timestamp metadata, so retain the bounded reply-navigation path.
    fun scrollToSearchMatch(messageIdHex: String) {
        val previousSearchJob = searchJob
        previousSearchJob?.cancel()
        highlightedMessageId = null
        searchJob =
            scope.launch {
                previousSearchJob?.join()
                if (!controller.loadUntilMessageAvailable(messageIdHex)) return@launch
                centerLoadedSearchMessage(messageIdHex)
            }
    }

    // Step the cursor (next = forward/newer, previous = backward/older) with
    // wrap-around, pin the new match, and jump+highlight it.
    fun navigateToSearchMatch(forward: Boolean) {
        if (effectiveSearchMatchIds.isEmpty()) return
        val next = MessageSearch.step(searchActiveIndex, effectiveSearchMatchIds.size, forward)
        if (next < 0) return
        val target = effectiveSearchMatches[next]
        searchPinnedMatchId = target.messageIdHex
        scrollToSearchMatch(target)
    }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
        searchPinnedMatchId = null
        val previousSearchJob = searchJob
        previousSearchJob?.cancel()
        val expectedRestoreIntent = scrollCoordinator.intentToken
        highlightedMessageId = null
        // A deep search jump can evict the original viewport from the capped
        // window. Page back to its durable local message before asking the
        // coordinator to restore the logical anchor and exact offset.
        preSearchScrollAnchor?.let { anchor ->
            searchJob =
                scope.launch {
                    previousSearchJob?.join()
                    val match = anchor.match
                    if (match != null && controller.loadSearchResultMessageAvailable(match)) {
                        withFrameNanos { }
                    }
                    scrollCoordinator.restoreBookmark(
                        anchor.bookmark,
                        expectedIntent = expectedRestoreIntent,
                        resolveAnchorIndex = { saved ->
                            resolveScrollAnchorIndex(saved)
                                ?: saved.listIndex.coerceIn(
                                    0,
                                    (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0),
                                )
                        },
                    )
                }
        }
        preSearchScrollAnchor = null
    }

    // Back exits partial text selection, then batch selection, then search,
    // then dismisses the composer before leaving the conversation.
    BackHandler {
        when (
            conversationBackAction(
                textSelectionActive = textSelectionMessageId != null,
                messageSelectionActive = selectionMode,
                searchOpen = searchOpen,
                composerFocused = composerFocused,
                imeIsOpen = imeIsOpen,
            )
        ) {
            ConversationBackAction.CLEAR_TEXT_SELECTION -> clearTextSelection()
            ConversationBackAction.CLEAR_MESSAGE_SELECTION -> {
                openActionMenuId = null
                selectedMessages.clear()
            }
            ConversationBackAction.CLOSE_SEARCH -> closeSearch()
            ConversationBackAction.DISMISS_COMPOSER -> {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
            }
            ConversationBackAction.NAVIGATE_UP -> onBack()
        }
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
    LaunchedEffect(effectiveSearchMatches.firstOrNull(), searchOpen) {
        if (searchOpen && effectiveSearchMatches.isNotEmpty()) {
            val first = effectiveSearchMatches[searchActiveIndex.coerceAtLeast(0)]
            scrollToSearchMatch(first)
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
    var entryUnreadDividerRetired by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, initialTimelineAnchored, entryUnreadCount, unreadIncomingCount) {
        if (initialTimelineAnchored && entryUnreadCount > 0 && unreadIncomingCount == 0) {
            entryUnreadDividerRetired = true
        }
    }
    var unreadDivergenceLogged by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller, initialTimelineAnchored, controller.timeline.size) {
        if (!initialTimelineAnchored || unreadDivergenceLogged || controller.timeline.isEmpty()) return@LaunchedEffect
        unreadCountDivergenceReport(
            projectionUnread = projectedEntryUnreadCount,
            timeline = controller.timeline,
            readAnchorMessageId = chat.projection?.lastReadMessageIdHex,
        )?.let { report ->
            logUnreadCountDivergence("DMConversation", report)
        }
        unreadDivergenceLogged = true
    }
    // IME re-anchoring is authorized by the coordinator snapshot taken at
    // the pre-inset focus edge, never by transient live list geometry.
    LaunchedEffect(controller, imeIsOpen, initialTimelineAnchored) {
        if (!imeIsOpen) {
            imeTransitionBookmark = null
            return@LaunchedEffect
        }
        val suppressForCustomInputSwap = suppressNextImeOpenReanchor.getAndSet(false)
        if (!initialTimelineAnchored || suppressForCustomInputSwap) return@LaunchedEffect
        val snapshot = imeTransitionBookmark ?: scrollCoordinator.bookmark(currentScrollAnchor())
        when (snapshot.settledMode) {
            ConversationScrollMode.FollowingTail ->
                scrollCoordinator.followTailIfAllowed(
                    resolveTailIndex = { currentTailIndex },
                    reason = ConversationScrollReason.ImeTransition,
                    frameCount = 24,
                )
            is ConversationScrollMode.ReadingHistory ->
                scrollCoordinator.restoreBookmark(snapshot, resolveAnchorIndex = ::resolveScrollAnchorIndex)
            else -> Unit
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
    // Keyed on controller so chat and same-group account/runtime switches both
    // rebind the observer; resolved through the existing Context.lifecycleOwner()
    // idiom (no new Local import).
    val currentScrollAnchorProvider by rememberUpdatedState(newValue = { currentScrollAnchor() })
    val currentScrollAnchorResolver by
        rememberUpdatedState(newValue = { anchor: ConversationScrollAnchor -> resolveScrollAnchorIndex(anchor) })
    val currentInitialTimelineAnchored by rememberUpdatedState(newValue = initialTimelineAnchored)
    val currentImeIsOpen by rememberUpdatedState(newValue = imeIsOpen)
    DisposableEffect(controller, resumeLifecycleOwner) {
        if (resumeLifecycleOwner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_PAUSE -> {
                            resumeScrollRestoreCoordinator.cancel()
                            wasComposerFocusedOnPause = composerFocused
                            pauseScrollBookmark = scrollCoordinator.bookmark(currentScrollAnchorProvider())
                        }
                        Lifecycle.Event.ON_RESUME -> {
                            val restoreFocus =
                                shouldRestoreComposerFocusOnResume(
                                    wasComposerFocusedOnPause = wasComposerFocusedOnPause,
                                    hasActiveEditOrReplySession =
                                        controller.editingMessageId != null ||
                                            controller.replyingTo != null,
                                )
                            val scrollSnapshot = pauseScrollBookmark
                            pauseScrollBookmark = null
                            resumeScrollRestoreCoordinator.launchResumeWork(scope) {
                                if (restoreFocus) {
                                    runCatching { composerFocus.requestFocus() }
                                    keyboardController?.show()
                                } else if (shouldClearFocusOnResume(
                                        restoringComposerFocus = restoreFocus,
                                        searchOpen = searchOpen,
                                    )
                                ) {
                                    focusManager.clearFocus(force = true)
                                    keyboardController?.hide()
                                }
                                if (currentInitialTimelineAnchored && scrollSnapshot != null) {
                                    var lastInset = -1
                                    var stableFrames = 0
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
                                    scrollCoordinator.restoreViewport(
                                        snapshot = scrollSnapshot,
                                        resolveAnchorIndex = currentScrollAnchorResolver,
                                        resolveTailIndex = { currentTailIndex },
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
            resumeLifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                resumeScrollRestoreCoordinator.cancel()
                resumeLifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }
    LaunchedEffect(listState, scrollCoordinator) {
        var previousViewportHeight: Int? = null
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { viewportHeight ->
            val previous = previousViewportHeight
            previousViewportHeight = viewportHeight
            val unchangedOrUninitialized = previous == null || previous == viewportHeight
            if (unchangedOrUninitialized || !currentInitialTimelineAnchored || currentImeIsOpen) {
                return@collect
            }
            when (scrollCoordinator.mode) {
                ConversationScrollMode.FollowingTail ->
                    scrollCoordinator.programmaticJump(
                        targetMessageId = null,
                        reason = ConversationScrollReason.ViewportChange,
                        resultingMode = ConversationScrollMode.FollowingTail,
                    ) {
                        scrollToItem(currentTailIndex)
                    }
                is ConversationScrollMode.ReadingHistory ->
                    scrollCoordinator.reanchorReadingHistory(currentScrollAnchorResolver)
                else -> Unit
            }
        }
    }

    // Re-apply a saved scroll position once the timeline materializes (#1107).
    // Seeding rememberLazyListState alone is not enough: the list can clamp
    // while the window is still empty, and the first-open anchor would snap to
    // bottom before the reader's position is restored.
    LaunchedEffect(controller, scrollRestore) {
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
        scrollCoordinator.programmaticJump(
            targetMessageId = restore.anchorMessageIdHex,
            reason = ConversationScrollReason.SavedRestore,
            resultingMode =
                ConversationScrollMode.ReadingHistory(
                    restore.anchorMessageIdHex,
                    restore.firstVisibleItemScrollOffset,
                ),
        ) {
            scrollToItem(targetIndex, restore.firstVisibleItemScrollOffset)
        }
        initialTimelineAnchored = true
        val restoredRendered =
            controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        val restoredOlderHeaderCount = if (controller.hasMoreBefore || controller.isLoadingOlder) 1 else 0
        val restoredItem = restoredRendered.getOrNull(targetIndex - 1 - restoredOlderHeaderCount)
        scrollCoordinator.settleReadingAt(
            ConversationScrollAnchor(
                listIndex = targetIndex,
                pixelOffset = restore.firstVisibleItemScrollOffset,
                itemId = restoredItem?.id ?: restore.anchorItemId,
                messageId = restoredItem?.record?.messageIdHex ?: restore.anchorMessageIdHex,
            ),
        )
        lastFollowedLatestId = restoredRendered.lastOrNull()?.id
    }
    LaunchedEffect(controller, latestTimelineItemId, notificationOpenRequestId) {
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
                        .firstUnreadTimelineIndex(entryUnreadCount)
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
                val resultingMode =
                    if (renderedUnreadIndex >= 0) {
                        ConversationScrollMode.ReadingHistory(unreadId, 0)
                    } else {
                        ConversationScrollMode.FollowingTail
                    }
                scrollCoordinator.programmaticJump(
                    targetMessageId = unreadId,
                    reason = ConversationScrollReason.InitialAnchor,
                    resultingMode = resultingMode,
                ) {
                    scrollToItem(targetIndex)
                }
                if (resultingMode is ConversationScrollMode.ReadingHistory) {
                    val unreadItem = renderedTimeline.getOrNull(renderedUnreadIndex)
                    scrollCoordinator.settleReadingAt(
                        ConversationScrollAnchor(
                            listIndex = targetIndex,
                            pixelOffset = 0,
                            itemId = unreadItem?.id,
                            messageId = unreadId,
                        ),
                    )
                }
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
                if (isAppend) {
                    scrollCoordinator.followTailIfAllowed(
                        resolveTailIndex = { currentTailIndex },
                        reason = ConversationScrollReason.NewMessage,
                    )
                }
            }
        }
    }

    // Re-resolve the durable history anchor whenever row/header composition
    // changes. Stable message identity, not the old lazy-list index, owns the
    // restoration after pagination, hydration, separators, or tail appends.
    LaunchedEffect(controller, renderedTimeline, olderHeaderCount, initialTimelineAnchored) {
        if (initialTimelineAnchored) {
            scrollCoordinator.reanchorReadingHistory(::resolveScrollAnchorIndex)
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
        controller,
        renderedTimeline
            .lastOrNull()
            ?.record
            ?.messageIdHex
            ?.let { controller.reactions[it] },
    ) {
        if (initialTimelineAnchored && renderedTimeline.isNotEmpty()) {
            scrollCoordinator.followTailIfAllowed(
                resolveTailIndex = { currentTailIndex },
                reason = ConversationScrollReason.ReactionLayout,
            )
        }
    }

    fun reanchorNewestAfterBottomInputChange() {
        if (!initialTimelineAnchored) return
        scope.launch {
            scrollCoordinator.followTailIfAllowed(
                resolveTailIndex = { currentTailIndex },
                reason = ConversationScrollReason.BottomInput,
                frameCount = 24,
            )
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
    LaunchedEffect(controller, focusMessageId) {
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
        centerTimelineItemAt(
            target,
            1 + olderMessagesHeaderCount + timelineIndex,
            ConversationScrollReason.FocusMessage,
        )
        highlightedMessageId = target
        delay(1_500L)
        if (highlightedMessageId == target) {
            highlightedMessageId = null
        }
    }

    // Scroll-driven read pointer advance. Watches the shared read anchor
    // (`readAnchorMessageId`) so the FFI only sees IDs that strictly advance
    // the pointer — scroll-up cannot regress the count. Settle-gated
    // (`!isScrollInProgress`) avoids per-frame FFI hops while scrolling.
    LaunchedEffect(listState, controller) {
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
            onOpenConversation = { item, created ->
                showDetails = false
                onOpenConversation(item, created)
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
            assumeMemberUntilVerified = notificationOpenRequestId != 0L,
            unrecoverable = controller.group.unrecoverable,
            disbanding = controller.group.disbanding,
            disbanded = controller.group.disbanded,
        )
    var createOpenConversationTiming by remember(chat.id) {
        mutableStateOf(ChatCreateOpenConversationTimingState())
    }
    LaunchedEffect(chat.id) {
        if (!appState.hasActiveChatCreateOpenTiming()) return@LaunchedEffect
        withFrameNanos { }
        val stage =
            chatCreateOpenConversationTimingStage(
                createOpenConversationTiming,
                ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted,
            )
        if (stage != null) {
            appState.markChatCreateOpenStage(stage)
            createOpenConversationTiming =
                reduceChatCreateOpenConversationTiming(
                    createOpenConversationTiming,
                    ChatCreateOpenConversationTimingEvent.ConversationFrameCommitted,
                )
        }
    }
    LaunchedEffect(chat.id, composerGate, createOpenConversationTiming.frameReadyMarked) {
        if (!appState.hasActiveChatCreateOpenTiming()) return@LaunchedEffect
        if (composerGate != ComposerGate.COMPOSER) return@LaunchedEffect
        val stage =
            chatCreateOpenConversationTimingStage(
                createOpenConversationTiming,
                ChatCreateOpenConversationTimingEvent.ComposerReady,
            )
        if (stage != null) {
            appState.completeChatCreateOpenTiming(stage)
            createOpenConversationTiming =
                reduceChatCreateOpenConversationTiming(
                    createOpenConversationTiming,
                    ChatCreateOpenConversationTimingEvent.ComposerReady,
                )
        }
    }
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
    val restoredDraftSnapshot = appState.draftSnapshotFor(controller.group.groupIdHex)
    val composerShareRevision =
        rememberComposerShareRevision(
            externalRevision = appState.inboundShareRevision,
            editingMessageId = controller.editingMessageId,
        )
    val composerTextState =
        rememberComposerTextState(
            draftKey = controller.group.groupIdHex,
            initialDraft = restoredDraftSnapshot?.textFieldValue ?: TextFieldValue(""),
            externalRevision = composerShareRevision,
        )
    val composerAutoFocusConsumed = remember(chat.id) { mutableStateOf(false) }

    // Hoisted from ComposerBar so a tap on the transcript can dismiss the
    // attachment sheet — the composer itself stays interactive while it's open.
    val composerAttachmentSheet = rememberComposerAttachmentSheetState()

    val openDetailsDescription = stringResource(R.string.details)
    LaunchedEffect(selectedForwardBodies.isEmpty()) {
        batchForwardSheetOpen =
            batchForwardSheetOpenForBodies(
                currentlyOpen = batchForwardSheetOpen,
                forwardBodies = selectedForwardBodies,
            )
    }
    Scaffold(
        modifier =
            Modifier
                .fillMaxSize()
                .dismissTextSelectionOnOutsideTap(
                    active = textSelectionMessageId != null,
                    selectedBoundsInWindow = textSelectionBubbleBounds,
                    onDismiss = ::clearTextSelection,
                ),
        // The transcript consumes IME insets; the composer bottom bar is the sole
        // owner of keyboard padding so the reply-preview chip and input row move
        // as one cluster (#895, #1109).
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            Column {
                if (selectionMode) {
                    MessageSelectionBar(
                        count = selectedActionItems.size,
                        canCopy = selectedCopyText.isNotBlank(),
                        canForward = selectedForwardBodies.isNotEmpty(),
                        onClose = { selectedMessages.clear() },
                        onCopy = {
                            if (selectedCopyText.isNotBlank()) {
                                clipboard.setText(AnnotatedString(selectedCopyText))
                                selectedMessages.clear()
                            }
                        },
                        onForward = {
                            if (selectedForwardBodies.isNotEmpty()) batchForwardSheetOpen = true
                        },
                        onDelete = { showBatchDeleteConfirm = true },
                    )
                } else if (searchOpen) {
                    ConversationSearchTopBar(
                        query = searchQuery,
                        onQueryChange = {
                            searchJob?.cancel()
                            searchJob = null
                            highlightedMessageId = null
                            searchQuery = it
                            // Re-anchor the cursor to the new query's match set on
                            // the next derivation; clearing the pin makes it land
                            // on the first match again.
                            searchPinnedMatchId = null
                        },
                        onClear = {
                            searchJob?.cancel()
                            searchJob = null
                            highlightedMessageId = null
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
                                GroupAvatar(
                                    appState = appState,
                                    group = controller.group,
                                    title = controller.title(groupTitleCopy),
                                    // For a 1:1 DM the seed must match the peer-derived
                                    // avatar so the initials fallback stays stable, just
                                    // like the chat-list row (#837).
                                    seed = controller.avatarAccount ?: controller.group.groupIdHex,
                                    size = 36.dp,
                                    fallbackPictureUrl = controller.avatarAccount?.let(appState::avatarUrl),
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
                                                memberCount = controller.memberCount,
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
                                                positionProvider =
                                                    TooltipDefaults.rememberRichTooltipPositionProvider(),
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
                                        val bookmark = scrollCoordinator.bookmark(currentScrollAnchor())
                                        val anchorMessage =
                                            bookmark.anchor.messageId?.let { messageId ->
                                                renderedTimeline.firstOrNull { it.record.messageIdHex == messageId }
                                            }
                                        preSearchScrollAnchor =
                                            ConversationSearchScrollAnchor(
                                                bookmark = bookmark,
                                                match =
                                                    anchorMessage?.let {
                                                        ConversationSearchMatch(
                                                            messageIdHex = it.record.messageIdHex,
                                                            timelineAt =
                                                                it.projected?.timelineAt
                                                                    ?: it.record.recordedAt,
                                                        )
                                                    },
                                            )
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
                                            appState.launchMutation {
                                                controller.setArchived(!controller.group.archived)
                                            }
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
                                                        LeaveAction.SoleAdminMustTransfer ->
                                                            showTransferAdminFirst = true
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
                // Read-aloud transport renders beneath whichever bar is
                // active and survives selection, search, and navigation.
                TtsTransportBar(appState)
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
                        if (bottomChromeHeightObserver.onMeasured(size.height)) {
                            reanchorNewestAfterBottomInputChange()
                        }
                        val chromeBottom = chromeInsets.getBottom(density)
                        snackbarBottomInset.value =
                            with(density) { (size.height - chromeBottom).coerceAtLeast(0).toDp() }
                    },
            ) {
                when {
                    // Selection owns the screen chrome; hide search navigation,
                    // invite controls, and the composer until it exits.
                    selectionMode -> Unit
                    // While search is open the composer steps aside for the match
                    // navigation bar pinned above the keyboard.
                    searchOpen ->
                        ConversationSearchNavBar(
                            matchCount = effectiveSearchMatchIds.size,
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
                    // Text handoff may proceed for a positively seeded current
                    // member; destructive/admin mutations still require verified
                    // membership through `canSendMessages`.
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
                            ComposerGate.FROZEN -> FrozenGroupComposerNotice()
                            ComposerGate.DISBANDED ->
                                DisbandedGroupComposerNotice(disbanded = controller.group.disbanded)
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
                                    replyingToMedia =
                                        controller.replyingTo
                                            ?.let(controller::mediaReferencesFor)
                                            .orEmpty(),
                                    replyingToDisplay =
                                        controller.replyingTo
                                            ?.let { controller.replyTargetPreview(it, messageTextCopy) },
                                    messageTextCopy = messageTextCopy,
                                    onCancelReply = { controller.replyingTo = null },
                                    onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                                    initialDraft = restoredDraftSnapshot?.textFieldValue ?: TextFieldValue(""),
                                    onDraftChange = { appState.setDraft(groupIdHex, it) },
                                    draftKey = groupIdHex,
                                    textState = composerTextState,
                                    attachmentSheetState = composerAttachmentSheet,
                                    editingMessageId = controller.editingMessageId,
                                    editingInitialText = editingRecord?.let { controller.displayedText(it) },
                                    onCancelEdit = { controller.editingMessageId = null },
                                    onAfterSend = {
                                        // Always pull the user down to see their just-sent
                                        // bubble, even if they were reading older history.
                                        // Matches standard chat-app behavior.
                                        revealSentMessage()
                                    },
                                    onPickFromGallery = {
                                        imagePickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                                        )
                                    },
                                    onPickRecentMedia = { uri ->
                                        // A tap on the recent-media strip stages that item
                                        // into the same shelf as the picker, so the preview
                                        // sheet opens with it queued (multi-select + send
                                        // live in the preview).
                                        pendingMediaUris =
                                            (pendingMediaUris + uri).distinct().take(MEDIA_PICKER_MAX_ITEMS)
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
                                    onShareLocation = {
                                        // Permission is requested here, on the tap, never
                                        // earlier. Fine and coarse together so the user's
                                        // approximate-only choice still works.
                                        if (hasLocationGrant(Manifest.permission.ACCESS_FINE_LOCATION) ||
                                            hasLocationGrant(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        ) {
                                            locationPickerOpen = true
                                        } else {
                                            locationPermissionLauncher.launch(
                                                arrayOf(
                                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                                ),
                                            )
                                        }
                                    },
                                    onShareUser = { shareUserPickerOpen = true },
                                    onShareContact = { contactPickerLauncher.launch(Unit) },
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
                                    autoFocusOnDraftRestore = shouldFocusComposerOnDraftRestore(restoredDraftSnapshot),
                                    autoFocusConsumedState = composerAutoFocusConsumed,
                                    enterKeyBehavior = appState.enterKeyBehavior,
                                    // #589: hoisted focus plumbing — the requester lets the
                                    // resume observer restore focus, and the callback keeps
                                    // `composerFocused` tracking the live keyboard state.
                                    composerFocus = composerFocus,
                                    onComposerFocusChanged = { focused ->
                                        if (focused && !composerFocused && !imeIsOpen) {
                                            imeTransitionBookmark =
                                                scrollCoordinator.bookmark(currentScrollAnchor())
                                        } else if (!focused && !imeIsOpen) {
                                            imeTransitionBookmark = null
                                        }
                                        composerFocused = focused
                                    },
                                    onBottomInputChanged = ::reanchorNewestAfterBottomInputChange,
                                    onKeyboardRestoreFromCustomInput = {
                                        suppressNextImeOpenReanchor.set(true)
                                    },
                                    onKeyboardRestoreFromCustomInputFailed = {
                                        suppressNextImeOpenReanchor.set(false)
                                    },
                                    recentEmojis = recentEmojiRecentsOwner.recents,
                                    onEmojiUsed = { recentEmojiRecentsOwner.onEmojiUsed(it) },
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
                            memberCount = controller.memberCount,
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
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize(),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp)
                                    .alpha(if (initialTimelineAnchored) 1f else 0f)
                                    .onGloballyPositioned { coordinates ->
                                        transcriptWindowTop = coordinates.positionInWindow().y
                                        transcriptHeightPx = coordinates.size.height.toFloat()
                                    },
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
                                // structurally similar rows across scroll.
                                contentType = { _, item ->
                                    when {
                                        MessageProjector.isGroupSystem(item.record) -> "groupSystem"
                                        MessageProjector.isAgentOperation(item.record) -> "agentOperation"
                                        else -> "message"
                                    }
                                },
                            ) { index, item ->
                                Column(Modifier.fillMaxWidth()) {
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
                                    if (
                                        shouldShowConversationEntryUnreadDivider(
                                            entryUnreadCount = entryUnreadCount,
                                            liveUnreadCount = unreadIncomingCount,
                                            dividerRetired = entryUnreadDividerRetired,
                                            messageId = item.record.messageIdHex,
                                            firstUnreadMessageId = entryFirstUnreadMessageId,
                                        )
                                    ) {
                                        UnreadMessagesDivider(count = entryUnreadCount)
                                    }
                                    // Measured below the day/unread separators, so centered
                                    // scroll targets get the bubble's own height, not a
                                    // separator-inflated one.
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { size ->
                                                if (size.height > 0 && timelineItemHeightsPx[item.record.messageIdHex] != size.height) {
                                                    timelineItemHeightsPx[item.record.messageIdHex] = size.height
                                                }
                                            },
                                    ) {
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
                                                    onDeleteForMe =
                                                        if (controller.group.pendingConfirmation) {
                                                            null
                                                        } else {
                                                            { controller.hideMessageForMe(item.record.messageIdHex) }
                                                        },
                                                )
                                                return@Column
                                            }
                                            TimelineRowKind.AgentOperation -> {
                                                // Standard bubbles own deletion and
                                                // convergence tombstones. Use the
                                                // dedicated chip only for live rows.
                                                val projectedDeleted = item.projected?.deleted == true
                                                val optimisticallyDeleted =
                                                    MessageProjector.isDeleted(
                                                        item.record.messageIdHex,
                                                        controller.deletedMessageIds,
                                                    )
                                                val invalidated = item.projected?.invalidationStatus != null
                                                if (
                                                    shouldRenderDedicatedAgentOperationRow(
                                                        projectedDeleted = projectedDeleted,
                                                        optimisticallyDeleted = optimisticallyDeleted,
                                                        invalidated = invalidated,
                                                    )
                                                ) {
                                                    val operation =
                                                        remember(item.record) {
                                                            AgentOperationProjector.project(item.record)
                                                        }
                                                    if (operation != null) {
                                                        AgentOperationTimelineRow(
                                                            item = item,
                                                            operation = operation,
                                                            controller = controller,
                                                            appState = appState,
                                                            readOnly = controller.group.pendingConfirmation,
                                                        )
                                                        return@Column
                                                    }
                                                }
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
                                        val newer = renderedTimeline.getOrNull(index + 1)
                                        val sameSenderAsOlderBubble =
                                            older?.let { candidate ->
                                                timelineRowKind(candidate.record, appState.streamingDebugEnabled) ==
                                                    TimelineRowKind.Bubble &&
                                                    GroupProjector.messagesShareTranscriptSenderRun(
                                                        firstSender = candidate.record.sender,
                                                        firstRecordedAt = candidate.record.recordedAt,
                                                        secondSender = item.record.sender,
                                                        secondRecordedAt = item.record.recordedAt,
                                                        sameDay = !differentDay(candidate.record.recordedAt, item.record.recordedAt),
                                                    )
                                            } == true
                                        val sameSenderAsNewerBubble =
                                            newer?.let { candidate ->
                                                timelineRowKind(candidate.record, appState.streamingDebugEnabled) ==
                                                    TimelineRowKind.Bubble &&
                                                    GroupProjector.messagesShareTranscriptSenderRun(
                                                        firstSender = item.record.sender,
                                                        firstRecordedAt = item.record.recordedAt,
                                                        secondSender = candidate.record.sender,
                                                        secondRecordedAt = candidate.record.recordedAt,
                                                        sameDay = !differentDay(item.record.recordedAt, candidate.record.recordedAt),
                                                    )
                                            } == true
                                        val senderDecoration =
                                            GroupProjector.transcriptSenderDecoration(
                                                isDm = controller.isDm,
                                                mine = controller.isMessageMine(item.record),
                                                sameSenderAsOlderBubble = sameSenderAsOlderBubble,
                                                sameSenderAsNewerBubble = sameSenderAsNewerBubble,
                                            )
                                        val messageId = item.record.messageIdHex
                                        val ownsActionMenu = openActionMenuId == messageId
                                        DismissMessageActionMenuOnDispose(
                                            messageId = messageId,
                                            isOpen = ownsActionMenu,
                                        ) {
                                            if (openActionMenuId == messageId) {
                                                openActionMenuId = null
                                            }
                                        }
                                        MessageBubble(
                                            item = item,
                                            controller = controller,
                                            appState = appState,
                                            composerTextState = composerTextState,
                                            highlighted = item.record.messageIdHex == highlightedMessageId,
                                            selectionMode = selectionMode,
                                            textSelectionMode = textSelectionMessageId == item.record.messageIdHex,
                                            onTextSelectionModeChange = { enabled ->
                                                val messageId = item.record.messageIdHex
                                                if (enabled) {
                                                    openActionMenuId = null
                                                    textSelectionMessageId = messageId
                                                    textSelectionBubbleBounds = null
                                                } else if (textSelectionMessageId == messageId) {
                                                    clearTextSelection()
                                                }
                                            },
                                            onTextSelectionBoundsChange = { bounds ->
                                                if (textSelectionMessageId == item.record.messageIdHex) {
                                                    textSelectionBubbleBounds = bounds
                                                }
                                            },
                                            batchSelectable = item.record.messageIdHex in selectableMessages,
                                            selected = selectedMessages.containsKey(item.record.messageIdHex),
                                            onToggleSelection = {
                                                val messageId = item.record.messageIdHex
                                                if (selectedMessages.containsKey(messageId)) {
                                                    selectedMessages.remove(messageId)
                                                } else {
                                                    selectableMessages[messageId]?.let {
                                                        selectedMessages[messageId] = it
                                                    }
                                                }
                                            },
                                            rangeDragActive = dragAnchorTimelineId == item.id,
                                            onDragSelectionStart = { pointerWindowY ->
                                                openActionMenuId = null
                                                clearTextSelection()
                                                scrollCoordinator.onUserGestureStarted(currentScrollAnchor())
                                                dragAnchorTimelineId = item.id
                                                dragPointerWindowY = pointerWindowY
                                            },
                                            onDragSelection = { pointerWindowY ->
                                                dragPointerWindowY = pointerWindowY
                                                updateMessageDragSelection(pointerWindowY)
                                            },
                                            onDragSelectionEnd = { finishMessageDrag(clearSelection = false) },
                                            onDragSelectionCancel = { finishMessageDrag(clearSelection = true) },
                                            quickReactionEmojis = quickReactionEmojis,
                                            recentEmojis = recentEmojiRecentsOwner.recents,
                                            onEmojiUsed = { recentEmojiRecentsOwner.onEmojiUsed(it) },
                                            isActionMenuOpen = ownsActionMenu,
                                            onActionMenuOpenChange = { open ->
                                                if (open) clearTextSelection()
                                                if (open) {
                                                    openActionMenuId = messageId
                                                } else if (openActionMenuId == messageId) {
                                                    openActionMenuId = null
                                                }
                                            },
                                            // Lambdas, not method references: the Compose
                                            // compiler memoizes lambdas but allocates a fresh
                                            // function reference per recomposition, which made
                                            // every visible bubble recompose on any timeline
                                            // change. See #110.
                                            onQuickReactionsSave = { saveQuickReactionEmojis(it) },
                                            onQuickReactionsReset = { resetQuickReactionEmojis() },
                                            onReplyPreviewClick = { navigateToReplyTarget(it) },
                                            composerGate = composerGate,
                                            groupDisbanded = controller.group.disbanded,
                                            inviteMutationInFlight = controller.mutationInFlight,
                                            onJoinInvite = { appState.launchMutation { controller.acceptInvite() } },
                                            onDeclineInvite = {
                                                appState.launchMutation {
                                                    if (controller.declineInvite()) onBack()
                                                }
                                            },
                                            mentionCandidates = mentionPicker.candidates,
                                            mentionPickerEnabled = mentionPicker.enabled,
                                            showSenderName = senderDecoration.showName,
                                            showSenderAvatar = senderDecoration.showAvatar,
                                            collapseLongMessages = collapseLongMessages,
                                            readOnly = controller.group.pendingConfirmation,
                                        )
                                    }
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
                        if (initialTimelineAnchored && !selectionMode) {
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
                                    ConversationJumpToNewestButton(
                                        unreadIncomingCount = unreadIncomingCount,
                                        onClick = {
                                            scope.launch {
                                                scrollCoordinator.jumpToNewest(bottomTimelineIndex)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
            }
            if (composerAttachmentSheet.isOpen) {
                // Transparent scrim over the transcript only — the composer
                // stays reachable, so the keyboard and emoji toggles can still
                // swap the sheet away directly. Carries a dismiss semantics
                // action + label so a screen reader announces (and can trigger)
                // this otherwise-invisible touch layer.
                val dismissLabel = stringResource(R.string.close)
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(composerAttachmentSheet) {
                            detectTapGestures { composerAttachmentSheet.dismiss() }
                        }.semantics {
                            contentDescription = dismissLabel
                            onClick(label = dismissLabel) {
                                composerAttachmentSheet.dismiss()
                                true
                            }
                        },
                )
            }
        }
    }

    if (batchForwardSheetOpen && selectedForwardBodies.isNotEmpty()) {
        ForwardMessageSheet(
            appState = appState,
            body = selectedForwardBodies.joinToString("\n"),
            originGroupIdHex = controller.group.groupIdHex,
            onDismiss = { batchForwardSheetOpen = false },
            onForward = { targetGroupIds ->
                appState.forwardTexts(targetGroupIds, selectedForwardBodies)
                selectedMessages.clear()
            },
        )
    }

    if (showBatchDeleteConfirm && selectedActionItems.isNotEmpty()) {
        val runBatchDelete: (BatchDeleteScope) -> Unit = { scope ->
            // In-flight guard on top of the mutation's own idempotency: a
            // second tap before the first completes is dropped.
            if (!batchDeleteInFlight) {
                batchDeleteInFlight = true
                val selections = selectedSelections
                appState.launchMutation {
                    try {
                        val result =
                            executeBatchDelete(
                                selections = selections,
                                scope = scope,
                                // deleteMessage re-validates the capability, so it
                                // is the moderation security boundary — a stale
                                // snapshot can never publish an unauthorized delete.
                                deleteForEveryone = { record ->
                                    controller.deleteMessage(record, presentFailure = false)
                                },
                                hideLocally = { messageId ->
                                    runCatching { controller.hideMessageForMe(messageId) }.isSuccess
                                },
                            )
                        when (result.succeeded) {
                            result.attempted -> appState.present(R.string.batch_delete_complete)
                            0 -> appState.present(R.string.batch_delete_failed, copyable = true)
                            else -> appState.present(R.string.batch_delete_partial, copyable = true)
                        }
                    } finally {
                        batchDeleteInFlight = false
                        showBatchDeleteConfirm = false
                        selectedMessages.clear()
                    }
                }
            }
        }
        BatchMessageDeleteDialog(
            selectedCount = selectedActionItems.size,
            breakdown = selectedDeleteBreakdown,
            deleteInFlight = batchDeleteInFlight,
            onDeleteForEveryone = { runBatchDelete(BatchDeleteScope.EVERYONE) },
            onDeleteForMe = { runBatchDelete(BatchDeleteScope.LOCAL_ONLY) },
            onDismissRequest = { if (!batchDeleteInFlight) showBatchDeleteConfirm = false },
        )
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

    pendingContactShare?.let { contact ->
        ContactPreviewScreen(
            contact = contact,
            onDismiss = { pendingContactShare = null },
            onSend = { selected ->
                pendingContactShare = null
                sendSharedContact(selected)
            },
        )
    }

    if (shareUserPickerOpen) {
        val activeHex = appState.activeAccount?.accountIdHex
        ContactPickerScreen(
            appState = appState,
            title = stringResource(R.string.share_user_title),
            selected = shareUserSelection,
            onBack = {
                shareUserPickerOpen = false
                shareUserSelection.clear()
            },
            onConfirm = {
                val picked = shareUserSelection.toList()
                shareUserPickerOpen = false
                shareUserSelection.clear()
                picked.forEach { sendSharedUser(it) }
            },
            confirmIcon = Icons.AutoMirrored.Filled.Send,
            autoSelectResolvedIdentifier = true,
            excludeAccountIdHexes = setOfNotNull(activeHex),
        )
    }

    if (locationPickerOpen) {
        LocationPickerScreen(
            hasFineGrant = hasLocationGrant(Manifest.permission.ACCESS_FINE_LOCATION),
            onDismiss = { locationPickerOpen = false },
            onPick = { location ->
                locationPickerOpen = false
                appState.launchMutation {
                    controller.send(formatLocationShareText(location)) {
                        revealSentMessage(bottomTimelineIndex)
                    }
                }
            },
        )
    }

    if (pendingMediaUris.isNotEmpty() || pendingDocumentUris.isNotEmpty()) {
        val imageUris = pendingMediaUris
        val documentUris = pendingDocumentUris
        // The typed composer draft seeds the caption, and an accepted send
        // consumes it exactly like a text send would — guarded so text typed
        // after staging is never wiped. Dismissing leaves the draft alone.
        val seededDraftText = composerTextState.valueState.value.text
        MediaPreviewScreen(
            uris = imageUris,
            documentUris = documentUris,
            chatTitle = controller.title(groupTitleCopy),
            initialCaption = seededDraftText,
            onDismiss = {
                pendingMediaUris = emptyList()
                pendingDocumentUris = emptyList()
            },
            onSend = { caption, onResult ->
                sendStagedAttachments(
                    imageUris,
                    documentUris,
                    caption,
                    onAccepted = {
                        pendingMediaUris = emptyList()
                        pendingDocumentUris = emptyList()
                        if (composerTextState.valueState.value.text == seededDraftText) {
                            composerTextState.valueState.value = TextFieldValue("")
                            appState.setDraft(controller.group.groupIdHex, TextFieldValue(""))
                        }
                        onResult(true)
                    },
                    onRejected = { onResult(false) },
                    onAfterSend = {
                        // Pull the user down to the just-seeded bubble.
                        // `bottomTimelineIndex` reads from
                        // [renderedTimeline.size] (the snapshot-backed
                        // controller list) instead of
                        // [LazyListState.layoutInfo.totalItemsCount], which
                        // is stale until the next recompose — for a
                        // multi-file send that staleness leaves the user
                        // one-or-more rows above the new bubble.
                        revealSentMessage(bottomTimelineIndex)
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
