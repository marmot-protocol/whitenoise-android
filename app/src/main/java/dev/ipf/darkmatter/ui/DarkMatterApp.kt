package dev.ipf.darkmatter.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MarkChatRead
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.core.os.ConfigurationCompat
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.AvatarImageLoader
import dev.ipf.darkmatter.core.ChatListIdentifierSearch
import dev.ipf.darkmatter.core.ChatListMessageSearch
import dev.ipf.darkmatter.core.ClipboardPasteAffordance
import dev.ipf.darkmatter.core.DiagnosticFormatter
import dev.ipf.darkmatter.core.EditState
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.GroupSystemCopy
import dev.ipf.darkmatter.core.GroupSystemEvents
import dev.ipf.darkmatter.core.GroupTitleCopy
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.LeaveAction
import dev.ipf.darkmatter.core.MentionComposer
import dev.ipf.darkmatter.core.MessageBodyMatch
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.MessageSearch
import dev.ipf.darkmatter.core.MessageTextCopy
import dev.ipf.darkmatter.core.Nip05Resolver
import dev.ipf.darkmatter.core.ProfileFieldValidation
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.core.QrCodeEncoder
import dev.ipf.darkmatter.core.ReactionTally
import dev.ipf.darkmatter.core.RecentEmojiList
import dev.ipf.darkmatter.core.RecipientReference
import dev.ipf.darkmatter.core.ReplySwipe
import dev.ipf.darkmatter.core.SnippetHighlight
import dev.ipf.darkmatter.core.TimelineProjector
import dev.ipf.darkmatter.core.replyMediaKindFromMime
import dev.ipf.darkmatter.media.DuckDuckGoImageSearchClient
import dev.ipf.darkmatter.media.ImageSearchClient
import dev.ipf.darkmatter.media.ImageSearchException
import dev.ipf.darkmatter.media.ImageSearchResult
import dev.ipf.darkmatter.media.MediaCacheDirs
import dev.ipf.darkmatter.media.MediaPipeline
import dev.ipf.darkmatter.media.MediaReferenceParser
import dev.ipf.darkmatter.media.Thumbhash
import dev.ipf.darkmatter.media.sanitizeHttpsAvatarUrl
import dev.ipf.darkmatter.notifications.NotificationNavStep
import dev.ipf.darkmatter.notifications.NotificationTarget
import dev.ipf.darkmatter.notifications.resolveNotificationNav
import dev.ipf.darkmatter.state.AppPhase
import dev.ipf.darkmatter.state.AppText
import dev.ipf.darkmatter.state.AppThemeMode
import dev.ipf.darkmatter.state.ChatListItem
import dev.ipf.darkmatter.state.ChatsController
import dev.ipf.darkmatter.state.ConversationController
import dev.ipf.darkmatter.state.ConversationControllerCopy
import dev.ipf.darkmatter.state.DarkMatterAppState
import dev.ipf.darkmatter.state.EnterKeyBehavior
import dev.ipf.darkmatter.state.MediaAutoDownloadNetwork
import dev.ipf.darkmatter.state.MediaAutoDownloadType
import dev.ipf.darkmatter.state.MediaQuality
import dev.ipf.darkmatter.state.MessageStatus
import dev.ipf.darkmatter.state.MessageStatusLabels
import dev.ipf.darkmatter.state.OutgoingMessageIndicator
import dev.ipf.darkmatter.state.PendingAttachment
import dev.ipf.darkmatter.state.ReactionParticipant
import dev.ipf.darkmatter.state.RelayListKind
import dev.ipf.darkmatter.state.TimelineMessage
import dev.ipf.darkmatter.state.countUnreadIncoming
import dev.ipf.darkmatter.state.formatExactTimestamp
import dev.ipf.darkmatter.state.isAcceptableRelayUrl
import dev.ipf.darkmatter.state.labelFor
import dev.ipf.darkmatter.state.nextReadAnchor
import dev.ipf.darkmatter.state.outgoingIndicator
import dev.ipf.darkmatter.state.shortHex
import dev.ipf.darkmatter.state.shouldShowOriginalTimestamp
import dev.ipf.darkmatter.ui.theme.Dimens
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupSystemEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayHealthFfi
import dev.ipf.marmotkit.RelayListFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.roundToInt

private enum class MainSection {
    Chats,
    Settings,
    Diagnostics,
}

private enum class SettingsDetail {
    Appearance,
    Data,
    Profile,
    Identity,
    Relays,
    KeyPackages,
    Notifications,
    SecurityPrivacy,
}

private data class DiagnosticLogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: ULong = (System.currentTimeMillis() / 1000L).toULong(),
    val text: String,
)

private data class LanguageOption(
    val tag: String,
    @param:StringRes val labelRes: Int,
)

private val languageOptions =
    listOf(
        LanguageOption("", R.string.language_system),
        LanguageOption("en", R.string.language_english),
        LanguageOption("de", R.string.language_german),
        LanguageOption("es", R.string.language_spanish),
        LanguageOption("fr", R.string.language_french),
        LanguageOption("it", R.string.language_italian),
        LanguageOption("pt", R.string.language_portuguese),
        LanguageOption("ru", R.string.language_russian),
        LanguageOption("tr", R.string.language_turkish),
        LanguageOption("zh", R.string.language_chinese_simplified),
        LanguageOption("zh-Hant", R.string.language_chinese_traditional),
    )

@Composable
private fun rememberGroupTitleCopy(): GroupTitleCopy =
    GroupTitleCopy(
        inviteFromFormat = stringResource(R.string.group_title_invite_from),
        groupOfPeopleFormat = stringResource(R.string.group_title_people_count),
    )

@Composable
private fun rememberMessageTextCopy(): MessageTextCopy =
    MessageTextCopy(
        reactedFormat = stringResource(R.string.message_reacted),
        reactionFallback = stringResource(R.string.message_reaction_fallback),
        deleted = stringResource(R.string.message_deleted_preview),
        invalidated = stringResource(R.string.message_invalidated_preview),
        agentStreamStarted = stringResource(R.string.agent_stream_started),
        streamFinished = stringResource(R.string.stream_finished),
        mediaAttachment = stringResource(R.string.media_attachment),
        message = stringResource(R.string.generic_message),
        groupSystem = rememberGroupSystemCopy(),
    )

@Composable
private fun rememberGroupSystemCopy(): GroupSystemCopy =
    GroupSystemCopy(
        memberAddedFormat = stringResource(R.string.group_system_member_added),
        memberAddedPassiveFormat = stringResource(R.string.group_system_member_added_passive),
        memberRemovedFormat = stringResource(R.string.group_system_member_removed),
        memberRemovedPassiveFormat = stringResource(R.string.group_system_member_removed_passive),
        memberLeftFormat = stringResource(R.string.group_system_member_left),
        adminAddedFormat = stringResource(R.string.group_system_admin_added),
        adminAddedPassiveFormat = stringResource(R.string.group_system_admin_added_passive),
        adminRemovedFormat = stringResource(R.string.group_system_admin_removed),
        adminRemovedPassiveFormat = stringResource(R.string.group_system_admin_removed_passive),
        renamedFormat = stringResource(R.string.group_system_renamed),
        renamedPassiveFormat = stringResource(R.string.group_system_renamed_passive),
        avatarChangedFormat = stringResource(R.string.group_system_avatar_changed),
        avatarChangedPassive = stringResource(R.string.group_system_avatar_changed_passive),
        youMemberAddedFormat = stringResource(R.string.group_system_you_member_added),
        memberAddedYouFormat = stringResource(R.string.group_system_member_added_you),
        memberAddedYouPassive = stringResource(R.string.group_system_member_added_you_passive),
        youMemberRemovedFormat = stringResource(R.string.group_system_you_member_removed),
        memberRemovedYouFormat = stringResource(R.string.group_system_member_removed_you),
        memberRemovedYouPassive = stringResource(R.string.group_system_member_removed_you_passive),
        youMemberLeft = stringResource(R.string.group_system_you_member_left),
        youAdminAddedFormat = stringResource(R.string.group_system_you_admin_added),
        adminAddedYouFormat = stringResource(R.string.group_system_admin_added_you),
        adminAddedYouPassive = stringResource(R.string.group_system_admin_added_you_passive),
        youAdminRemovedFormat = stringResource(R.string.group_system_you_admin_removed),
        adminRemovedYouFormat = stringResource(R.string.group_system_admin_removed_you),
        adminRemovedYouPassive = stringResource(R.string.group_system_admin_removed_you_passive),
        youRenamedFormat = stringResource(R.string.group_system_you_renamed),
        youAvatarChanged = stringResource(R.string.group_system_you_avatar_changed),
        someone = stringResource(R.string.group_system_someone),
        fallback = stringResource(R.string.group_system_fallback),
    )

@Composable
private fun rememberConversationControllerCopy(): ConversationControllerCopy =
    ConversationControllerCopy(
        waitingForStream = stringResource(R.string.waiting_for_stream),
        streamFailedFormat = stringResource(R.string.stream_failed_format),
    )

@Composable
private fun rememberRelativeTimeCopy(): dev.ipf.darkmatter.core.RelativeTimeCopy {
    val future = stringResource(R.string.relative_time_future)
    val now = stringResource(R.string.relative_time_now)
    // Resolve plural-aware unit strings through getQuantityString so inflected
    // locales (Russian one/few/many, etc.) render the correct grammatical form
    // for the count. The Resources handle comes from the Compose LocalContext.
    val resources = LocalContext.current.resources
    return remember(future, now, resources) {
        dev.ipf.darkmatter.core.RelativeTimeCopy(
            future = future,
            now = now,
            minutes = { count ->
                resources.getQuantityString(R.plurals.relative_time_minutes, count, count)
            },
            hours = { count ->
                resources.getQuantityString(R.plurals.relative_time_hours, count, count)
            },
            days = { count ->
                resources.getQuantityString(R.plurals.relative_time_days, count, count)
            },
        )
    }
}

@Composable
private fun rememberedRelativeTime(epochSeconds: ULong): String =
    IdentityFormatter.relativeTime(
        epochSeconds,
        rememberRelativeTimeCopy(),
        LocalConfiguration.current.locales[0],
    )

// Clock time only (locale-aware short form, e.g. "3:28 PM" / "15:28"). The
// transcript groups messages under day separators, so a bubble footer doesn't
// need the date — just the time. The full date stays available in message
// details.
@Composable
private fun rememberedClockTime(epochSeconds: ULong): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochSeconds, locale) {
        if (epochSeconds == 0uL) {
            ""
        } else {
            Instant
                .ofEpochSecond(epochSeconds.toLong())
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        }
    }
}

private val AppThemeMode.labelRes: Int
    @StringRes
    get() =
        when (this) {
            AppThemeMode.System -> R.string.theme_system
            AppThemeMode.Light -> R.string.theme_light
            AppThemeMode.Dark -> R.string.theme_dark
            AppThemeMode.Amoled -> R.string.theme_amoled
        }

private val MediaQuality.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaQuality.Low -> R.string.media_quality_low
            MediaQuality.Standard -> R.string.media_quality_standard
            MediaQuality.High -> R.string.media_quality_high
            MediaQuality.Original -> R.string.media_quality_original
        }

private val MediaQuality.subtitleRes: Int
    @StringRes
    get() =
        when (this) {
            MediaQuality.Low -> R.string.media_quality_low_subtitle
            MediaQuality.Standard -> R.string.media_quality_standard_subtitle
            MediaQuality.High -> R.string.media_quality_high_subtitle
            MediaQuality.Original -> R.string.media_quality_original_subtitle
        }

private val EnterKeyBehavior.labelRes: Int
    @StringRes
    get() =
        when (this) {
            EnterKeyBehavior.SendMessage -> R.string.enter_key_behavior_send
            EnterKeyBehavior.NewLine -> R.string.enter_key_behavior_newline
        }

@Composable
fun DarkMatterApp(
    appState: DarkMatterAppState,
    inboundProfilePayload: String? = null,
    onProfilePayloadHandled: (String) -> Unit = {},
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Mutable bottom-chrome inset so screens further down the tree
    // (e.g. ConversationScreen) can push the snackbar above their
    // composer. Owned here so the host — which lives at this level —
    // can read it; child screens mutate via [LocalSnackbarBottomInset].
    val snackbarBottomInset = remember { mutableStateOf(0.dp) }
    val toast = appState.toast
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted) {
                appState.launchMutation { appState.enableDefaultNotificationsIfReady() }
            } else {
                appState.markDefaultNotificationsEnableAttempted()
            }
        }

    LaunchedEffect(Unit) {
        appState.bootstrap()
        // Stale share-temp janitor. Runs once per process start, off the
        // main thread because directory walks on cold cache can take a
        // moment. Files in `shared_media` from earlier sessions that
        // any external reader has long since finished with are deleted.
        withContext(Dispatchers.IO) {
            sweepStaleSharedMedia(context, SHARED_MEDIA_MAX_AGE_MS)
        }
    }
    LaunchedEffect(
        appState.phase,
        appState.activeAccountRef,
        appState.localNotificationPermissionGranted,
        appState.backgroundConnectionEnabled,
        appState.localNotificationSettings?.localNotificationsEnabled,
        appState.runtimeGeneration,
    ) {
        if (appState.phase != AppPhase.Ready) return@LaunchedEffect
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
        if (appState.shouldRequestDefaultNotificationPermission()) {
            appState.markDefaultNotificationPermissionPromptLaunched()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            appState.enableDefaultNotificationsIfReady()
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            snackbarHostState.showSnackbar(
                listOfNotNull(toast.title.resolve(context), toast.detail?.resolve(context)).joinToString("\n"),
            )
            appState.clearToast()
        }
    }
    LaunchedEffect(inboundProfilePayload, appState.phase) {
        val payload = inboundProfilePayload ?: return@LaunchedEffect
        if (appState.phase == AppPhase.Ready && appState.presentProfilePayload(payload)) {
            onProfilePayloadHandled(payload)
        }
    }

    // Privacy hardening (#405): when "Force incognito keyboard" is on, wrap the
    // whole app UI so every descendant text field requests incognito mode from
    // the IME (no learning / suggestion history / cloud sync of typed content).
    IncognitoKeyboardScope(enabled = appState.forceIncognitoKeyboard) {
        CompositionLocalProvider(LocalSnackbarBottomInset provides snackbarBottomInset) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp),
                snackbarHost = { DarkMatterSnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (val phase = appState.phase) {
                        AppPhase.Bootstrapping -> LoadingScreen()
                        AppPhase.Onboarding -> OnboardingScreen(appState)
                        AppPhase.Ready ->
                            MainShell(
                                appState = appState,
                                inboundNotificationTarget = inboundNotificationTarget,
                                onNotificationTargetHandled = onNotificationTargetHandled,
                            )
                        is AppPhase.Failed ->
                            FailureScreen(
                                message = phase.message,
                                onRetry = { appState.present(R.string.toast_restarting) },
                                onRetryAction = { appState.bootstrap() },
                            )
                    }
                }
            }
        }
    }
}

/**
 * Bottom inset the global snackbar host should reserve to clear any
 * persistent bottom chrome on the currently visible surface (e.g. the
 * conversation composer). Held as a `MutableState<Dp>` so screens
 * BELOW the host in the composition tree can push their chrome height
 * up to the parent-owned state — a plain CompositionLocal would only
 * flow values DOWN and couldn't reach the host. Default `0.dp` keeps
 * non-composer surfaces unaffected.
 *
 * See issue #122 (post-invite-accept toast overlapping message input).
 */
private val LocalSnackbarBottomInset =
    staticCompositionLocalOf<MutableState<Dp>> {
        // Safe fallback for hosts rendered outside the app shell —
        // androidTest fixtures, Compose previews, or any future caller
        // that uses [DarkMatterSnackbarHost] without going through
        // [DarkMatterApp]'s provider. The host reads `.value`, so the
        // factory must return a real MutableState rather than throw;
        // 0.dp matches the no-composer surface behaviour.
        mutableStateOf(0.dp)
    }

@Composable
fun DarkMatterSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    snackbar: @Composable (SnackbarData) -> Unit = { SwipeDismissibleSnackbar(it) },
) {
    val extraInset = LocalSnackbarBottomInset.current.value
    SnackbarHost(
        hostState = hostState,
        modifier =
            modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp + extraInset),
        snackbar = snackbar,
    )
}

/**
 * A [Snackbar] the user can swipe away horizontally (issue #352). Material 3's
 * [SnackbarHost] does not wire up swipe-to-dismiss itself, so a snackbar
 * otherwise sits until it times out or an action is tapped. Wrapping it in a
 * [SwipeToDismissBox] restores the standard Material gesture: a horizontal
 * swipe in either direction calls [SnackbarData.dismiss], which resolves the
 * host's suspending `showSnackbar` with [SnackbarResult.Dismissed] (never
 * [SnackbarResult.ActionPerformed]), so any actionable snackbar treats a
 * swipe-away as "ignored", not as tapping the action.
 *
 * The box is re-keyed per [SnackbarData] so its dismiss state resets for each
 * new snackbar; without that, a settled-away state would carry over and the
 * next snackbar would never render.
 */
@Composable
fun SwipeDismissibleSnackbar(data: SnackbarData) {
    val dismissState =
        key(data) {
            rememberSwipeToDismissBoxState(
                confirmValueChange = { target ->
                    if (target != SwipeToDismissBoxValue.Settled) {
                        data.dismiss()
                    }
                    // Let the box animate the snackbar off in the swipe
                    // direction; data.dismiss() pulls it from the host so it
                    // does not re-enter once the gesture settles.
                    true
                },
            )
        }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {},
    ) {
        Snackbar(snackbarData = data)
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FailureScreen(
    message: String,
    onRetry: () -> Unit,
    onRetryAction: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(44.dp))
            Text(stringResource(R.string.dark_matter_couldnt_start), style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    onRetry()
                    scope.launch { onRetryAction() }
                },
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.retry))
            }
        }
    }
}

@Composable
private fun OnboardingScreen(appState: DarkMatterAppState) {
    var identity by remember { mutableStateOf("") }
    var creatingIdentity by remember { mutableStateOf(false) }
    var signingInBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    OnboardingContent(
        identity = identity,
        creatingIdentity = creatingIdentity,
        signingInBusy = signingInBusy,
        onIdentityChange = { identity = it },
        onCreateIdentity = {
            creatingIdentity = true
            scope.launch {
                try {
                    appState.createIdentity()
                } finally {
                    creatingIdentity = false
                }
            }
        },
        onImportIdentity = { value ->
            signingInBusy = true
            scope.launch {
                try {
                    appState.importIdentity(value)
                } finally {
                    signingInBusy = false
                }
            }
        },
    )
}

@Composable
fun OnboardingContent(
    identity: String,
    creatingIdentity: Boolean,
    signingInBusy: Boolean,
    onIdentityChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onImportIdentity: (String) -> Unit,
) {
    var signingIn by remember { mutableStateOf(false) }
    val busy = creatingIdentity || signingInBusy
    val creatingIdentityDescription = stringResource(R.string.creating_identity)

    if (signingIn) {
        SignInContent(
            identity = identity,
            busy = signingInBusy,
            onIdentityChange = onIdentityChange,
            onBack = { signingIn = false },
            onSignIn = { onImportIdentity(identity.trim()) },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = stringResource(R.string.dark_matter_shield),
                modifier = Modifier.size(88.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.onboarding_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onCreateIdentity,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
            ) {
                if (creatingIdentity) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .semantics { contentDescription = creatingIdentityDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Key, contentDescription = null)
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (creatingIdentity) R.string.creating_identity_title else R.string.create_new_identity),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick = { signingIn = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SignInContent(
    identity: String,
    busy: Boolean,
    onIdentityChange: (String) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    WindowSecureFlag()
    val canSignIn = identity.isNotBlank() && !busy

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.sign_in_to_dark_matter), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.sign_in_secret_key_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = identity,
                onValueChange = onIdentityChange,
                label = { Text(stringResource(R.string.nostr_nsec)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                // Treat the nsec entry as a password: hides the value on
                // screen, and tells the IME not to retain it for suggestions,
                // autofill, or third-party clipboard inspection.
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            if (canSignIn) onSignIn()
                        },
                    ),
            )
        }
        Button(
            onClick = onSignIn,
            enabled = canSignIn,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Icon(Icons.Default.Person, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PublicIdentifierFieldTrailingAction(
    value: String,
    enabled: Boolean = true,
    allowHexPublicKey: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java)
        }
    val canOfferPaste = rememberClipboardCanOfferPaste(clipboardManager)

    when {
        value.isNotEmpty() -> {
            IconButton(onClick = { onValueChange("") }, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
            }
        }
        canOfferPaste -> {
            IconButton(
                onClick = {
                    val pasteValue =
                        ClipboardPasteAffordance.pasteValue(
                            clipboardManager?.primaryClipPlainText(context),
                            allowHexPublicKey,
                        )
                    if (pasteValue != null) onValueChange(pasteValue)
                },
                enabled = enabled,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
            }
        }
    }
}

@Composable
private fun rememberClipboardCanOfferPaste(clipboardManager: android.content.ClipboardManager?): Boolean {
    var canOfferPaste by remember(clipboardManager) {
        mutableStateOf(clipboardManager.canOfferTextPaste())
    }

    DisposableEffect(clipboardManager) {
        if (clipboardManager == null) {
            onDispose { }
        } else {
            val listener =
                android.content.ClipboardManager.OnPrimaryClipChangedListener {
                    canOfferPaste = clipboardManager.canOfferTextPaste()
                }
            clipboardManager.addPrimaryClipChangedListener(listener)
            canOfferPaste = clipboardManager.canOfferTextPaste()
            onDispose { clipboardManager.removePrimaryClipChangedListener(listener) }
        }
    }

    return canOfferPaste
}

private fun android.content.ClipboardManager?.canOfferTextPaste(): Boolean =
    this?.primaryClipDescription?.hasMimeType(ClipboardPasteAffordance.TEXT_MIME_TYPE_PATTERN) ?: false

private fun android.content.ClipboardManager.primaryClipPlainText(context: android.content.Context): String? =
    primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(
    appState: DarkMatterAppState,
    inboundNotificationTarget: NotificationTarget? = null,
    onNotificationTargetHandled: (NotificationTarget) -> Unit = {},
) {
    var sectionName by rememberSaveable { mutableStateOf(MainSection.Chats.name) }
    var settingsDetailName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChat by remember { mutableStateOf<ChatListItem?>(null) }
    // The open conversation must survive Activity recreation / process death
    // (issue #386): the in-app camera foregrounds an external activity that can
    // get the host process killed on low-memory devices, and on return a null
    // selection drops the user on the chat list and discards the staged capture
    // owned by ConversationScreen. Per AGENTS.md we must NOT serialize the
    // ChatListItem (it wraps FFI records) into the saved bundle — only the
    // lightweight group id hex is persisted, and selectedChat is re-resolved
    // from the live ChatsController once its list loads.
    var savedSelectedGroupIdHex by rememberSaveable { mutableStateOf<String?>(null) }
    // Reset on each new composition (plain remember, not Saveable): a fresh
    // composition after process death must be allowed exactly one restore
    // attempt. Once that attempt runs — or the user explicitly navigates —
    // the sync effect below owns the saved id and this stays true.
    var hasRestoredSelectedChat by remember { mutableStateOf(false) }
    // When a conversation is opened from a chat-list message-body search hit
    // (issue #290), this carries the matched message id so ConversationScreen
    // can scroll to and briefly highlight it on open. Null for every other
    // open path (row tap, notification, new-chat), which lands at the normal
    // unread/newest anchor.
    var selectedChatFocusMessageId by remember { mutableStateOf<String?>(null) }
    // True only when `selectedChat` was opened straight off a just-completed
    // New Chat / Create Group flow (issue #321), so ConversationScreen raises
    // the composer + keyboard once on entry. Plain `remember` (not
    // rememberSaveable) so it never survives process death. Reset on every
    // other open path and on back.
    var selectedChatJustCreated by remember { mutableStateOf(false) }
    val chatsController = remember(appState.activeAccountRef, appState.runtimeGeneration) { ChatsController(appState) }
    val section = runCatching { MainSection.valueOf(sectionName) }.getOrDefault(MainSection.Chats)
    val settingsDetail = settingsDetailName?.let { runCatching { SettingsDetail.valueOf(it) }.getOrNull() }

    DisposableEffect(chatsController) {
        appState.attachChatsController(chatsController)
        onDispose { appState.attachChatsController(null) }
    }

    LaunchedEffect(chatsController, appState.activeAccountRef, appState.runtimeGeneration) {
        chatsController.bind(appState.activeAccountRef)
    }

    // Freshness model for #6: the chat-list subscription stays bound while a
    // conversation is foregrounded (returning shows the current list instantly,
    // no reload), but its recompute is paused so account-wide list projection
    // doesn't contend with the conversation's own streams on the heaviest nav
    // path. The subscription keeps draining updates into the controller's maps
    // throughout; one recompute flushes on return.
    LaunchedEffect(chatsController, selectedChat == null) {
        chatsController.setChatListVisible(selectedChat == null)
    }

    // Notification tap routing: switch to the target account if needed, wait
    // for its chat list, then open the conversation — or fall back to the chat
    // list with a toast for a stale/removed target. Pure logic in
    // [resolveNotificationNav]; this effect just acts on each step and re-fires
    // as account/chat-list state changes.
    LaunchedEffect(
        inboundNotificationTarget,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.accounts,
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
    ) {
        val target = inboundNotificationTarget ?: return@LaunchedEffect
        if (appState.accounts.isEmpty()) return@LaunchedEffect // accounts not loaded yet
        val chatListReady =
            chatsController.boundAccountRef == target.accountRef &&
                !chatsController.isLoading
        // Archived conversations still exist — include them so an archived
        // group isn't treated as a missing conversation.
        val allChats = chatsController.items + chatsController.archivedItems
        val step =
            resolveNotificationNav(
                target = target,
                knownAccountRefs = appState.accounts.mapTo(mutableSetOf()) { it.label },
                activeAccountRef = appState.activeAccountRef,
                chatListReady = chatListReady,
                availableGroupIds = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex },
            )

        fun fallBackToChatList() {
            sectionName = MainSection.Chats.name
            settingsDetailName = null
            selectedChat = null
            // Notification routing never opens a just-created conversation, so
            // clear any leftover open-time state from a prior New Chat / Create
            // Group flow; otherwise a stale justCreated flag would auto-raise
            // the IME on the next opened conversation (issue #321 guard).
            selectedChatFocusMessageId = null
            selectedChatJustCreated = false
        }
        when (step) {
            is NotificationNavStep.SwitchAccount -> {
                // Close any conversation open under the previous account before
                // switching. Otherwise the destination conversation is built
                // mid-switch against a not-yet-settled chat-list projection and
                // anchors to a stale unread count / old messages. Clearing it
                // here makes tapping from inside a chat take the same clean path
                // as tapping after returning to the chat list.
                selectedChat = null
                selectedChatFocusMessageId = null
                selectedChatJustCreated = false
                appState.setActiveAccount(step.accountRef)
            }
            NotificationNavStep.AwaitChatList -> Unit // re-fires when list state settles
            is NotificationNavStep.OpenConversation -> {
                // Ensure we're on the Chats section so back-from-conversation
                // lands on the chat list, not whatever section was open.
                sectionName = MainSection.Chats.name
                settingsDetailName = null
                allChats
                    .firstOrNull { it.group.groupIdHex == step.groupIdHex }
                    ?.let {
                        selectedChatFocusMessageId = null
                        // Notification routing is never a just-created open, so
                        // clear any stale justCreated flag carried over from a
                        // prior New Chat / Create Group flow before showing the
                        // target conversation (issue #321 guard).
                        selectedChatJustCreated = false
                        selectedChat = it
                    }
                onNotificationTargetHandled(target)
            }
            NotificationNavStep.MissingAccount -> {
                fallBackToChatList()
                appState.present(R.string.toast_notification_account_unavailable)
                onNotificationTargetHandled(target)
            }
            NotificationNavStep.MissingConversation -> {
                fallBackToChatList()
                appState.present(R.string.toast_notification_conversation_unavailable)
                onNotificationTargetHandled(target)
            }
        }
    }

    // One-shot restore after process death: once the chat list for the active
    // account is loaded, re-resolve the saved group id back to a live
    // ChatListItem (issue #386). Runs before the sync effect can clobber the
    // saved id, and closes the restore window (hasRestoredSelectedChat) as soon
    // as the list is ready — whether or not a saved selection was present — so
    // it never overrides a later explicit user navigation.
    LaunchedEffect(
        chatsController,
        chatsController.boundAccountRef,
        chatsController.isLoading,
        chatsController.items,
    ) {
        if (hasRestoredSelectedChat) return@LaunchedEffect
        val chatListReady =
            chatsController.boundAccountRef == appState.activeAccountRef &&
                !chatsController.isLoading
        if (!chatListReady) return@LaunchedEffect
        val savedGroupId = savedSelectedGroupIdHex
        if (savedGroupId != null && selectedChat == null) {
            (chatsController.items + chatsController.archivedItems)
                .firstOrNull { it.group.groupIdHex == savedGroupId }
                ?.let {
                    selectedChatFocusMessageId = null
                    selectedChat = it
                }
        }
        hasRestoredSelectedChat = true
    }

    // Keep the saved (Saveable) group id in step with the live selection so a
    // recreation always restores the right conversation — and so returning to
    // the chat list (selectedChat == null) clears it, preventing a stale
    // restore. Gated until the one-shot restore window has closed so it can't
    // null out the saved id before restore reads it. See issue #386.
    LaunchedEffect(selectedChat?.group?.groupIdHex, hasRestoredSelectedChat) {
        if (hasRestoredSelectedChat) {
            savedSelectedGroupIdHex = selectedChat?.group?.groupIdHex
        }
    }

    DisposableEffect(selectedChat?.id) {
        appState.setActiveConversation(selectedChat?.group?.groupIdHex)
        onDispose {
            if (selectedChat != null) appState.setActiveConversation(null)
        }
    }

    appState.pendingProfileNpub?.let { npub ->
        ProfileSheet(
            appState = appState,
            npub = npub,
            onOpenGroup = { item, justCreated ->
                selectedChatFocusMessageId = null
                selectedChatJustCreated = justCreated
                selectedChat = item
                appState.clearPresentedProfile()
            },
            onDismiss = { appState.clearPresentedProfile() },
        )
    }

    if (selectedChat != null) {
        ConversationScreen(
            appState = appState,
            chat = selectedChat!!,
            focusMessageId = selectedChatFocusMessageId,
            justCreated = selectedChatJustCreated,
            onBack = {
                selectedChat = null
                selectedChatFocusMessageId = null
                selectedChatJustCreated = false
            },
        )
        return
    }

    when (section) {
        MainSection.Chats ->
            ChatsScreen(
                appState = appState,
                controller = chatsController,
                onOpenSettings = {
                    sectionName = MainSection.Settings.name
                    settingsDetailName = null
                },
                onOpenGroup = { item, focusMessageId, justCreated ->
                    selectedChatFocusMessageId = focusMessageId
                    selectedChatJustCreated = justCreated
                    selectedChat = item
                },
            )
        MainSection.Settings ->
            SettingsScreen(
                appState = appState,
                onBackToChats = {
                    sectionName = MainSection.Chats.name
                    settingsDetailName = null
                },
                onOpenDiagnostics = {
                    // Preserve `settingsDetailName` so backing out of
                    // Diagnostics returns to Security & Privacy (its only
                    // entry point) rather than the Settings home, restoring
                    // the breadcrumb the user walked in on (#412).
                    sectionName = MainSection.Diagnostics.name
                },
                detail = settingsDetail,
                onDetailChange = { settingsDetailName = it?.name },
            )
        MainSection.Diagnostics ->
            DiagnosticsScreen(
                appState = appState,
                onBack = {
                    // Leave `settingsDetailName` alone — it still holds the
                    // detail (Security & Privacy) the user opened Diagnostics
                    // from, so Settings re-enters that screen directly (#412).
                    sectionName = MainSection.Settings.name
                },
            )
    }
}

@Composable
fun AccountAvatarButton(
    title: String,
    seed: String,
    pictureUrl: String?,
    size: Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openSettingsDescription = stringResource(R.string.open_settings)
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .semantics { contentDescription = openSettingsDescription },
    ) {
        Avatar(
            title = title,
            seed = seed,
            size = size,
            pictureUrl = pictureUrl,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatsScreen(
    appState: DarkMatterAppState,
    controller: ChatsController,
    onOpenSettings: () -> Unit,
    // (chat, focusMessageId, justCreated): focusMessageId is non-null only when
    // the row was a message-body search hit (issue #290); the conversation then
    // scrolls to that message on open. justCreated is true only when the chat
    // was just created by the New Chat / Create Group flow (issue #321), which
    // opens it with the composer focused + keyboard up.
    onOpenGroup: (ChatListItem, String?, Boolean) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var showNewChat by remember { mutableStateOf(false) }
    var newChatTitle by remember { mutableStateOf(R.string.new_chat) }
    var newChatDirect by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var quickActionsExpanded by remember { mutableStateOf(false) }
    // Search expand/collapse + live query. The search input is anchored in
    // the top bar; tapping the magnifier swaps the chrome (account avatar
    // + nav icons) for a TextField that filters in real time on title +
    // last-message preview. Closing the search clears the query so the
    // next open starts fresh.
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Async message-body search results (issue #290), keyed by group id. The
    // title/preview match in `applyChatListSearchAndFilter` is synchronous;
    // body matching has to query each conversation's local timeline via the
    // `timelineMessages` FFI, so it lands here a debounce later. A row uses
    // its entry (if any) to render the highlighted snippet line and to scroll
    // to the matched message on tap-through.
    var bodyMatches by remember { mutableStateOf<Map<String, MessageBodyMatch>>(emptyMap()) }
    // Resolution state for a pasted Nostr identifier in the search field (#344).
    // An npub resolves synchronously; a NIP-05 address resolves over the network
    // (loading → resolved/failed). Plain-text queries stay [None] and the list
    // filters exactly as before.
    var identifierResolution by remember { mutableStateOf<IdentifierResolution>(IdentifierResolution.None) }
    var filter by remember { mutableStateOf(ChatListFilter.All) }
    // The Archived filter is a view switch, not a row predicate: it swaps the
    // source list to archived chats (replacing the old dedicated Archived row).
    val showArchived = filter == ChatListFilter.Archived
    val searchFocusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Lift the app-level toast host (it flows through DarkMatterSnackbarHost,
    // which reads LocalSnackbarBottomInset) above the quick-action FAB so a
    // toast — e.g. the archive confirmation — can't sit over the FAB and
    // intercept its taps for the toast's duration — issue #352. Resets to
    // zero on dispose so other surfaces aren't affected, mirroring
    // ConversationScreen's composer lift (#122).
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    DisposableEffect(Unit) {
        snackbarBottomInset.value = FAB_SNACKBAR_INSET
        onDispose { snackbarBottomInset.value = 0.dp }
    }

    LaunchedEffect(showArchived) {
        if (showArchived) quickActionsExpanded = false
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            // LaunchedEffect runs after composition + layout, so the
            // TextField node is already attached by the time we call
            // requestFocus — no explicit frame deferral needed.
            searchFocusRequester.requestFocus()
        } else {
            searchQuery = ""
        }
    }
    // Back from chat-list search unwinds the search state — close the field and
    // restore the normal top bar (which drops focus and the IME) — instead of
    // exiting the app, matching the Settings/Diagnostics back behavior (#121,
    // #149). See #320.
    BackHandler(enabled = searchOpen) {
        searchOpen = false
    }

    // System voice-input integration for the dictation button. The recognizer
    // is invoked via the standard `ACTION_RECOGNIZE_SPEECH` intent; on a
    // successful capture we paste the spoken text straight into the search
    // field. No permissions to declare ourselves — the system dialog handles
    // mic access on the user's behalf.
    val voiceSearchLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val recognized =
                    result.data
                        ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                        ?.firstOrNull()
                        ?.trim()
                if (!recognized.isNullOrBlank()) {
                    searchQuery = recognized
                }
            }
        }

    val sourceList = if (showArchived) controller.archivedItems else controller.items
    // Subscribing read of the profile-cache revision so the filter
    // re-runs when a DM peer's display name resolves — the title
    // projection inside `applyChatListSearchAndFilter` reads
    // `appState.chatMemberTitle(...)`, but that read happens from a
    // `remember` block where Compose's snapshot system doesn't track
    // state. Keying on `profileRev` re-fires the filter when the
    // backing presentation cache invalidates.
    val profileRev = appState.profileRevisionForCompose
    // Debounced async message-body search. Re-runs whenever the query or the
    // source list changes; the trimmed query is the unit of work, so edits
    // that don't change the trimmed needle (e.g. trailing spaces) don't
    // re-query. A blank query clears results immediately (no debounce) so the
    // snippet lines vanish the moment the field is emptied. The 275 ms wait
    // sits inside the existing chat-list input-debounce band (250–300 ms);
    // each keystroke cancels the prior in-flight search via the LaunchedEffect
    // key change, so only the settled query hits the FFI.
    val trimmedQuery = searchQuery.trim()
    LaunchedEffect(trimmedQuery, sourceList) {
        if (trimmedQuery.isEmpty()) {
            bodyMatches = emptyMap()
            return@LaunchedEffect
        }
        delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
        bodyMatches = controller.searchMessageBodies(sourceList, trimmedQuery)
    }
    // Resolve a pasted Nostr identifier in the search field (#344). An npub is
    // validated (and normalized) via the FFI key parser — no network. A NIP-05
    // address shows a loading state, then a `/.well-known/nostr.json` lookup
    // resolves it to a pubkey. Each keystroke re-keys this effect, cancelling
    // any in-flight lookup, and a plain-text query resets to None so the list
    // filters as before. The resolve result drives ChatListIdentifierResult.
    LaunchedEffect(trimmedQuery) {
        when (val id = ChatListIdentifierSearch.classify(trimmedQuery)) {
            null -> identifierResolution = IdentifierResolution.None
            is ChatListIdentifierSearch.Identifier.Npub -> {
                // accountIdHex validates the bech32 key (rejecting a checksum
                // failure that the cheap format check in ProfileLink lets
                // through); a non-null result means the npub is real.
                val hex = appState.accountIdHex(id.npub)
                identifierResolution =
                    if (hex != null) {
                        IdentifierResolution.Resolved(id.npub)
                    } else {
                        IdentifierResolution.Failed(R.string.chat_list_search_invalid_npub)
                    }
            }
            is ChatListIdentifierSearch.Identifier.Nip05 -> {
                identifierResolution = IdentifierResolution.Resolving
                // Small debounce so a half-typed address (e.g. mid-domain)
                // doesn't fire a lookup on every keystroke; the effect re-keys
                // and cancels the prior attempt as the user keeps typing.
                delay(CHAT_LIST_SEARCH_DEBOUNCE_MS)
                val hex = Nip05Resolver.resolve(id.identifier)
                val npub = hex?.let { appState.npub(it) }
                identifierResolution =
                    if (npub != null) {
                        IdentifierResolution.Resolved(npub)
                    } else {
                        IdentifierResolution.Failed(
                            R.string.chat_list_search_nip05_not_found,
                            id.identifier,
                        )
                    }
            }
        }
    }
    val visibleItems =
        remember(sourceList, searchQuery, filter, groupTitleCopy, profileRev, bodyMatches) {
            applyChatListSearchAndFilter(
                sourceList,
                searchQuery,
                filter,
                appState,
                groupTitleCopy,
                bodyMatchGroupIds = bodyMatches.keys,
            )
        }
    // Hoisted list state so the jump-to-top FAB (issue #413) can both read the
    // scroll position for its visibility predicate and drive the animated
    // scroll-to-top on tap. Wrapped in key(showArchived) so switching the
    // active vs. archived view starts a fresh state at the top rather than
    // carrying a stale scroll anchor across the source-list swap.
    val chatListState = key(showArchived) { rememberLazyListState() }
    // Hysteresis for the jump-to-top button: show once the user is ≥ 5 rows
    // deep, hide only after they climb back to ≤ 2. The 3–4 dead band keeps a
    // quick scroll wiggle near the threshold from toggling the button (issue
    // #413). derivedStateOf reads the previous decision so the band is sticky.
    var jumpToTopVisible by remember(showArchived) { mutableStateOf(false) }
    // Keyed on chatListState: switching active/archived recreates the list
    // state under key(showArchived) above, so the derived read must re-bind to
    // the new state or the FAB would keep observing the disposed one and reflect
    // a stale scroll position after the toggle (issue #413 review).
    val firstVisibleIndex by remember(chatListState) { derivedStateOf { chatListState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex) {
        if (firstVisibleIndex >= CHAT_LIST_JUMP_TO_TOP_SHOW_INDEX) {
            jumpToTopVisible = true
        } else if (firstVisibleIndex <= CHAT_LIST_JUMP_TO_TOP_HIDE_INDEX) {
            jumpToTopVisible = false
        }
    }
    val archivedUnreadCount =
        remember(controller.archivedItems) {
            controller.archivedItems.count { it.hasUnread }
        }
    // Leave the archived view if its last chat is unarchived — the chip is then
    // hidden, so don't strand the selection on it.
    LaunchedEffect(controller.archivedItems.isEmpty(), filter) {
        if (filter == ChatListFilter.Archived && controller.archivedItems.isEmpty()) filter = ChatListFilter.All
    }

    Scaffold(
        topBar = {
            ChatListTopBar(
                appState = appState,
                searchOpen = searchOpen,
                searchQuery = searchQuery,
                searchFocusRequester = searchFocusRequester,
                onSearchQueryChange = { searchQuery = it },
                onSearchOpen = { searchOpen = true },
                onSearchClose = { searchOpen = false },
                onMic = {
                    val intent =
                        android.content
                            .Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(
                                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                            )
                    // ActivityNotFoundException fires on devices without
                    // any RecognizerIntent handler (rare on consumer
                    // hardware; possible on AOSP forks or kiosk-mode
                    // devices). Surface that as a toast instead of
                    // swallowing — otherwise the mic tap is silent.
                    try {
                        voiceSearchLauncher.launch(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        appState.present(R.string.chat_list_voice_unavailable)
                    }
                },
                onOpenSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            if (!searchOpen) {
                QuickActionFabMenu(
                    expanded = quickActionsExpanded,
                    onExpandedChange = { quickActionsExpanded = it },
                    onScanQr = { showScanner = true },
                    onNewChat = {
                        newChatTitle = R.string.new_chat
                        newChatDirect = true
                        showNewChat = true
                    },
                    onCreateGroup = {
                        newChatTitle = R.string.new_group
                        newChatDirect = false
                        showNewChat = true
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Filter chips visible whenever there's content to filter — both
            // in the active and archived lists. They're sticky above the
            // list rather than sticky inside the LazyColumn so they survive
            // an empty-state swap without flicker.
            if (controller.items.isNotEmpty() || controller.archivedItems.isNotEmpty()) {
                ChatListFilterChips(
                    filter = filter,
                    onChange = { filter = it },
                    activeUnreadCount = controller.items.count { it.hasUnread },
                    hasArchived = controller.archivedItems.isNotEmpty(),
                    archivedUnreadCount = archivedUnreadCount,
                )
            }
            // Pasted-identifier resolution result (#344). Sits above the list so
            // a recognized npub / NIP-05 surfaces a tappable result while
            // plain-text queries below keep filtering the list. When the active
            // account already has a 1:1 chat with the resolved key, the result
            // IS that chat row and tapping it opens the conversation directly
            // (issue #344 step 2); otherwise it's an "open profile" affordance
            // routing into the shared profile sheet, which handles the
            // Start-chat path (step 3). Hidden for plain-text queries.
            ChatListIdentifierResult(
                resolution = identifierResolution,
                appState = appState,
                existingDirectChat = { npub -> appState.existingDirectChat(npub) },
                onOpenChat = { chat ->
                    searchOpen = false
                    onOpenGroup(chat, null, false)
                },
                onOpenProfile = { npub ->
                    searchOpen = false
                    appState.presentProfile(npub)
                },
            )
            Box(Modifier.fillMaxSize()) {
                when {
                    controller.isLoading && sourceList.isEmpty() -> LoadingScreen()
                    controller.error != null ->
                        ErrorContent(
                            stringResource(R.string.couldnt_load_chats),
                            controller.error.orEmpty(),
                        )
                    sourceList.isEmpty() && showArchived ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                stringResource(R.string.no_archived_chats),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    sourceList.isEmpty() ->
                        EmptyChats(onCreate = {
                            newChatTitle = R.string.new_chat
                            newChatDirect = true
                            showNewChat = true
                        })
                    visibleItems.isEmpty() && identifierResolution != IdentifierResolution.None -> Unit
                    visibleItems.isEmpty() ->
                        ChatListNoResults(
                            query = searchQuery.trim(),
                            filter = filter,
                        )
                    else ->
                        LazyColumn(Modifier.fillMaxSize(), state = chatListState) {
                            items(visibleItems, key = { it.id }) { item ->
                                // Body-match snippet + tap-to-message focus are
                                // for rows that matched ONLY on an older message
                                // body. A row that also matches its title or
                                // current preview keeps the normal single-line
                                // layout and a normal conversation open, so drop
                                // its body match here (issue #290 contract). The
                                // title/preview test mirrors the synchronous
                                // match in applyChatListSearchAndFilter so the
                                // classification can't drift from the filter.
                                val rawBodyMatch = bodyMatches[item.id]
                                val bodyMatch =
                                    rawBodyMatch?.takeUnless {
                                        ChatListMessageSearch.titleOrPreviewMatches(
                                            displayTitle = chatListItemDisplayTitle(item, appState, groupTitleCopy),
                                            previewText = item.projectedPreviewText(),
                                            ciNeedle = trimmedQuery.lowercase(),
                                            description = item.group.description,
                                        )
                                    }
                                ChatRowWithMenu(
                                    item = item,
                                    appState = appState,
                                    bodyMatch = bodyMatch,
                                    onOpen = { onOpenGroup(item, bodyMatch?.messageIdHex, false) },
                                    onMenuArchiveToggle = {
                                        // Durable process-lifetime scope, not the
                                        // composable's: archiving drops the row out of
                                        // the active list, and a cancelled mutation would
                                        // skip the post-await confirmation toast.
                                        appState.launchMutation {
                                            controller.setArchived(item.group.groupIdHex, !item.group.archived)
                                        }
                                    },
                                    onMarkRead = {
                                        appState.launchMutation { controller.markAllRead(item) }
                                    },
                                )
                            }
                        }
                }
                // Jump-to-top FAB (issue #413). Overlaid on the chat-list Box
                // (a sibling of the list `when`) and aligned bottom-end so it
                // floats above the rows. Fades + slides in from the bottom-right
                // when the user is scrolled deep, hides near the top — the
                // `jumpToTopVisible` hysteresis above debounces threshold
                // jitter. Lifted by FAB_SNACKBAR_INSET (the same clearance the
                // snackbar host uses, #352/#356) on top of the navigation-bar
                // inset so it clears the quick-action FAB and the nav bar.
                // Fully-qualified so Kotlin binds the top-level overload rather
                // than the ColumnScope extension (the outer Column is also an
                // implicit receiver here) — the bottom-end alignment is carried
                // by the Modifier in the BoxScope, not a scoped AnimatedVisibility.
                androidx.compose.animation.AnimatedVisibility(
                    visible = jumpToTopVisible,
                    enter = fadeIn() + slideInHorizontally { it / 2 },
                    exit = fadeOut() + slideOutHorizontally { it / 2 },
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            // end = 24dp (not 16) so this 40dp small FAB's centre
                            // lines up with the 56dp quick-action FAB above it,
                            // which the Scaffold insets 16dp from the edge:
                            // 24 + 40/2 == 16 + 56/2 (#451).
                            .padding(end = 24.dp, bottom = FAB_SNACKBAR_INSET),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch {
                                // For a very deep scroll, animating every row to
                                // the top is a multi-second crawl. Snap to a
                                // near-top index first, then animate the final
                                // viewport so the motion stays short and smooth
                                // regardless of how far down the user was.
                                if (chatListState.firstVisibleItemIndex > CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX) {
                                    chatListState.scrollToItem(CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX)
                                }
                                chatListState.animateScrollToItem(0)
                            }
                        },
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = stringResource(R.string.scroll_to_top),
                        )
                    }
                }
                // Tap-absorbing backdrop while the quick-action FAB menu is open:
                // without it a tap outside the menu falls through to the chat row
                // beneath and opens the wrong chat (#452). Transparent (no dim) —
                // it only consumes the gesture and collapses the menu. The menu
                // sits in the Scaffold FAB slot above this Box, so its items stay
                // tappable; this also blocks the jump-to-top FAB while open.
                if (quickActionsExpanded) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // Consume the press in the Initial pass so the
                                // chat row's clickable (Main pass) never sees an
                                // unconsumed down — a Main-pass detectTapGestures
                                // here loses the arbitration to the row and the
                                // tap falls through. Collapsing on down also makes
                                // the dismissal feel immediate.
                                awaitEachGesture {
                                    awaitFirstDown(pass = PointerEventPass.Initial).consume()
                                    quickActionsExpanded = false
                                }
                            },
                    )
                }
            }
        }
    }

    if (showNewChat) {
        NewChatSheet(
            appState = appState,
            titleRes = newChatTitle,
            directMessage = newChatDirect,
            existingDirectChat = { pubkey -> appState.existingDirectChat(pubkey) },
            onOpenConversation = { chat, justCreated -> onOpenGroup(chat, null, justCreated) },
            onDismiss = { showNewChat = false },
        )
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    appState.present(R.string.error_not_dark_matter_profile_qr)
                } else {
                    appState.presentProfile(scanned.npub)
                }
            },
        )
    }
}

/** Chat-list filter state. `Archived` swaps the source list to archived chats
 *  rather than predicate-filtering the active one. */
private enum class ChatListFilter { All, Unread, Groups, Archived }

/**
 * Resolution state for a chat-list search query that parsed as a Nostr
 * identifier (npub / NIP-05) — issue #344. An npub resolves synchronously to
 * its key; a NIP-05 address has to be looked up over the network, so it passes
 * through [Resolving] before landing on [Resolved] or [Failed]. [None] is the
 * ordinary plain-text-query case where the chat list filters as before.
 */
private sealed interface IdentifierResolution {
    data object None : IdentifierResolution

    data object Resolving : IdentifierResolution

    /** Resolved to a profile; [npub] is ready to hand to the profile sheet. */
    data class Resolved(
        val npub: String,
    ) : IdentifierResolution

    /** Resolution failed; [messageRes] is the inline error to show. */
    data class Failed(
        @StringRes val messageRes: Int,
        val arg: String? = null,
    ) : IdentifierResolution
}

/**
 * Inline chat-list search result for a pasted Nostr identifier (#344). Renders
 * the resolve lifecycle: a spinner while a NIP-05 lookup is in flight; once
 * resolved, either the existing 1:1 chat row (when the active account already
 * has a DM with the resolved key — tapping opens that conversation directly,
 * issue #344 step 2) or an "open profile" row (no existing chat — tapping
 * routes into the shared profile sheet, which surfaces the Start-chat
 * affordance, step 3); or an inline error on failure. Plain-text queries never
 * reach here — they keep filtering the list as before.
 */
@Composable
private fun ChatListIdentifierResult(
    resolution: IdentifierResolution,
    appState: DarkMatterAppState,
    existingDirectChat: (String) -> ChatListItem?,
    onOpenChat: (ChatListItem) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    when (resolution) {
        IdentifierResolution.None -> Unit
        IdentifierResolution.Resolving ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(R.string.chat_list_search_resolving),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        is IdentifierResolution.Resolved -> {
            // Surface the existing 1:1 chat as the top result so a single tap
            // opens the conversation (issue #344 step 2). existingDirectChat
            // reads snapshot-backed projected items, so this recomposes if the
            // chat list lands the DM after the npub already resolved. Only when
            // there is no existing DM do we fall back to the profile preview /
            // Start-chat path (step 3).
            val existing = existingDirectChat(resolution.npub)
            if (existing != null) {
                ChatRow(
                    item = existing,
                    appState = appState,
                    onClick = { onOpenChat(existing) },
                )
            } else {
                ListItem(
                    modifier =
                        Modifier.clickable(role = Role.Button) { onOpenProfile(resolution.npub) },
                    leadingContent = {
                        Avatar(
                            title = IdentityFormatter.short(resolution.npub),
                            seed = resolution.npub,
                            size = 40.dp,
                            pictureUrl = null,
                        )
                    },
                    headlineContent = {
                        Text(
                            stringResource(R.string.chat_list_search_open_profile),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            IdentityFormatter.short(resolution.npub, prefix = 12, suffix = 8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    },
                )
            }
        }
        is IdentifierResolution.Failed ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    resolution.arg
                        ?.let { stringResource(resolution.messageRes, it) }
                        ?: stringResource(resolution.messageRes),
                    color = MaterialTheme.colorScheme.error,
                )
            }
    }
}

private fun applyChatListSearchAndFilter(
    source: List<ChatListItem>,
    rawQuery: String,
    filter: ChatListFilter,
    appState: DarkMatterAppState,
    titleCopy: GroupTitleCopy,
    bodyMatchGroupIds: Set<String> = emptySet(),
): List<ChatListItem> {
    val byFilter =
        when (filter) {
            // Archived swaps the source list upstream, so there's nothing extra
            // to filter here — show every archived chat.
            ChatListFilter.All, ChatListFilter.Archived -> source
            ChatListFilter.Unread -> source.filter { it.hasUnread }
            // A named two-member chat is a group, not a DM — classify by type,
            // not raw headcount, so it isn't wrongly hidden here.
            ChatListFilter.Groups -> source.filter { !GroupProjector.isDm(it.memberCount, it.group.name) }
        }
    val needle = rawQuery.trim()
    if (needle.isEmpty()) return byFilter
    val ciNeedle = needle.lowercase()
    return byFilter.filter { item ->
        // Match against the SAME title the user sees in the row, not the
        // raw group.name. For DMs and other unnamed chats, group.name is
        // blank and the visible title is projected from the other
        // member's profile — without this projection the search misses
        // direct messages by their displayed name.
        val title = chatListItemDisplayTitle(item, appState, titleCopy).lowercase()
        if (title.contains(ciNeedle)) return@filter true
        val preview = item.projectedPreviewText().lowercase()
        if (preview.contains(ciNeedle)) return@filter true
        // Group description matches (issue #388): descriptions hold the
        // context users put there to find a group later ("research workgroup",
        // "family planning"), so they should surface the row even when the
        // title and preview don't mention the needle. Same lowercase +
        // substring containment as title/preview.
        val description = item.group.description.lowercase()
        if (description.isNotEmpty() && description.contains(ciNeedle)) return@filter true
        // Message-body matches (issue #290): the async per-chat search
        // (ChatsController.searchMessageBodies) found the needle inside this
        // conversation's local timeline even though it isn't in the title or
        // preview line. The matched message id + highlighted snippet ride a
        // separate map keyed by group id; here we only need to know the chat
        // qualifies so it joins the result set. Body matches still pass
        // through the chip filter above, so an Unread/Groups filter narrows
        // them the same way it narrows title/preview hits.
        item.group.groupIdHex in bodyMatchGroupIds
    }
}

/**
 * Display title shown for a chat-list row. Shared between `ChatRow` (the
 * visible label) and `applyChatListSearchAndFilter` (the searchable
 * label) so a typed query always matches what the user sees on screen.
 *
 * For NAMED groups (`group.name` non-blank) we honour whatever the
 * projection's title field carries — it's a localized rendering of the
 * group name and may differ from the raw `group.name`.
 *
 * For UNNAMED groups we deliberately ignore `projectedTitle`: the
 * upstream projection emits the group id hex there when no name is set,
 * and using it would leak hex into the UI. Instead we route through
 * `GroupProjector.displayTitle`, which falls back to (in order)
 * inviter-welcomer copy for pending invites, the other member's title
 * for two-member groups, the "Group of N people" copy for ≥3-member
 * groups, and finally a short hex if no member data has resolved yet.
 * The local fallback then live-updates once `ChatsController` populates
 * the member cache from the `groupMembers` FFI.
 */
private fun chatListItemDisplayTitle(
    item: ChatListItem,
    appState: DarkMatterAppState,
    copy: GroupTitleCopy,
): String {
    if (item.group.name.isNotBlank()) {
        return item.projectedTitle ?: item.group.name
    }
    return GroupProjector.displayTitle(
        group = item.group,
        otherMemberAccount = item.otherMemberAccount,
        memberCount = item.memberCount,
        memberTitle = { appState.chatMemberTitle(it) },
        copy = copy,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListTopBar(
    appState: DarkMatterAppState,
    searchOpen: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onSearchQueryChange: (String) -> Unit,
    onSearchOpen: () -> Unit,
    onSearchClose: () -> Unit,
    onMic: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            when {
                searchOpen ->
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_list_search_hint)) },
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Search,
                            ),
                    )
                else -> Unit
            }
        },
        navigationIcon = {
            when {
                searchOpen ->
                    IconButton(onClick = onSearchClose) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                else -> {
                    val active = appState.activeAccount
                    AccountAvatarButton(
                        title = active?.let { appState.displayName(it.accountIdHex) } ?: stringResource(R.string.app_name),
                        seed = active?.accountIdHex ?: "darkmatter",
                        pictureUrl = active?.let { appState.avatarUrl(it.accountIdHex) },
                        onClick = onOpenSettings,
                    )
                }
            }
        },
        actions = {
            if (searchOpen) {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.chat_list_search_clear),
                        )
                    }
                }
                IconButton(onClick = onMic) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = stringResource(R.string.chat_list_search_voice),
                    )
                }
            } else {
                IconButton(onClick = onSearchOpen) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.chat_list_search_open),
                    )
                }
            }
        },
    )
}

// Roomier than Material's default menu-item padding so conversation overflow
// rows read as full lines of text rather than compact cells.
private val conversationMenuItemPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)

/**
 * Inline top-bar search for a single conversation (#292): a back arrow plus an
 * auto-focused field (✕ clears). The result count and previous/next match
 * navigation live on the bottom bar above the keyboard
 * ([ConversationSearchNavBar]); the IME "search" action steps to the next
 * match so the on-screen keyboard alone can walk the results.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
    onSearchAction: () -> Unit,
    focusRequester: FocusRequester,
) {
    val hasQuery = query.isNotBlank()
    TopAppBar(
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.conversation_search_hint)) },
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                // Clear sits inline in the field; match navigation and the
                // result count live on the bottom bar above the keyboard.
                trailingIcon = {
                    if (hasQuery) {
                        IconButton(onClick = onClear) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.conversation_search_clear),
                            )
                        }
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Search,
                    ),
                keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.conversation_search_close),
                )
            }
        },
    )
}

/**
 * Search match navigation pinned above the keyboard while in-chat search is
 * open: a centered result count with previous/next steppers on the trailing
 * edge. Lives in the conversation `bottomBar` slot in place of the composer,
 * mirroring the composer's `navigationBarsPadding().imePadding()` so it rides
 * up with the soft keyboard rather than hiding behind it.
 */
@Composable
private fun ConversationSearchNavBar(
    matchCount: Int,
    activeIndex: Int,
    hasQuery: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val navEnabled = matchCount > 0
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text =
                    when {
                        !hasQuery -> ""
                        matchCount > 0 ->
                            stringResource(
                                R.string.conversation_search_match_count,
                                activeIndex + 1,
                                matchCount,
                            )
                        else -> stringResource(R.string.conversation_search_no_matches)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onPrev, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.conversation_search_prev),
                )
            }
            IconButton(onClick = onNext, enabled = navEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.conversation_search_next),
                )
            }
        }
    }
}

@Composable
private fun ChatListFilterChips(
    filter: ChatListFilter,
    onChange: (ChatListFilter) -> Unit,
    activeUnreadCount: Int,
    hasArchived: Boolean,
    archivedUnreadCount: Int,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChatListFilterChip(filter, ChatListFilter.All, R.string.chat_list_filter_all, onChange)
        ChatListFilterChip(
            filter,
            ChatListFilter.Unread,
            R.string.chat_list_filter_unread,
            onChange,
            trailingCount = activeUnreadCount,
        )
        ChatListFilterChip(filter, ChatListFilter.Groups, R.string.chat_list_filter_groups, onChange)
        if (hasArchived || filter == ChatListFilter.Archived) {
            ChatListFilterChip(
                filter,
                ChatListFilter.Archived,
                R.string.archived,
                onChange,
                trailingCount = archivedUnreadCount,
            )
        }
    }
}

@Composable
private fun ChatListFilterChip(
    current: ChatListFilter,
    value: ChatListFilter,
    @StringRes labelRes: Int,
    onChange: (ChatListFilter) -> Unit,
    enabled: Boolean = true,
    trailingCount: Int = 0,
) {
    val selected = current == value
    FilterChip(
        selected = selected,
        onClick = { onChange(value) },
        enabled = enabled,
        label = { Text(stringResource(labelRes)) },
        trailingIcon =
            if (trailingCount > 0) {
                {
                    Text(
                        text = if (trailingCount > 99) "99+" else trailingCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                null
            },
        shape = RoundedCornerShape(percent = 50),
        border = null,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
    )
}

@Composable
private fun ChatListNoResults(
    query: String,
    filter: ChatListFilter,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            val copy =
                when {
                    query.isNotEmpty() -> stringResource(R.string.chat_list_no_results_for, query)
                    filter == ChatListFilter.Unread -> stringResource(R.string.chat_list_no_unread)
                    else -> stringResource(R.string.no_chats_yet)
                }
            Text(
                copy,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Chat row with a long-press action menu (Archive / Unarchive,
 * Mark-as-read).
 *
 * Archiving is reached only through this menu and the in-conversation
 * menu — there is no swipe-to-archive gesture on the chat list. The
 * gesture was removed because it kept firing accidentally during
 * vertical scrolls and successive hardening passes never made it
 * reliably intentional. [onMenuArchiveToggle] runs the archive toggle,
 * whose controller call shows the plain confirmation toast.
 */
@Composable
private fun ChatRowWithMenu(
    item: ChatListItem,
    appState: DarkMatterAppState,
    onOpen: () -> Unit,
    onMenuArchiveToggle: () -> Unit,
    onMarkRead: () -> Unit,
    // Non-null when this row matched the chat-list search on a message body
    // (issue #290); drives the highlighted snippet line under the row.
    bodyMatch: MessageBodyMatch? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ChatRow(
            item = item,
            appState = appState,
            onClick = onOpen,
            onLongClick = { menuOpen = true },
            bodyMatch = bodyMatch,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (item.group.archived) {
                                R.string.chat_row_action_unarchive
                            } else {
                                R.string.chat_row_action_archive
                            },
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        if (item.group.archived) Icons.Default.Unarchive else Icons.Default.Archive,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuOpen = false
                    onMenuArchiveToggle()
                },
            )
            if (item.hasUnread) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_row_action_mark_read)) },
                    leadingIcon = { Icon(Icons.Default.MarkChatRead, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onMarkRead()
                    },
                )
            }
            // Leave-group is reachable from the conversation Details
            // screen, which carries the sole-admin guard + confirmation
            // context in one place. The chat-list menu stays focused on
            // archive + mark-as-read for now; Pin / Mute slot in here
            // once the FFI exposes them.
        }
    }
}

@Composable
fun QuickActionFabMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onNewChat: () -> Unit,
    onCreateGroup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    fun runAction(action: () -> Unit) {
        onExpandedChange(false)
        action()
    }

    BackHandler(enabled = expanded) {
        onExpandedChange(false)
    }

    FloatingActionButtonMenu(
        expanded = expanded,
        modifier = modifier,
        button = {
            ToggleFloatingActionButton(
                checked = expanded,
                onCheckedChange = { onExpandedChange(it) },
            ) {
                val imageVector by remember {
                    derivedStateOf {
                        if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add
                    }
                }
                Icon(
                    painter = rememberVectorPainter(imageVector),
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.close_quick_actions else R.string.open_quick_actions,
                        ),
                    modifier = Modifier.animateIcon({ checkedProgress }),
                )
            }
        },
    ) {
        FloatingActionButtonMenuItem(
            onClick = { runAction(onNewChat) },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(20.dp)) },
            text = { Text(stringResource(R.string.new_chat), style = MaterialTheme.typography.labelLarge) },
        )
        FloatingActionButtonMenuItem(
            onClick = { runAction(onCreateGroup) },
            icon = { Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(20.dp)) },
            text = { Text(stringResource(R.string.new_group), style = MaterialTheme.typography.labelLarge) },
        )
        FloatingActionButtonMenuItem(
            onClick = { runAction(onScanQr) },
            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp)) },
            text = { Text(stringResource(R.string.scan_qr_code), style = MaterialTheme.typography.labelLarge) },
        )
    }
}

/**
 * Render a [SnippetHighlight] as an [AnnotatedString] with the matched needle
 * range styled by [highlight]. The range is guaranteed valid by
 * `ChatListMessageSearch.buildSnippet`, but we clamp defensively so a future
 * change to the snippet builder can never throw here on the chat-list hot path.
 */
private fun highlightedSnippet(
    snippet: SnippetHighlight,
    highlight: SpanStyle,
): AnnotatedString {
    val text = snippet.text
    val start = snippet.highlightStart.coerceIn(0, text.length)
    val end = snippet.highlightEnd.coerceIn(start, text.length)
    return buildAnnotatedString {
        append(text)
        if (end > start) addStyle(highlight, start, end)
    }
}

@Composable
private fun ChatRow(
    item: ChatListItem,
    appState: DarkMatterAppState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // Message-body search hit for this row (issue #290): when present, a
    // second supporting line shows the matched message with the needle
    // highlighted, so the user can see why the chat appeared in the results.
    bodyMatch: MessageBodyMatch? = null,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    val messageTextCopy = rememberMessageTextCopy()
    // derivedStateOf so the title is only recomputed when its snapshot reads
    // (item, the profile-presentation revision read inside chatMemberTitle)
    // actually change, instead of every chat-list recomposition pass.
    // Routed through the shared `chatListItemDisplayTitle` so the same
    // projection drives the search filter.
    val title by remember(item, groupTitleCopy) {
        derivedStateOf { chatListItemDisplayTitle(item, appState, groupTitleCopy) }
    }
    val inviteAccount = GroupProjector.inviteAccount(item.group, item.otherMemberAccount)
    val avatarAccount =
        inviteAccount
            ?: item.otherMemberAccount.takeIf { item.group.name.isBlank() && item.memberCount == 2 }
    val openableDmAvatarAccount =
        avatarAccount
            ?.takeIf { GroupProjector.isDm(memberCount = item.memberCount, name = item.group.name) }
    val rowModifier =
        if (onLongClick != null) {
            Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
        } else {
            Modifier.clickable(onClick = onClick)
        }
    ListItem(
        modifier = rowModifier,
        leadingContent = {
            Box(
                modifier =
                    if (openableDmAvatarAccount != null) {
                        Modifier
                            .clip(CircleShape)
                            .clickable(role = Role.Button) {
                                appState.presentProfile(appState.npub(openableDmAvatarAccount))
                            }
                    } else {
                        Modifier
                    },
            ) {
                Avatar(
                    title = title,
                    seed = avatarAccount ?: item.group.groupIdHex,
                    size = 44.dp,
                    // A group's own avatar URL wins over the member-derived avatar.
                    pictureUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
                )
            }
        },
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            val draft = appState.draftFor(item.group.groupIdHex)?.takeIf { it.isNotBlank() }
            // Tokens only ever describe the last message's body, so they're
            // ignored whenever the line shows something else (invite copy,
            // draft). When the controller hasn't parsed yet (or the parse
            // produced nothing), fall back to today's plaintext line. No
            // parsing happens here — composition stays parse-free.
            val markdownPreview =
                item.previewTokens
                    ?.takeIf { !item.group.pendingConfirmation && draft == null && it.blocks.isNotEmpty() }
            val preview =
                if (markdownPreview != null) {
                    rememberMarkdownPreviewText(
                        markdownPreview,
                        mentionDisplayName =
                            remember(appState) {
                                { bech32: String -> appState.mentionDisplayName(bech32) }
                            },
                    )
                } else {
                    AnnotatedString(
                        when {
                            item.group.pendingConfirmation -> stringResource(R.string.invitation)
                            draft != null -> stringResource(R.string.chat_row_draft_prefix) + draft
                            else ->
                                item.projectedPreviewText(
                                    copy = messageTextCopy,
                                    empty = stringResource(R.string.no_messages_yet),
                                )
                        },
                    )
                }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preview,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontStyle = if (draft != null) FontStyle.Italic else FontStyle.Normal,
                )
                // Search snippet line: only for rows matched on a message body
                // (not title/preview), so a normal chat list and title/preview
                // hits keep the single-line layout. The matched needle is
                // emphasized so the reason the chat surfaced is obvious.
                if (bodyMatch != null) {
                    val highlightStyle =
                        SpanStyle(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    val snippetText =
                        remember(bodyMatch.snippet, highlightStyle) {
                            highlightedSnippet(bodyMatch.snippet, highlightStyle)
                        }
                    Text(
                        text = snippetText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    rememberedRelativeTime(item.latestAt ?: 0uL),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (item.hasUnread) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (item.group.pendingConfirmation) {
                    Badge { Text(stringResource(R.string.invite)) }
                } else if (item.hasUnread) {
                    UnreadCountBadge(item.unreadCount)
                }
            }
        },
    )
}

@Composable
private fun UnreadCountBadge(
    unreadCount: ULong,
    modifier: Modifier = Modifier,
) {
    val accessibleCount = unreadCount.coerceAtMost(Int.MAX_VALUE.toULong()).toInt()
    val description = pluralStringResource(R.plurals.unread_messages_count, accessibleCount, accessibleCount)
    // Default Badge is error-red, which reads as an alert not a count.
    Badge(
        modifier = modifier.semantics { contentDescription = description },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(if (unreadCount > 99uL) "99+" else unreadCount.toString())
    }
}

@Composable
private fun EmptyChats(onCreate: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.no_chats_yet), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.empty_chats_invite_npub),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.new_chat))
            }
        }
    }
}

internal fun canSubmitNewChatSheet(
    directMessage: Boolean,
    busy: Boolean,
    pendingRecipient: String,
    groupName: String,
): Boolean =
    !busy &&
        if (directMessage) {
            pendingRecipient.isNotBlank()
        } else {
            groupName.isNotBlank()
        }

internal fun newChatMemberRefs(
    directMessage: Boolean,
    normalizedPendingRecipients: List<String>,
): List<String> =
    if (directMessage) {
        normalizedPendingRecipients.distinct().take(1)
    } else {
        emptyList()
    }

internal fun canInviteFromEmptyGroup(
    isSelfMember: Boolean,
    isSelfAdmin: Boolean,
    membersLoaded: Boolean,
    memberCount: Int,
): Boolean =
    isSelfMember &&
        isSelfAdmin &&
        membersLoaded &&
        memberCount == 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(
    appState: DarkMatterAppState,
    @StringRes titleRes: Int = R.string.new_chat,
    directMessage: Boolean = false,
    existingDirectChat: (String) -> ChatListItem? = { null },
    // (chat, justCreated): justCreated is true when this open follows a
    // brand-new group/DM create in the same step (issue #321) so the
    // conversation lands with the composer focused + keyboard up. Reusing an
    // existing 1:1 passes false.
    onOpenConversation: (ChatListItem, Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    var pending by remember { mutableStateOf("") }
    var groupName by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val validRecipientReferenceError = stringResource(R.string.error_valid_recipient_reference)
    val missingKeyPackageError = stringResource(R.string.error_missing_key_package)
    val missingKeyPackageForFormat = stringResource(R.string.error_missing_key_package_for)
    val invalidIdentityReferenceError = stringResource(R.string.error_invalid_identity_reference)
    val groupPublishFailedFormat = stringResource(R.string.error_group_publish_failed)
    val notDarkMatterProfileQrError = stringResource(R.string.error_not_dark_matter_profile_qr)
    val groupNameFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(directMessage) {
        if (!directMessage) {
            withFrameNanos { }
            groupNameFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    fun createGroupErrorMessage(throwable: Throwable): String =
        when (throwable) {
            is MarmotKitException.MissingKeyPackage ->
                // Name the failing recipient so the user knows which invited
                // member to ask, instead of a generic 'that account' (#322).
                // chatMemberTitle returns the cached display name when one is
                // known and falls back to a short npub otherwise — never the
                // raw hex pubkey. Fall back to the generic copy if the engine
                // ever reports a blank account ref (defensive: the FFI declares
                // a non-null String, but the value can in principle be empty).
                if (throwable.account.isNotBlank()) {
                    String.format(missingKeyPackageForFormat, appState.chatMemberTitle(throwable.account))
                } else {
                    missingKeyPackageError
                }
            is MarmotKitException.InvalidIdentity ->
                invalidIdentityReferenceError
            is MarmotKitException.Publish ->
                String.format(groupPublishFailedFormat, throwable.details)
            else -> throwable.message ?: throwable.javaClass.simpleName
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            if (directMessage) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pending,
                        onValueChange = {
                            pending = it
                            error = null
                        },
                        label = { Text(stringResource(R.string.npub_or_hex_public_key)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        trailingIcon = {
                            PublicIdentifierFieldTrailingAction(
                                value = pending,
                                enabled = !busy,
                                onValueChange = {
                                    pending = it
                                    error = null
                                },
                            )
                        },
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                    )
                    FloatingActionButton(onClick = { showScanner = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_recipient_qr_code))
                    }
                }
            } else {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.group_name)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(groupNameFocusRequester),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.description)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }
            if (error != null) {
                Text(
                    error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    val normalizedPending =
                        if (directMessage) {
                            RecipientReference.tokenize(pending).map { input ->
                                RecipientReference.normalize(input) ?: run {
                                    error = validRecipientReferenceError
                                    return@Button
                                }
                            }
                        } else {
                            emptyList()
                        }
                    val recipients = newChatMemberRefs(directMessage, normalizedPending)
                    pending = ""
                    val account = appState.activeAccountRef ?: return@Button
                    // Reuse an existing 1:1 instead of creating a duplicate.
                    if (directMessage) {
                        val target = recipients.firstOrNull() ?: return@Button
                        existingDirectChat(target)?.let { existing ->
                            onOpenConversation(existing, false)
                            onDismiss()
                            return@Button
                        }
                    }
                    busy = true
                    error = null
                    // Process-lifetime scope so MLS commit + Nostr publish complete
                    // even if the sheet dismisses mid-flight.
                    appState.launchMutation {
                        runCatching {
                            appState.marmotIo {
                                createGroup(
                                    account,
                                    groupName.trim(),
                                    recipients,
                                    description.trim().ifBlank { null },
                                )
                            }
                        }.onSuccess { groupIdHex ->
                            appState.present(R.string.toast_chat_created)
                            // #321/#385: navigate straight into the new
                            // conversation instead of leaving the user on the
                            // chat list. Direct chats keep the composer-ready
                            // handoff; groups land unfocused so the empty-state
                            // Add Members CTA is the next obvious action.
                            // Dismiss the sheet first so the conversation isn't
                            // opened behind the modal, then wait for the
                            // chat-list projection to surface
                            // the freshly-created group (it lands a beat after
                            // createGroup returns). If it doesn't materialize in
                            // time, fall back to just dismissing — the sort fix
                            // already floats the new chat to the top of the list.
                            onDismiss()
                            appState.awaitChatListItem(groupIdHex)?.let { item ->
                                onOpenConversation(item, directMessage)
                            }
                        }.onFailure {
                            error = createGroupErrorMessage(it)
                        }
                        busy = false
                    }
                },
                enabled = canSubmitNewChatSheet(directMessage, busy, pending, groupName),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.create))
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    error = notDarkMatterProfileQrError
                } else {
                    error = null
                    // A new direct chat keeps the scanned recipient in the field.
                    if (directMessage) pending = scanned.npub
                }
            },
        )
    }
}

/** Within this many items of the trailing edge counts as "at bottom". */
private const val ConversationNearBottomItemSlack = 3

// Maximum images per multi-pick. The Android Photo Picker enforces this
// cap on the system dialog side; 10 keeps the album payload bounded
// (10 * 1920px JPEG ≈ a few MB encrypted) without feeling artificially low.
// Approximate clearance the conversation composer occupies above the
// navigation bar. Used by ConversationScreen to push the global
// snackbar host above the composer so toasts don't intercept touches
// on the message input — see [LocalSnackbarBottomInset] + issue #122.
// 72.dp covers the single-line composer plus its vertical padding; a
// multi-line composer can grow taller but the snackbar still clears
// the input row that needs to remain tappable. TODO: derive from the
// composer's measured height instead of hardcoding so multi-line and
// attachment-preview states stay covered exactly.
private val COMPOSER_SNACKBAR_INSET = 72.dp

// Approximate clearance the chat-list quick-action FAB occupies above the
// navigation bar. Used by ChatsScreen to push the global + chat-list snackbar
// hosts above the FAB so a toast (e.g. "npub copied" from a profile sheet)
// doesn't sit over the FAB and swallow its taps for the toast's duration —
// issue #352. The toggle FAB is ~56.dp and the Scaffold floats it 16.dp above
// the bottom inset; 80.dp clears that stack with a small gap so the lifted
// snackbar reads as sitting *above* the FAB, per Material guidance.
private val FAB_SNACKBAR_INSET = 80.dp

// Chat-list jump-to-top FAB thresholds (issue #413). The button shows once the
// first visible row index reaches SHOW, and only hides again once it falls back
// to HIDE — the 3–4 dead band gives hysteresis so a quick scroll wiggle near
// the threshold doesn't flicker the button. SNAP bounds the tap animation: from
// deeper than SNAP rows down we hard-jump to SNAP first, then animate the final
// stretch, so a tap from hundreds of rows deep isn't a multi-second crawl.
private const val CHAT_LIST_JUMP_TO_TOP_SHOW_INDEX = 5
private const val CHAT_LIST_JUMP_TO_TOP_HIDE_INDEX = 2
private const val CHAT_LIST_JUMP_TO_TOP_SNAP_INDEX = 10

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

private data class ImageAttachmentReadOutcome(
    val attachment: PendingAttachment?,
    val overflowed: Boolean = false,
)

/** Fixed height of an in-timeline image bubble — constant across load states
 *  so async decode never reflows the list (would break the open-time anchor). */
private val MediaBubbleHeight = 240.dp

/** Hard cap on the height a `dim`-shaped image bubble can claim, so a tall
 *  portrait can't dominate the chat viewport. Width fills the bubble; this
 *  bounds the height so the aspect-ratio sizing degrades to a cropped
 *  preview at the extremes. */
private val MediaBubbleMaxHeight = 340.dp

/** Fixed card width used for portrait image bubbles, so every portrait
 *  reads as a consistently-sized card rather than a width-varying strip.
 *  Landscape bubbles still fill the parent. */
private val MediaBubbleCardWidth = 280.dp

/** Sizing modifier for both the optimistic and the confirmed single-image
 *  bubble. Portrait images become uniform-width cards with a height cap;
 *  landscape images fill the bubble width and derive their natural height
 *  (which can't exceed the width for ratio ≥ 1). Falls back to the legacy
 *  fixed-height slab when the aspect ratio is unknown. */
@Composable
private fun imageBubbleSizing(ratio: Float?): Modifier =
    when {
        ratio == null -> Modifier.fillMaxWidth().height(MediaBubbleHeight)
        ratio >= 1f -> Modifier.fillMaxWidth().aspectRatio(ratio)
        else -> {
            val natural = (MediaBubbleCardWidth.value / ratio).dp
            val height = if (natural > MediaBubbleMaxHeight) MediaBubbleMaxHeight else natural
            Modifier.width(MediaBubbleCardWidth).height(height)
        }
    }

/**
 * Decode an imeta `thumbhash` field into a tiny ARGB ImageBitmap, cached
 * for the lifetime of the composition. Returns null when the field is
 * absent or doesn't decode. Callers render the bitmap with
 * [ContentScale.Crop] under the loading state so the bubble shows a
 * blurred preview before the real bytes arrive.
 */
@Composable
private fun rememberThumbhashImage(thumbhash: String?): ImageBitmap? {
    if (thumbhash.isNullOrBlank()) return null
    // The decode is a few hundred μs to a couple ms (cosine-basis sum
    // across a 32×32 grid). Doing it inside `remember { ... }` runs it on
    // the Compose / Main thread during the initial composition pass, which
    // multiplied across the bubbles entering composition during scroll adds
    // up to a measurable Input+Anim+Layout cost. `produceState` defers the
    // decode to Dispatchers.Default and emits the result when ready —
    // initial composition returns instantly with `null` and the bubble
    // shows the underlying surface tint until the blurred placeholder
    // arrives.
    val state =
        produceState<ImageBitmap?>(initialValue = null, key1 = thumbhash) {
            value =
                withContext(Dispatchers.Default) {
                    Thumbhash.decodeToBitmap(thumbhash)?.asImageBitmap()
                }
        }
    return state.value
}

/**
 * Parse the imeta `dim` field ("WxH") into a width/height aspect ratio.
 * Returns null when [dim] is null, blank, malformed, or non-positive on
 * either axis. Caller falls back to [MediaBubbleHeight] in that case.
 */
private fun aspectRatioFromDim(dim: String?): Float? {
    if (dim.isNullOrBlank()) return null
    val parts = dim.split('x', 'X', ignoreCase = true)
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    if (w <= 0 || h <= 0) return null
    // Clamp wide panoramas so the bubble doesn't squeeze to a sliver.
    // Tall portraits are bounded by [MediaBubbleMaxHeight] at the layout
    // site instead — keeping the aspect ratio uncramped lets the placeholder
    // still convey "this is a tall image" before the bytes arrive.
    return (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f)
}

/** Saves a nullable Uri across process death (camera capture round-trip). */
private val NullableUriSaver: Saver<android.net.Uri?, String> =
    Saver(
        save = { it?.toString() ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let(android.net.Uri::parse) },
    )

/**
 * Saves a nullable [java.io.File] across process death by its absolute path.
 * Used so the camera capture's temp-file handle survives the round-trip and a
 * capture cancelled after process death can still delete the empty temp
 * instead of leaking it (issue #531).
 */
private val NullableFileSaver: Saver<java.io.File?, String> =
    Saver(
        save = { it?.absolutePath ?: "" },
        restore = { s -> s.takeIf { it.isNotEmpty() }?.let { path -> java.io.File(path) } },
    )

// Persist a multi-pick selection across rotation / process death. Empty list
// encodes "no preview shown" so the parent re-render skips the sheet on
// restore. Uses '\n' as the separator — content URIs don't contain newlines.
private val UriListSaver: Saver<List<android.net.Uri>, String> =
    Saver(
        save = { encodeUriListTokens(it.map { uri -> uri.toString() }) },
        restore = { s -> decodeUriListTokens(s).map(android.net.Uri::parse) },
    )

/**
 * Pure string codec backing [UriListSaver], split out from the [android.net.Uri]
 * conversion so the separator and empty-list contract is unit-testable on the
 * JVM (the Android `Uri` stubs are non-functional in local unit tests). Joins
 * tokens with '\n'; an empty list encodes to "".
 */
internal fun encodeUriListTokens(tokens: List<String>): String = tokens.joinToString("\n")

/**
 * Inverse of [encodeUriListTokens]. An empty input decodes to an empty list
 * (the "no preview shown" sentinel); blank tokens are dropped so a trailing or
 * doubled separator can't yield empty URI strings.
 */
internal fun decodeUriListTokens(encoded: String): List<String> =
    if (encoded.isEmpty()) {
        emptyList()
    } else {
        encoded.split('\n').filter { it.isNotEmpty() }
    }

private fun senderTitleForReply(
    senderPubkey: String,
    appState: DarkMatterAppState,
): String = appState.displayName(senderPubkey)

private fun isOwnReplySender(
    senderPubkey: String,
    appState: DarkMatterAppState,
): Boolean {
    val active = appState.activeAccount?.accountIdHex ?: return false
    return senderPubkey.equals(active, ignoreCase = true)
}

@Composable
private fun ReplyPreviewCard(
    senderTitle: String,
    isOwn: Boolean,
    body: String,
    mediaKind: dev.ipf.darkmatter.core.ReplyMediaKind,
    onClick: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    // The composer banner spans the input row, so it fills its width. The
    // in-bubble quote (#208) must instead hug its content: forcing
    // fillMaxWidth there expands the enclosing bubble Column to its max
    // width even when the quote and reply text are both short.
    fillWidth: Boolean = true,
) {
    val title = if (isOwn) stringResource(R.string.reply_you) else senderTitle
    val mediaLabel =
        when (mediaKind) {
            dev.ipf.darkmatter.core.ReplyMediaKind.Photo -> stringResource(R.string.reply_media_photo)
            dev.ipf.darkmatter.core.ReplyMediaKind.Video -> stringResource(R.string.reply_media_video)
            dev.ipf.darkmatter.core.ReplyMediaKind.Voice -> stringResource(R.string.reply_media_voice)
            dev.ipf.darkmatter.core.ReplyMediaKind.Document -> stringResource(R.string.reply_media_document)
            dev.ipf.darkmatter.core.ReplyMediaKind.None -> null
        }
    val mediaIcon =
        when (mediaKind) {
            dev.ipf.darkmatter.core.ReplyMediaKind.Photo -> Icons.Default.Image
            dev.ipf.darkmatter.core.ReplyMediaKind.Video -> Icons.Default.Movie
            dev.ipf.darkmatter.core.ReplyMediaKind.Voice -> Icons.Default.Mic
            dev.ipf.darkmatter.core.ReplyMediaKind.Document -> Icons.Default.Description
            dev.ipf.darkmatter.core.ReplyMediaKind.None -> null
        }
    val bodyText = mediaLabel ?: body
    val accent =
        if (isOwn) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val container = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
    Surface(
        color = container,
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier =
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(accent),
            )
            Row(
                // weight(1f) forces this Row to fill the parent's width, which
                // re-expands the bubble (#208). Only claim it when the card is
                // meant to span its container (composer banner). When hugging,
                // wrap to content so a short quote keeps the bubble narrow.
                modifier =
                    (if (fillWidth) Modifier.weight(1f) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = if (fillWidth) Modifier.weight(1f) else Modifier) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (mediaIcon != null) {
                            Icon(
                                mediaIcon,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            bodyText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_reply),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaImageBubble(
    item: TimelineMessage,
    reference: MediaAttachmentReferenceFfi,
    attachmentIndex: Int,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
    uploading: Boolean = false,
) {
    val record = item.record
    val key = record.messageIdHex
    // Decode-state keys split into two buckets:
    //   - Bytes-level state (bitmap, failed, reloadToken): keyed on
    //     `sourceEpoch` so a typed-reference upgrade from imeta-fallback
    //     (epoch = 0) to the real listMedia value clears a stuck failure.
    //   - User-interaction state (viewerOpen, startDownload): NOT keyed on
    //     epoch, because we never want a background typed-ref upgrade to
    //     close a viewer the user just opened, or re-gate a download the
    //     user just consented to.
    val epoch = reference.sourceEpoch
    // Seed from the decoded-thumbnail cache so an already-fetched or just-sent
    // image paints on the first frame — no decode spinner, no visible "reload".
    var bitmap by remember(key, attachmentIndex, epoch) {
        mutableStateOf(controller.thumbnailFor(key, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(key, attachmentIndex, epoch) { mutableStateOf(false) }
    var viewerOpen by remember(key, attachmentIndex) { mutableStateOf(false) }
    var reloadToken by remember(key, attachmentIndex, epoch) { mutableStateOf(0) }
    // Auto-download gating (#10): own messages always render (bytes are cached
    // from the send), incoming honor the policy. Keyed on the policy so
    // flipping the setting re-gates undownloaded bubbles.
    var startDownload by remember(key, attachmentIndex, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    LaunchedEffect(key, attachmentIndex, epoch, startDownload, reloadToken) {
        if (bitmap != null) return@LaunchedEffect // already have a decoded thumbnail
        if (!startDownload) return@LaunchedEffect
        // The imeta-tag parser falls back to sourceEpoch=0 (the wire format
        // doesn't carry it). Calling downloadMedia with epoch=0 errors with
        // "missing encrypted media secret for epoch 0". Wait for the typed
        // reference upgrade via `refreshMediaReferences` — once it lands,
        // `epoch` re-keys this effect with the real value. The spinner stays
        // visible during the wait (bitmap=null, failed=false, startDownload).
        if (epoch == 0uL) return@LaunchedEffect
        failed = false
        try {
            // Own optimistic sends still have their bytes only in the
            // pending list (the projection hasn't reconciled them into the
            // L1 cache yet). Use those directly so the bubble paints during
            // the upload window instead of hanging on a missing-epoch FFI.
            val pendingBytes =
                if (mine) {
                    controller.pendingAttachmentsList(key).getOrNull(attachmentIndex)?.plaintextBytes
                } else {
                    null
                }
            val data = pendingBytes ?: controller.downloadAttachment(key, attachmentIndex, reference)
            // Decode a sampled bitmap sized to the bubble — a full 1920px
            // image would be a ~14 MB ARGB_8888 bitmap per visible row.
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            if (decoded != null) {
                controller.cacheThumbnail(key, attachmentIndex, decoded)
                bitmap = decoded.asImageBitmap()
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            // Composable left composition or key changed — propagate. A
            // cancelled effect isn't a download failure; the bubble is gone.
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w(
                "MediaImageBubble",
                "auto-download failed for msg=${key.take(8)} idx=$attachmentIndex",
                t,
            )
            failed = true
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        // Single source of truth for image-bubble shape: portraits become
        // uniform-width cards (capped height), landscapes fill the bubble
        // width. Used by both the confirmed bubble and the optimistic
        // upload-phase bubble so the optimistic → confirmed swap is a
        // visual no-op.
        modifier = imageBubbleSizing(aspectRatioFromDim(reference.dim)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val current = bitmap
            val placeholder = rememberThumbhashImage(reference.thumbhash)
            // Paint the blurred placeholder behind whatever loading-state is
            // shown so the bubble has a perceptual preview before the real
            // bytes arrive. The real image (when `current != null`) covers it.
            if (current == null && placeholder != null) {
                Image(
                    bitmap = placeholder,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            when {
                current != null ->
                    Image(
                        bitmap = current,
                        contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .clickable { viewerOpen = true },
                    )
                failed ->
                    MediaCircleAction(
                        icon = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.media_tap_to_retry),
                        onClick = {
                            failed = false
                            reloadToken++
                        },
                    )
                !startDownload ->
                    MediaCircleAction(
                        icon = Icons.Default.ArrowDownward,
                        contentDescription = stringResource(R.string.media_tap_to_download),
                        onClick = { startDownload = true },
                    )
                else ->
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (uploading) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }

    if (viewerOpen) {
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = key,
            attachments = listOf(IndexedValue(attachmentIndex, reference)),
            startIndex = 0,
            onDismiss = { viewerOpen = false },
            mine = mine,
        )
    }
}

/**
 * Count-specific masonry scaffolding for a 2-6 image album. Lays out the
 * tiles so a 3-image set is tall-left + two-stacked-right (no empty cell),
 * 5 is 2-up over 3-down, 6+ is 3×2 with a "+N" tile six. Caller provides
 * the per-tile composable through [tile]; the helper supplies each tile
 * its size modifier so the layout shape stays one source of truth across
 * the confirmed bubble and the optimistic upload-phase placeholder.
 */
@Composable
private fun MasonryImageLayout(
    visibleCount: Int,
    tile: @Composable (index: Int, tileModifier: Modifier) -> Unit,
) {
    when (visibleCount) {
        2 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                tile(0, Modifier.weight(1f).aspectRatio(1f))
                tile(1, Modifier.weight(1f).aspectRatio(1f))
            }
        3 ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp).aspectRatio(1f),
            ) {
                tile(0, Modifier.weight(1f).fillMaxHeight())
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                ) {
                    tile(1, Modifier.weight(1f).fillMaxWidth())
                    tile(2, Modifier.weight(1f).fillMaxWidth())
                }
            }
        4 ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(0, Modifier.weight(1f).aspectRatio(1f))
                    tile(1, Modifier.weight(1f).aspectRatio(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(2, Modifier.weight(1f).aspectRatio(1f))
                    tile(3, Modifier.weight(1f).aspectRatio(1f))
                }
            }
        5 ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(0, Modifier.weight(1f).aspectRatio(1f))
                    tile(1, Modifier.weight(1f).aspectRatio(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(2, Modifier.weight(1f).aspectRatio(1f))
                    tile(3, Modifier.weight(1f).aspectRatio(1f))
                    tile(4, Modifier.weight(1f).aspectRatio(1f))
                }
            }
        else ->
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth().padding(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(0, Modifier.weight(1f).aspectRatio(1f))
                    tile(1, Modifier.weight(1f).aspectRatio(1f))
                    tile(2, Modifier.weight(1f).aspectRatio(1f))
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tile(3, Modifier.weight(1f).aspectRatio(1f))
                    tile(4, Modifier.weight(1f).aspectRatio(1f))
                    tile(5, Modifier.weight(1f).aspectRatio(1f))
                }
            }
    }
}

@Composable
private fun MediaImageGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
) {
    val record = item.record
    // Show up to six tiles before collapsing the remainder into a "+N"
    // overlay on tile six. Higher counts trip the overflow chip in the
    // 3×2 layout below.
    val visible = attachments.take(6)
    val overflow = (attachments.size - visible.size).coerceAtLeast(0)
    var viewerOpenAt by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

    val tileAt: @Composable (Int, Modifier) -> Unit = { tileIndex, tileModifier ->
        val entry = visible[tileIndex]
        val showOverflow = tileIndex == visible.lastIndex && overflow > 0
        MediaImageGridTile(
            messageIdHex = record.messageIdHex,
            attachmentIndex = entry.index,
            reference = entry.value,
            controller = controller,
            appState = appState,
            mine = mine,
            onTap = { viewerOpenAt = tileIndex },
            overflowCount = if (showOverflow) overflow else 0,
            modifier = tileModifier,
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        MasonryImageLayout(visibleCount = visible.size, tile = tileAt)
    }

    viewerOpenAt?.let { index ->
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = record.messageIdHex,
            attachments = attachments,
            startIndex = index,
            onDismiss = { viewerOpenAt = null },
            mine = mine,
        )
    }
}

/**
 * Mixed image + video album bubble. Each tile picks its renderer based on
 * MIME — image tiles open the image viewer, video tiles tap-to-play in the
 * fullscreen ExoPlayer. Layout is the same masonry as MediaImageGridBubble.
 */
@Composable
private fun MediaVisualGridBubble(
    item: TimelineMessage,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
    uploading: Boolean = false,
) {
    val record = item.record
    val visible = attachments.take(6)
    val overflow = (attachments.size - visible.size).coerceAtLeast(0)
    var viewerOpenAt by remember(record.messageIdHex) { mutableStateOf<Int?>(null) }

    val tileAt: @Composable (Int, Modifier) -> Unit = { tileIndex, tileModifier ->
        val entry = visible[tileIndex]
        val showOverflow = tileIndex == visible.lastIndex && overflow > 0
        if (MediaReferenceParser.isVideoMedia(entry.value)) {
            MediaVideoGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { _ -> viewerOpenAt = tileIndex },
                overflowCount = if (showOverflow) overflow else 0,
                modifier = tileModifier,
                uploading = uploading,
            )
        } else {
            MediaImageGridTile(
                messageIdHex = record.messageIdHex,
                attachmentIndex = entry.index,
                reference = entry.value,
                controller = controller,
                appState = appState,
                mine = mine,
                onTap = { viewerOpenAt = tileIndex },
                overflowCount = if (showOverflow) overflow else 0,
                modifier = tileModifier,
                uploading = uploading,
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        MasonryImageLayout(visibleCount = visible.size, tile = tileAt)
    }

    viewerOpenAt?.let { tileIndex ->
        // Unified viewer walks the full attachments list — each page picks
        // its renderer (image vs video) by MIME, swipes between siblings
        // regardless of type. mine threads through so an own optimistic
        // overflow video (>6 tiles) materialises from retained bytes
        // instead of trying an FFI download at epoch=0.
        FullScreenImageViewer(
            controller = controller,
            appState = appState,
            messageIdHex = record.messageIdHex,
            attachments = attachments,
            startIndex = tileIndex,
            onDismiss = { viewerOpenAt = null },
            mine = mine,
        )
    }
}

/**
 * Single video tile in an album grid. Auto-materialises on first
 * composition (mine + cached short-circuit; otherwise FFI download honoring
 * the auto-download policy), decodes a scaled poster, overlays a centered
 * play affordance. Tap delivers the materialised file to the parent so
 * the bubble can open the fullscreen player.
 */
@Composable
private fun MediaVideoGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
    onTap: (java.io.File) -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    uploading: Boolean = false,
) {
    val context = LocalContext.current
    val epoch = reference.sourceEpoch
    var localFile by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf<java.io.File?>(null) }
    var posterBitmap by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(messageIdHex, attachmentIndex, epoch) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    var startDownload by remember(messageIdHex, attachmentIndex, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video))
    }

    LaunchedEffect(messageIdHex, attachmentIndex, epoch, startDownload) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        if (!mine && epoch == 0uL) return@LaunchedEffect
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            failed = true
        }
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        if (posterBitmap != null) return@LaunchedEffect
        posterBitmap =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                    mmr
                        .getScaledFrameAtTime(
                            0L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            edge,
                            edge,
                        )?.asImageBitmap()
                } catch (t: Throwable) {
                    null
                } finally {
                    runCatching { mmr.release() }
                }
            }
    }

    Box(
        modifier =
            modifier.clickable {
                val f = localFile
                if (f != null) onTap(f) else startDownload = true
            },
    ) {
        val poster = posterBitmap
        when {
            poster != null ->
                Image(
                    bitmap = poster,
                    contentDescription = stringResource(R.string.reply_media_video),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            thumbhashImage != null ->
                Image(
                    bitmap = thumbhashImage,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            else ->
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        }
        Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = CircleShape,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    failed ->
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.voice_message_failed),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    !startDownload && localFile == null ->
                        Icon(
                            Icons.Default.Download,
                            contentDescription = stringResource(R.string.media_open),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    localFile == null ->
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    else ->
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = stringResource(R.string.reply_media_video),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                }
            }
        }
        if (overflowCount > 0) {
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * One tile of the album grid: square thumbnail + per-tile download state.
 * The thumbnail-cache lookup is keyed on `(messageId, attachmentIndex)` so
 * tiles never clobber each other. Tap fires [onTap] (the parent opens the
 * full-screen viewer at this attachment's index).
 */
@Composable
private fun MediaImageGridTile(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
    onTap: () -> Unit,
    overflowCount: Int,
    modifier: Modifier = Modifier,
    uploading: Boolean = false,
) {
    // Two-bucket key model (mirrors `MediaImageBubble`):
    //   - `decodeKey` includes `sourceEpoch`, scoped to bytes-level state
    //     so a typed-reference upgrade clears a failed-at-epoch-0 tile.
    //   - `tileSlot` omits the epoch, scoped to user-choice state
    //     (startDownload) so a background ref upgrade can't re-gate a tile
    //     the user already consented to fetch.
    val decodeKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    val tileSlot = "$messageIdHex#$attachmentIndex"
    var bitmap by remember(decodeKey) {
        mutableStateOf(controller.thumbnailFor(messageIdHex, attachmentIndex)?.asImageBitmap())
    }
    var failed by remember(decodeKey) { mutableStateOf(false) }
    var reloadToken by remember(decodeKey) { mutableStateOf(0) }
    // Mirror the single-image bubble's auto-download gate (#10) so the
    // policy applies to album tiles too. Outgoing tiles (`mine`) always
    // download because the bytes are seeded from the send. Re-keyed on
    // the policy so flipping the setting re-gates undownloaded tiles.
    var startDownload by remember(tileSlot, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    LaunchedEffect(decodeKey, startDownload, reloadToken) {
        if (bitmap != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        val pendingBytes =
            if (mine) {
                controller.pendingAttachmentsList(messageIdHex).getOrNull(attachmentIndex)?.plaintextBytes
            } else {
                null
            }
        // Pre-confirm own albums: bytes live in pendingAttachmentsList and the
        // FFI imeta isn't ready yet, so skip the sourceEpoch guard for that
        // path. After reconcile, downloadAttachment hits the cache instead.
        if (pendingBytes == null && reference.sourceEpoch == 0uL) return@LaunchedEffect
        failed = false
        try {
            val data = pendingBytes ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                }
            if (decoded != null) {
                controller.cacheThumbnail(messageIdHex, attachmentIndex, decoded)
                bitmap = decoded.asImageBitmap()
            } else {
                failed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w(
                "MediaImageGridTile",
                "tile auto-download failed for msg=${messageIdHex.take(8)} idx=$attachmentIndex",
                t,
            )
            failed = true
        }
    }

    Box(
        modifier =
            modifier.clickable(
                // Two modes:
                //   - Bytes ready (`bitmap != null`): tap opens the viewer.
                //   - Auto-download gated: tap flips startDownload, so the
                //     first tap fetches and the next tap (once decoded)
                //     opens the viewer. Same UX as the single-image bubble.
                onClick = {
                    if (bitmap != null) {
                        onTap()
                    } else if (!startDownload) {
                        startDownload = true
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val current = bitmap
        val placeholder = rememberThumbhashImage(reference.thumbhash)
        if (current == null && placeholder != null) {
            Image(
                bitmap = placeholder,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when {
            current != null ->
                Image(
                    bitmap = current,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            failed ->
                MediaCircleAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.media_tap_to_retry),
                    onClick = {
                        failed = false
                        reloadToken++
                    },
                )
            !startDownload ->
                MediaCircleAction(
                    icon = Icons.Default.ArrowDownward,
                    contentDescription = stringResource(R.string.media_tap_to_download),
                    onClick = { startDownload = true },
                )
            else ->
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
        }
        if (overflowCount > 0 && current != null) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (uploading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Receive-side bubble for any attachment whose MIME isn't an image. Renders
 * as a tappable pill: icon (chosen by MIME family), filename, size + status.
 * Tapping fetches the bytes (cached after first tap), writes a temp file
 * routed through the app's FileProvider, and fires `ACTION_VIEW` so the
 * system picks an external app (PDF reader, etc.) to open it.
 */
@Composable
private fun MediaFileBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    var inFlight by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    val noOpenAppMessage = stringResource(R.string.media_no_app_to_open)
    val couldntOpenMessage = stringResource(R.string.media_couldnt_open)
    // Cached bytes (own send, or downloaded earlier) mean the chevron is
    // misleading — there's nothing to fetch. Probe on first composition,
    // then flip after a successful in-bubble download. Outgoing sends are
    // implicitly cached, so `mine` short-circuits to true.
    var cached by remember(pillKey) {
        mutableStateOf(mine || controller.hasCachedAttachment(messageIdHex, attachmentIndex))
    }
    // Auto-download gate (#407): own sends are already cached; incoming
    // documents honor the Documents matrix row for the active connection.
    // Re-keyed on the matrix so flipping a toggle re-gates an un-fetched
    // file. A tap flips this to true so manual fetch/open is always
    // available regardless of the policy.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
    }

    // When the Documents policy allows auto-download, prefetch the bytes into
    // the attachment cache so the file is ready to open without a tap. We
    // only materialize (warm the L1/L2 cache); opening still happens on tap
    // via openAttachmentExternally below. Mirrors the audio/video bubbles.
    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload) {
        if (cached) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        inFlight = true
        runCatching {
            controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
        }.onSuccess {
            cached = true
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaFileBubble", "auto-download failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
        }
        inFlight = false
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !inFlight) {
                    failed = false
                    // Tap is an explicit opt-in: ensure the gate is open so a
                    // policy-gated file still fetches on demand.
                    startDownload = true
                    inFlight = true
                    scope.launch {
                        val outcome =
                            runCatching {
                                // For own sends, the retained-uploads LRU still
                                // holds the source plaintext during the upload
                                // window. Prefer those bytes — the FFI download
                                // path is mid-flight (the blob may not have
                                // fully propagated through the Blossom server
                                // yet) and would otherwise return invalid bytes
                                // that the system reader rejects.
                                val retained =
                                    if (mine) {
                                        controller
                                            .pendingAttachmentsList(messageIdHex)
                                            .getOrNull(attachmentIndex)
                                            ?.plaintextBytes
                                    } else {
                                        null
                                    }
                                val data =
                                    retained
                                        ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
                                cached = true
                                openAttachmentExternally(context, data, reference.fileName, reference.mediaType)
                            }.onFailure {
                                // Swipe-up / screen-dispose cancels this
                                // coroutine. The download itself continues on
                                // `mutationsScope` and lands in the cache —
                                // rethrow so the launch dies quietly instead
                                // of misreporting cancellation as a generic
                                // "couldn't open file" toast.
                                if (it is kotlinx.coroutines.CancellationException) throw it
                            }.getOrDefault(OpenAttachmentResult.Error)
                        when (outcome) {
                            OpenAttachmentResult.Opened -> Unit
                            OpenAttachmentResult.NoHandler -> {
                                failed = true
                                appState.present(noOpenAppMessage)
                            }
                            OpenAttachmentResult.Error -> {
                                failed = true
                                appState.present(couldntOpenMessage)
                            }
                        }
                        inFlight = false
                    }
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(reference.mediaType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    MediaPipeline.safeDisplayName(reference.fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shortMediaTypeLabel(reference.mediaType),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (failed) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            } else if (!cached) {
                // Bytes aren't local yet — show the chevron so the user
                // knows the tap will fetch. Once cached (own send, or after
                // first tap-and-download) the chevron disappears: nothing
                // to fetch, and the row is just "tap to open".
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = stringResource(R.string.media_open),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun MediaVideoBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
    uploading: Boolean = false,
    uploadFailed: Boolean = false,
    onRetryUpload: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"
    val epoch = reference.sourceEpoch
    var localFile by remember(pillKey, epoch) { mutableStateOf<java.io.File?>(null) }
    var loading by remember(pillKey, epoch) { mutableStateOf(false) }
    var failed by remember(pillKey, epoch) { mutableStateOf(false) }
    var posterBitmap by remember(pillKey, epoch) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var durationMs by remember(pillKey, epoch) { mutableStateOf(0L) }
    var playerOpen by remember(pillKey) { mutableStateOf(false) }
    val thumbhashImage = rememberThumbhashImage(reference.thumbhash)
    // Mirrors the image bubble's auto-download gate. When the policy says
    // no (e.g. Wi-Fi-only on cellular), a tap flips startDownload=true so
    // the user always has a path to fetch — never "looks present but can't
    // be opened". See PR #191 reviewer feedback.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Video))
    }

    LaunchedEffect(pillKey, epoch, startDownload) {
        if (localFile != null) return@LaunchedEffect
        if (!startDownload) return@LaunchedEffect
        if (!mine && epoch == 0uL) return@LaunchedEffect
        loading = true
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { f ->
            localFile = f
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVideoBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    LaunchedEffect(localFile) {
        val f = localFile ?: return@LaunchedEffect
        if (posterBitmap != null) return@LaunchedEffect
        val (bm, dur) =
            withContext(Dispatchers.IO) {
                val mmr = android.media.MediaMetadataRetriever()
                try {
                    mmr.setDataSource(f.absolutePath)
                    // Scale down to bubble preview size so a 4K source doesn't
                    // hold a ~33 MB ARGB bitmap per visible video bubble.
                    val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                    val frame =
                        mmr.getScaledFrameAtTime(
                            0L,
                            android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            edge,
                            edge,
                        )
                    val d =
                        mmr
                            .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull() ?: 0L
                    frame to d
                } catch (t: Throwable) {
                    Log.w("MediaVideoBubble", "poster extract failed", t)
                    null to 0L
                } finally {
                    runCatching { mmr.release() }
                }
            }
        durationMs = dur
        posterBitmap = bm?.asImageBitmap()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = imageBubbleSizing(aspectRatioFromDim(reference.dim)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val poster = posterBitmap
            when {
                poster != null ->
                    Image(
                        bitmap = poster,
                        contentDescription = stringResource(R.string.reply_media_video),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                thumbhashImage != null ->
                    Image(
                        bitmap = thumbhashImage,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                else ->
                    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }

            // Centered play overlay — semi-transparent dark circle with white
            // triangle. While uploading we replace the triangle with a spinner
            // so the user sees the send is in flight (matches the image bubble).
            // When startDownload is gated off (policy says no auto-fetch), the
            // triangle becomes a download icon and tap consents to the fetch.
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clickable {
                            when {
                                uploadFailed -> onRetryUpload?.invoke()
                                else -> {
                                    val f = localFile
                                    if (f != null) {
                                        playerOpen = true
                                    } else {
                                        startDownload = true
                                    }
                                }
                            }
                        },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        uploadFailed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable { onRetryUpload?.invoke() },
                            )
                        uploading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White,
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                Icons.Default.Download,
                                contentDescription = stringResource(R.string.media_open),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp),
                            )
                        loading && posterBitmap == null ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        failed ->
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = Color.White,
                                modifier =
                                    Modifier
                                        .size(28.dp)
                                        .clickable {
                                            failed = false
                                            scope.launch {
                                                runCatching {
                                                    materializeVideoAttachment(
                                                        context = context,
                                                        controller = controller,
                                                        messageIdHex = messageIdHex,
                                                        attachmentIndex = attachmentIndex,
                                                        reference = reference,
                                                        mine = mine,
                                                    )
                                                }.onSuccess { localFile = it }
                                                    .onFailure { failed = true }
                                            }
                                        },
                            )
                        else ->
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.reply_media_video),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                    }
                }
            }

            // Duration pill bottom-start. Only shown once duration is known.
            if (durationMs > 0L) {
                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(6.dp),
                    modifier =
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                ) {
                    Text(
                        formatVoiceTime(durationMs.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
    if (playerOpen) {
        val file = localFile
        if (file != null) {
            FullscreenVideoPlayer(file = file, onDismiss = { playerOpen = false })
        }
    }
}

/** Decrypted video on disk under cacheDir/video_attachments; reuses the
 *  age-based janitor that already sweeps shared_media / voice_attachments. */
private suspend fun materializeVideoAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    val dir = java.io.File(context.cacheDir, MediaCacheDirs.VIDEO).apply { mkdirs() }
    val ext =
        when {
            reference.mediaType.contains("quicktime", ignoreCase = true) -> "mov"
            reference.mediaType.contains("webm", ignoreCase = true) -> "webm"
            else -> "mp4"
        }
    val file = java.io.File(dir, "$messageIdHex-$attachmentIndex.$ext")
    if (file.exists() && file.length() > 0) return file
    val retained =
        if (mine) {
            controller
                .pendingAttachmentsList(messageIdHex)
                .getOrNull(attachmentIndex)
                ?.plaintextBytes
        } else {
            null
        }
    val bytes = retained ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
    withContext(Dispatchers.IO) { file.writeBytes(bytes) }
    return file
}

/**
 * Fullscreen player backed by Media3 ExoPlayer + PlayerView — the same
 * controller the platform media apps ship. Tap toggles the transport bar;
 * play/pause/seek work reliably without VideoView's MediaController quirks.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FullscreenVideoPlayer(
    file: java.io.File,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val exo =
        remember(file) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                    prepare()
                    playWhenReady = true
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties =
            androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    androidx.media3.ui.PlayerView(ctx).apply {
                        player = exo
                        useController = true
                        setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        controllerShowTimeoutMs = 2500
                    }
                },
            )
            IconButton(
                onClick = onDismiss,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun MediaVoiceBubble(
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    mine: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pillKey = "$messageIdHex#$attachmentIndex"

    var localFile by remember(pillKey) { mutableStateOf<java.io.File?>(null) }
    var totalDurationMs by remember(pillKey) { mutableStateOf(0) }
    var loading by remember(pillKey) { mutableStateOf(false) }
    var failed by remember(pillKey) { mutableStateOf(false) }
    // Auto-download gate (#407): own clips always materialize (bytes are
    // cached from the send), incoming honor the Audio matrix row. Re-keyed on
    // the matrix so flipping a toggle re-gates an un-fetched clip. A tap on
    // the bubble flips this to true so manual fetch/playback is always
    // available even when auto-download is off.
    var startDownload by remember(pillKey, appState.mediaAutoDownloadMatrix) {
        mutableStateOf(mine || appState.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio))
    }

    val playback by dev.ipf.darkmatter.audio.VoicePlaybackController.state
        .collectAsState()
    val isThis = playback.key == pillKey
    val isPlayingThis = isThis && playback.isPlaying
    val isPausedThis = isThis && !playback.isPlaying && playback.positionMs > 0
    val activeDurationMs =
        if (isThis && playback.durationMs > 0) playback.durationMs else totalDurationMs
    val activePositionMs = if (isThis) playback.positionMs else 0
    val progressFraction =
        if (activeDurationMs > 0) {
            (activePositionMs.toFloat() / activeDurationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val pseudoWaveform: FloatArray =
        remember(pillKey) {
            val bytes =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(pillKey.toByteArray())
            FloatArray(dev.ipf.darkmatter.audio.AudioWaveformExtractor.BARS) { i ->
                val byte = bytes[i % bytes.size].toInt() and 0xFF
                0.3f + (byte / 255f) * 0.7f
            }
        }
    var realWaveform by remember(pillKey) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(localFile, pillKey) {
        val file = localFile ?: return@LaunchedEffect
        if (realWaveform != null) return@LaunchedEffect
        realWaveform =
            dev.ipf.darkmatter.audio.AudioWaveformExtractor
                .decode(file)
    }
    val waveform: FloatArray = realWaveform ?: pseudoWaveform

    LaunchedEffect(pillKey, reference.sourceEpoch, startDownload) {
        if (localFile != null) return@LaunchedEffect
        // Honor the auto-download gate: when Audio is off for the active
        // connection the clip waits behind a Download affordance until the
        // user opts in (tap flips startDownload=true). Manual playback below
        // stays available regardless.
        if (!startDownload) return@LaunchedEffect
        // Receive-side imeta-parsed refs start with sourceEpoch=0 until the
        // controller's listMedia FFI lands the real epoch; the FFI download
        // path errors with "missing encrypted media secret for epoch 0".
        // Skip + retry once the projection rebinds the bubble with a real
        // epoch. Own sends keep epoch 0 valid (retained bytes short-circuit).
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        val instant = mine || controller.hasCachedAttachment(messageIdHex, attachmentIndex)
        if (!instant) loading = true
        runCatching {
            materializeVoiceAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { file ->
            localFile = file
            failed = false
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            Log.w("MediaVoiceBubble", "auto-materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
            failed = true
        }
        loading = false
    }

    // Surface a cached duration as soon as the file is materialized so the
    // bubble shows "0:12" instead of "0:00" before the user taps Play.
    LaunchedEffect(pillKey, localFile) {
        val file = localFile ?: return@LaunchedEffect
        if (totalDurationMs == 0) {
            val probed =
                dev.ipf.darkmatter.audio.VoicePlaybackController
                    .probeDuration(file)
            if (probed > 0) totalDurationMs = probed
        }
    }

    val onSurfaceMuted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary
    val onAccent = MaterialTheme.colorScheme.onPrimary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Circular play/pause button. Anchors the bubble and is the
            // primary tap target — sized generously (48dp) so it reads as
            // the focal control.
            Surface(
                color = accent,
                shape = androidx.compose.foundation.shape.CircleShape,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clickable(enabled = !loading) {
                            failed = false
                            // First tap on an un-fetched, auto-download-off clip
                            // is a Download affordance: opt in and let the
                            // gated effect fetch it, rather than fetch+play in
                            // one tap. Mirrors the video bubble's tap-to-fetch.
                            if (!startDownload && localFile == null) {
                                startDownload = true
                                return@clickable
                            }
                            if (isPlayingThis) {
                                dev.ipf.darkmatter.audio.VoicePlaybackController
                                    .pause()
                                return@clickable
                            }
                            scope.launch {
                                val file =
                                    localFile ?: runCatching {
                                        loading = true
                                        materializeVoiceAttachment(
                                            context = context,
                                            controller = controller,
                                            messageIdHex = messageIdHex,
                                            attachmentIndex = attachmentIndex,
                                            reference = reference,
                                            mine = mine,
                                        )
                                    }.onFailure {
                                        if (it is kotlinx.coroutines.CancellationException) throw it
                                        Log.w("MediaVoiceBubble", "materialize failed for msg=${messageIdHex.take(8)}#$attachmentIndex", it)
                                        failed = true
                                    }.also { loading = false }
                                        .getOrNull()

                                if (file == null) return@launch
                                localFile = file
                                dev.ipf.darkmatter.audio.VoicePlaybackController
                                    .play(pillKey, file)
                            }
                        },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when {
                        loading ->
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = onAccent,
                            )
                        failed ->
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.voice_message_failed),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        !startDownload && localFile == null ->
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = stringResource(R.string.media_tap_to_download),
                                tint = onAccent,
                                modifier = Modifier.size(26.dp),
                            )
                        isPlayingThis ->
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = stringResource(R.string.voice_message_pause),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                        else ->
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.voice_message_play),
                                tint = onAccent,
                                modifier = Modifier.size(28.dp),
                            )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                VoiceWaveform(
                    bars = waveform,
                    progress = progressFraction,
                    playedColor = accent,
                    remainingColor = onSurfaceMuted,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp),
                    onSeek =
                        if (isThis && activeDurationMs > 0) {
                            { fraction ->
                                dev.ipf.darkmatter.audio.VoicePlaybackController
                                    .seekTo(pillKey, (fraction * activeDurationMs).toInt())
                            }
                        } else {
                            null
                        },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val timeText =
                        when {
                            isPlayingThis || isPausedThis ->
                                "${formatVoiceTime(activePositionMs)} / ${formatVoiceTime(activeDurationMs)}"
                            totalDurationMs > 0 -> formatVoiceTime(totalDurationMs)
                            else -> "0:00"
                        }
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // Speed pill: only shown once playback has been engaged
                    // for this clip, so an unplayed bubble stays uncluttered.
                    if (isThis) {
                        VoiceSpeedPill(currentSpeed = playback.speed)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceSpeedPill(currentSpeed: Float) {
    val label =
        when {
            currentSpeed >= 1.95f -> "2×"
            currentSpeed >= 1.45f -> "1.5×"
            else -> "1×"
        }
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        shape = RoundedCornerShape(10.dp),
        modifier =
            Modifier.clickable {
                dev.ipf.darkmatter.audio.VoicePlaybackController
                    .cycleSpeed()
            },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Voice attachments need a file on disk for MediaPlayer; reuse the
 * downloaded plaintext to populate a stable per-message cache file so
 * subsequent plays are instant. Own outgoing sends short-circuit through
 * the still-retained source bytes from the pending-attachments list while
 * the Blossom upload is in flight.
 */
private suspend fun materializeVoiceAttachment(
    context: android.content.Context,
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): java.io.File {
    val cacheDir = java.io.File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }
    val extension =
        when {
            reference.mediaType.contains("mp4", ignoreCase = true) -> "m4a"
            reference.mediaType.contains("aac", ignoreCase = true) -> "aac"
            reference.mediaType.contains("ogg", ignoreCase = true) -> "ogg"
            reference.mediaType.contains("wav", ignoreCase = true) -> "wav"
            else -> "bin"
        }
    val cacheFile = java.io.File(cacheDir, "$messageIdHex-$attachmentIndex.$extension")
    if (cacheFile.exists() && cacheFile.length() > 0) return cacheFile

    val retained =
        if (mine) {
            controller
                .pendingAttachmentsList(messageIdHex)
                .getOrNull(attachmentIndex)
                ?.plaintextBytes
        } else {
            null
        }
    val bytes =
        retained
            ?: controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
    withContext(Dispatchers.IO) { cacheFile.writeBytes(bytes) }
    return cacheFile
}

/** mm:ss formatter; durations cap below an hour for voice notes. */
private fun formatVoiceTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
}

@Composable
private fun VoiceWaveform(
    bars: FloatArray,
    progress: Float,
    playedColor: Color,
    remainingColor: Color,
    modifier: Modifier = Modifier,
    onSeek: ((fraction: Float) -> Unit)? = null,
) {
    var widthPx by remember { mutableStateOf(0f) }
    val seekModifier =
        if (onSeek != null) {
            Modifier.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Consume so the bubble's parent swipe-to-reply gesture
                    // doesn't snatch a rightward drag mid-scrub.
                    down.consume()
                    onSeek((down.position.x / widthPx).coerceIn(0f, 1f))
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        onSeek((change.position.x / widthPx).coerceIn(0f, 1f))
                        if (change.changedToUp() || !change.pressed) break
                    }
                }
            }
        } else {
            Modifier
        }
    Canvas(
        modifier =
            modifier
                .then(seekModifier)
                .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        val barCount = bars.size
        if (barCount == 0) return@Canvas
        val totalWidth = size.width
        val totalHeight = size.height
        val barSlot = totalWidth / barCount
        val barWidth = barSlot * 0.55f
        val cornerRadius =
            androidx.compose.ui.geometry
                .CornerRadius(barWidth / 2f, barWidth / 2f)
        val playedBars = (progress * barCount).toInt()
        for (i in 0 until barCount) {
            val barHeight = totalHeight * bars[i]
            val x = i * barSlot + (barSlot - barWidth) / 2f
            val y = (totalHeight - barHeight) / 2f
            val color = if (i < playedBars) playedColor else remainingColor
            drawRoundRect(
                color = color,
                topLeft =
                    androidx.compose.ui.geometry
                        .Offset(x, y),
                size =
                    androidx.compose.ui.geometry
                        .Size(barWidth, barHeight),
                cornerRadius = cornerRadius,
            )
        }
    }
}

/**
 * Compact uppercase label for the file-bubble's MIME line: `application/pdf`
 * becomes "PDF", `image/jpeg` becomes "JPG", `application/vnd.…` falls back
 * to the lowercase MIME so the bubble never goes blank.
 */
private fun shortMediaTypeLabel(mediaType: String): String {
    val trimmed = mediaType.trim()
    if (trimmed.isEmpty()) return ""
    val tail = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
    return when (val canonical = tail.substringBefore('+').substringBefore(';').lowercase()) {
        "jpeg" -> "JPG"
        "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
        "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLSX"
        "vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPTX"
        "msword" -> "DOC"
        "vnd.ms-excel" -> "XLS"
        "vnd.ms-powerpoint" -> "PPT"
        "" -> trimmed
        else -> canonical.uppercase()
    }
}

private fun fileIconFor(mediaType: String): androidx.compose.ui.graphics.vector.ImageVector =
    when {
        mediaType.startsWith("audio/", ignoreCase = true) -> Icons.Default.Audiotrack
        mediaType.startsWith("video/", ignoreCase = true) -> Icons.Default.Movie
        mediaType.startsWith("image/", ignoreCase = true) -> Icons.Default.Image
        else -> Icons.Default.Description
    }

private fun formatFileSize(bytes: Long): String {
    if (bytes < 0L) return ""
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(java.util.Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.US, "%.1f GB", gb)
}

private enum class OpenAttachmentResult { Opened, NoHandler, Error }

/** Which `ImageSearchSheet` button is currently driving an in-flight
 *  mutation, so the sheet can place the spinner on it. */
private enum class GroupImageAction { Apply, Remove }

/**
 * Write [bytes] to a temp file in the cache directory and fire `ACTION_VIEW`
 * for it via the app's FileProvider so an external app (PDF reader, etc.)
 * can open it.
 *
 * Distinguishes "no app claims this MIME" ([OpenAttachmentResult.NoHandler])
 * from "we couldn't even try" ([OpenAttachmentResult.Error]) so the caller
 * can surface the right toast.
 *
 * `resolveActivity`/`queryIntentActivities` are intentionally NOT used to
 * pre-flight the launch: under Android 11+ package visibility they return
 * null for any handler whose package isn't declared in `<queries>`, even
 * when the activity exists and `startActivity` would launch it. Catching
 * `ActivityNotFoundException` from `startActivity` is the authoritative
 * "nothing handles this MIME" signal.
 *
 * Suspends because the temp-file write can be a multi-megabyte hop —
 * documents and videos picked from the document bubble are read whole
 * into a `ByteArray` and need to land on disk before the intent fires.
 * Doing that on the main dispatcher would jank the UI for the whole
 * write; the `Dispatchers.IO` jump moves it off the main thread.
 *
 * The temp file is owned by the cache cleanup pass triggered on screen
 * exit; we don't track it per-call because the handing-off intent may
 * need it alive for an unbounded duration after this function returns.
 */
private suspend fun openAttachmentExternally(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): OpenAttachmentResult {
    val uri =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                val name = MediaPipeline.safeDisplayName(fileName)
                val file = java.io.File.createTempFile("open_", "_$name", dir)
                file.writeBytes(bytes)
                fileProviderUri(context, file)
            }.getOrNull()
        } ?: return OpenAttachmentResult.Error
    val mime = mediaType.ifBlank { "application/octet-stream" }
    val intent =
        android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    return try {
        context.startActivity(intent)
        OpenAttachmentResult.Opened
    } catch (_: android.content.ActivityNotFoundException) {
        OpenAttachmentResult.NoHandler
    } catch (_: SecurityException) {
        // FileProvider grant rejected, or target activity has no permission
        // to access this URI for some reason. Surfacing this as a generic
        // error is more useful than crashing.
        OpenAttachmentResult.Error
    }
}

/**
 * Circular tap target overlaid on a media bubble. Used for both the
 * "tap to download" affordance (download arrow) and the "tap to retry"
 * affordance (refresh arrow) so the receiver-side bubble feels like a
 * polished media-message card instead of a flat icon-label stack.
 *
 * Renders as a ~52dp opaque scrim circle with a centered icon — works
 * over a blurred thumbhash placeholder or a plain surface tint without
 * fighting the background.
 */
@Composable
private fun MediaCircleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier.size(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun MediaBubbleAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .wrapContentSize(Alignment.Center)
                .padding(16.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaPendingPlaceholder(
    pendingAttachments: List<PendingAttachment>,
    failed: Boolean,
    onRetry: (() -> Unit)? = null,
) {
    val statusLabel = stringResource(if (failed) R.string.media_upload_failed else R.string.media_uploading)
    val statusColor = if (failed) MaterialTheme.colorScheme.error else Color.White

    // Image-only sends keep the fixed-height image bubble. The moment a
    // non-image attachment is part of the album the bubble shape switches to
    // a stack of file-pill placeholders so the optimistic → confirmed swap
    // matches the post-upload layout (image grid above, file pills below).
    val allImages = pendingAttachments.isNotEmpty() && pendingAttachments.all { isImagePendingAttachment(it) }
    if (!allImages) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            pendingAttachments.forEach { attachment ->
                PendingFilePill(
                    fileName = attachment.fileName,
                    mediaType = attachment.mediaType,
                    sizeBytes = attachment.plaintextBytes.size.toLong(),
                    failed = failed,
                    statusLabel = statusLabel,
                    onRetry = onRetry,
                )
            }
        }
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (pendingAttachments.size <= 1) {
                // Single-image optimistic: same sizing as the confirmed
                // bubble so the optimistic→confirmed swap doesn't reflow
                // the timeline. Source aspect ratio comes from the
                // attachment's own `dim` (set at pick time).
                val attachment = pendingAttachments.firstOrNull()
                val preview = rememberSampledBitmap(attachment?.plaintextBytes)
                val ratio = aspectRatioFromDim(attachment?.dim)
                Box(
                    imageBubbleSizing(ratio),
                    contentAlignment = Alignment.Center,
                ) {
                    preview?.let {
                        Image(
                            bitmap = it,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)))
                    }
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = preview != null,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                    )
                }
            } else {
                // Album: route through the same count-specific masonry
                // layout the confirmed bubble uses so the optimistic →
                // confirmed transition is a visual no-op even on the
                // 3-image case. Each tile decodes from local bytes (no
                // network), and a single status overlay sits across the
                // whole bubble.
                val visible = pendingAttachments.take(6)
                val overflow = (pendingAttachments.size - visible.size).coerceAtLeast(0)
                Box(Modifier.fillMaxWidth()) {
                    MasonryImageLayout(visibleCount = visible.size) { index, tileModifier ->
                        val attachment = visible[index]
                        val showOverflow = index == visible.lastIndex && overflow > 0
                        PendingGridTile(
                            bytes = attachment.plaintextBytes,
                            overflowCount = if (showOverflow) overflow else 0,
                            modifier = tileModifier,
                        )
                    }
                    Box(
                        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f)),
                    )
                    PendingStatusOverlay(
                        failed = failed,
                        hasPreview = true,
                        statusLabel = statusLabel,
                        statusColor = statusColor,
                        onRetry = onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

private fun isImagePendingAttachment(attachment: PendingAttachment): Boolean = attachment.mediaType.startsWith("image/", ignoreCase = true)

@Composable
private fun PendingFilePill(
    fileName: String,
    mediaType: String,
    sizeBytes: Long,
    failed: Boolean,
    statusLabel: String,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (failed && onRetry != null) {
                        Modifier.clickable(onClick = onRetry)
                    } else {
                        Modifier
                    },
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = fileIconFor(mediaType),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    MediaPipeline.safeDisplayName(fileName),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatFileSize(sizeBytes)} · $statusLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (failed) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PendingStatusOverlay(
    failed: Boolean,
    hasPreview: Boolean,
    statusLabel: String,
    statusColor: Color,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (failed) {
            // Tap target for retry. Without this the user only has the
            // small refresh icon down in the status row, which is easy to
            // miss on a media bubble dominated by a blurred preview.
            if (onRetry != null) {
                MediaCircleAction(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry),
                    onClick = onRetry,
                )
            } else {
                Icon(
                    Icons.Default.BrokenImage,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp),
                )
            }
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.dp,
                color = if (hasPreview) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (hasPreview) {
                    statusColor
                } else {
                    if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun PendingGridTile(
    bytes: ByteArray,
    overflowCount: Int,
    modifier: Modifier = Modifier,
) {
    val preview = rememberSampledBitmap(bytes)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (overflowCount > 0 && preview != null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Decode [bytes] to a sampled [ImageBitmap] off the main thread; null while
 *  decoding or when [bytes] is null/undecodable. */
@Composable
private fun rememberSampledBitmap(bytes: ByteArray?): ImageBitmap? {
    var bitmap by remember(bytes) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap =
            if (bytes == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(bytes, MediaPipeline.THUMBNAIL_MAX_EDGE_PX)?.asImageBitmap()
                }
            }
    }
    return bitmap
}

/**
 * Resolve the decrypted bytes for an attachment, preferring the retained
 * plaintext in `pendingAttachmentsList` for own optimistic sends so the
 * viewer / save / share paths don't spin while waiting for the projection
 * to reconcile. Falls back to the standard FFI download for everything else.
 */
private suspend fun attachmentBytes(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    mine: Boolean,
): ByteArray {
    if (mine) {
        controller
            .pendingAttachmentsList(messageIdHex)
            .getOrNull(attachmentIndex)
            ?.plaintextBytes
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
    }
    return controller.downloadAttachment(messageIdHex, attachmentIndex, reference)
}

@Composable
private fun FullScreenImageViewer(
    controller: ConversationController,
    appState: DarkMatterAppState,
    messageIdHex: String,
    attachments: List<IndexedValue<MediaAttachmentReferenceFfi>>,
    startIndex: Int,
    onDismiss: () -> Unit,
    mine: Boolean = false,
) {
    if (attachments.isEmpty()) {
        // Defensive — callers shouldn't open an empty viewer, but guard so the
        // pager doesn't NPE on a vanished album.
        onDismiss()
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.media_saved)
    val saveFailedMessage = stringResource(R.string.media_save_failed)
    val pagerState =
        rememberPagerState(
            initialPage = startIndex.coerceIn(0, attachments.lastIndex),
            pageCount = { attachments.size },
        )
    val currentEntry = attachments[pagerState.currentPage]
    val currentReference = currentEntry.value
    val currentAttachmentIndex = currentEntry.index
    // Zoom state is hoisted to the viewer scope (not per-page) so the pager
    // can read it to gate horizontal swipe. Without this gate, the page's
    // `detectTransformGestures` claims every horizontal drag and the pager
    // never moves. Page change resets to identity below.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // Disable pager swipe while the visible page is zoomed in —
                // otherwise the pan gesture and the pager's swipe both want
                // the horizontal drag. At scale 1× the pager wins.
                userScrollEnabled = scale <= 1f,
            ) { page ->
                val pageEntry = attachments[page]
                if (MediaReferenceParser.isVideoMedia(pageEntry.value)) {
                    VideoViewerPage(
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = pageEntry.index,
                        reference = pageEntry.value,
                        isCurrent = page == pagerState.currentPage,
                        mine = mine,
                    )
                } else {
                    ViewerPage(
                        controller = controller,
                        messageIdHex = messageIdHex,
                        attachmentIndex = pageEntry.index,
                        reference = pageEntry.value,
                        scale = if (page == pagerState.currentPage) scale else 1f,
                        offset = if (page == pagerState.currentPage) offset else Offset.Zero,
                        onScaleChange = { if (page == pagerState.currentPage) scale = it },
                        onOffsetChange = { if (page == pagerState.currentPage) offset = it },
                        mine = mine,
                    )
                }
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
                }
                if (attachments.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${attachments.size}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Row {
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            scope.launch {
                                val data =
                                    runCatching {
                                        attachmentBytes(controller, messageIdHex, attachmentIndex, ref, mine)
                                    }.getOrNull()
                                val ok =
                                    data != null &&
                                        withContext(Dispatchers.IO) {
                                            if (MediaReferenceParser.isVideoMedia(ref)) {
                                                saveVideoToGallery(context, data, ref.fileName, ref.mediaType)
                                            } else {
                                                saveImageToGallery(context, data, ref.fileName, ref.mediaType)
                                            }
                                        }
                                snackbarHostState.showSnackbar(if (ok) savedMessage else saveFailedMessage)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.media_save), tint = Color.White)
                    }
                    IconButton(
                        onClick = {
                            val ref = currentReference
                            val attachmentIndex = currentAttachmentIndex
                            scope.launch {
                                runCatching {
                                    attachmentBytes(controller, messageIdHex, attachmentIndex, ref, mine)
                                }.getOrNull()?.let { shareImage(context, it, ref.fileName, ref.mediaType) }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.share), tint = Color.White)
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
            )
        }
    }
}

/**
 * One page of the full-screen pager. Owns its own download + decode + pan/zoom
 * state so swiping to a sibling page doesn't carry zoom across, and disposing
 * the page recycles the multi-MB native bitmap instead of leaning on GC. The
 * pager prefetches one page either side by default, which is why
 * `LaunchedEffect` doesn't need to wait for "page becomes visible" — it
 * downloads as soon as the page composes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    isCurrent: Boolean,
    mine: Boolean,
) {
    val context = LocalContext.current
    var localFile by remember(messageIdHex, attachmentIndex, reference.sourceEpoch) {
        mutableStateOf<java.io.File?>(null)
    }
    LaunchedEffect(messageIdHex, attachmentIndex, reference.sourceEpoch) {
        if (localFile != null) return@LaunchedEffect
        // Receive-side: skip epoch=0 (FFI download would error). Own
        // optimistic sends still have their bytes in pendingAttachmentsList
        // even at epoch=0, so we let materializeVideoAttachment short-
        // circuit through the retained-bytes path with mine=true.
        if (!mine && reference.sourceEpoch == 0uL) return@LaunchedEffect
        runCatching {
            materializeVideoAttachment(
                context = context,
                controller = controller,
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                reference = reference,
                mine = mine,
            )
        }.onSuccess { localFile = it }
    }
    val file = localFile
    if (file == null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
        return
    }
    val exo =
        remember(file) {
            androidx.media3.exoplayer.ExoPlayer
                .Builder(context)
                .build()
                .apply {
                    setMediaItem(
                        androidx.media3.common.MediaItem
                            .fromUri(android.net.Uri.fromFile(file)),
                    )
                    prepare()
                }
        }
    DisposableEffect(exo) { onDispose { exo.release() } }
    // Pre-composed neighbour pages must NOT play audio — only the visible
    // one autoplays. Pause when the page scrolls off-screen.
    LaunchedEffect(isCurrent, exo) {
        if (isCurrent) exo.playWhenReady = true else exo.pause()
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                player = exo
                useController = true
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                controllerShowTimeoutMs = 2500
            }
        },
    )
}

@Composable
private fun ViewerPage(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    reference: MediaAttachmentReferenceFfi,
    scale: Float,
    offset: Offset,
    onScaleChange: (Float) -> Unit,
    onOffsetChange: (Offset) -> Unit,
    mine: Boolean,
) {
    // `pointerInput(pageKey)` only restarts when the key changes — its
    // coroutine outlives any single gesture. Function parameters
    // (`scale`, `offset`, the callbacks) captured directly inside that
    // coroutine would stay at their initial values for the lifetime of
    // the gesture, causing jumpy zoom/pan and stale callback dispatch.
    // `rememberUpdatedState` snapshots each parameter into a stable
    // State<T> whose `.value` reads inside the coroutine always reflect
    // the most recent recomposition's value.
    val latestScale by rememberUpdatedState(scale)
    val latestOffset by rememberUpdatedState(offset)
    val latestOnScaleChange by rememberUpdatedState(onScaleChange)
    val latestOnOffsetChange by rememberUpdatedState(onOffsetChange)
    // `sourceEpoch` is folded into the page key so a viewer that failed
    // its first decrypt at epoch 0 (typed reference not yet loaded) re-keys
    // and retries when the real reference arrives.
    val pageKey = "$messageIdHex#$attachmentIndex#${reference.sourceEpoch}"
    var androidBitmap by remember(pageKey) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var viewerFailed by remember(pageKey) { mutableStateOf(false) }
    var viewerReloadToken by remember(pageKey) { mutableStateOf(0) }
    val bitmap = remember(androidBitmap) { androidBitmap?.asImageBitmap() }
    LaunchedEffect(pageKey, viewerReloadToken) {
        viewerFailed = false
        try {
            val data = attachmentBytes(controller, messageIdHex, attachmentIndex, reference, mine)
            // Bounded sampled decode. A 5000px remote image decoded full-size
            // is ~100 MB ARGB_8888 and OOMs mid-class devices; the viewer
            // ceiling caps that while keeping quality high enough on phones.
            val decoded =
                withContext(Dispatchers.Default) {
                    MediaPipeline.decodeSampledBitmap(data, MediaPipeline.VIEWER_MAX_EDGE_PX)
                }
            if (decoded != null) {
                androidBitmap = decoded
            } else {
                viewerFailed = true
            }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            viewerFailed = true
        }
    }
    DisposableEffect(pageKey) {
        onDispose { androidBitmap?.recycle() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val current = bitmap
        when {
            current != null ->
                Image(
                    bitmap = current,
                    contentDescription = MediaPipeline.safeDisplayName(reference.fileName),
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .pointerInput(pageKey) {
                                detectTapGestures(onDoubleTap = {
                                    latestOnScaleChange(1f)
                                    latestOnOffsetChange(Offset.Zero)
                                })
                            }.pointerInput(pageKey) {
                                // Hand-rolled gesture loop instead of
                                // `detectTransformGestures` because the latter
                                // consumes single-pointer drags unconditionally,
                                // which steals horizontal swipes from the
                                // HorizontalPager parent. Here:
                                //   - Pinch (≥2 pointers): consume → zoom + pan.
                                //   - Single-pointer at scale > 1: consume → pan.
                                //   - Single-pointer at scale 1×: DO NOT consume →
                                //     pager handles the swipe between attachments.
                                //
                                // All references to scale/offset/callbacks go
                                // through `latest*` delegates so each loop
                                // iteration sees the freshest values — see the
                                // `rememberUpdatedState` setup above for why.
                                awaitEachGesture {
                                    do {
                                        val event = awaitPointerEvent()
                                        val pressedCount =
                                            event.changes.count { it.pressed }
                                        if (pressedCount == 0) break
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val currentScale = latestScale
                                        val currentOffset = latestOffset
                                        val handleAsTransform =
                                            pressedCount >= 2 || currentScale > 1f
                                        if (!handleAsTransform) {
                                            // Let the pager have it.
                                            continue
                                        }
                                        val nextScale = (currentScale * zoom).coerceIn(1f, 5f)
                                        if (nextScale != currentScale) latestOnScaleChange(nextScale)
                                        if (nextScale > 1f) {
                                            val viewportW = size.width.toFloat()
                                            val viewportH = size.height.toFloat()
                                            val imageAspect = current.width.toFloat() / current.height.toFloat()
                                            val viewportAspect = viewportW / viewportH
                                            val baseWidth: Float
                                            val baseHeight: Float
                                            if (imageAspect > viewportAspect) {
                                                baseWidth = viewportW
                                                baseHeight = viewportW / imageAspect
                                            } else {
                                                baseHeight = viewportH
                                                baseWidth = viewportH * imageAspect
                                            }
                                            val maxX = ((baseWidth * nextScale) - viewportW).coerceAtLeast(0f) / 2f
                                            val maxY = ((baseHeight * nextScale) - viewportH).coerceAtLeast(0f) / 2f
                                            latestOnOffsetChange(
                                                Offset(
                                                    (currentOffset.x + pan.x).coerceIn(-maxX, maxX),
                                                    (currentOffset.y + pan.y).coerceIn(-maxY, maxY),
                                                ),
                                            )
                                        } else if (currentOffset != Offset.Zero) {
                                            latestOnOffsetChange(Offset.Zero)
                                        }
                                        event.changes.forEach { it.consume() }
                                    } while (true)
                                }
                            }.graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                )
            viewerFailed ->
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        stringResource(R.string.media_save_failed),
                        color = Color.White,
                    )
                    TextButton(onClick = { viewerReloadToken += 1 }) {
                        Text(stringResource(R.string.media_tap_to_retry), color = Color.White)
                    }
                }
            else ->
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
        }
    }
}

/**
 * Persist [bytes] to the device gallery (Pictures/DarkMatter). Returns success.
 * Uses the IS_PENDING dance so other apps never see a half-written entry, and
 * sanitizes the remote-supplied [fileName] to a basename.
 */
private fun saveImageToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            // Preserve the attachment's real MIME (a peer may send PNG/WebP/HEIC),
            // so gallery indexing matches the actual bytes.
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME })
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DarkMatter")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
    return try {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw java.io.IOException("null output stream")
            out.write(bytes)
        }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null) // don't leave a pending orphan
        false
    }
}

/** Persist a decrypted video to the public Movies/DarkMatter folder via the
 *  Video MediaStore so it shows up in the system gallery. Mirrors the image
 *  save flow's IS_PENDING dance. */
private fun saveVideoToGallery(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
): Boolean {
    val resolver = context.contentResolver
    val values =
        android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, MediaPipeline.safeDisplayName(fileName))
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, mediaType.ifBlank { "video/mp4" })
            put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/DarkMatter")
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
    return try {
        resolver.openOutputStream(uri).use { out ->
            if (out == null) throw java.io.IOException("null output stream")
            out.write(bytes)
        }
        values.clear()
        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (_: Throwable) {
        resolver.delete(uri, null, null)
        false
    }
}

/**
 * Share [bytes] via a FileProvider Uri using the system share sheet.
 *
 * Suspends because the temp-file write is multi-megabyte for any non-trivial
 * attachment; doing it on the main dispatcher would stall the UI for the
 * write. The `startActivity` call has to run on Main, so the I/O is hopped
 * to `Dispatchers.IO` and the chooser is fired back on Main.
 */
private suspend fun shareImage(
    context: android.content.Context,
    bytes: ByteArray,
    fileName: String,
    mediaType: String,
) {
    val uri =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = java.io.File(context.cacheDir, MediaCacheDirs.SHARED).apply { mkdirs() }
                // Unique temp keyed off a sanitized basename — avoids
                // collisions and path traversal from a remote-supplied
                // filename.
                val file = java.io.File.createTempFile("share_", "_" + MediaPipeline.safeDisplayName(fileName), dir)
                file.outputStream().use { it.write(bytes) }
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
            }.getOrNull()
        } ?: return
    runCatching {
        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mediaType.ifBlank { MediaPipeline.RECOMPRESSED_MIME }
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(
            android.content.Intent.createChooser(intent, null).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

/** Create a cache file for a camera capture. Returns null if it can't be made. */
private fun createImageCaptureFile(context: android.content.Context): java.io.File? =
    try {
        val dir = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
        java.io.File.createTempFile("capture_", ".jpg", dir)
    } catch (_: Throwable) {
        null
    }

private fun fileProviderUri(
    context: android.content.Context,
    file: java.io.File,
): android.net.Uri =
    androidx.core.content.FileProvider
        .getUriForFile(context, "${context.packageName}.fileprovider", file)

/**
 * Best-effort wipe of decrypted camera-capture temp files from cache.
 *
 * Intentionally does NOT touch `shared_media`. Those entries back live
 * FileProvider URIs the system may still be reading after the user backs
 * out of a chat (an external PDF reader holding the granted URI, the
 * system share-sheet target, etc.). Yanking the file out from under
 * those readers caused the "opened PDF goes blank when I leave the chat"
 * class of bug — the [sweepStaleSharedMedia] janitor cleans those on a
 * stale-age basis at app start instead.
 */
private fun clearMediaTempFiles(context: android.content.Context) {
    runCatching { java.io.File(context.cacheDir, "camera").deleteRecursively() }
}

/**
 * Delete `shared_media` files older than [maxAgeMillis]. Called once at
 * app start so transient FileProvider temps for opened/shared
 * attachments don't accumulate across sessions, without racing the
 * external readers that may still be using them in the current session.
 */
private fun sweepStaleSharedMedia(
    context: android.content.Context,
    maxAgeMillis: Long,
) {
    runCatching {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        // Same age-based reaper covers the decrypted voice cache too —
        // those bytes are plaintext E2EE-decrypted audio and shouldn't
        // linger past the last MediaPlayer that opened them.
        listOf(MediaCacheDirs.SHARED, MediaCacheDirs.VOICE, MediaCacheDirs.VIDEO).forEach { name ->
            val dir = java.io.File(context.cacheDir, name)
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { entry ->
                if (entry.isFile && entry.lastModified() < cutoff) {
                    runCatching { entry.delete() }
                }
            }
        }
    }
}

/** Files in `shared_media` older than this are considered safe to delete —
 *  any external reader has had ample time to finish loading the bytes. */
private const val SHARED_MEDIA_MAX_AGE_MS: Long = 10L * 60L * 1000L

/** Decode a downscaled preview bitmap for a local content Uri, off-thread. */
@Composable
private fun rememberLocalPreviewBitmap(uri: android.net.Uri): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            withContext(Dispatchers.Default) {
                val mime = safeGetType(context.contentResolver, uri)
                if (mime.startsWith("video/", ignoreCase = true)) {
                    // Video URI: extract the first frame as the staging thumbnail
                    // instead of trying to decode the bytes as JPEG (which spins
                    // forever on a video and leaves the sheet stuck). Scaled to
                    // the staging tile size — full-res posters from a 4K clip
                    // would be a ~33 MB ARGB bitmap per tile.
                    runCatching {
                        val mmr = android.media.MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(context, uri)
                            val edge = MediaPipeline.THUMBNAIL_MAX_EDGE_PX
                            mmr
                                .getScaledFrameAtTime(
                                    0L,
                                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                    edge,
                                    edge,
                                )?.asImageBitmap()
                        } finally {
                            runCatching { mmr.release() }
                        }
                    }.getOrNull()
                } else {
                    // Decode the picked image straight to a sampled bitmap,
                    // preserving its native format and alpha. Earlier this
                    // round-tripped through MediaPipeline.readDownscaledJpeg
                    // (recompress to JPEG) and then re-decoded those bytes at
                    // full resolution — that flattened transparent PNGs onto
                    // white and, on large lossless sources (e.g. PNG
                    // screenshots), the recompress or the un-sampled re-decode
                    // could silently OOM/fail, leaving the staging tile stuck
                    // on a spinner that never resolved (#387). Mirrors the
                    // in-bubble thumbnail path (decodeSampledBitmap).
                    runCatching {
                        MediaPipeline
                            .decodeSampledFromUri(
                                context.contentResolver,
                                uri,
                                MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
                            )?.asImageBitmap()
                    }.getOrNull()
                }
            }
    }
    return bitmap
}

@Composable
private fun LocalImagePreview(
    uri: android.net.Uri,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberLocalPreviewBitmap(uri)
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StagingTile(
    onRemove: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .aspectRatio(1f),
    ) {
        content()
        FilledIconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp),
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.media_attachment_remove),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun StagingDocumentTile(uri: android.net.Uri) {
    val context = LocalContext.current
    val displayName =
        remember(uri) { queryDisplayName(context.contentResolver, uri) ?: "file" }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaPreviewSheet(
    uris: List<android.net.Uri>,
    documentUris: List<android.net.Uri>,
    onDismiss: () -> Unit,
    onSend: (caption: String) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onRemoveDocumentAt: (Int) -> Unit,
    onAddPhotos: () -> Unit,
    onAddDocuments: () -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    // Local guard against a rapid double-tap firing onSend twice before the
    // parent clears pendingMediaUris and the sheet leaves composition.
    var sending by remember { mutableStateOf(false) }
    var addMoreMenuOpen by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Horizontally-scrollable shelf of square tiles, one per staged
            // attachment plus a trailing "Add more" tile. Each tile carries a
            // small `✕` overlay that removes only that item from the queue.
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp, max = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(uris, key = { index, uri -> "image:$index:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveAt(index) },
                    ) {
                        LocalImagePreview(
                            uri = uri,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
                itemsIndexed(documentUris, key = { index, uri -> "doc:$index:$uri" }) { index, uri ->
                    StagingTile(
                        onRemove = { if (!sending) onRemoveDocumentAt(index) },
                    ) {
                        StagingDocumentTile(uri = uri)
                    }
                }
                item(key = "media_preview_add_more_tile") {
                    // Anchor a DropdownMenu to the tile so the user can add
                    // either kind to a mixed shelf — the tile alone can't
                    // know which (images vs files) the user wants to append.
                    Box {
                        OutlinedButton(
                            onClick = { if (!sending) addMoreMenuOpen = true },
                            modifier =
                                Modifier
                                    .fillMaxHeight()
                                    .aspectRatio(1f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !sending,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.media_attachment_add_more),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = addMoreMenuOpen,
                            onDismissRequest = { addMoreMenuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddPhotos()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                onClick = {
                                    addMoreMenuOpen = false
                                    onAddDocuments()
                                },
                            )
                        }
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.add_caption)) },
                    maxLines = 4,
                    enabled = !sending,
                )
                FilledIconButton(
                    onClick = {
                        if (sending) return@FilledIconButton
                        sending = true
                        onSend(caption)
                    },
                    enabled = !sending,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun UnreadMessagesDivider(count: Int) {
    val text = pluralStringResource(R.plurals.unread_messages_count, count, count)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .padding(horizontal = 12.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private fun differentDay(
    a: ULong,
    b: ULong,
): Boolean {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochSecond(a.toLong()).atZone(zone).toLocalDate() !=
        Instant.ofEpochSecond(b.toLong()).atZone(zone).toLocalDate()
}

// Today/Yesterday, then weekday within a week, then a locale-medium date —
// all sourced from the platform so the ribbon needs no new translation keys.
private fun messageDayLabel(
    epochSeconds: ULong,
    locale: Locale,
): String {
    if (epochSeconds == 0uL) return ""
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochSecond(epochSeconds.toLong()).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(zone))
    return when {
        days <= 0L || days == 1L ->
            DateUtils
                .getRelativeTimeSpanString(
                    epochSeconds.toLong() * 1000L,
                    System.currentTimeMillis(),
                    DateUtils.DAY_IN_MILLIS,
                ).toString()
        days in 2L..6L -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, locale)
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

@Composable
private fun DaySeparator(label: String) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * Centered one-line row for a kind-1210 group system event ("%s changed the
 * group avatar", membership changes, renames). Rendered from `system_type` +
 * `data` with display names resolved live — [DarkMatterAppState.displayName]
 * reads the profile revision, so the row re-renders when a name loads. An
 * unparseable payload renders the generic fallback, never the raw content.
 */
@Composable
private fun GroupSystemRow(
    record: AppMessageRecordFfi,
    appState: DarkMatterAppState,
    groupSystem: GroupSystemEventFfi? = null,
) {
    val copy = rememberGroupSystemCopy()
    val event = remember(record.plaintext, groupSystem) { GroupSystemEvents.resolve(record.plaintext, groupSystem) }
    val summary =
        if (event != null) {
            run {
                val selfHex = appState.activeAccount?.accountIdHex
                val actorHex = GroupSystemEvents.actorHex(event, record.sender)
                GroupSystemEvents.summary(
                    event = event,
                    actorName = actorHex?.let { appState.displayName(it) },
                    subjectName = event.subject?.let { appState.displayName(it) },
                    actorIsSelf = GroupSystemEvents.isSelf(selfHex, actorHex),
                    subjectIsSelf = GroupSystemEvents.isSelf(selfHex, event.subject),
                    copy = copy,
                )
            }
        } else {
            copy.fallback
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier =
                Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// How many rows from the top to begin prefetching the next older page.
private const val OLDER_PAGE_PREFETCH_ROWS = 4

// Debounce before the chat-list message-body search fires its per-chat FFI
// queries (issue #290). Sits inside the existing 250–300 ms chat-list input
// debounce band so a fast typist doesn't trigger a query per keystroke.
private const val CHAT_LIST_SEARCH_DEBOUNCE_MS: Long = 275L

/**
 * Shared definition of "user is at (or near) the newest message". Used both
 * by the auto-scroll LaunchedEffect (issue #59) and the jump-to-newest FAB
 * so they can't disagree on the threshold.
 */
private fun isNearBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    timelineSize: Int,
    hasOlderHeader: Boolean,
): Boolean {
    if (!listState.canScrollForward) return true
    val olderHeaderCount = if (hasOlderHeader) 1 else 0
    val bottomTimelineIndex = timelineSize + 1 + olderHeaderCount
    // Check the LAST visible item, not the first — keeps "near bottom"
    // truthful when the viewport shrinks (e.g. keyboard open) and fewer
    // items fit, which pushes firstVisibleItemIndex earlier even though
    // the bottom is still on-screen.
    val lastVisible =
        listState.layoutInfo.visibleItemsInfo
            .lastOrNull()
            ?.index ?: return false
    return lastVisible >= bottomTimelineIndex - 1
}

/** Read the user-visible filename a content Uri exposes via OpenableColumns,
 *  falling back to the Uri's path segment. Null when neither is available.
 *
 *  Guarded against a revoked grant: a Photo Picker / SAF Uri staged before
 *  process death (issue #531) comes back as a ghost whose session-scoped read
 *  permission is gone, so `query()` throws `SecurityException` (or the backing
 *  provider may be dead — `IllegalArgumentException` / `NullPointerException`).
 *  We swallow it and fall through to the path-segment fallback so the staging
 *  preview renders a placeholder name instead of crashing; the actual decode
 *  still fails gracefully into the existing toast path. */
private fun queryDisplayName(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String? {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
    }
    return uri.lastPathSegment
}

/**
 * Best-effort byte size of a content Uri, queried via `OpenableColumns.SIZE`.
 * Returns -1 when the provider doesn't report a size (some virtual / streamed
 * providers omit it); callers must then enforce a cap via the bounded read.
 *
 * Also returns -1 when the Uri's grant has been revoked (a ghost Uri restored
 * after process death — see [queryDisplayName] / issue #531): the bounded read
 * downstream is itself `SecurityException`-guarded and will reject the file, so
 * treating a revoked grant as "size unknown" routes it into the same graceful
 * rejection rather than crashing the send coroutine.
 */
private fun queryContentSize(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): Long {
    runCatching {
        contentResolver
            .query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0)
                }
            }
    }
    return -1L
}

/** `ContentResolver.getType` for a content Uri whose read grant may have been
 *  revoked (a ghost staging Uri restored after process death — issue #531).
 *  The platform docs say `getType` can throw `SecurityException` for a Uri the
 *  caller can no longer access; an unguarded call on a ghost Uri crashes the
 *  preview composition or the send coroutine before the already-guarded decode
 *  gets a chance to degrade. Returns "" on any failure so callers treat the
 *  ghost as an unknown / non-video type and let the guarded decode reject it
 *  into the existing decode-failure toast. */
private fun safeGetType(
    contentResolver: android.content.ContentResolver,
    uri: android.net.Uri,
): String = coerceResolvedMime { contentResolver.getType(uri) }

/** Pure swallow-and-default kernel behind [safeGetType], split out so the
 *  ghost-Uri contract (issue #531) — a throwing or null resolver lookup must
 *  collapse to "" rather than propagate — is unit-testable on the JVM without
 *  Robolectric, mirroring the `UriListSaver` codec split. */
internal inline fun coerceResolvedMime(getType: () -> String?): String = runCatching(getType).getOrNull().orEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    appState: DarkMatterAppState,
    chat: ChatListItem,
    onBack: () -> Unit,
    // When opened from a chat-list message-body search hit (issue #290), the
    // matched message id to scroll to and briefly highlight once the timeline
    // has paged it in. Null for every normal open path.
    focusMessageId: String? = null,
    // True only when this conversation was just created in the same navigation
    // step (issue #321) — drives a one-shot composer focus + keyboard raise so
    // the user can type the first message without an extra tap. False for row
    // taps, notification routing, and search hits.
    justCreated: Boolean = false,
) {
    // Push the global snackbar host above the conversation composer so
    // a toast (e.g. the post-invite-accept confirmation) doesn't
    // overlap and intercept touches on the message input. Resets to
    // zero on dispose so other surfaces aren't affected. Issue #122.
    val snackbarBottomInset = LocalSnackbarBottomInset.current
    // Keyed on chat.id so that a back-to-back conversation push (Compose
    // reusing the same node across nav) re-runs the effect: the
    // previous chat's onDispose may not have fired before the next
    // enters, leaving the inset at zero on a stale snackbar host.
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
                copy = controllerCopy,
            )
        }
    var menuOpen by remember { mutableStateOf(false) }
    // Keyed on chat.id so opening a different conversation (e.g. via Message or
    // a shared-group tap from a profile opened on this group's details page)
    // lands on the conversation, not the new chat's details page.
    var showDetails by remember(chat.id) { mutableStateOf(false) }
    var confirmLeaveFromTopBar by remember { mutableStateOf(false) }
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
    val listState = rememberLazyListState()
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
    // Size of the rendered (edit-filtered) list. Derived once per timeline
    // change and shared by every position calculation, so the filter never
    // re-runs on the scroll path. Read as a State, so the derived blocks below
    // stay reactive to it.
    val renderedSize by remember {
        derivedStateOf { controller.timeline.count { !MessageProjector.isEdit(it.record) } }
    }
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
        val rendered = controller.timeline.filterNot { MessageProjector.isEdit(it.record) }
        readAnchorMessageId = nextReadAnchor(rendered, readAnchorMessageId, idx)
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
    LaunchedEffect(context) {
        val loaded = withContext(Dispatchers.IO) { RecentEmojiPreferences.load(context) }
        // A pick made before this load lands has already merged the disk list
        // (recordPicked re-reads prefs), so a non-empty state is strictly newer
        // — don't clobber it with the stale read.
        if (recentReactionEmojis.isEmpty()) {
            recentReactionEmojis = loaded
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
            appState.present(R.string.toast_couldnt_decode_image)
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
                    mediaType = dev.ipf.darkmatter.audio.VoiceRecorder.MIME_TYPE,
                    fileName = "voice-${durationMs}ms.${dev.ipf.darkmatter.audio.VoiceRecorder.FILE_EXTENSION}",
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
        if (quality.preservesOriginalImageBytes) {
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
                -> Unit // Fall back to JPEG re-encode so unsupported containers still strip metadata.
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
            dev.ipf.darkmatter.audio.VoiceRecordingController(
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
        dev.ipf.darkmatter.audio.VoicePlaybackController.onCompletion = { completedKey ->
            val completedMsgId = completedKey.substringBefore('#')
            val completedIdx = controller.timeline.indexOfFirst { it.record.messageIdHex == completedMsgId }
            if (completedIdx >= 0) {
                // Walk forward only as long as the next item is a derived-
                // state row (edit / group system) — those are invisible to
                // the user, so skipping them doesn't violate "immediate
                // neighbor" semantics.
                var nextIdx = completedIdx + 1
                while (nextIdx < controller.timeline.size &&
                    MessageProjector.isGroupSystem(controller.timeline[nextIdx].record)
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
                        dev.ipf.darkmatter.audio.VoicePlaybackController
                            .play("${nextMsg.record.messageIdHex}#$idx", file)
                    }
                }
            }
        }
        onDispose {
            dev.ipf.darkmatter.audio.VoicePlaybackController.onCompletion = null
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
                    appState.present(toast)
                    return@launchMutation
                }
            }
            if (acceptedImages.size < imageUris.size && !imageAlbumOverflowed) {
                appState.present(visualFailureToast)
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
                listState.animateScrollToItem(1 + olderMessagesHeaderCount + timelineIndex)
                highlightedMessageId = targetMessageId
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
    // inviteStreamScope outlives a single start() — acceptInvite() launches into
    // it from a separate mutation scope — so it's cancelled on controller
    // disposal here rather than in start()'s teardown (#279).
    DisposableEffect(controller) {
        onDispose { controller.onCleared() }
    }
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
                listState.animateScrollToItem(1 + olderHeaderCount + timelineIndex)
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
    LaunchedEffect(latestTimelineItemId) {
        if (renderedTimeline.isNotEmpty()) {
            if (!initialTimelineAnchored) {
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

    // Scroll-to-message for a chat-list message-body search hit (issue #290).
    // Waits for the first-open anchor to settle, then pages the local timeline
    // back until the matched message is materialized and scrolls to it with a
    // brief highlight — the same affordance the reply-jump uses. Fires once
    // per (chat.id, focusMessageId); a missing message (e.g. it was deleted
    // between the search and the tap) just toasts and leaves the user at the
    // normal anchor. Local-only: loadUntilMessageAvailable paginates the
    // already-persisted store, never a relay fetch.
    LaunchedEffect(chat.id, focusMessageId) {
        val target = focusMessageId ?: return@LaunchedEffect
        // Let the initial unread/newest anchor run first so our scroll isn't
        // immediately overwritten by it.
        snapshotFlow { initialTimelineAnchored }.filter { it }.first()
        if (!controller.loadUntilMessageAvailable(target)) {
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
        listState.animateScrollToItem(1 + olderMessagesHeaderCount + timelineIndex)
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
            autoOpenTransferAdmin = openTransferOnDetails,
            autoOpenAddMember = openAddMemberOnDetails,
            onAutoOpenAddMemberConsumed = { openAddMemberOnDetails = false },
        )
        return
    }

    val openDetailsDescription = stringResource(R.string.details)
    Scaffold(
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
                                seed = controller.group.groupIdHex,
                                size = 36.dp,
                                pictureUrl = controller.group.avatarUrl,
                            )
                            Column {
                                Text(
                                    controller.title(groupTitleCopy),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!controller.isDm) {
                                    Text(
                                        controller.subtitle(
                                            justYou = stringResource(R.string.just_you),
                                            oneMember = stringResource(R.string.one_member),
                                            membersFormat = stringResource(R.string.members_count),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
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
                                        if (controller.isSoleAdminWithOtherMembers) {
                                            showTransferAdminFirst = true
                                        } else {
                                            confirmLeaveFromTopBar = true
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
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
                controller.error != null || controller.group.pendingConfirmation -> Unit
                // Only suppress the composer when we've *confirmed* the user
                // is no longer a member. The kicked-notice branch must lose
                // to the composer during the load window (`membersLoaded`
                // still false) so the user isn't staring at a blank bottom
                // bar while `refreshMembers()` round-trips. The controller's
                // `canSendMessages` guard in send/upload/react/delete keeps
                // any actual mutation safe until membership is confirmed.
                controller.membersLoaded && !controller.isSelfMember -> RemovedMemberComposerNotice()
                else -> {
                    val groupIdHex = controller.group.groupIdHex
                    val editingRecord =
                        controller.editingMessageId?.let { id ->
                            controller.timeline.firstOrNull { it.record.messageIdHex == id }?.record
                        }
                    // #414: candidates for the @-mention picker — the group's
                    // own roster minus the local account (you can't mention
                    // yourself). Keyed on the roster + profile revision so a
                    // late-arriving display name / nip05 re-derives the list.
                    // The list already arrives most-recently-active first from
                    // the roster, and MentionComposer.filter preserves order.
                    val mentionPickerEnabled = !controller.isDm
                    val mentionCandidates =
                        if (mentionPickerEnabled) {
                            val revision = appState.profileRevisionForCompose
                            val activeAccountIdHex = appState.activeAccount?.accountIdHex
                            remember(controller.members, revision, activeAccountIdHex) {
                                controller.members
                                    // Exclude only the active account, not every member
                                    // flagged `local`. Marmot sets `local` for any identity
                                    // present on the device, which on some rosters marks all
                                    // members local and would empty the mention list entirely.
                                    // Mirrors the isActiveAccountMember gate used for admin actions.
                                    .filterNot { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
                                    .map { member ->
                                        MentionComposer.Candidate(
                                            accountIdHex = member.memberIdHex,
                                            npub = appState.npub(member.memberIdHex),
                                            displayName = appState.chatMemberTitle(member.memberIdHex),
                                            nip05 = appState.userProfile(member.memberIdHex)?.nip05,
                                        )
                                    }
                            }
                        } else {
                            emptyList()
                        }
                    ComposerBar(
                        replyingTo = controller.replyingTo,
                        messageTextCopy = messageTextCopy,
                        onCancelReply = { controller.replyingTo = null },
                        onSend = { text, onAccepted -> appState.launchMutation { controller.send(text, onAccepted) } },
                        initialDraft = appState.draftFor(groupIdHex).orEmpty(),
                        onDraftChange = { appState.setDraft(groupIdHex, it) },
                        draftKey = groupIdHex,
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
                        voiceRecordingController = voiceRecordingController,
                        appState = appState,
                        mentionCandidates = mentionCandidates,
                        mentionPickerEnabled = mentionPickerEnabled,
                        autoFocusOnEnter = justCreated,
                        enterKeyBehavior = appState.enterKeyBehavior,
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                controller.error != null -> ErrorContent(stringResource(R.string.couldnt_load_conversation), controller.error.orEmpty())
                controller.group.pendingConfirmation ->
                    PendingInviteContent(
                        title = controller.title(groupTitleCopy),
                        pictureUrl = controller.inviteAccount?.let { appState.avatarUrl(it) },
                        avatarSeed = controller.inviteAccount ?: controller.group.groupIdHex,
                        onAccept = {
                            appState.launchMutation { controller.acceptInvite() }
                        },
                        onDecline = {
                            appState.launchMutation {
                                if (controller.declineInvite()) onBack()
                            }
                        },
                    )
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
                                // Rendered inside the slot, not as its own item, so
                                // the anchor index math stays intact.
                                val older = renderedTimeline.getOrNull(index - 1)
                                if (older == null || differentDay(older.record.recordedAt, item.record.recordedAt)) {
                                    DaySeparator(messageDayLabel(item.record.recordedAt, transcriptLocale))
                                }
                                if (entryUnreadCount > 0 && item.record.messageIdHex == entryFirstUnreadMessageId) {
                                    UnreadMessagesDivider(count = entryUnreadCount)
                                }
                                // Group system rows (kind 1210) are derived state
                                // facts, not chat: render the centered summary row,
                                // never a bubble with the raw JSON content.
                                if (MessageProjector.isGroupSystem(item.record)) {
                                    GroupSystemRow(
                                        record = item.record,
                                        appState = appState,
                                        groupSystem = item.projected?.groupSystem,
                                    )
                                    return@itemsIndexed
                                }
                                MessageBubble(
                                    item = item,
                                    controller = controller,
                                    appState = appState,
                                    highlighted = item.record.messageIdHex == highlightedMessageId,
                                    recentReactionEmojis = recentReactionEmojis,
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
                                    onReplyPreviewClick = { navigateToReplyTarget(it) },
                                )
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
                        if (initialTimelineAnchored && !nearBottom) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shadowElevation = 2.dp,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(12.dp)
                                        .size(34.dp)
                                        .clickable {
                                            scope.launch {
                                                val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                                listState.animateScrollToItem(lastIndex)
                                            }
                                        },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.ArrowDownward,
                                        contentDescription = stringResource(R.string.jump_to_newest),
                                        modifier = Modifier.size(16.dp),
                                    )
                                    if (unreadIncomingCount > 0) {
                                        Badge(
                                            modifier =
                                                Modifier
                                                    .align(Alignment.TopEnd)
                                                    .offset(x = 10.dp, y = (-10).dp),
                                        ) {
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

    if (confirmLeaveFromTopBar) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_leave_title),
            message = stringResource(R.string.confirm_leave_message),
            confirmLabel = stringResource(R.string.leave),
            onConfirm = {
                confirmLeaveFromTopBar = false
                appState.launchMutation {
                    if (controller.leaveGroup()) onBack()
                }
            },
            onDismiss = { confirmLeaveFromTopBar = false },
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

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                // Material 3 destructive affordance: error-colored confirm
                // text for irreversible actions (leave group, remove member).
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun PendingInviteContent(
    title: String,
    pictureUrl: String?,
    avatarSeed: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(title = title, seed = avatarSeed, size = 64.dp, pictureUrl = pictureUrl)
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.invited_to_this_chat),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDecline) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.decline))
                }
                Button(onClick = onAccept) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.accept))
                }
            }
        }
    }
}

@Composable
private fun EmptyGroupConversation(onAddMembers: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(56.dp))
            Text(
                stringResource(R.string.group_empty_only_you_title),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.group_empty_invite_members),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onAddMembers) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_member))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupEditScreen(
    appState: DarkMatterAppState,
    controller: ConversationController,
    onBack: () -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    // Key only on the group id, not on name/description: the group-state
    // subscription can converge a backend update (another admin's edit, a
    // kind-1210 row) while this screen is open, and re-keying on those values
    // would re-init the fields and discard the user's in-progress edit. State
    // resets only when navigating to a different group. (CodeRabbit, #512.)
    var name by remember(controller.group.groupIdHex) { mutableStateOf(controller.group.name) }
    var description by remember(controller.group.groupIdHex) { mutableStateOf(controller.group.description) }
    var showImageSearch by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var imageSaving by remember { mutableStateOf(false) }
    val canEdit = controller.isSelfMember && controller.isSelfAdmin

    // System back returns to Group Details, not all the way out to the
    // conversation. This composes after the details screen's own BackHandler
    // (rendered just before the early return that shows this screen), so it
    // wins the back event while the editor is open.
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.edit)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = Dimens.spaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                ) {
                    val editImageLabel =
                        stringResource(
                            if (controller.group.avatarUrl.isNullOrBlank()) {
                                R.string.group_image_search_set
                            } else {
                                R.string.group_image_search_edit
                            },
                        )
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            if (canEdit) {
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        onClickLabel = editImageLabel,
                                        role = Role.Button,
                                    ) { showImageSearch = true }
                            } else {
                                Modifier.clip(CircleShape)
                            },
                    ) {
                        Avatar(
                            title = controller.title(groupTitleCopy),
                            seed = controller.group.groupIdHex,
                            size = 96.dp,
                            pictureUrl = controller.group.avatarUrl,
                        )
                        if (canEdit) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.32f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.edit)) {
                    val profileFieldColors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                        )
                    TextField(
                        colors = profileFieldColors,
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.group_name)) },
                        singleLine = true,
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        colors = profileFieldColors,
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(R.string.description)) },
                        minLines = 3,
                        enabled = canEdit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (canEdit) {
                item {
                    Button(
                        onClick = {
                            saving = true
                            controller.clearLastMutationError()
                            appState.launchMutation {
                                try {
                                    if (controller.updateGroupProfile(name, description)) onBack()
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled =
                            !saving &&
                                !controller.mutationInFlight &&
                                (name != controller.group.name || description != controller.group.description),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (saving) R.string.saving_group else R.string.save_group))
                    }
                }
            }
        }
    }

    if (showImageSearch) {
        ImageSearchSheet(
            initialUrl = controller.group.avatarUrl.orEmpty(),
            header = stringResource(R.string.group_image_search_title),
            title = controller.title(groupTitleCopy),
            seed = controller.group.groupIdHex,
            urlLabel = stringResource(R.string.group_avatar_url),
            applyInFlight = imageSaving || controller.mutationInFlight,
            onApply = { picked ->
                imageSaving = true
                controller.clearLastMutationError()
                appState.launchMutation {
                    try {
                        if (controller.updateGroupAvatarUrl(picked)) showImageSearch = false
                    } finally {
                        imageSaving = false
                    }
                }
            },
            onDismiss = { showImageSearch = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailsScreen(
    appState: DarkMatterAppState,
    controller: ConversationController,
    onBack: () -> Unit,
    onLeft: () -> Unit,
    // When true (sole admin routed in from the blocked top-level Leave gate),
    // open the transfer-admin picker immediately so the trapped admin lands on
    // the action instead of having to hunt for it in the Admins section (#417).
    autoOpenTransferAdmin: Boolean = false,
    // When true (empty-group CTA), open the shared add-member sheet after the
    // details screen is mounted so the initial invite path reuses later-add UI.
    autoOpenAddMember: Boolean = false,
    onAutoOpenAddMemberConsumed: () -> Unit = {},
) {
    var pendingMember by remember { mutableStateOf("") }
    var pendingMemberAsAdmin by remember { mutableStateOf(false) }
    var pendingMemberError by remember { mutableStateOf<String?>(null) }
    var showMemberScanner by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showEditGroup by remember { mutableStateOf(false) }
    var showAddMember by remember { mutableStateOf(false) }
    // Sole-admin "Transfer admin first" picker. Surfaced from the blocked
    // leave path and the Admins prompt so a trapped sole admin can hand the
    // role to another member (issue #417).
    var showTransferAdmin by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // Honor the caller's request to jump straight into the transfer picker
    // (sole admin routed here from the blocked top-level Leave gate). Gated on
    // the sole-admin predicate so a stale flag can't pop the sheet once the
    // user is no longer trapped (e.g. transfer already completed).
    LaunchedEffect(autoOpenTransferAdmin, controller.isSoleAdminWithOtherMembers) {
        if (autoOpenTransferAdmin && controller.isSoleAdminWithOtherMembers) {
            showTransferAdmin = true
        }
    }
    LaunchedEffect(autoOpenAddMember, controller.isSelfMember, controller.isSelfAdmin) {
        if (autoOpenAddMember && controller.isSelfMember && controller.isSelfAdmin) {
            pendingMemberError = null
            pendingMemberAsAdmin = false
            showAddMember = true
            onAutoOpenAddMemberConsumed()
        }
    }
    var mlsState by remember(controller.group.groupIdHex) { mutableStateOf<AppGroupMlsStateFfi?>(null) }
    var mlsLoading by remember(controller.group.groupIdHex) { mutableStateOf(false) }
    // Scoped to the visible group; the controller mutation continues on appState
    // if the user switches conversations, but this sheet stops tracking it.
    var activeMutation by remember(controller.group.groupIdHex) { mutableStateOf<ActiveGroupMutation?>(null) }
    var pendingInvites by remember(controller.group.groupIdHex) { mutableStateOf<List<String>>(emptyList()) }
    var pendingConfirm by remember { mutableStateOf<DetailsConfirm?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val inviteLabelText = stringResource(R.string.invite)
    val groupTitleCopy = rememberGroupTitleCopy()
    val oneValidMemberReferenceError = stringResource(R.string.error_one_valid_member_reference)
    val qrNotValidNpubOrPublicKeyError = stringResource(R.string.error_qr_not_valid_npub_or_public_key)

    suspend fun refreshMlsDetails() {
        if (!appState.developerMode) return
        mlsLoading = true
        try {
            mlsState = controller.groupMlsState()
        } finally {
            mlsLoading = false
        }
    }

    fun runGroupMutation(
        action: GroupMutationAction,
        mutation: suspend () -> Boolean,
        target: String? = null,
        onSuccess: () -> Unit = {},
    ) {
        // Launched on a process-lifetime scope so the MLS commit + Nostr
        // publish complete even if the user dismisses this sheet mid-flight.
        // The refreshMembers() + appState.present(toast) inside each
        // ConversationController.* method then always run, regardless of
        // whether this composable is still on screen.
        activeMutation = ActiveGroupMutation(action, target)
        controller.clearLastMutationError()
        appState.launchMutation {
            try {
                if (mutation()) onSuccess()
            } finally {
                // onSuccess() may have already dismissed this sheet; clearing
                // detached Compose state is harmless in that case.
                activeMutation = null
            }
        }
    }

    // Route the Leave action (#416) to the right confirm dialog based on the
    // user's role in the group. Shared by the overflow item and the bottom
    // destructive button so both surfaces stay in lockstep. The display name
    // is resolved here (not in the dialog) so the variants read the same title.
    fun requestLeave(displayName: String) {
        controller.clearLastMutationError()
        pendingConfirm =
            when (GroupProjector.leaveAction(controller.group, appState.activeAccount?.accountIdHex, controller.members.size)) {
                LeaveAction.SoleMemberDeletesGroup -> DetailsConfirm.LeaveSoleMember(displayName)
                LeaveAction.SoleAdminMustTransfer -> DetailsConfirm.LeaveSoleAdmin(displayName)
                LeaveAction.Standard -> DetailsConfirm.Leave(displayName)
            }
    }

    LaunchedEffect(
        appState.developerMode,
        controller.group.groupIdHex,
        controller.group.admins,
        controller.members.map { it.memberIdHex },
    ) {
        refreshMlsDetails()
    }

    LaunchedEffect(controller.members.map { it.memberIdHex }, pendingInvites) {
        val memberIds = controller.members.map { it.memberIdHex.lowercase() }.toSet()
        val filtered =
            pendingInvites.filter { invite ->
                val accountIdHex = appState.accountIdHex(invite)?.lowercase()
                accountIdHex == null || accountIdHex !in memberIds
            }
        if (filtered != pendingInvites) pendingInvites = filtered
    }

    BackHandler { onBack() }

    if (showEditGroup) {
        GroupEditScreen(appState = appState, controller = controller, onBack = { showEditGroup = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.actions))
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(20.dp),
                        // Match the conversation top-bar menu exactly: inset from
                        // the right edge, roomy iconless body-large rows.
                        offset = DpOffset(x = (-8).dp, y = 0.dp),
                        modifier = Modifier.widthIn(min = 232.dp),
                    ) {
                        if (controller.isSelfMember && controller.isSelfAdmin) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.edit),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                enabled = activeMutation == null && !controller.mutationInFlight,
                                onClick = {
                                    menuOpen = false
                                    showEditGroup = true
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        when {
                                            activeMutation?.action == GroupMutationAction.Archive && controller.group.archived -> R.string.restoring_chat
                                            activeMutation?.action == GroupMutationAction.Archive -> R.string.archiving_chat
                                            controller.group.archived -> R.string.unarchive_chat
                                            else -> R.string.archive_chat
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            },
                            contentPadding = conversationMenuItemPadding,
                            enabled = activeMutation == null && !controller.mutationInFlight,
                            onClick = {
                                menuOpen = false
                                runGroupMutation(
                                    action = GroupMutationAction.Archive,
                                    mutation = { controller.setArchived(!controller.group.archived) },
                                )
                            },
                        )
                        if (controller.isSelfMember) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (activeMutation?.action ==
                                                GroupMutationAction.Leave
                                            ) {
                                                R.string.leaving_chat
                                            } else {
                                                R.string.leave_chat
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                },
                                contentPadding = conversationMenuItemPadding,
                                // Tappable for members (greyed while a mutation is
                                // in flight). The sole-admin gate is surfaced as an
                                // explanatory dialog by requestLeave rather than a
                                // silently-disabled item — but only once the roster
                                // is loaded, since requestLeave classifies the leave
                                // from member count and an empty roster reads as
                                // "sole member" (delete group).
                                enabled = activeMutation == null && !controller.mutationInFlight && controller.membersLoaded,
                                onClick = {
                                    menuOpen = false
                                    requestLeave(controller.title(groupTitleCopy))
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GroupDetailsHeader(
                title = controller.title(groupTitleCopy),
                subtitle =
                    controller.subtitle(
                        justYou = stringResource(R.string.just_you),
                        oneMember = stringResource(R.string.one_member),
                        membersFormat = stringResource(R.string.members_count),
                    ),
                description = controller.group.description,
                groupIdHex = controller.group.groupIdHex,
                pictureUrl = controller.group.avatarUrl,
                archived = controller.group.archived,
            )

            controller.lastMutationError?.let { message ->
                GroupMutationErrorBanner(
                    message = message,
                    onDismiss = { controller.clearLastMutationError() },
                )
            }

            SectionCardWithAction(
                title = "${stringResource(R.string.members)} · ${controller.members.size}",
                action = {
                    if (controller.isSelfMember && controller.isSelfAdmin) {
                        TextButton(
                            onClick = {
                                pendingMemberError = null
                                pendingMemberAsAdmin = false
                                showAddMember = true
                            },
                            enabled = activeMutation == null && !controller.mutationInFlight,
                        ) {
                            if (activeMutation?.action == GroupMutationAction.InviteMember) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null)
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.add_member))
                        }
                    }
                },
            ) {
                if (controller.isSelfMember && controller.isSoleAdminWithOtherMembers) {
                    // Trapped sole admin: they can't leave or step down until
                    // they hand admin to someone else. Surface the transfer
                    // entry point right here so the otherwise-blocked leave /
                    // revoke paths have a way forward (issue #417).
                    SoleAdminTransferPrompt(
                        enabled = activeMutation == null && !controller.mutationInFlight,
                        onTransfer = { showTransferAdmin = true },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                }
                controller.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        controller = controller,
                        appState = appState,
                        activeMutation = activeMutation,
                        onPromote = {
                            runGroupMutation(
                                action = GroupMutationAction.PromoteAdmin,
                                mutation = { controller.setMemberAdmin(member, admin = true) },
                                target = member.memberIdHex,
                            )
                        },
                        onDemote = {
                            runGroupMutation(
                                action = GroupMutationAction.DemoteAdmin,
                                mutation = { controller.setMemberAdmin(member, admin = false) },
                                target = member.memberIdHex,
                            )
                        },
                        onSelfDemote = {
                            pendingConfirm =
                                if (controller.isSoleAdminWithOtherMembers) {
                                    DetailsConfirm.StepDownSoleAdmin
                                } else {
                                    DetailsConfirm.StepDownAdmin(member)
                                }
                        },
                        onRemove = {
                            pendingConfirm = DetailsConfirm.RemoveMember(member)
                        },
                    )
                    if (member != controller.members.last()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    }
                }
                if (pendingInvites.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pendingInvites.forEach { invite ->
                            // Pending invites stay non-actionable, but a tap
                            // copies the full invite key to the clipboard.
                            AssistChip(
                                onClick = {
                                    clipboard.setText(AnnotatedString(invite))
                                    appState.presentText(
                                        AppText.Resource(R.string.toast_copied_value, listOf(inviteLabelText)),
                                    )
                                },
                                label = { Text(stringResource(R.string.invite_pending, IdentityFormatter.short(invite))) },
                                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                            )
                        }
                    }
                }
            }

            SectionCard(title = stringResource(R.string.info)) {
                CopyableValueRow(
                    label = stringResource(R.string.group_id),
                    display = IdentityFormatter.short(controller.group.groupIdHex),
                    value = controller.group.groupIdHex,
                    clipboard = clipboard,
                    appState = appState,
                )
                DiagnosticRow(
                    stringResource(R.string.nostr_group),
                    IdentityFormatter.short(controller.group.nostrGroupIdHex),
                    copyValue = controller.group.nostrGroupIdHex,
                    appState = appState,
                )
                DiagnosticRow(
                    stringResource(R.string.relays),
                    controller.group.relays.size
                        .toString(),
                )
                controller.group.relays.forEach { relay ->
                    Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (appState.developerMode) {
                SectionCard(title = stringResource(R.string.mls)) {
                    when {
                        mlsLoading -> Text(stringResource(R.string.loading_mls_state), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        mlsState == null -> Text(stringResource(R.string.mls_state_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> {
                            val state = requireNotNull(mlsState)
                            DiagnosticRow(
                                stringResource(R.string.group_id),
                                IdentityFormatter.short(state.groupIdHex),
                                copyValue = state.groupIdHex,
                                appState = appState,
                            )
                            DiagnosticRow(stringResource(R.string.epoch), state.epoch.toString())
                            DiagnosticRow(stringResource(R.string.mls_members), state.memberCount.toString())
                            DiagnosticRow(stringResource(R.string.required_components), state.requiredAppComponents.joinToString(", "))
                        }
                    }
                }
            }

            // Destructive "Leave group" affordance at the bottom of the details
            // screen (#416). Routes through requestLeave so the sole-admin and
            // sole-member cases get their own confirm copy. On failure the
            // controller's lastMutationError surfaces inline here (in addition
            // to the snackbar) so the user stays on the screen and can retry.
            if (controller.isSelfMember) {
                val leaving = activeMutation?.action == GroupMutationAction.Leave
                controller.lastMutationError?.let { error ->
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = { requestLeave(controller.title(groupTitleCopy)) },
                    enabled = activeMutation == null && !controller.mutationInFlight && controller.membersLoaded,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    if (leaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (leaving) R.string.leaving_chat else R.string.leave_group))
                }
            }
        }
    }
    if (showMemberScanner) {
        QrScannerSheet(
            onDismiss = { showMemberScanner = false },
            onScan = { raw ->
                val ref = RecipientReference.normalize(raw)
                showMemberScanner = false
                if (ref == null) {
                    pendingMemberError = qrNotValidNpubOrPublicKeyError
                } else {
                    pendingMember = ref
                    pendingMemberError = null
                }
            },
        )
    }

    if (showAddMember) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddMember = false
                pendingMemberAsAdmin = false
            },
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.add_member), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = pendingMember,
                    onValueChange = {
                        pendingMember = it
                        pendingMemberError = null
                    },
                    label = { Text(stringResource(R.string.npub_or_public_key)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    isError = pendingMemberError != null,
                    supportingText =
                        pendingMemberError?.let { message ->
                            { Text(message) }
                        },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PublicIdentifierFieldTrailingAction(
                                value = pendingMember,
                                enabled = activeMutation == null && !controller.mutationInFlight,
                                onValueChange = {
                                    pendingMember = it
                                    pendingMemberError = null
                                },
                            )
                            IconButton(onClick = { showMemberScanner = true }) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_member_qr_code))
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.add_as_admin), style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = pendingMemberAsAdmin,
                        onCheckedChange = { pendingMemberAsAdmin = it },
                        enabled = activeMutation == null && !controller.mutationInFlight,
                    )
                }
                Button(
                    onClick = {
                        val ref =
                            RecipientReference.normalize(pendingMember) ?: run {
                                pendingMemberError = oneValidMemberReferenceError
                                return@Button
                            }
                        pendingMemberError = null
                        runGroupMutation(
                            action = GroupMutationAction.InviteMember,
                            mutation = { controller.inviteMembers(listOf(ref), addAsAdmin = pendingMemberAsAdmin) },
                            target = ref,
                            onSuccess = {
                                pendingMember = ""
                                pendingMemberAsAdmin = false
                                pendingMemberError = null
                                pendingInvites = (pendingInvites + ref).distinct()
                                showAddMember = false
                            },
                        )
                    },
                    enabled = pendingMember.isNotBlank() && activeMutation == null && !controller.mutationInFlight,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (activeMutation?.action == GroupMutationAction.InviteMember) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (activeMutation?.action == GroupMutationAction.InviteMember) R.string.adding_member else R.string.add_member))
                }
            }
        }
    }

    if (showTransferAdmin) {
        TransferAdminSheet(
            controller = controller,
            appState = appState,
            busy = activeMutation != null || controller.mutationInFlight,
            onPick = { member ->
                showTransferAdmin = false
                pendingConfirm = DetailsConfirm.TransferAdmin(member)
            },
            onDismiss = { showTransferAdmin = false },
        )
    }

    pendingConfirm?.let { confirm ->
        when (confirm) {
            is DetailsConfirm.RemoveMember ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_remove_member_title),
                    message =
                        stringResource(
                            R.string.confirm_remove_member_message,
                            controller.memberDisplayName(confirm.member),
                        ),
                    confirmLabel = stringResource(R.string.remove_member),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.RemoveMember,
                            mutation = { controller.removeMember(confirm.member) },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.TransferAdmin ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_transfer_admin_title),
                    message =
                        stringResource(
                            R.string.confirm_transfer_admin_message,
                            controller.memberDisplayName(confirm.member),
                        ),
                    confirmLabel = stringResource(R.string.transfer_admin),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.TransferAdmin,
                            mutation = { controller.transferAdmin(confirm.member) },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                )
            is DetailsConfirm.StepDownAdmin ->
                ConfirmDialog(
                    title = stringResource(R.string.step_down_as_admin),
                    message = stringResource(R.string.confirm_step_down_admin_message),
                    confirmLabel = stringResource(R.string.step_down_as_admin),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.SelfDemoteAdmin,
                            mutation = { controller.stepDownAsAdmin() },
                            target = confirm.member.memberIdHex,
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            DetailsConfirm.StepDownSoleAdmin ->
                AlertDialog(
                    onDismissRequest = { pendingConfirm = null },
                    title = { Text(stringResource(R.string.sole_admin_leave_blocked_title)) },
                    text = { Text(stringResource(R.string.sole_admin_transfer_hint)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                pendingConfirm = null
                                showTransferAdmin = true
                            },
                        ) {
                            Text(stringResource(R.string.transfer_admin))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingConfirm = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            is DetailsConfirm.Leave ->
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_title_named, confirm.groupName),
                    message = stringResource(R.string.confirm_leave_message_named),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
                        // Process-lifetime scope so a swipe-dismiss mid-leave doesn't
                        // kill the toast/refresh; onDismiss + onLeft are safe Compose
                        // state writes from Main.immediate.
                        runGroupMutation(
                            action = GroupMutationAction.Leave,
                            mutation = { controller.leaveGroup(displayName = confirm.groupName) },
                            onSuccess = {
                                onLeft()
                            },
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.LeaveSoleMember ->
                // Sole member: leaving dissolves the group entirely. Same engine
                // path as a normal leave (no other member to orphan), but the copy
                // makes the destructive "this deletes the group" consequence clear.
                ConfirmDialog(
                    title = stringResource(R.string.confirm_leave_sole_member_title, confirm.groupName),
                    message = stringResource(R.string.confirm_leave_sole_member_message),
                    confirmLabel = stringResource(R.string.leave),
                    onConfirm = {
                        pendingConfirm = null
                        runGroupMutation(
                            action = GroupMutationAction.Leave,
                            mutation = { controller.leaveGroup(displayName = confirm.groupName) },
                            onSuccess = {
                                onLeft()
                            },
                        )
                    },
                    onDismiss = { pendingConfirm = null },
                    destructive = true,
                )
            is DetailsConfirm.LeaveSoleAdmin ->
                // Sole admin with other members: leaving would strand the group
                // with no admin. This is an informational gate — no Leave button.
                // The fix is to promote another member via their row's "Make
                // admin" action, after which the standard Leave flow applies.
                AlertDialog(
                    onDismissRequest = { pendingConfirm = null },
                    title = { Text(stringResource(R.string.confirm_leave_sole_admin_title)) },
                    text = { Text(stringResource(R.string.confirm_leave_sole_admin_message, confirm.groupName)) },
                    confirmButton = {
                        TextButton(onClick = { pendingConfirm = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
        }
    }
}

/**
 * Bottom sheet that lets an admin pick a new group avatar.
 *
 * Two entry points: (1) the URL TextField (manual HTTPS paste with live
 * preview + validation), (2) the search field (DuckDuckGo image search,
 * results shown as a grid of thumbnails). The sheet commits the change
 * itself via [onApply] — the caller wires that to the avatar-update FFI
 * and presents a success toast.
 *
 * When the group already has an avatar, a destructive "Remove image"
 * button is exposed, which calls [onApply] with `null` (caller maps that
 * to clearing the avatar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageSearchSheet(
    initialUrl: String,
    header: String,
    title: String,
    seed: String,
    urlLabel: String,
    applyInFlight: Boolean,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit,
    searchClient: ImageSearchClient = remember { DuckDuckGoImageSearchClient() },
) {
    // Tracks which button initiated the current in-flight mutation, so the
    // spinner lands on THAT button and the other one just greys out. Local
    // to the sheet because the caller's `applyInFlight` is a binary
    // "anything running" flag. Reset to null when the mutation completes
    // (success closes the sheet via the caller; failure keeps the sheet
    // open and unlocks the buttons for a retry).
    var pendingAction by remember { mutableStateOf<GroupImageAction?>(null) }
    LaunchedEffect(applyInFlight) {
        if (!applyInFlight) pendingAction = null
    }
    val scope = rememberCoroutineScope()
    var urlDraft by remember(initialUrl) { mutableStateOf(initialUrl) }
    var queryDraft by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ImageSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchErrorRes by remember { mutableStateOf<Int?>(null) }
    // Ticket counter for stale-result guarding. Each `launchSearch()` bumps
    // it and the in-flight coroutine captures that ticket value; on
    // completion, the coroutine only writes back to `results` /
    // `searchErrorRes` / `isSearching` if `requestId` still equals its
    // captured ticket. Without this, a slow first search can resolve AFTER
    // a faster second search has already populated the UI, clobbering the
    // new view with stale thumbnails. `Job.cancel()` alone isn't enough
    // because cancellation is cooperative — a returning coroutine still
    // executes its tail past the suspension point.
    var requestId by remember { mutableStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val trimmedUrl = urlDraft.trim()
    val previewUrl =
        remember(trimmedUrl) {
            // Same HTTPS-only safety check the picker applies; an
            // unsanitized URL won't render in the preview avatar either.
            sanitizeHttpsAvatarUrl(trimmedUrl)
        }
    val emptyQueryRes = R.string.group_image_search_enter_query
    val missingTokenRes = R.string.group_image_search_unavailable
    val badResponseRes = R.string.group_image_search_bad_response
    val noResultsRes = R.string.group_image_search_no_results

    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }

    fun launchSearch() {
        searchJob?.cancel()
        val q = queryDraft.trim()
        if (q.isEmpty()) {
            searchErrorRes = emptyQueryRes
            results = emptyList()
            return
        }
        val ticket = requestId + 1
        requestId = ticket
        searchErrorRes = null
        results = emptyList()
        isSearching = true
        searchJob =
            scope.launch {
                try {
                    val hits = searchClient.search(q)
                    if (requestId != ticket) return@launch
                    results = hits
                    searchErrorRes = if (hits.isEmpty()) noResultsRes else null
                } catch (_: ImageSearchException.EmptyQuery) {
                    if (requestId == ticket) searchErrorRes = emptyQueryRes
                } catch (_: ImageSearchException.MissingToken) {
                    if (requestId == ticket) searchErrorRes = missingTokenRes
                } catch (e: CancellationException) {
                    // Rethrow the original so the cause chain stays
                    // intact for downstream loggers + structured
                    // concurrency tracking.
                    throw e
                } catch (_: Throwable) {
                    if (requestId == ticket) searchErrorRes = badResponseRes
                } finally {
                    if (requestId == ticket) isSearching = false
                }
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                header,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            // Live preview row: avatar bubble seeded from the current draft
            // URL, plus the entity's name so the user knows what they're
            // editing.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Avatar(
                    title = title,
                    seed = seed,
                    size = 64.dp,
                    pictureUrl = previewUrl,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { header },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitleRes =
                        when {
                            trimmedUrl.isEmpty() -> R.string.group_image_search_preview_subtitle_empty
                            previewUrl == null -> R.string.group_image_search_preview_subtitle_invalid
                            else -> R.string.group_image_search_preview_subtitle_ready
                        }
                    Text(
                        stringResource(subtitleRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                label = { Text(urlLabel) },
                placeholder = { Text("https://example.com/image.jpg") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = queryDraft,
                    onValueChange = { queryDraft = it },
                    label = { Text(stringResource(R.string.group_image_search_query_label)) },
                    placeholder = { Text(stringResource(R.string.group_image_search_query_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Search,
                            keyboardType = KeyboardType.Text,
                        ),
                    keyboardActions = KeyboardActions(onSearch = { launchSearch() }),
                )
                IconButton(
                    onClick = { launchSearch() },
                    enabled = !isSearching && queryDraft.trim().isNotEmpty(),
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.group_image_search_action),
                        )
                    }
                }
            }
            searchErrorRes?.let { errRes ->
                Text(
                    stringResource(errRes),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (errRes == noResultsRes || errRes == emptyQueryRes) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                )
            }
            if (results.isNotEmpty()) {
                // Bounded height so the grid scrolls inside the sheet rather
                // than fighting the sheet's own gesture for vertical scrolling.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                ) {
                    items(results, key = { it.imageUrl }) { hit ->
                        GroupImageSearchTile(
                            hit = hit,
                            isSelected = hit.imageUrl == trimmedUrl,
                            onTap = { urlDraft = hit.imageUrl },
                        )
                    }
                }
            }
            // Destructive "Remove image" only when the group ALREADY has an
            // avatar — for a group without one there's nothing to remove.
            // Greyed out while the mutation is running (no double-tap into
            // a silently no-op'd second call inside withMutationLockResult).
            if (initialUrl.isNotBlank()) {
                TextButton(
                    onClick = {
                        pendingAction = GroupImageAction.Remove
                        onApply(null)
                    },
                    enabled = !applyInFlight,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    if (pendingAction == GroupImageAction.Remove) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = null)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.group_image_search_remove))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !applyInFlight,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    // Persist the SANITIZED URL so any normalization
                    // (schemeless `//host/path` upgrade, trim) survives
                    // through to storage — saving the raw draft would
                    // drop the work `sanitizeHttpsAvatarUrl` already did.
                    onClick = {
                        previewUrl?.let { sanitized ->
                            pendingAction = GroupImageAction.Apply
                            onApply(sanitized)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    // Apply is the additive path ONLY. Clearing the avatar
                    // is exclusively the "Remove image" button's job — a
                    // primary button that doubles as a destructive action
                    // makes accidental avatar loss one mistap away.
                    enabled = previewUrl != null && !applyInFlight,
                ) {
                    if (pendingAction == GroupImageAction.Apply) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text(stringResource(R.string.group_image_search_apply))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupImageSearchTile(
    hit: ImageSearchResult,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val thumbnailKey = hit.thumbnailUrl ?: hit.imageUrl
    val thumbnail by produceState<ImageBitmap?>(
        initialValue = AvatarImageLoader.peek(thumbnailKey),
        key1 = thumbnailKey,
    ) {
        if (value == null) value = AvatarImageLoader.load(thumbnailKey)
    }
    val borderColor =
        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        modifier =
            Modifier
                .aspectRatio(1f)
                // TalkBack: announce the current selection so users hear
                // which thumbnail is staged. Without this, the only cue is
                // the border color change, which is inaccessible.
                .semantics { selected = isSelected }
                .clickable(onClick = onTap),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val bmp = thumbnail
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = hit.title.ifBlank { hit.sourceHost ?: "" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Footer strip: source host (left) and pixel dimensions (right).
            // Surfacing the host helps avoid picking trackable hotlinked
            // images by mistake; the dimensions hint at "is this big enough
            // to use as an avatar" before the user commits.
            val host = hit.sourceHost?.takeIf { it.isNotBlank() }
            val dims = hit.dimensionsLabel?.takeIf { it.isNotBlank() }
            if (host != null || dims != null) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (host != null) {
                        Text(
                            host,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (dims != null) {
                        if (host != null) Spacer(Modifier.weight(1f))
                        Text(
                            dims,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupDetailsHeader(
    title: String,
    subtitle: String,
    description: String,
    groupIdHex: String,
    pictureUrl: String?,
    archived: Boolean,
) {
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(title = title, seed = groupIdHex, size = 88.dp, pictureUrl = pictureUrl)
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (archived) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text(stringResource(R.string.archive_chat)) },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMutationErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null)
            Text(
                stringResource(R.string.latest_group_error, message),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss))
            }
        }
    }
}

/**
 * Static notice shown at the top of the Edit Profile screen warning the user
 * that their kind:0 metadata is broadcast unencrypted to relays. Copy mirrors
 * Whitenoise Flutter (`profileIsPublic` / `profilePublicDescription`) for
 * cross-client parity. See #380. Informational (tonal secondary container),
 * not an error — it's expected behaviour the user should simply be aware of.
 */
@Composable
private fun ProfilePublicWarning() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Default.Public,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.profile_is_public),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.profile_public_description),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun GroupActionRow(
    icon: @Composable () -> Unit,
    title: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 0.5f else 0.28f),
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                icon()
            }
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private sealed class DetailsConfirm {
    data class RemoveMember(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data class TransferAdmin(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data class StepDownAdmin(
        val member: AppGroupMemberRecordFfi,
    ) : DetailsConfirm()

    data object StepDownSoleAdmin : DetailsConfirm()

    /**
     * Standard leave: the active account is an ordinary member (or an admin
     * with at least one other admin left behind). Confirm → leave.
     */
    data class Leave(
        val groupName: String,
    ) : DetailsConfirm()

    /**
     * Sole-admin gate: the active account is the only admin of a group that
     * still has other members. Leaving would strand the group with no admin,
     * so this is an informational dialog (Cancel only, no Leave button) that
     * asks the user to promote another member to admin first.
     */
    data class LeaveSoleAdmin(
        val groupName: String,
    ) : DetailsConfirm()

    /**
     * Sole-member leave: the active account is the only member left, so
     * leaving dissolves the group entirely. Confirm → leave (the engine
     * `leaveGroup` path drops the now-empty group from local projections).
     */
    data class LeaveSoleMember(
        val groupName: String,
    ) : DetailsConfirm()
}

private enum class GroupMutationAction {
    SaveProfile,
    InviteMember,
    RemoveMember,
    PromoteAdmin,
    DemoteAdmin,
    SelfDemoteAdmin,
    TransferAdmin,
    Archive,
    Leave,
}

private data class ActiveGroupMutation(
    val action: GroupMutationAction,
    val target: String? = null,
)

internal enum class GroupMemberMenuAction {
    GrantAdmin,
    RevokeAdmin,
    RemoveMember,
    StepDownAsAdmin,
}

internal fun groupMemberMenuActions(
    viewerIsMember: Boolean,
    viewerIsAdmin: Boolean,
    targetIsSelf: Boolean,
    targetIsAdmin: Boolean,
): List<GroupMemberMenuAction> =
    when {
        !viewerIsMember || !viewerIsAdmin -> emptyList()
        targetIsSelf -> if (targetIsAdmin) listOf(GroupMemberMenuAction.StepDownAsAdmin) else emptyList()
        targetIsAdmin -> listOf(GroupMemberMenuAction.RevokeAdmin, GroupMemberMenuAction.RemoveMember)
        else -> listOf(GroupMemberMenuAction.GrantAdmin, GroupMemberMenuAction.RemoveMember)
    }

@Composable
private fun GroupMemberRow(
    member: AppGroupMemberRecordFfi,
    controller: ConversationController,
    appState: DarkMatterAppState,
    activeMutation: ActiveGroupMutation?,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onSelfDemote: () -> Unit,
    onRemove: () -> Unit,
) {
    val isAdmin = controller.isAdmin(member)
    val isSelfRow =
        GroupProjector.isActiveAccountMember(
            member,
            appState.activeAccount?.accountIdHex,
        )
    val memberActions =
        groupMemberMenuActions(
            viewerIsMember = controller.isSelfMember,
            viewerIsAdmin = controller.isSelfAdmin,
            targetIsSelf = isSelfRow,
            targetIsAdmin = isAdmin,
        )
    val rowMutation = activeMutation?.takeIf { it.target == member.memberIdHex }
    var menuOpen by remember(member.memberIdHex) { mutableStateOf(false) }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { appState.presentProfile(appState.npub(member.memberIdHex)) }
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = controller.memberDisplayName(member),
            seed = member.memberIdHex,
            size = 40.dp,
            pictureUrl = controller.memberAvatarUrl(member),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(controller.memberDisplayName(member), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isSelfRow) {
                    Text(
                        stringResource(R.string.you),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isAdmin) {
                    Text(
                        stringResource(R.string.admin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (rowMutation != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(rowMutation.action.memberStatusLabelRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (memberActions.isNotEmpty()) {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !controller.mutationInFlight,
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.member_actions))
                }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    memberActions.forEach { action ->
                        when (action) {
                            GroupMemberMenuAction.GrantAdmin ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.make_admin)) },
                                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) },
                                    enabled = !controller.mutationInFlight,
                                    onClick = {
                                        menuOpen = false
                                        onPromote()
                                    },
                                )
                            GroupMemberMenuAction.RevokeAdmin ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_admin)) },
                                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) },
                                    enabled = !controller.mutationInFlight,
                                    onClick = {
                                        menuOpen = false
                                        onDemote()
                                    },
                                )
                            GroupMemberMenuAction.RemoveMember ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.remove_member)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    enabled = !controller.mutationInFlight,
                                    colors =
                                        MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.error,
                                            leadingIconColor = MaterialTheme.colorScheme.error,
                                        ),
                                    onClick = {
                                        menuOpen = false
                                        onRemove()
                                    },
                                )
                            GroupMemberMenuAction.StepDownAsAdmin ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.step_down_as_admin)) },
                                    leadingIcon = { Icon(Icons.Default.Shield, contentDescription = null) },
                                    enabled = !controller.mutationInFlight,
                                    colors =
                                        MenuDefaults.itemColors(
                                            textColor = MaterialTheme.colorScheme.error,
                                            leadingIconColor = MaterialTheme.colorScheme.error,
                                        ),
                                    onClick = {
                                        menuOpen = false
                                        onSelfDemote()
                                    },
                                )
                        }
                    }
                }
            }
        }
    }
}

private val GroupMutationAction.memberStatusLabelRes: Int
    @StringRes
    get() =
        when (this) {
            GroupMutationAction.PromoteAdmin -> R.string.adding_admin
            GroupMutationAction.DemoteAdmin,
            GroupMutationAction.SelfDemoteAdmin,
            -> R.string.removing_admin
            GroupMutationAction.TransferAdmin -> R.string.transferring_admin
            GroupMutationAction.RemoveMember -> R.string.removing_member
            else -> R.string.member_actions
        }

/**
 * Inline prompt shown in the Members section when the active account is the
 * group's only admin while other members remain. Such an admin can't leave or
 * step down until they hand admin to someone else, so this exposes the
 * transfer entry point right where the otherwise-blocked actions live
 * (issue #417).
 */
@Composable
private fun SoleAdminTransferPrompt(
    enabled: Boolean,
    onTransfer: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.sole_admin_transfer_hint),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onTransfer, enabled = enabled) {
            Text(stringResource(R.string.transfer_admin))
        }
    }
}

/**
 * Bottom sheet listing the non-admin members eligible to receive a transferred
 * admin role, with a search/filter field. Picking a member hands selection
 * back to the caller, which confirms before mutating. Mirrors the Add member
 * sheet's structure (issue #417).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferAdminSheet(
    controller: ConversationController,
    appState: DarkMatterAppState,
    busy: Boolean,
    onPick: (AppGroupMemberRecordFfi) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates = controller.transferAdminCandidates()
    val filtered =
        remember(query, candidates) {
            val needle = query.trim()
            if (needle.isBlank()) {
                candidates
            } else {
                candidates.filter { member ->
                    controller.memberDisplayName(member).contains(needle, ignoreCase = true) ||
                        appState.npub(member.memberIdHex).contains(needle, ignoreCase = true) ||
                        member.memberIdHex.contains(needle, ignoreCase = true)
                }
            }
        }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.transfer_admin),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.transfer_admin_picker_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (candidates.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.conversation_search_open)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                candidates.isEmpty() ->
                    Text(
                        stringResource(R.string.transfer_admin_no_candidates),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                filtered.isEmpty() ->
                    Text(
                        stringResource(R.string.conversation_search_no_matches),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else ->
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    ) {
                        filtered.forEach { member ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !busy, role = Role.Button) { onPick(member) }
                                        .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(
                                    title = controller.memberDisplayName(member),
                                    seed = member.memberIdHex,
                                    size = 40.dp,
                                    pictureUrl = controller.memberAvatarUrl(member),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        controller.memberDisplayName(member),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        controller.memberSubtitle(member),
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    item: TimelineMessage,
    controller: ConversationController,
    appState: DarkMatterAppState,
    highlighted: Boolean,
    recentReactionEmojis: List<String>,
    isActionMenuOpen: Boolean,
    onActionMenuOpenChange: (Boolean) -> Unit,
    onReactionEmojiPicked: (String) -> Unit,
    onReplyPreviewClick: (TimelineMessage) -> Unit,
) {
    val record = item.record
    val mine = MessageProjector.isMine(record, appState.activeAccount?.accountIdHex)
    val deleted = item.projected?.deleted == true || MessageProjector.isDeleted(record.messageIdHex, controller.deletedMessageIds)
    // Convergence dropped this message onto a losing branch: it never reached
    // the group. The record survives as a tombstone, so flag it (an explicit
    // delete takes precedence over an invalidation tombstone).
    val invalidated = !deleted && item.projected?.invalidationStatus != null
    val bubbleColor =
        when {
            deleted -> MaterialTheme.colorScheme.surfaceVariant
            invalidated -> MaterialTheme.colorScheme.errorContainer
            mine -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    // #414: "you were mentioned" treatment. A received (not mine), live (not
    // deleted/invalidated) message whose markdown body @-mentions the current
    // account gets a left-edge accent line so a self-mention is spottable while
    // scrolling. Keyed on the body tokens + account so a late account switch /
    // profile load re-evaluates. The resolver is the FFI bech32→hex encoding;
    // the detection walk itself is the pure documentMentionsAccount.
    val selfAccountIdHex = appState.activeAccount?.accountIdHex
    val mentionedSelf =
        !mine &&
            !deleted &&
            !invalidated &&
            remember(record.contentTokens, selfAccountIdHex) {
                documentMentionsAccount(
                    document = record.contentTokens,
                    accountIdHex = selfAccountIdHex,
                    resolveAccountIdHex = { bech32 -> appState.accountIdHexForMention(bech32) },
                )
            }
    val mentionedYouLabel = stringResource(R.string.mentioned_you)
    val scope = rememberCoroutineScope()
    // Window-space y of the long-press touch so the action popover can anchor
    // near the finger on a bubble taller than the screen instead of
    // degenerating to the visible bubble top (#326). Window space (not
    // row-local) so the menu can offset against its own anchor regardless of
    // where in the transcript the bubble sits.
    var longPressWindowY by remember { mutableStateOf<Float?>(null) }
    var rowBoundsTopPx by remember { mutableStateOf(0f) }
    var swipeDrag by remember(record.messageIdHex) { mutableStateOf(0f) }
    val animatedSwipeOffset by animateFloatAsState(targetValue = swipeDrag, label = "replySwipeOffset")
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val replySwipeThresholdPx = with(density) { 64.dp.toPx() }
    val maxSwipeOffsetPx = with(density) { 72.dp.toPx() }
    val messageTextCopy = rememberMessageTextCopy()
    val deletedBodyText = stringResource(R.string.message_deleted)
    val messageActionsLabel = stringResource(R.string.message_actions)
    val invalidatedBodyText = stringResource(R.string.message_invalidated)
    // Cached like the media references below: displayBody sanitizes/allocates
    // per call, and recomputing it for every visible bubble on every timeline
    // recomposition adds up. See #131.
    // Kind-1009 edits replace the body of an existing kind-9 chat. When an
    // edit is present for this message's id, prefer the latest edited text
    // over the original projection. Keyed on editState so a fresh edit
    // recomposes the bubble in place.
    val editState = controller.editsByTarget[record.messageIdHex]
    val displayedBody =
        remember(item, deleted, invalidated, messageTextCopy, deletedBodyText, invalidatedBodyText, editState) {
            when {
                // Check `deleted` first so the optimistic tombstone (from
                // controller.deletedMessageIds) renders immediately on tap.
                deleted -> deletedBodyText
                invalidated -> invalidatedBodyText
                // Edit overlay wins over both projected and raw plaintext.
                // We don't go through MessageProjector here — the edit
                // payload is plain text by spec; markdown re-parse will
                // happen below if record.contentTokens is populated, but
                // for kind-9 edits the body is the latest version verbatim.
                editState != null && record.kind == 9uL -> editState.latestText
                item.projected != null ->
                    TimelineProjector.displayBody(
                        item.projected,
                        messageTextCopy.copy(deleted = deletedBodyText),
                    )
                else -> MessageProjector.displayBody(record, messageTextCopy)
            }
        }
    // Issue #390 v1 forwards text only. Forward must be hidden for any record
    // whose displayed body is a synthetic surrogate (media filename/placeholder,
    // "Reacted …", delete/system summaries, agent-stream copy) — forwarding
    // `displayedBody` there would send misleading text into other groups. The
    // raw text to forward is the edit-aware verbatim body, never the display
    // fallback; `forwardBody` is null exactly when the message is not a
    // forwardable text record, which also drives the menu gate below.
    val forwardBody: String? =
        remember(record, editState, deleted, invalidated) {
            if (deleted || invalidated) {
                null
            } else {
                MessageProjector.forwardableText(
                    record,
                    editedText = editState?.latestText?.takeIf { record.kind == 9uL },
                )
            }
        }
    val showSenderAvatar =
        GroupProjector.shouldShowTranscriptSenderAvatar(
            memberCount = controller.members.size,
            mine = mine,
        )
    // Match the timestamp to the bubble's on-color family. The mine bubble
    // fills with primaryContainer, so the M3 paired token is onPrimaryContainer;
    // using onSurfaceVariant there blends into the tint and reads as invisible.
    val timestampColor =
        when {
            invalidated -> MaterialTheme.colorScheme.onErrorContainer
            mine && !deleted -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    var emojiPickerOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    // A long body clips to a few lines with an inline Read More; opening it
    // routes through a full-screen view rather than expanding in place, so the
    // only state to track is whether that view is showing. Resets on re-entry
    // by keying on the message id (#325).
    var expandedFullView by remember(record.messageIdHex) { mutableStateOf(false) }
    var infoSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var forwardSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var editHistoryOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    var reactionSheetOpen by remember(record.messageIdHex) { mutableStateOf(false) }
    val quickReactionEmojis = RecentEmojiList.quickChoices(recentReactionEmojis)
    // A deleted message is inert: tear down any open action/reaction surface if
    // the message is deleted out from under it (optimistic or remote delete).
    LaunchedEffect(deleted) {
        if (deleted) {
            onActionMenuOpenChange(false)
            emojiPickerOpen = false
            reactionSheetOpen = false
        }
    }

    fun beginReply() {
        controller.replyingTo = record
        onActionMenuOpenChange(false)
    }

    fun openInfoSheet() {
        onActionMenuOpenChange(false)
        infoSheetOpen = true
    }

    fun reactWithEmoji(emoji: String) {
        // Chokepoint guard: never react to a deleted message, whatever path
        // (menu, emoji picker) called in — even if that surface was open when
        // the delete landed.
        if (deleted) return
        onReactionEmojiPicked(emoji)
        // Route via launchMutation: same survives-navigation rationale as delete/send.
        appState.launchMutation { controller.toggleReaction(emoji, record) }
    }

    fun copyMessageText() {
        clipboard.setText(AnnotatedString(displayedBody))
        appState.present(R.string.copied)
        onActionMenuOpenChange(false)
    }

    fun beginForward() {
        // Defensive: the menu only renders Forward when forwardBody != null, but
        // gate here too so a stale tap can never open the picker for a non-text
        // record (issue #390 is text-only).
        if (forwardBody == null) return
        onActionMenuOpenChange(false)
        forwardSheetOpen = true
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val messageGroupMaxWidth = maxWidth * 0.95f
        val senderAvatarWidth = if (showSenderAvatar) 40.dp else 0.dp
        val bubbleColumnMaxWidth = (messageGroupMaxWidth - senderAvatarWidth).coerceAtLeast(120.dp)

        Row(
            // Both reply-swipe and long-press hitboxes cover the ENTIRE row,
            // not just the bubble: a swipe-right or long-press starting on the
            // surrounding whitespace (avatar gutter, empty space next to the
            // bubble) triggers the same action as one starting on the bubble.
            // See #204. The visual slide stays on the Surface below via
            // `.offset`; only gesture detection lives on the row. Nested
            // handlers (avatar, sender name, reaction chips) are children and
            // still win for their own SHORT taps. Long-press is detected with a
            // raw pointerInput (below) rather than combinedClickable so it wins
            // over inner media `clickable` children for the long-press while
            // leaving their single-tap behavior intact (#262). The detector
            // raises no ripple, matching the previous full-row behavior.
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        // A deleted message has no actionable content, so
                        // disable swipe-to-reply entirely: no drag, no trigger.
                        if (deleted) {
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex, replySwipeThresholdPx, maxSwipeOffsetPx) {
                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { change, dragAmount ->
                                        val next = ReplySwipe.visualOffset(swipeDrag + dragAmount, maxSwipeOffsetPx)
                                        if (next != swipeDrag || dragAmount > 0f) change.consume()
                                        swipeDrag = next
                                    },
                                    onDragEnd = {
                                        if (ReplySwipe.shouldTriggerReply(swipeDrag, totalY = 0f, threshold = replySwipeThresholdPx)) {
                                            beginReply()
                                        }
                                        swipeDrag = 0f
                                    },
                                    onDragCancel = { swipeDrag = 0f },
                                )
                            }
                        },
                    ).then(
                        // Long-press lives in a raw pointerInput, not
                        // combinedClickable, so it WINS over inner media
                        // children (image/video/file/voice) that install their
                        // own tap `clickable`. Those children sit deeper in the
                        // hit-test tree and would otherwise swallow the press
                        // before a row-level combinedClickable saw the
                        // long-press — which is why long-press did nothing on a
                        // media bubble while it worked on a text bubble (#262).
                        // awaitLongPressOrCancellation observes the down WITHOUT
                        // consuming it (so a quick tap still reaches the child's
                        // viewer/player) and only fires once the press is held
                        // past the long-press timeout, at which point it wins
                        // the gesture and opens the actions menu. It self-cancels
                        // on movement beyond touch slop, so swipe-to-reply above
                        // is unaffected.
                        if (deleted) {
                            // A deleted message has no actions menu.
                            Modifier
                        } else {
                            Modifier.pointerInput(record.messageIdHex) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        longPress.consume()
                                        // Capture the press y in window space before
                                        // opening so the popover anchors at the
                                        // finger, not the bubble top (#326).
                                        longPressWindowY = rowBoundsTopPx + longPress.position.y
                                        haptics.performHapticFeedback(
                                            androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                        )
                                        onActionMenuOpenChange(true)
                                    }
                                }
                            }
                        },
                    ).then(
                        // The raw pointerInput above only fires on a physical
                        // pointer long-press, so it leaves accessibility services
                        // (TalkBack, Switch Access) and keyboard/semantic callers
                        // without a way to reach the actions menu — a regression
                        // from the old combinedClickable, which exposed an
                        // onLongClick semantic action for the whole row (#262).
                        // Re-publish that action via Modifier.semantics so the
                        // reply/copy/delete/reaction entry point stays reachable
                        // without a hold gesture. Guarded by `!deleted` to match
                        // the pointer detector (a deleted message has no menu).
                        if (deleted) {
                            Modifier
                        } else {
                            Modifier.semantics {
                                onLongClick(label = messageActionsLabel) {
                                    // Accessibility entry has no touch point;
                                    // anchor to the bubble top (#326).
                                    longPressWindowY = null
                                    onActionMenuOpenChange(true)
                                    true
                                }
                            }
                        },
                    )
                    // Window-space top of the row, added to the local press y so
                    // the popover can be offset against the menu's own anchor (#326).
                    .onGloballyPositioned { rowBoundsTopPx = it.boundsInWindow().top },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            if (showSenderAvatar) {
                Box(
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .clickable { appState.presentProfile(appState.npub(record.sender)) },
                ) {
                    Avatar(
                        title = appState.displayName(record.sender),
                        seed = record.sender,
                        size = 32.dp,
                        pictureUrl = appState.avatarUrl(record.sender),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleColumnMaxWidth),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
            ) {
                Surface(
                    // Swipe-to-reply and long-press now live on the parent Row
                    // (see #204) so the whole message row is the hitbox. The
                    // Surface keeps only the visual slide driven by swipeDrag.
                    modifier =
                        Modifier
                            .offset { IntOffset(animatedSwipeOffset.roundToInt(), 0) },
                    color = bubbleColor,
                    shape = RoundedCornerShape(18.dp),
                    border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null,
                    tonalElevation = if (mine) 1.dp else 0.dp,
                ) {
                    // #414: left-edge accent in the bubble's start gutter when
                    // the current account is mentioned. Drawn in the 14.dp
                    // start padding so it reads as a rail without reflowing the
                    // content; uses the same content-derived accent the mention
                    // highlight uses. drawBehind paints over the surface fill
                    // (it's the Column's own background layer), so it stays
                    // visible on the surfaceVariant received bubble.
                    val mentionAccentColor = MaterialTheme.colorScheme.primary
                    val mentionRailModifier =
                        if (mentionedSelf) {
                            Modifier
                                .semantics { contentDescription = mentionedYouLabel }
                                .drawBehind {
                                    val railWidth = 3.dp.toPx()
                                    val inset = 4.dp.toPx()
                                    val radius =
                                        androidx.compose.ui.geometry
                                            .CornerRadius(railWidth / 2f, railWidth / 2f)
                                    drawRoundRect(
                                        color = mentionAccentColor,
                                        topLeft =
                                            androidx.compose.ui.geometry
                                                .Offset(inset, inset),
                                        size =
                                            androidx.compose.ui.geometry.Size(
                                                railWidth,
                                                (size.height - inset * 2).coerceAtLeast(railWidth),
                                            ),
                                        cornerRadius = radius,
                                    )
                                }
                        } else {
                            Modifier
                        }
                    // Resolved before the content column so its presence can pick
                    // the column's width strategy (#428).
                    //
                    // Projected items: the preview is a pure function of
                    // item.projected, so caching keyed on the item is always
                    // correct (a reprojection replaces the instance). The
                    // optimistic fallback instead resolves the target from
                    // controller.messageById, which can gain the target after
                    // this bubble composes — resolve those live. Display names
                    // resolve outside the cache either way so a late profile
                    // load still updates them. See #131.
                    val replyPreview =
                        if (item.projected != null) {
                            remember(item, messageTextCopy) {
                                controller.replyPreview(item, messageTextCopy)
                            }
                        } else {
                            controller.replyPreview(item, messageTextCopy)
                        }
                    Column(
                        mentionRailModifier
                            // With a reply quote present, size the column to its
                            // widest child so the inner quote can fill the bubble
                            // width instead of hugging its own (possibly short)
                            // text and leaving a gap on the right (#428). Non-reply
                            // bubbles keep the wrap-content path untouched, so only
                            // reply-bubble measurement changes.
                            .then(if (replyPreview != null) Modifier.width(IntrinsicSize.Max) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (showSenderAvatar) {
                            Text(
                                appState.displayName(record.sender),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier =
                                    Modifier.combinedClickable(
                                        onClick = { appState.presentProfile(appState.npub(record.sender)) },
                                        onLongClick = {
                                            if (!deleted) {
                                                longPressWindowY = null
                                                onActionMenuOpenChange(true)
                                            }
                                        },
                                    ),
                            )
                        }
                        replyPreview?.let { preview ->
                            ReplyPreviewCard(
                                senderTitle = senderTitleForReply(preview.sender, appState),
                                isOwn = isOwnReplySender(preview.sender, appState),
                                body = preview.body,
                                mediaKind = preview.mediaKind,
                                onClick = { onReplyPreviewClick(item) },
                                onDismiss = null,
                                // Fill the bubble's content width: the column is
                                // sized to its widest child (IntrinsicSize.Max above),
                                // so this matches the quote to the bubble instead of
                                // hugging its own text (#428). A short quote + short
                                // reply still keeps a narrow bubble because the
                                // widest child is then small (#208 preserved).
                                fillWidth = true,
                            )
                        }
                        // Prefer the controller's listMedia cache — it carries
                        // the receive-side `sourceEpoch`, which the imeta-tag
                        // parser can't recover (no epoch field in the wire
                        // format). Fall back to the imeta parser for optimistic
                        // bridge records that haven't been projected yet.
                        val mediaReferences =
                            remember(record.tags, record.messageIdHex, controller.mediaReferences) {
                                controller.mediaReferences[record.messageIdHex]
                                    ?: MediaReferenceParser.parseAllImetaTags(record.tags)
                            }
                        // Split media into image refs (rendered as a bubble or
                        // 2-col grid) and file refs (a list of pills). Mixed
                        // albums render both: images on top, file pills below.
                        // `IndexedValue` preserves the real protocol-level
                        // attachmentIndex from the full `mediaReferences`
                        // list so per-tile cache lookups never collide across
                        // image and file subsets.
                        val imageAttachments =
                            remember(mediaReferences) {
                                mediaReferences
                                    .withIndex()
                                    .filter { (_, ref) -> MediaReferenceParser.isImageMedia(ref) }
                                    .toList()
                            }
                        val audioAttachments =
                            remember(mediaReferences) {
                                mediaReferences
                                    .withIndex()
                                    .filter { (_, ref) -> MediaReferenceParser.isAudioMedia(ref) }
                                    .toList()
                            }
                        val videoAttachments =
                            remember(mediaReferences) {
                                mediaReferences
                                    .withIndex()
                                    .filter { (_, ref) -> MediaReferenceParser.isVideoMedia(ref) }
                                    .toList()
                            }
                        val fileAttachments =
                            remember(mediaReferences) {
                                mediaReferences
                                    .withIndex()
                                    .filter { (_, ref) ->
                                        !MediaReferenceParser.isImageMedia(ref) &&
                                            !MediaReferenceParser.isAudioMedia(ref) &&
                                            !MediaReferenceParser.isVideoMedia(ref)
                                    }.toList()
                            }
                        val mediaPendingName =
                            remember(record.tags) {
                                record.tags
                                    .firstOrNull { it.values.firstOrNull() == "_media_pending" }
                                    ?.values
                                    ?.getOrNull(1)
                            }
                        // Visual attachments (image + video) ride one bubble:
                        // a singleton routes to its dedicated bubble, a multi
                        // goes to MediaVisualGridBubble which mixes image
                        // and video tiles in pick order.
                        val visualAttachments =
                            remember(imageAttachments, videoAttachments) {
                                (imageAttachments + videoAttachments).sortedBy { it.index }
                            }
                        // An uncaptioned single image/video carries the footer
                        // overlaid on its bottom-right; a caption (if any) takes
                        // it instead via the text path below.
                        val footerOnVisualMedia =
                            !deleted &&
                                !invalidated &&
                                visualAttachments.size == 1 &&
                                (editState?.latestText ?: record.plaintext).isBlank()
                        if (!deleted && !invalidated && visualAttachments.isNotEmpty()) {
                            if (visualAttachments.size == 1) {
                                val entry = visualAttachments.first()
                                Box {
                                    if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                        MediaVideoBubble(
                                            messageIdHex = record.messageIdHex,
                                            attachmentIndex = entry.index,
                                            reference = entry.value,
                                            mine = mine,
                                            controller = controller,
                                            appState = appState,
                                        )
                                    } else {
                                        MediaImageBubble(
                                            item = item,
                                            reference = entry.value,
                                            attachmentIndex = entry.index,
                                            controller = controller,
                                            appState = appState,
                                            mine = mine,
                                        )
                                    }
                                    if (footerOnVisualMedia) {
                                        MediaFooterOverlay(
                                            timeText = rememberedClockTime(record.recordedAt),
                                            showStatus = mine,
                                            status = item.status,
                                        )
                                    }
                                }
                            } else {
                                MediaVisualGridBubble(
                                    item = item,
                                    attachments = visualAttachments,
                                    controller = controller,
                                    appState = appState,
                                    mine = mine,
                                )
                            }
                        }
                        if (!deleted && !invalidated && audioAttachments.isNotEmpty()) {
                            audioAttachments.forEach { entry ->
                                MediaVoiceBubble(
                                    messageIdHex = record.messageIdHex,
                                    attachmentIndex = entry.index,
                                    reference = entry.value,
                                    mine = mine,
                                    controller = controller,
                                    appState = appState,
                                )
                            }
                        }
                        if (!deleted && !invalidated && fileAttachments.isNotEmpty()) {
                            fileAttachments.forEach { entry ->
                                MediaFileBubble(
                                    messageIdHex = record.messageIdHex,
                                    attachmentIndex = entry.index,
                                    reference = entry.value,
                                    mine = mine,
                                    controller = controller,
                                    appState = appState,
                                )
                            }
                        }
                        val anyConfirmedMedia =
                            imageAttachments.isNotEmpty() ||
                                audioAttachments.isNotEmpty() ||
                                videoAttachments.isNotEmpty() ||
                                fileAttachments.isNotEmpty()
                        val pendingAttachmentsForRecord =
                            remember(record.messageIdHex, controller.pendingAttachmentsList(record.messageIdHex)) {
                                controller.pendingAttachmentsList(record.messageIdHex)
                            }
                        val pendingAudio =
                            remember(pendingAttachmentsForRecord) {
                                pendingAttachmentsForRecord
                                    .withIndex()
                                    .filter { (_, p) -> p.mediaType.startsWith("audio/", ignoreCase = true) }
                                    .toList()
                            }
                        val pendingVideo =
                            remember(pendingAttachmentsForRecord) {
                                pendingAttachmentsForRecord
                                    .withIndex()
                                    .filter { (_, p) -> p.mediaType.startsWith("video/", ignoreCase = true) }
                                    .toList()
                            }
                        val pendingImage =
                            remember(pendingAttachmentsForRecord) {
                                pendingAttachmentsForRecord
                                    .withIndex()
                                    .filter { (_, p) -> p.mediaType.startsWith("image/", ignoreCase = true) }
                                    .toList()
                            }
                        val pendingVisuals =
                            remember(pendingImage, pendingVideo) {
                                (pendingImage + pendingVideo).sortedBy { it.index }
                            }
                        if (!deleted && !invalidated && !anyConfirmedMedia && pendingAudio.isNotEmpty()) {
                            pendingAudio.forEach { (index, pending) ->
                                MediaVoiceBubble(
                                    messageIdHex = record.messageIdHex,
                                    attachmentIndex = index,
                                    reference =
                                        remember(record.messageIdHex, index, pending) {
                                            MediaAttachmentReferenceFfi(
                                                locators = emptyList(),
                                                ciphertextSha256 = "",
                                                plaintextSha256 = "",
                                                nonceHex = "",
                                                fileName = pending.fileName,
                                                mediaType = pending.mediaType,
                                                version = "encrypted-media-v1",
                                                sourceEpoch = 0u,
                                                dim = null,
                                                thumbhash = null,
                                            )
                                        },
                                    mine = true,
                                    controller = controller,
                                    appState = appState,
                                )
                            }
                        }
                        // Synthesize references for each pending visual so
                        // the existing single-bubble + grid bubble can render
                        // them. mine=true threads the bytes through the
                        // pendingAttachmentsList fallback in the auto-download
                        // path.
                        val pendingVisualRefs =
                            remember(record.messageIdHex, pendingVisuals) {
                                pendingVisuals.map { (index, pending) ->
                                    IndexedValue(
                                        index,
                                        MediaAttachmentReferenceFfi(
                                            locators = emptyList(),
                                            ciphertextSha256 = "",
                                            plaintextSha256 = "",
                                            nonceHex = "",
                                            fileName = pending.fileName,
                                            mediaType = pending.mediaType,
                                            version = "encrypted-media-v1",
                                            sourceEpoch = 0u,
                                            dim = pending.dim,
                                            thumbhash = pending.thumbhash,
                                        ),
                                    )
                                }
                            }
                        val footerOnPendingVisual =
                            !deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.size == 1
                        if (!deleted && !invalidated && !anyConfirmedMedia && pendingVisualRefs.isNotEmpty()) {
                            val uploadFailed = item.status == MessageStatus.Failed
                            val retryUpload: () -> Unit = {
                                appState.launchMutation { controller.retryFailedSend(item) }
                            }
                            if (pendingVisualRefs.size == 1) {
                                val entry = pendingVisualRefs.first()
                                Box {
                                    if (MediaReferenceParser.isVideoMedia(entry.value)) {
                                        MediaVideoBubble(
                                            messageIdHex = record.messageIdHex,
                                            attachmentIndex = entry.index,
                                            reference = entry.value,
                                            mine = true,
                                            controller = controller,
                                            appState = appState,
                                            uploading = !uploadFailed,
                                            uploadFailed = uploadFailed,
                                            onRetryUpload = if (uploadFailed) retryUpload else null,
                                        )
                                    } else {
                                        MediaImageBubble(
                                            item = item,
                                            reference = entry.value,
                                            attachmentIndex = entry.index,
                                            controller = controller,
                                            appState = appState,
                                            mine = true,
                                            uploading = !uploadFailed,
                                        )
                                    }
                                    MediaFooterOverlay(
                                        timeText = rememberedClockTime(record.recordedAt),
                                        showStatus = true,
                                        status = item.status,
                                    )
                                }
                            } else {
                                MediaVisualGridBubble(
                                    item = item,
                                    attachments = pendingVisualRefs,
                                    controller = controller,
                                    appState = appState,
                                    mine = true,
                                    uploading = !uploadFailed,
                                )
                            }
                        }
                        val showPendingPlaceholder =
                            !deleted &&
                                !invalidated &&
                                !anyConfirmedMedia &&
                                pendingAudio.isEmpty() &&
                                pendingVisualRefs.isEmpty() &&
                                mediaPendingName != null
                        if (showPendingPlaceholder) {
                            MediaPendingPlaceholder(
                                pendingAttachments = controller.pendingAttachmentsList(record.messageIdHex),
                                failed = item.status == MessageStatus.Failed,
                                onRetry =
                                    if (mine && item.status == MessageStatus.Failed) {
                                        { appState.launchMutation { controller.retryFailedSend(item) } }
                                    } else {
                                        null
                                    },
                            )
                        }
                        // Body text policy:
                        // - Pending optimistic with an attachment: placeholder
                        //   composable already renders, suppress text.
                        // - Confirmed media (imeta tag present): render the
                        //   user-typed caption, edit-overlay-aware so a
                        //   subsequent edit on a media bubble updates the
                        //   caption in place. We deliberately don't use
                        //   `displayedBody` directly because MessageProjector
                        //   falls back to the imeta filename for a blank
                        //   caption — fine for chat-list previews, wrong for
                        //   a bubble already showing the image inline.
                        // - Non-media: render displayedBody (covers reactions,
                        //   deletions, agent streams, plain text).
                        val bodyTextToRender: String? =
                            when {
                                // Deleted/invalidated tombstones show only the
                                // tombstone copy, never an inline image/caption.
                                deleted || invalidated -> displayedBody
                                mediaPendingName != null && !anyConfirmedMedia -> null
                                anyConfirmedMedia ->
                                    (editState?.latestText ?: record.plaintext).takeIf { it.isNotBlank() }
                                else -> displayedBody
                            }
                        val editedLabel =
                            if (editState != null && record.kind == 9uL && !deleted && !invalidated) {
                                if (editState.count > 1) {
                                    stringResource(R.string.edited_count, editState.count)
                                } else {
                                    stringResource(R.string.edited)
                                }
                            } else {
                                null
                            }
                        val inlineFooter: @Composable () -> Unit = {
                            MessageInlineFooter(
                                timeText = rememberedClockTime(record.recordedAt),
                                color = timestampColor,
                                showStatus = mine && !deleted && !invalidated,
                                status = item.status,
                                editedLabel = editedLabel,
                                onEditedClick = if (editState != null) ({ editHistoryOpen = true }) else null,
                            )
                        }
                        // Last-line geometry of the body so the footer can sit on
                        // that line when it fits, not merely when the widest line does.
                        var lastLineLayout by remember(record.messageIdHex) { mutableStateOf<TextLayoutResult?>(null) }
                        // Overflow decision is derived from a measurement of the FULL
                        // body only. Keeping it separate from lastLineLayout (which
                        // the currently-rendered text updates) avoids a recompose
                        // loop: once we clip, the clipped text no longer overflows,
                        // which would otherwise flip the decision back and forth.
                        var bodyFullLayout by remember(record.messageIdHex) { mutableStateOf<TextLayoutResult?>(null) }
                        // A long body collapses to MESSAGE_COLLAPSE_LINE_LIMIT lines
                        // with an inline Read More that opens the full-screen view;
                        // tombstones and edit/info copy never collapse (#325).
                        val collapsible = !deleted && !invalidated
                        val readMoreLabel = stringResource(R.string.message_read_more)
                        val readMoreStyle =
                            SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        if (bodyTextToRender != null) {
                            BubbleFooterLayout(
                                footer = inlineFooter,
                                // Body text is always start-aligned inside the
                                // bubble, regardless of which side the bubble sits
                                // on or how wide a sibling (reply quote, media)
                                // makes the content column. End-aligning own
                                // messages left a short reply drifting to the right
                                // of a wide bubble (#439). The footer still places
                                // itself at the block's trailing edge internally.
                                modifier = Modifier.align(Alignment.Start),
                                lastLineWidth =
                                    lastLineLayout?.let { layout ->
                                        if (layout.lineCount > 0) ceil(layout.getLineRight(layout.lineCount - 1)).toInt() else null
                                    },
                            ) {
                                // Markdown only when the tokens describe exactly
                                // the text we're about to show: tombstone copy,
                                // imeta-filename fallbacks, etc. all diverge from
                                // record.plaintext and must stay plain. An empty
                                // document (legacy record, parse failure) falls
                                // through to the unchanged plain-text path.
                                val markdownDocument = record.contentTokens
                                if (!deleted &&
                                    !invalidated &&
                                    markdownDocument.blocks.isNotEmpty() &&
                                    bodyTextToRender == record.plaintext
                                ) {
                                    // Markdown can't be cleanly truncated to a line
                                    // count mid-document, so clip to the height of
                                    // MESSAGE_COLLAPSE_LINE_LIMIT body-large lines
                                    // and drop a Read More beneath when the natural
                                    // content is taller. The natural height is
                                    // measured on the inner content (clipToBounds is
                                    // visual only and doesn't constrain it); the
                                    // overflow flag latches true so applying the cap
                                    // can't shrink the measurement and flip it back.
                                    val lineHeightPx =
                                        with(density) { (MaterialTheme.typography.bodyLarge.lineHeight).toPx() }
                                    val maxBodyHeightPx = lineHeightPx * MESSAGE_COLLAPSE_LINE_LIMIT
                                    val maxBodyHeightDp = with(density) { maxBodyHeightPx.toDp() }
                                    var markdownOverflows by remember(record.messageIdHex) { mutableStateOf(false) }
                                    val collapseMarkdown = collapsible && markdownOverflows
                                    Column {
                                        Box(
                                            modifier =
                                                Modifier
                                                    .onSizeChanged {
                                                        if (collapsible && it.height > maxBodyHeightPx) {
                                                            markdownOverflows = true
                                                        }
                                                    }.then(
                                                        if (collapseMarkdown) {
                                                            Modifier
                                                                .heightIn(max = maxBodyHeightDp)
                                                                .clipToBounds()
                                                        } else {
                                                            Modifier
                                                        },
                                                    ),
                                        ) {
                                            // Mention names resolve through the profile
                                            // cache; npub taps stay in-app via the
                                            // profile sheet (never an external nostr:
                                            // intent).
                                            MarkdownMessageBody(
                                                markdownDocument,
                                                mentionDisplayName =
                                                    remember(appState) {
                                                        { bech32: String -> appState.mentionDisplayName(bech32) }
                                                    },
                                                onNostrProfileTap =
                                                    remember(appState) {
                                                        { bech32: String -> appState.presentNostrProfile(bech32) }
                                                    },
                                                onLastTextLayout = { lastLineLayout = it },
                                            )
                                        }
                                        if (collapseMarkdown) {
                                            Text(
                                                readMoreLabel,
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable { expandedFullView = true },
                                            )
                                        }
                                    }
                                } else {
                                    // Plain text truncates inline: render up to the
                                    // line limit, and if that overflows rebuild the
                                    // clipped text ending with "… Read More" (bold,
                                    // onSurface — reads as dark emphasis, not a link).
                                    // Measured with maxLines = limit + 1, so visual
                                    // overflow means the body needs more than one
                                    // extra line past the limit — clip it.
                                    val overflows =
                                        collapsible &&
                                            bodyFullLayout?.let {
                                                it.hasVisualOverflow && it.lineCount > MESSAGE_COLLAPSE_LINE_LIMIT
                                            } == true
                                    if (overflows) {
                                        val layout = bodyFullLayout!!
                                        // Cut at the last fully-visible line, trim trailing
                                        // whitespace, then append the ellipsis + a Read More
                                        // link. Only the Read More span is clickable, so a
                                        // long-press anywhere on the bubble still falls through
                                        // to the action menu rather than expanding the bubble.
                                        val cut =
                                            remember(bodyTextToRender, layout) {
                                                bodyTextToRender
                                                    .substring(0, layout.getLineEnd(MESSAGE_COLLAPSE_LINE_LIMIT - 1, visibleEnd = true))
                                                    .trimEnd()
                                            }
                                        val clippedText =
                                            buildAnnotatedString {
                                                append(cut)
                                                append("… ")
                                                withLink(
                                                    LinkAnnotation.Clickable("read_more") { expandedFullView = true },
                                                ) {
                                                    withStyle(readMoreStyle) { append(readMoreLabel) }
                                                }
                                            }
                                        Text(
                                            clippedText,
                                            style = MaterialTheme.typography.bodyLarge,
                                            // Footer geometry follows the clipped text's
                                            // real last line, not the full measurement.
                                            onTextLayout = { lastLineLayout = it },
                                        )
                                    } else {
                                        Text(
                                            bodyTextToRender,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = if (collapsible) MESSAGE_COLLAPSE_LINE_LIMIT + 1 else Int.MAX_VALUE,
                                            onTextLayout = {
                                                lastLineLayout = it
                                                bodyFullLayout = it
                                            },
                                        )
                                    }
                                }
                            }
                        } else if (!footerOnVisualMedia && !footerOnPendingVisual) {
                            Box(modifier = Modifier.align(if (mine) Alignment.End else Alignment.Start)) {
                                inlineFooter()
                            }
                        }
                        if (mine && item.status == MessageStatus.Failed) {
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { appState.launchMutation { controller.retryFailedSend(item) } },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = stringResource(R.string.retry),
                                        tint = timestampColor,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                IconButton(
                                    onClick = { controller.discardFailedSend(item) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.discard_failed_message),
                                        tint = timestampColor,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                        MessageActionMenu(
                            // Never render the menu for a deleted message, even
                            // if it was open when the delete landed.
                            expanded = isActionMenuOpen && !deleted,
                            anchorWindowYPx = longPressWindowY,
                            alignEnd = mine,
                            canDelete = mine && record.messageIdHex.isNotBlank() && !deleted,
                            canEdit = mine && record.kind == 9uL && record.messageIdHex.isNotBlank() && !deleted,
                            canForward = forwardBody != null,
                            quickReactionEmojis = quickReactionEmojis,
                            onDismissRequest = { onActionMenuOpenChange(false) },
                            onReact = { emoji ->
                                onActionMenuOpenChange(false)
                                reactWithEmoji(emoji)
                            },
                            onOpenEmojiPicker = {
                                onActionMenuOpenChange(false)
                                emojiPickerOpen = true
                            },
                            onReply = ::beginReply,
                            onEdit = {
                                onActionMenuOpenChange(false)
                                // Cancel any reply-in-progress: reply and
                                // edit modes are mutually exclusive in the
                                // composer banner.
                                controller.replyingTo = null
                                controller.editingMessageId = record.messageIdHex
                            },
                            onCopyText = ::copyMessageText,
                            onForward = ::beginForward,
                            onInfo = ::openInfoSheet,
                            onDelete = {
                                onActionMenuOpenChange(false)
                                // launchMutation so the MLS commit + Nostr publish
                                // survive navigating away from the conversation —
                                // the optimistic tombstone is already set in the
                                // controller's state and the FFI write needs to
                                // complete regardless of whether this bubble is
                                // still in composition.
                                appState.launchMutation { controller.deleteMessage(record) }
                            },
                        )
                    }
                }
                if (expandedFullView) {
                    MessageFullScreenView(
                        senderDisplayName = appState.displayName(record.sender),
                        senderSeed = record.sender,
                        senderAvatarUrl = appState.avatarUrl(record.sender),
                        body = displayedBody,
                        timeText = rememberedClockTime(record.recordedAt),
                        showStatus = mine && !deleted && !invalidated,
                        status = item.status,
                        canDelete = mine && record.messageIdHex.isNotBlank() && !deleted,
                        onReply = {
                            expandedFullView = false
                            beginReply()
                        },
                        onReact = {
                            expandedFullView = false
                            emojiPickerOpen = true
                        },
                        onCopy = ::copyMessageText,
                        onDelete = {
                            expandedFullView = false
                            appState.launchMutation { controller.deleteMessage(record) }
                        },
                        onDismiss = { expandedFullView = false },
                    )
                }
                if (emojiPickerOpen) {
                    EmojiPickerSheet(
                        onDismissRequest = { emojiPickerOpen = false },
                        onEmojiPicked = { emoji ->
                            emojiPickerOpen = false
                            reactWithEmoji(emoji)
                        },
                    )
                }
                if (editHistoryOpen && editState != null) {
                    EditHistorySheet(
                        original = record.plaintext,
                        originalTimestamp = record.recordedAt,
                        editState = editState,
                        onDismissRequest = { editHistoryOpen = false },
                    )
                }
                if (infoSheetOpen) {
                    MessageInfoSheet(
                        item = item,
                        mine = mine,
                        senderDisplayName = appState.displayName(record.sender),
                        senderNpub = appState.npub(record.sender),
                        onDismissRequest = { infoSheetOpen = false },
                        onCopy = { value ->
                            clipboard.setText(AnnotatedString(value))
                            appState.present(R.string.copied)
                        },
                    )
                }
                if (forwardSheetOpen && forwardBody != null) {
                    ForwardMessageSheet(
                        appState = appState,
                        body = forwardBody,
                        originGroupIdHex = record.groupIdHex,
                        onDismiss = { forwardSheetOpen = false },
                        onForward = { targetGroupIds ->
                            appState.forwardText(targetGroupIds, forwardBody)
                        },
                    )
                }
                val tallies = controller.reactions[record.messageIdHex].orEmpty()
                // Hide reaction tallies on a deleted message — nothing to show.
                if (tallies.isNotEmpty() && !deleted) {
                    // The chip overlaps the bubble's rounded bottom by 14dp. A
                    // plain `offset` is draw-only: it still reserves the chip's
                    // full height in the column, leaving a 14dp gap before the
                    // next message so reacted rows sit looser than un-reacted
                    // ones (#455). Shrink the consumed height by the same 14dp
                    // via `layout` so the overlap costs no extra vertical space.
                    Box(
                        modifier =
                            Modifier
                                .padding(horizontal = 10.dp)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    val shift = 14.dp.roundToPx()
                                    val height = (placeable.height - shift).coerceAtLeast(0)
                                    layout(placeable.width, height) {
                                        placeable.place(0, -shift)
                                    }
                                },
                    ) {
                        ReactionSummaryChip(
                            tallies = tallies,
                            onClick = { reactionSheetOpen = true },
                        )
                    }
                }
                if (reactionSheetOpen) {
                    val participants =
                        remember(record.messageIdHex, item.projected?.reactions, tallies) {
                            controller.reactionParticipantsFor(record.messageIdHex)
                        }
                    // Close when the participant list drains, without re-firing for every list update.
                    LaunchedEffect(participants.isEmpty()) {
                        if (participants.isEmpty()) reactionSheetOpen = false
                    }
                    if (participants.isNotEmpty()) {
                        ReactionDetailsSheet(
                            participants = participants,
                            appState = appState,
                            onRemoveOwnReaction = { emoji -> appState.launchMutation { controller.toggleReaction(emoji, record) } },
                            onDismissRequest = { reactionSheetOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen reader for a body too long to show inline. Reached from the
 * collapsed bubble's Read More; Back returns to the conversation unchanged
 * (#325). A full-bleed Dialog avoids touching the existing nav backstack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageFullScreenView(
    senderDisplayName: String,
    senderSeed: String,
    senderAvatarUrl: String?,
    body: String,
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
    canDelete: Boolean,
    onReply: () -> Unit,
    onReact: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
            ),
    ) {
        var overflowOpen by remember { mutableStateOf(false) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        // The sender identity lives in the bar itself — avatar +
                        // name with the send time (and delivery status for own
                        // messages) as a subtitle — so the body below is just the
                        // message, no redundant in-content header.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Avatar(title = senderDisplayName, seed = senderSeed, size = 36.dp, pictureUrl = senderAvatarUrl)
                            Column {
                                Text(
                                    senderDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (showStatus) {
                                        OutgoingMessageStatusIcon(status, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.message_actions))
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reply)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onReply()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.message_react)) },
                                leadingIcon = { Icon(Icons.Default.EmojiEmotions, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onReact()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.copy_text)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    overflowOpen = false
                                    onCopy()
                                },
                            )
                            if (canDelete) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.delete)) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        overflowOpen = false
                                        onDelete()
                                    },
                                )
                            }
                        }
                    },
                    // The Dialog window already sits below the status bar, so the
                    // bar's own status-bar inset would double the gap and inflate
                    // its height. Zero it to render at the standard compact height.
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            },
        ) { padding ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * One consolidated reaction pill: the distinct emojis clustered together with a
 * total count, mirroring the familiar messenger style — a single compact target
 * rather than a spread of separate chips. Tapping opens the reactor list, where
 * a reaction can be removed.
 */
@Composable
private fun ReactionSummaryChip(
    tallies: List<ReactionTally>,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val mine = tallies.any { it.mine }
    val total = tallies.sumOf { it.count }
    val emojis = tallies.take(MAX_VISIBLE_REACTIONS).joinToString(separator = "") { it.emoji }
    val viewReactorsLabel = stringResource(R.string.view_reactors)
    Surface(
        modifier =
            Modifier
                // Expose the "you reacted" state to TalkBack, not just via color.
                .semantics { selected = mine }
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(percent = 50))
                .clickable(role = Role.Button, onClick = onClick, onClickLabel = viewReactorsLabel),
        shape = RoundedCornerShape(percent = 50),
        color = if (mine) colorScheme.secondaryContainer else colorScheme.surfaceContainerHigh,
        contentColor = if (mine) colorScheme.onSecondaryContainer else colorScheme.onSurface,
        border = BorderStroke(1.5.dp, colorScheme.surface),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(emojis, style = MaterialTheme.typography.labelLarge)
            if (total > 1) {
                Text(total.toString(), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionDetailsSheet(
    participants: List<ReactionParticipant>,
    appState: DarkMatterAppState,
    onRemoveOwnReaction: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var selectedEmoji by remember(participants) { mutableStateOf<String?>(null) }
    val activeAccountId = appState.activeAccount?.accountIdHex
    val emojiCounts =
        remember(participants) {
            participants
                .groupingBy { it.emoji }
                .eachCount()
                .toList()
                .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        }
    val visibleParticipants =
        remember(participants, selectedEmoji) {
            selectedEmoji?.let { emoji -> participants.filter { it.emoji == emoji } } ?: participants
        }

    ModalBottomSheet(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedEmoji == null,
                    onClick = { selectedEmoji = null },
                    label = { Text("${stringResource(R.string.reaction_filter_all)} · ${participants.size}") },
                )
                emojiCounts.forEach { (emoji, count) ->
                    FilterChip(
                        selected = selectedEmoji == emoji,
                        onClick = { selectedEmoji = emoji },
                        label = { Text("$emoji $count") },
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(
                    visibleParticipants,
                    key = { index, participant -> "${participant.sender}:${participant.emoji}:${participant.reactedAt}:$index" },
                ) { _, participant ->
                    val isMine = activeAccountId != null && participant.sender.equals(activeAccountId, ignoreCase = true)
                    ReactionParticipantRow(
                        participant = participant,
                        appState = appState,
                        mine = isMine,
                        onRemove = if (isMine) ({ onRemoveOwnReaction(participant.emoji) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionParticipantRow(
    participant: ReactionParticipant,
    appState: DarkMatterAppState,
    mine: Boolean,
    onRemove: (() -> Unit)?,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable {
                    if (mine && onRemove != null) onRemove() else appState.presentProfile(appState.npub(participant.sender))
                }.padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            title = appState.displayName(participant.sender),
            seed = participant.sender,
            size = 44.dp,
            pictureUrl = appState.avatarUrl(participant.sender),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (mine) stringResource(R.string.you) else appState.displayName(participant.sender),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mine) {
                Text(
                    text = stringResource(R.string.reaction_tap_to_remove),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = participant.emoji,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

private data class EditHistoryRow(
    val versionNumber: Int,
    val text: String,
    val recordedAt: ULong,
    val isLatest: Boolean,
    val isOriginal: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditHistorySheet(
    original: String,
    originalTimestamp: ULong,
    editState: EditState,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Newest first reads as "this is what's shown now ← earlier revisions ← original".
    val rows =
        remember(original, originalTimestamp, editState) {
            buildList {
                editState.versions.reversed().forEachIndexed { reversedIndex, version ->
                    val versionNumber = editState.versions.size - reversedIndex
                    add(
                        EditHistoryRow(
                            versionNumber = versionNumber,
                            text = version.text,
                            recordedAt = version.recordedAt,
                            isLatest = reversedIndex == 0,
                            isOriginal = false,
                        ),
                    )
                }
                add(
                    EditHistoryRow(
                        versionNumber = 0,
                        text = original,
                        recordedAt = originalTimestamp,
                        isLatest = false,
                        isOriginal = true,
                    ),
                )
            }
        }
    ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        // The header is anchored above the scroll region so the title and
        // count chip remain visible while the user pages through a long edit
        // chain. The rail keeps its visual continuity because every row is
        // a child of the same Column — a LazyColumn would compose each row
        // independently and break the dot-to-dot line through the rail.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_history),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = stringResource(R.string.edited_count, editState.versions.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    EditHistoryVersionRow(
                        row = row,
                        isFirst = index == 0,
                        isLast = index == rows.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditHistoryVersionRow(
    row: EditHistoryRow,
    isFirst: Boolean,
    isLast: Boolean,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Leading rail: dot anchored to the label row + a vertical line
        // connecting consecutive dots so the column reads as a single
        // timeline rather than disconnected cards.
        Column(
            Modifier.width(16.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(
                Modifier
                    .height(10.dp)
                    .width(2.dp)
                    .background(
                        if (isFirst) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
            val dotColor =
                when {
                    row.isLatest -> MaterialTheme.colorScheme.primary
                    row.isOriginal -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            Box(Modifier.size(10.dp).background(dotColor, shape = CircleShape))
            Spacer(
                Modifier
                    .weight(1f)
                    .width(2.dp)
                    .background(
                        if (isLast) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
        Column(
            Modifier.fillMaxWidth().padding(bottom = if (isLast) 0.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (row.isLatest) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.edit_history_version_label, row.versionNumber),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    Text(
                        text =
                            if (row.isOriginal) {
                                stringResource(R.string.edit_history_original)
                            } else {
                                stringResource(R.string.edit_history_version_label, row.versionNumber)
                            },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = rememberedRelativeTime(row.recordedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                color =
                    if (row.isOriginal) {
                        MaterialTheme.colorScheme.surfaceContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (row.isOriginal) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MessageActionMenu(
    expanded: Boolean,
    anchorWindowYPx: Float?,
    alignEnd: Boolean,
    canDelete: Boolean,
    canEdit: Boolean,
    canForward: Boolean,
    quickReactionEmojis: List<String>,
    onDismissRequest: () -> Unit,
    onReact: (String) -> Unit,
    onOpenEmojiPicker: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onForward: () -> Unit,
    onCopyText: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
) {
    // focusable = false keeps the soft keyboard up while this menu is open.
    // A focusable popup window steals window focus from the conversation's
    // host window, and Android dismisses the IME when the window holding the
    // focused composer loses focus. That collapse then removes the composer's
    // imePadding, reflowing the transcript down by the keyboard height mid
    // gesture — so the long-press popover lands at a shifted position rather
    // than where the user pressed (#284). Same "modal UI fights the IME"
    // family as the voice-record bar in #207.
    //
    // A non-focusable popup has two gaps versus the old focusable menu that we
    // restore explicitly here, without re-focusing (which would collapse the
    // IME again):
    //   1. Back dismissal — Popup's dismissOnBackPress is a no-op while the
    //      popup is non-focusable, so a Back press would fall through to the
    //      IME/activity instead of closing the menu. A host-window BackHandler
    //      (same pattern as QuickActionFabMenu) closes the menu on Back. It
    //      runs in the conversation window and does not touch IME focus.
    //   2. Outside-tap click-through — events outside a non-focusable popup are
    //      delivered to the windows beneath it, so a dismiss tap would also
    //      activate the underlying chat content (open a profile, a link, the
    //      media viewer, etc.). A full-window, non-focusable scrim Popup placed
    //      below this menu consumes those taps: tapping it dismisses the menu
    //      and the press is consumed so it never reaches the transcript. The
    //      scrim is itself non-focusable, so it preserves the open keyboard.
    //
    // Everything below is wrapped in a single zero-size Box so this composable
    // always contributes exactly ONE (zero-height) child to the caller's
    // spacedBy bubble Column, whether or not the menu is open. Emitting the
    // scrim popup as a second sibling only while expanded would otherwise add
    // an extra Arrangement.spacedBy gap, visibly growing the bubble height on
    // long-press (#284 review).
    val density = LocalDensity.current
    // Single zero-size Box wrapper: this composable always contributes exactly
    // ONE (zero-height) child to the caller's spacedBy bubble Column, open or
    // not. Emitting the scrim + menu popups as bare siblings only while expanded
    // adds extra Arrangement.spacedBy gaps that visibly grow the bubble on
    // long-press (#284 review). The popups themselves render in their own
    // windows, so the Box stays zero-size either way.
    Box {
        if (!expanded) return@Box
        BackHandler(enabled = true) { onDismissRequest() }
        // Scrim popup: composed before the menu so the menu renders on top of it.
        // Fills the window and swallows any tap as a pure dismissal.
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    // We own dismissal via the tap handler below; let the menu's own
                    // outside-tap detection stay off so a single outside tap is
                    // handled exactly once, here, and consumed.
                    dismissOnClickOutside = false,
                ),
            onDismissRequest = onDismissRequest,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { onDismissRequest() }
                        },
            )
        }
        // Position the popup purely from the captured window touch y, independent of
        // any anchor's layout position. DropdownMenu derived flip-above from the
        // anchor's bounds, so a bubble taller than the viewport (anchor off-screen)
        // could send the menu above the finger even with room below (#326). This
        // provider clamps/flips against the window directly.
        val edgeInsetPx = with(density) { 8.dp.roundToPx() }
        // Compose runs calculatePosition on the FIRST layout pass with
        // popupContentSize == (0,0) (content not yet measured). With height 0 the
        // "fits below" branch is always true, so a near-top message would place
        // the menu top AT touchY on frame 1, then flip/clamp once the real tall
        // height arrives — a visible above-then-below jump (#389). Decide the side
        // deterministically from frame 1 by feeding a non-zero height into the
        // provider: the real measured height once known, else a per-variant
        // estimate derived from the menu's own layout so frame 1 already matches
        // the height the side decision will settle on.
        var measuredPopupHeightPx by remember { mutableStateOf(0) }
        // First-frame fallback only. A flat constant (the previous 240.dp) both
        // overestimated short menus — flipping them above even when they fit
        // below — and underestimated tall menus, so the measured height could
        // still flip the side on frame 2 (the same jump #389 set out to remove,
        // see #517). Instead, predict the height from the exact menu layout:
        //   - one emoji/quick-reaction Row (36.dp)
        //   - a HorizontalDivider (1.dp)
        //   - the action buttons (each 48.dp min) in a spacedBy(2.dp) Column:
        //       Reply, Copy, Info always; +Edit when canEdit; +Delete when canDelete
        //   - the outer Column's 8.dp padding (top + bottom) and its two
        //     spacedBy(8.dp) gaps between the three sections.
        // Keep this in sync with the menu Column below if its layout changes.
        val estimatedPopupHeightPx =
            with(density) {
                val actionButtonCount = 3 + (if (canEdit) 1 else 0) + (if (canDelete) 1 else 0)
                val actionsColumnHeight = (actionButtonCount * 48).dp + ((actionButtonCount - 1) * 2).dp
                val totalHeight = (8.dp + 8.dp) + (8.dp + 8.dp) + 36.dp + 1.dp + actionsColumnHeight
                totalHeight.roundToPx()
            }
        val positionProvider =
            remember(anchorWindowYPx, alignEnd, edgeInsetPx, measuredPopupHeightPx, estimatedPopupHeightPx) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize,
                    ): IntOffset {
                        val touchY = anchorWindowYPx?.roundToInt() ?: (windowSize.height / 2)
                        // Horizontal: hug the bubble side, clamped inside the window.
                        val x =
                            if (alignEnd) {
                                windowSize.width - popupContentSize.width - edgeInsetPx
                            } else {
                                edgeInsetPx
                            }.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                        // Decide vertical placement against a non-zero height so the
                        // chosen side is stable from the first frame. Once the popup
                        // has any real measurement (content or onSizeChanged), use it
                        // directly so the *settled* placement reflects the true menu
                        // height; the per-variant estimate is consulted only on the
                        // first frame before either is known (#517).
                        val measuredHeight = maxOf(popupContentSize.height, measuredPopupHeightPx)
                        val effectiveHeight =
                            if (measuredHeight > 0) measuredHeight else estimatedPopupHeightPx
                        // Vertical: top at the touch y; flip upward if it would spill
                        // past the bottom inset; if it still doesn't fit, clamp to top.
                        val bottomLimit = windowSize.height - edgeInsetPx
                        val y =
                            when {
                                touchY + effectiveHeight <= bottomLimit -> touchY
                                effectiveHeight <= touchY - edgeInsetPx -> touchY - effectiveHeight
                                else -> edgeInsetPx
                            }.coerceIn(edgeInsetPx, (windowSize.height - effectiveHeight).coerceAtLeast(0))
                        return IntOffset(x, y)
                    }
                }
            }
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismissRequest,
            properties =
                PopupProperties(
                    focusable = false,
                    // Outside taps are handled by the scrim above (which also blocks
                    // click-through); disabling the menu's own outside-dismiss keeps a
                    // single tap from being processed twice.
                    dismissOnClickOutside = false,
                ),
        ) {
            // Surface restores the menu chrome (rounded shape + elevation) that
            // DropdownMenu provided.
            Surface(
                modifier = Modifier.onSizeChanged { measuredPopupHeightPx = it.height },
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(8.dp).widthIn(min = 292.dp, max = 328.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        quickReactionEmojis.forEach { emoji ->
                            EmojiActionButton(
                                emoji = emoji,
                                onClick = { onReact(emoji) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        IconButton(
                            onClick = onOpenEmojiPicker,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.EmojiEmotions,
                                contentDescription = stringResource(R.string.open_emoji_picker),
                            )
                        }
                    }
                    HorizontalDivider()
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        MessageActionButton(
                            label = stringResource(R.string.reply),
                            icon = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onReply,
                        )
                        if (canEdit) {
                            MessageActionButton(
                                label = stringResource(R.string.edit),
                                icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onEdit,
                            )
                        }
                        MessageActionButton(
                            label = stringResource(R.string.copy_text),
                            icon = { Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onCopyText,
                        )
                        if (canForward) {
                            MessageActionButton(
                                label = stringResource(R.string.forward),
                                icon = { Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onForward,
                            )
                        }
                        MessageActionButton(
                            label = stringResource(R.string.message_info),
                            icon = { Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp)) },
                            onClick = onInfo,
                        )
                        if (canDelete) {
                            MessageActionButton(
                                label = stringResource(R.string.delete),
                                icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                onClick = onDelete,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Chat-picker sheet for forwarding a message into one or more other chats
 * (issue #390). Multi-select, searchable, recent-first. Confirming fans the
 * message out to every selected chat as an independent fresh send (see
 * [DarkMatterAppState.forwardText]) — each target is re-encrypted under its own
 * group state, with no source-group key reuse and no original-sender / source
 * attribution carried across the boundary.
 *
 * The picker deliberately omits the chat the message came from
 * ([originGroupIdHex]): forwarding a message back into its own conversation is
 * never the intent and would just duplicate it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForwardMessageSheet(
    appState: DarkMatterAppState,
    body: String,
    originGroupIdHex: String,
    onDismiss: () -> Unit,
    onForward: (List<String>) -> Unit,
) {
    val groupTitleCopy = rememberGroupTitleCopy()
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    // Snapshot the forward targets once when the sheet opens. The chat list is
    // a live projection, but a picker that re-sorts under the user's finger as
    // a background send confirms would shuffle rows mid-selection; a stable
    // snapshot keeps the selection anchored to the rows the user actually saw.
    val targets =
        remember {
            appState.forwardTargets().filterNot { it.group.groupIdHex.equals(originGroupIdHex, ignoreCase = true) }
        }
    val titledTargets =
        remember(targets, groupTitleCopy) {
            targets.map { it to chatListItemDisplayTitle(it, appState, groupTitleCopy) }
        }
    val filtered =
        remember(titledTargets, query) {
            val needle = query.trim()
            if (needle.isEmpty()) {
                titledTargets
            } else {
                titledTargets.filter { (_, title) -> title.contains(needle, ignoreCase = true) }
            }
        }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.forward_to),
                style = MaterialTheme.typography.titleLarge,
            )
            // Preview of what is being forwarded, so the user can confirm the
            // content before fanning it out to several chats.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.forward_search_chats)) },
            )
            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.forward_no_chats),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (filtered.isEmpty()) {
                Text(
                    stringResource(R.string.forward_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    items(filtered, key = { (item, _) -> item.group.groupIdHex }) { (item, title) ->
                        val groupId = item.group.groupIdHex
                        val isSelected = selected.contains(groupId)
                        ListItem(
                            modifier =
                                Modifier.clickable {
                                    if (isSelected) selected.remove(groupId) else selected.add(groupId)
                                },
                            leadingContent = {
                                val avatarAccount =
                                    item.otherMemberAccount
                                        ?.takeIf { item.group.name.isBlank() && item.memberCount == 2 }
                                Avatar(
                                    title = title,
                                    seed = avatarAccount ?: item.group.groupIdHex,
                                    size = 40.dp,
                                    pictureUrl = item.group.avatarUrl ?: avatarAccount?.let { appState.avatarUrl(it) },
                                )
                            },
                            headlineContent = {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        if (isSelected) selected.remove(groupId) else selected.add(groupId)
                                    },
                                )
                            },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    onForward(selected.toList())
                    onDismiss()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Forward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selected.isEmpty()) {
                        stringResource(R.string.forward)
                    } else {
                        pluralStringResource(
                            R.plurals.forward_to_chats_count,
                            selected.size,
                            selected.size,
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageInfoSheet(
    item: TimelineMessage,
    mine: Boolean,
    senderDisplayName: String,
    senderNpub: String,
    onDismissRequest: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val record = item.record
    val configuration = LocalConfiguration.current
    val locale =
        remember(configuration) {
            ConfigurationCompat.getLocales(configuration).get(0) ?: Locale.getDefault()
        }
    val zone = remember { ZoneId.systemDefault() }
    val statusLabels =
        MessageStatusLabels(
            pending = stringResource(R.string.message_status_pending),
            sent = stringResource(R.string.message_status_sent),
            received = stringResource(R.string.message_status_received),
            failed = stringResource(R.string.message_status_failed),
            streaming = stringResource(R.string.message_status_streaming),
        )
    val statusText = labelFor(item.status, statusLabels)
    // Label derives from status, not `mine`, so an outgoing Failed bubble
    // doesn't read "Sent" while the Status row says "Failed". For outgoing
    // pending/failed the row reflects local composition time.
    val timestampLabel =
        when (item.status) {
            MessageStatus.Sent -> stringResource(R.string.message_info_sent_at)
            MessageStatus.Received, MessageStatus.Streaming -> stringResource(R.string.message_info_received_at)
            MessageStatus.Pending, MessageStatus.Failed -> stringResource(R.string.message_info_created_at)
        }
    // For incoming, prefer the *local* arrival time — sender's claimed
    // `recordedAt` can be spoofed. Surface `recordedAt` as a second row only
    // when it diverges from receivedAt by more than a few seconds (anything
    // less is clock-skew noise).
    val primarySeconds = if (!mine && record.receivedAt > 0uL) record.receivedAt else record.recordedAt
    val formattedTimestamp = formatExactTimestamp(primarySeconds, zone, locale)
    val showOriginal = !mine && shouldShowOriginalTimestamp(record.recordedAt, record.receivedAt)
    val formattedOriginalTimestamp =
        if (showOriginal) {
            formatExactTimestamp(record.recordedAt, zone, locale)
        } else {
            ""
        }
    val npubShort = shortHex(senderNpub, head = 12, tail = 6)
    val messageIdShort = shortHex(record.messageIdHex)
    val copyActionLabel = stringResource(R.string.copy_text)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.message_info),
                style = MaterialTheme.typography.titleMedium,
            )
            if (formattedTimestamp.isNotBlank()) {
                MessageInfoRow(
                    label = timestampLabel,
                    value = formattedTimestamp,
                )
            }
            if (formattedOriginalTimestamp.isNotBlank()) {
                // Sender's claimed send time. Suppressed when it matches the
                // local Received time within the skew tolerance — see
                // shouldShowOriginalTimestamp — so the row only appears when
                // it adds information.
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sent_at),
                    value = formattedOriginalTimestamp,
                )
            }
            // "From" is meaningful only for incoming messages; hide for own
            // messages where it would read tautologically "From: <my name>".
            if (!mine && senderNpub.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_sender),
                    value = if (senderDisplayName.isNotBlank()) "$senderDisplayName · $npubShort" else npubShort,
                    onCopy = { onCopy(senderNpub) },
                    copyActionLabel = copyActionLabel,
                )
            }
            if (record.messageIdHex.isNotBlank()) {
                MessageInfoRow(
                    label = stringResource(R.string.message_info_message_id),
                    value = messageIdShort,
                    onCopy = { onCopy(record.messageIdHex) },
                    copyActionLabel = copyActionLabel,
                )
            }
            MessageInfoRow(
                label = stringResource(R.string.message_info_status),
                value = statusText,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MessageInfoRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null,
    copyActionLabel: String? = null,
) {
    val rowModifier =
        if (onCopy != null) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyActionLabel,
                    role = Role.Button,
                    onClick = onCopy,
                )
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        modifier = rowModifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (onCopy != null) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmojiPickerSheet(
    onDismissRequest: () -> Unit,
    onEmojiPicked: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(384.dp).navigationBarsPadding(),
            factory = { context ->
                EmojiPickerView(context).apply {
                    emojiGridColumns = 8
                    emojiGridRows = 5.25f
                    setOnEmojiPickedListener { item -> onEmojiPicked(item.emoji) }
                }
            },
        )
    }
}

@Composable
private fun EmojiActionButton(
    emoji: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.height(36.dp).clip(CircleShape).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun MessageActionButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OutgoingMessageStatusIcon(
    status: MessageStatus,
    tint: Color,
) {
    val indicator = status.outgoingIndicator() ?: return
    when (indicator) {
        OutgoingMessageIndicator.Sending ->
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = stringResource(R.string.sending),
                modifier = Modifier.size(14.dp),
                tint = tint.copy(alpha = 0.76f),
            )
        OutgoingMessageIndicator.Sent ->
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = stringResource(R.string.sent),
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
        OutgoingMessageIndicator.Failed ->
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = stringResource(R.string.send_failed),
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.error,
            )
    }
}

// Gap between a bubble's text and its trailing inline footer.
private val BubbleFooterGap = 8.dp

// A body longer than this many rendered lines collapses to a Read More that
// opens the full-screen view rather than spilling down the transcript (#325).
private const val MESSAGE_COLLAPSE_LINE_LIMIT = 12

// Distinct emojis shown in the consolidated reaction pill; the total count
// still reflects every reaction beyond them.
private const val MAX_VISIBLE_REACTIONS = 4

/** Legibility scrim for a footer overlaid on visual media (image/video). */
@Composable
private fun MediaScrimFooter(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        content()
    }
}

/**
 * Sticky day-ribbon overlay: the day label of the topmost visible message,
 * faded in only while the timeline is actively scrolling (the inline
 * [DaySeparator]s carry the day at rest).
 *
 * Reads the scroll-backed state (`labelState` derived from
 * `firstVisibleItemIndex`, and `listState.isScrollInProgress`) inside this
 * small child so per-scroll-frame recomposition is confined here and does not
 * propagate to the LazyColumn-hosting Box scope (#375).
 */
@Composable
private fun BoxScope.StickyDayRibbon(
    listState: androidx.compose.foundation.lazy.LazyListState,
    labelState: State<String>,
) {
    val label by labelState
    val alpha by animateFloatAsState(
        targetValue = if (listState.isScrollInProgress && label.isNotEmpty()) 1f else 0f,
        label = "stickyDayRibbon",
    )
    if (alpha > 0.01f) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .alpha(alpha)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/** Time (+ outgoing status) overlaid on the bottom-right of a visual-media bubble. */
@Composable
private fun BoxScope.MediaFooterOverlay(
    timeText: String,
    showStatus: Boolean,
    status: MessageStatus,
) {
    MediaScrimFooter(
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp),
    ) {
        MessageInlineFooter(
            timeText = timeText,
            color = Color.White,
            showStatus = showStatus,
            status = status,
            editedLabel = null,
            onEditedClick = null,
        )
    }
}

/**
 * Bottom-end footer for a message bubble: an optional "edited" affordance, the
 * time, and (outgoing only) the send-status icon, in a subtle tint.
 */
@Composable
private fun MessageInlineFooter(
    timeText: String,
    color: Color,
    showStatus: Boolean,
    status: MessageStatus,
    editedLabel: String?,
    onEditedClick: (() -> Unit)?,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editedLabel != null) {
            Text(
                text = editedLabel,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = if (onEditedClick != null) Modifier.clickable(onClick = onEditedClick) else Modifier,
            )
        }
        Text(
            text = timeText,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
        if (showStatus) {
            OutgoingMessageStatusIcon(status, tint = color)
        }
    }
}

/**
 * Lays [content] with [footer] pinned bottom-end. The footer joins the last
 * line when it leaves room ([lastLineWidth], the real last-line right edge when
 * the caller can supply it; otherwise the widest line); else it drops to its
 * own line below. Either way it stays right of the text and never overlaps.
 */
@Composable
private fun BubbleFooterLayout(
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    lastLineWidth: Int? = null,
    content: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Box { content() }
            Box { footer() }
        },
    ) { measurables, constraints ->
        val footerPlaceable = measurables[1].measure(Constraints())
        val contentPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val gap = BubbleFooterGap.roundToPx()
        val lastRight = (lastLineWidth ?: contentPlaceable.width).coerceIn(0, contentPlaceable.width)
        val inline = lastRight + gap + footerPlaceable.width <= constraints.maxWidth
        if (inline) {
            val width = maxOf(contentPlaceable.width, lastRight + gap + footerPlaceable.width)
            layout(width, contentPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height - footerPlaceable.height)
            }
        } else {
            val width = maxOf(contentPlaceable.width, footerPlaceable.width).coerceAtMost(constraints.maxWidth)
            layout(width, contentPlaceable.height + footerPlaceable.height) {
                contentPlaceable.place(0, 0)
                footerPlaceable.place(width - footerPlaceable.width, contentPlaceable.height)
            }
        }
    }
}

@Composable
private fun RemovedMemberComposerNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Text(
            text = stringResource(R.string.you_are_no_longer_a_member),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ComposerBar(
    replyingTo: AppMessageRecordFfi?,
    messageTextCopy: MessageTextCopy,
    onCancelReply: () -> Unit,
    onSend: (text: String, onAccepted: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    initialDraft: String = "",
    onDraftChange: (String) -> Unit = {},
    draftKey: Any? = null,
    onAfterSend: () -> Unit = {},
    onPickFromGallery: (() -> Unit)? = null,
    onCaptureFromCamera: (() -> Unit)? = null,
    onPickDocument: (() -> Unit)? = null,
    voiceRecordingController: dev.ipf.darkmatter.audio.VoiceRecordingController? = null,
    editingMessageId: String? = null,
    editingInitialText: String? = null,
    onCancelEdit: () -> Unit = {},
    appState: DarkMatterAppState? = null,
    // Group @-mention picker (#414): candidates the picker filters over, and
    // whether this conversation is a group (the picker is suppressed in DMs —
    // a 1:1 chat has no one to disambiguate, so typing `@` stays literal).
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    mentionPickerEnabled: Boolean = false,
    // When the conversation was just created in the same navigation step
    // (issue #321), request focus on the composer and raise the soft keyboard
    // once on entry so the user can type the first message without an extra
    // tap. One-shot: a guard flag stops a revisit / recomposition from
    // re-opening the IME, and the flag is not persisted across process death.
    autoFocusOnEnter: Boolean = false,
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    // Field state is a TextFieldValue (not a bare String) so the caret can
    // be positioned at the end of the prefilled body on edit-entry, and so
    // a re-tap on a different message rebases the caret too. Keyed on
    // draftKey so switching to a different chat re-hydrates the text field
    // from that chat's saved draft rather than carrying state across.
    var textFieldValue by remember(draftKey) { mutableStateOf(TextFieldValue(initialDraft)) }
    val text = textFieldValue.text
    // Snapshot the in-flight composer state (full TextFieldValue — text +
    // caret) when entering edit mode so cancelling restores both. Keyed on
    // the message id so a tap-Edit on a different message snapshots a fresh
    // baseline.
    var preEditFieldValue by remember(draftKey) { mutableStateOf<TextFieldValue?>(null) }
    // Claim focus on edit-entry so the IME opens with the caret at the end
    // of the prefill, without making the user tap the field a second time.
    val composerFocus = remember { FocusRequester() }
    // Keyed on editingMessageId only: prefill once when an edit session starts,
    // not on every reprojection of editingInitialText — otherwise a background
    // timeline update would overwrite the user's in-progress edit.
    LaunchedEffect(editingMessageId) {
        if (editingMessageId != null) {
            // Save the in-flight composer once per edit session, then push
            // the message's current text into the input so the user edits
            // from where it stands today (which is the latest applied edit
            // if there's already an edit chain). Selection at `length` lands
            // the caret past the last character — same caret model as a
            // long-press-to-edit on every other modern chat composer.
            if (preEditFieldValue == null) preEditFieldValue = textFieldValue
            val prefill = editingInitialText.orEmpty()
            textFieldValue = TextFieldValue(text = prefill, selection = TextRange(prefill.length))
            runCatching { composerFocus.requestFocus() }
        } else if (preEditFieldValue != null) {
            // Edit cancelled or submitted: restore the draft the user had
            // been composing before they tapped Edit (text + original caret).
            textFieldValue = preEditFieldValue ?: TextFieldValue("")
            preEditFieldValue = null
        }
    }
    // #321: a just-created conversation opens directly with the composer ready.
    // Request focus and raise the soft keyboard exactly once, gated by a
    // plain-`remember` flag (NOT rememberSaveable) so it fires per composition
    // and never re-fires on a revisit or after process death. Skipped while
    // editing — the edit effect above already owns focus then.
    val keyboardController = LocalSoftwareKeyboardController.current
    var autoFocusConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(autoFocusOnEnter, editingMessageId) {
        if (autoFocusOnEnter && !autoFocusConsumed && editingMessageId == null) {
            autoFocusConsumed = true
            runCatching { composerFocus.requestFocus() }
            keyboardController?.show()
        }
    }
    // Single send path shared by the FAB and the Enter key (#404). Clears the
    // input/draft and scroll-to-newest ONLY after the controller confirms the
    // optimistic bubble is committed (it invokes onAccepted then). If a guard
    // rejects the send the callback never runs, so the user's text stays in
    // the field instead of vanishing silently (issue #264). For an in-place
    // edit the controller short-circuits and never calls onAccepted; the
    // LaunchedEffect that watches `editingMessageId` restores the pre-edit
    // composer once edit state clears — so we pass a no-op and don't blank
    // the field here.
    val submitMessage: () -> Unit = {
        if (text.isNotBlank()) {
            val sendingEdit = editingMessageId != null
            val sentText = text
            onSend(sentText) {
                if (!sendingEdit) {
                    // onAccepted can land after the user has started typing the
                    // next message (Enter-to-send makes that common). Only clear
                    // if the field still holds exactly what we sent, so newly
                    // typed text is never wiped.
                    if (textFieldValue.text == sentText) {
                        textFieldValue = TextFieldValue("")
                        onDraftChange("")
                    }
                    onAfterSend()
                }
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (editingMessageId != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.editing_message),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onCancelEdit, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_edit), modifier = Modifier.size(18.dp))
                }
            }
        } else if (replyingTo != null) {
            val refs = remember(replyingTo.tags) { MediaReferenceParser.parseAllImetaTags(replyingTo.tags) }
            val mediaKind = remember(refs) { replyMediaKindFromMime(refs.firstOrNull()?.mediaType) }
            ReplyPreviewCard(
                senderTitle =
                    if (replyingTo.direction == "sent") {
                        stringResource(R.string.reply_you)
                    } else {
                        appState?.displayName(replyingTo.sender) ?: replyingTo.sender.take(8)
                    },
                isOwn = replyingTo.direction == "sent",
                body = MessageProjector.displayBody(replyingTo, messageTextCopy),
                mediaKind = mediaKind,
                onClick = null,
                onDismiss = onCancelReply,
            )
        }
        // #414: live @-mention picker. Compute the open query from the current
        // caret; suppressed entirely in DMs and while editing/recording or with
        // no roster. Anchored directly above the composer input row, capped at
        // ~50% of the viewport height.
        val mentionQuery =
            if (mentionPickerEnabled && editingMessageId == null) {
                MentionComposer
                    .activeMentionQuery(textFieldValue.text, textFieldValue.selection.start)
                    .takeIf { textFieldValue.selection.collapsed }
            } else {
                null
            }
        val mentionMatches =
            remember(mentionQuery?.query, mentionCandidates) {
                if (mentionQuery == null) emptyList() else MentionComposer.filter(mentionQuery.query, mentionCandidates)
            }
        if (mentionQuery != null && mentionMatches.isNotEmpty()) {
            val openQuery = mentionQuery
            MentionPicker(
                candidates = mentionMatches,
                onPick = { candidate ->
                    val insertion = MentionComposer.insertMention(textFieldValue.text, openQuery, candidate)
                    val updated = TextFieldValue(text = insertion.text, selection = TextRange(insertion.selection))
                    textFieldValue = updated
                    if (editingMessageId == null) onDraftChange(updated.text)
                    runCatching { composerFocus.requestFocus() }
                },
            )
        }
        val activeRecordingController = voiceRecordingController?.takeIf { it.isRecording }
        val isRecordingVoice = activeRecordingController != null
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Keep the text field composed while recording. Removing the focused
            // BasicTextField makes Android dismiss the IME, which then removes
            // imePadding and drops this whole bottom bar under the user's finger.
            // The recording strip is only a visual overlay; focus stays with the
            // hidden composer so an already-open keyboard remains open.
            Box(modifier = Modifier.weight(1f)) {
                ComposerPill(
                    textFieldValue = textFieldValue,
                    composerFocus = composerFocus,
                    onValueChange = { value ->
                        if (!isRecordingVoice) {
                            // #414: a single Backspace at the right edge of an
                            // `@npub1…` chip (or just past its trailing space,
                            // the post-insert caret position) deletes the whole
                            // chip in one keypress, so a mention reads as one
                            // token. Falls through to the verbatim IME edit
                            // otherwise.
                            val whole =
                                MentionComposer.wholeChipBackspace(
                                    oldText = textFieldValue.text,
                                    oldCaret = textFieldValue.selection.start,
                                    newText = value.text,
                                    newCaret = value.selection.start,
                                )
                            val edited =
                                if (whole != null) {
                                    TextFieldValue(text = whole.text, selection = TextRange(whole.selection))
                                } else {
                                    value
                                }
                            // #414: keep the caret/selection out of the interior
                            // of any `@npub1…` chip so a tap, drag, or arrow key
                            // can't land inside the token (which would let a
                            // stray edit corrupt it or reopen the picker
                            // mid-token). Only in groups, where chips exist.
                            val applied =
                                if (mentionPickerEnabled) {
                                    val clamped =
                                        MentionComposer.clampSelectionOutOfChips(
                                            edited.text,
                                            edited.selection.start,
                                            edited.selection.end,
                                        )
                                    if (clamped.start != edited.selection.start ||
                                        clamped.end != edited.selection.end
                                    ) {
                                        edited.copy(selection = TextRange(clamped.start, clamped.end))
                                    } else {
                                        edited
                                    }
                                } else {
                                    edited
                                }
                            textFieldValue = applied
                            // While editing, the field holds the edit candidate,
                            // not a fresh chat draft. Persisting it would clobber
                            // whatever the user was composing before they tapped
                            // Edit — that's the snapshot we restore from
                            // preEditFieldValue on cancel/submit.
                            if (editingMessageId == null) onDraftChange(applied.text)
                        }
                    },
                    onAttachMenuToggle = { attachMenuOpen = !attachMenuOpen },
                    attachMenuOpen = attachMenuOpen,
                    onAttachMenuDismiss = { attachMenuOpen = false },
                    onCaptureFromCamera = onCaptureFromCamera,
                    onPickFromGallery = onPickFromGallery,
                    onPickDocument = onPickDocument,
                    // #414: tint inserted `@npub1…` chips so they read as a
                    // single styled token while composing. Only when the picker
                    // is enabled (groups) — DMs never insert chips.
                    highlightMentionChips = mentionPickerEnabled,
                    mentionCandidates = mentionCandidates,
                    enterKeyBehavior = enterKeyBehavior,
                    onImeSend = submitMessage,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (isRecordingVoice) 0f else 1f)
                            .then(if (isRecordingVoice) Modifier.clearAndSetSemantics {} else Modifier),
                )
                if (activeRecordingController != null) {
                    RecordingStripLeading(
                        controller = activeRecordingController,
                        modifier =
                            Modifier
                                .matchParentSize()
                                .pointerInput(activeRecordingController) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                },
                    )
                }
            }
            // Trailing MicHoldButton call site below must stay shared by both
            // recording and non-recording states; separate call sites break the
            // pointer-gesture identity for the active hold gesture.
            val showMicButton =
                (text.isBlank() || isRecordingVoice) &&
                    editingMessageId == null &&
                    voiceRecordingController != null
            if (showMicButton && voiceRecordingController?.locked == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = { voiceRecordingController.cancel() },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.voice_message_cancel),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    FloatingActionButton(
                        onClick = { voiceRecordingController.stop() },
                        modifier = Modifier.size(44.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            } else if (showMicButton) {
                Box(contentAlignment = Alignment.BottomCenter) {
                    LockHintAbove(controller = voiceRecordingController!!)
                    MicHoldButton(controller = voiceRecordingController)
                }
            } else {
                FloatingActionButton(
                    onClick = { submitMessage() },
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Floating member picker for the group composer's `@`-mention flow (#414).
 * Anchored directly above the composer input row (it's the child placed just
 * before the input [Row] in [ComposerBar]); capped at ~50% of the viewport
 * height so the keyboard and a long roster both stay reachable. Tapping a row
 * inserts that member as a chip via [MentionComposer.insertMention].
 */
@Composable
private fun MentionPicker(
    candidates: List<MentionComposer.Candidate>,
    onPick: (MentionComposer.Candidate) -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.heightIn(max = maxHeight)) {
            Text(
                stringResource(R.string.mention_picker_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            LazyColumn(Modifier.fillMaxWidth()) {
                items(candidates, key = { it.accountIdHex }) { candidate ->
                    val mentionLabel = stringResource(R.string.mention_picker_member, candidate.displayName)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(candidate) }
                                .semantics { contentDescription = mentionLabel }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Avatar(title = candidate.displayName, seed = candidate.accountIdHex, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                candidate.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val subtitle = candidate.nip05?.takeIf { it.isNotBlank() }
                            if (subtitle != null) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Hold-to-record voice button. Press → start; release inside the button
 * bounds → stop and send. Drag the finger outside the button before
 * releasing → cancel. The cancel threshold is `cancelThresholdPx` away
 * from the down position; the gesture stays as a pointerInput input so
 * Compose doesn't fight us for the up event.
 */
@Composable
private fun MicHoldButton(controller: dev.ipf.darkmatter.audio.VoiceRecordingController) {
    val haptics = LocalHapticFeedback.current
    val cancelThresholdDp = 120.dp
    val lockThresholdDp = 80.dp
    val density = LocalDensity.current
    val cancelThresholdPx = with(density) { cancelThresholdDp.toPx() }
    val lockThresholdPx = with(density) { lockThresholdDp.toPx() }
    val recording = controller.isRecording
    FloatingActionButton(
        // Accessibility fallback: a tap (TalkBack double-tap, keyboard
        // Enter, switch access) toggles record-and-lock so users who can't
        // perform the press-and-hold gesture can still send voice notes.
        onClick = {
            if (controller.isRecording) {
                controller.stop()
            } else if (controller.start()) {
                controller.lock()
            }
        },
        modifier =
            Modifier
                .size(44.dp)
                .pointerInput(controller) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val started = controller.start()
                        if (!started) return@awaitEachGesture
                        // Consume the down so the FAB's internal clickable
                        // doesn't ALSO interpret this press as a tap and fire
                        // its accessibility onClick after our hold gesture
                        // already handled stop/send/cancel.
                        down.consume()
                        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var canceled = false
                        var locked = false
                        var terminated = false
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    // Parent stole the pointer — cancel rather than orphan the recorder.
                                    controller.cancel()
                                    terminated = true
                                    break
                                }
                                change.consume()
                                val deltaX = change.position.x - down.position.x
                                val deltaY = change.position.y - down.position.y
                                controller.updateDrag(deltaX, deltaY, cancelThresholdPx, lockThresholdPx)
                                if (!locked && -deltaY > lockThresholdPx && -deltaX <= cancelThresholdPx) {
                                    locked = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                    controller.lock()
                                    terminated = true
                                    return@awaitEachGesture
                                }
                                if (!canceled && -deltaX > cancelThresholdPx) {
                                    canceled = true
                                    haptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                                    )
                                } else if (canceled && -deltaX <= cancelThresholdPx) {
                                    canceled = false
                                }
                                if (change.changedToUp() || !change.pressed) {
                                    if (canceled) controller.cancel() else controller.stop()
                                    terminated = true
                                    break
                                }
                            }
                        } finally {
                            // Composable removal / coroutine cancellation while still
                            // recording-unlocked → cancel cleanly instead of letting
                            // the recorder tick to the 60 s auto-stop.
                            if (!terminated && controller.isRecording && !controller.locked) {
                                controller.cancel()
                            }
                        }
                    }
                },
        containerColor =
            if (recording) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = stringResource(R.string.voice_message_record),
        )
    }
}

@Composable
private fun RecordingStripLeading(
    controller: dev.ipf.darkmatter.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    val pulseScale by rememberInfiniteRecordingPulse()
    val canceling = controller.willCancel
    val locked = controller.locked
    val cancelTint = MaterialTheme.colorScheme.error

    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Box(
            modifier =
                Modifier
                    .size((10 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
        )
        Text(
            formatRecordingDuration(controller.elapsedMs),
            style = MaterialTheme.typography.labelLarge,
            color = if (canceling) cancelTint else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (locked) {
            // Locked: the user has handed off control. The hint copy
            // collapses to a compact "Locked" indicator so the row stays
            // visually quiet while the trailing Stop+Trash do the work.
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                stringResource(R.string.voice_message_locked),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp),
            )
            Text(
                stringResource(R.string.voice_message_release_to_send),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LockHintAbove(
    controller: dev.ipf.darkmatter.audio.VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    if (controller.locked || !controller.isRecording) return
    val density = LocalDensity.current
    val rawDp = with(density) { (-controller.verticalOffsetPx).toDp() }
    val rise = rawDp.value.coerceIn(0f, 80f).dp
    val armed = controller.willLock
    Box(
        modifier =
            modifier
                .offset(y = -rise - 56.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint =
                if (armed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
    }
}

// Anchored dropdown that does NOT collapse the soft keyboard while it is open.
//
// A default DropdownMenu opens a *focusable* popup window; Android dismisses the
// IME the moment the window holding the focused composer loses focus. That
// collapse strips the composer's imePadding and reflows the transcript down by
// the keyboard height — so a menu/picker launched from the composer toolbar
// (the attach clip, the conversation overflow) animates in over a shifted
// layout, and dismissing it leaves the composer unfocused with the keyboard
// down (#323). Same "modal UI launched from the composer toolbar steals IME
// state instead of overlaying it" family as the voice-record bar (#207) and the
// long-press popover (#284).
//
// focusable = false keeps the keyboard up but opens two gaps versus a focusable
// menu, restored here exactly as ConversationLongPressMenu does for #284:
//   1. Back dismissal — Popup's dismissOnBackPress is a no-op while the popup is
//      non-focusable, so Back would fall through to the IME/activity instead of
//      closing the menu. A host-window BackHandler closes it without touching
//      IME focus.
//   2. Outside-tap click-through — taps outside a non-focusable popup are
//      delivered to the windows beneath it, so a dismiss tap would also activate
//      the underlying content. A full-window, non-focusable scrim Popup placed
//      below the menu consumes those taps: tapping it dismisses the menu and the
//      press is consumed so it never reaches the content. The scrim is itself
//      non-focusable, so it too preserves the open keyboard.
//
// Positioning, anchoring and the menu chrome stay DropdownMenu's — only its
// focus behavior and dismissal plumbing change.
@Composable
private fun KeyboardPreservingDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    shape: Shape = MenuDefaults.shape,
    content: @Composable ColumnScope.() -> Unit,
) {
    // BackHandler + scrim only exist while the menu is open. They render in
    // their own popup windows, so they never disturb the anchor's layout.
    if (expanded) {
        BackHandler(enabled = true) { onDismissRequest() }
        // Scrim: composed before the menu so the menu renders on top of it.
        // Fills the window and swallows any tap as a pure dismissal.
        Popup(
            properties =
                PopupProperties(
                    focusable = false,
                    // The scrim owns outside-tap dismissal; the menu's own
                    // outside-tap detection is disabled below so a single tap is
                    // handled exactly once, here, and consumed.
                    dismissOnClickOutside = false,
                ),
            onDismissRequest = onDismissRequest,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures { onDismissRequest() }
                        },
            )
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = shape,
        properties =
            PopupProperties(
                focusable = false,
                // Outside taps are handled by the scrim above (which also blocks
                // click-through); disabling the menu's own outside-dismiss keeps
                // a single tap from being processed twice.
                dismissOnClickOutside = false,
            ),
        content = content,
    )
}

// BasicTextField (not Material3 TextField) so the pill height isn't pinned
// to the 56dp filled-textfield minimum.
@Composable
private fun ComposerPill(
    textFieldValue: TextFieldValue,
    composerFocus: FocusRequester,
    onValueChange: (TextFieldValue) -> Unit,
    onAttachMenuToggle: () -> Unit,
    attachMenuOpen: Boolean,
    onAttachMenuDismiss: () -> Unit,
    onCaptureFromCamera: (() -> Unit)?,
    onPickFromGallery: (() -> Unit)?,
    onPickDocument: (() -> Unit)?,
    modifier: Modifier = Modifier,
    highlightMentionChips: Boolean = false,
    mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
    enterKeyBehavior: EnterKeyBehavior = EnterKeyBehavior.SendMessage,
    onImeSend: () -> Unit = {},
) {
    // #414/#442: paint stored `@npub1…` chip runs as friendly visible labels
    // (`@alice` when the profile is resolved, short `@npub1…` otherwise)
    // while keeping the backing TextFieldValue canonical for send/markdown.
    val chipColor = MaterialTheme.colorScheme.primary
    val mentionCandidateLookup =
        remember(highlightMentionChips, mentionCandidates) {
            if (highlightMentionChips) MentionComposer.candidatesByNpub(mentionCandidates) else emptyMap()
        }
    val mentionVisualTransformation =
        remember(highlightMentionChips, chipColor, mentionCandidateLookup) {
            if (!highlightMentionChips) {
                VisualTransformation.None
            } else {
                VisualTransformation { text ->
                    val visual = MentionComposer.visualText(text.text, mentionCandidateLookup)
                    val styled =
                        buildAnnotatedString {
                            append(visual.text)
                            visual.ranges.forEach { range ->
                                addStyle(
                                    SpanStyle(
                                        color = chipColor,
                                        fontWeight = FontWeight.Medium,
                                        background = chipColor.copy(alpha = 0.12f),
                                    ),
                                    range.transformed.first,
                                    range.transformed.last + 1,
                                )
                            }
                        }
                    val offsetMapping =
                        object : OffsetMapping {
                            override fun originalToTransformed(offset: Int): Int = visual.originalToTransformed(offset)

                            override fun transformedToOriginal(offset: Int): Int = visual.transformedToOriginal(offset)
                        }
                    TransformedText(styled, offsetMapping)
                }
            }
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .padding(start = 16.dp, end = 4.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
            ) {
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(composerFocus)
                            // #404: honor the Enter-key toggle for hardware
                            // keyboards (Bluetooth/foldable/ChromeOS). Shift+Enter
                            // always inserts a line break as an escape hatch; in
                            // NewLine mode a bare Enter falls through to the normal
                            // newline insertion.
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                ) {
                                    when {
                                        event.isShiftPressed -> false
                                        enterKeyBehavior == EnterKeyBehavior.SendMessage -> {
                                            onImeSend()
                                            true
                                        }
                                        else -> false
                                    }
                                } else {
                                    false
                                }
                            },
                    textStyle =
                        LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                        ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = mentionVisualTransformation,
                    // #404: in SendMessage mode the soft-keyboard action sends;
                    // in NewLine mode the IME shows an Enter/newline key that
                    // inserts `\n`.
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences,
                            keyboardType = KeyboardType.Text,
                            imeAction =
                                if (enterKeyBehavior == EnterKeyBehavior.SendMessage) {
                                    ImeAction.Send
                                } else {
                                    ImeAction.Default
                                },
                        ),
                    keyboardActions = KeyboardActions(onSend = { onImeSend() }),
                    maxLines = 5,
                )
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        stringResource(R.string.message),
                        style = LocalTextStyle.current.copy(fontSize = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            if (onPickFromGallery != null || onPickDocument != null) {
                Box {
                    IconButton(
                        onClick = onAttachMenuToggle,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.AttachFile,
                            contentDescription = stringResource(R.string.attach_image),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    KeyboardPreservingDropdownMenu(
                        expanded = attachMenuOpen,
                        onDismissRequest = onAttachMenuDismiss,
                    ) {
                        if (onPickFromGallery != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_photo_library)) },
                                leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                                onClick = {
                                    onAttachMenuDismiss()
                                    onPickFromGallery()
                                },
                            )
                        }
                        if (onPickDocument != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.attach_document)) },
                                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                onClick = {
                                    onAttachMenuDismiss()
                                    onPickDocument()
                                },
                            )
                        }
                    }
                }
            }
            if (onCaptureFromCamera != null) {
                IconButton(
                    onClick = onCaptureFromCamera,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.PhotoCamera,
                        contentDescription = stringResource(R.string.attach_take_photo),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberInfiniteRecordingPulse(): State<Float> {
    val transition = rememberInfiniteTransition(label = "rec-pulse")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.6f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "rec-pulse-scale",
    )
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    appState: DarkMatterAppState,
    onBackToChats: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    detail: SettingsDetail?,
    onDetailChange: (SettingsDetail?) -> Unit,
) {
    // Issue #121: the prior shape only handled back from a detail
    // subscreen; when on the Settings home (detail == null) the system
    // back fell through to the Activity and exited the app. Always
    // claim back here — pop the detail when on a subscreen, otherwise
    // hand control to the chats list (mirroring the top-bar back arrow).
    BackHandler {
        if (detail != null) {
            onDetailChange(null)
        } else {
            onBackToChats()
        }
    }

    when (detail) {
        SettingsDetail.Appearance -> AppearanceScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Data -> AutoDownloadDataScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Profile -> ProfileEditScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Identity -> IdentityScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Relays -> RelaysScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.KeyPackages -> KeyPackagesScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.Notifications -> NotificationsScreen(appState, onBack = { onDetailChange(null) })
        SettingsDetail.SecurityPrivacy ->
            SecurityPrivacyScreen(
                appState = appState,
                onBack = { onDetailChange(null) },
                onOpenDiagnostics = onOpenDiagnostics,
            )
        null ->
            SettingsHomeScreen(
                appState = appState,
                onBackToChats = onBackToChats,
                onOpenDetail = { onDetailChange(it) },
            )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTopBar(onBackToChats: () -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.settings)) },
        navigationIcon = {
            IconButton(onClick = onBackToChats) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back_to_chats))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    appState: DarkMatterAppState,
    onBackToChats: () -> Unit,
    onOpenDetail: (SettingsDetail) -> Unit,
) {
    var qrAccountId by remember { mutableStateOf<String?>(null) }
    var showAccountSelector by remember { mutableStateOf(false) }
    var showAddIdentity by remember { mutableStateOf(false) }

    LaunchedEffect(appState.accounts.size) {
        if (showAddIdentity) showAddIdentity = false
    }

    Scaffold(
        topBar = {
            SettingsTopBar(onBackToChats = onBackToChats)
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg)) {
            item {
                SectionCard(title = stringResource(R.string.account)) {
                    appState.activeAccount?.let { account ->
                        SettingsAccountHeader(
                            title = appState.displayName(account.accountIdHex),
                            subtitle = appState.shortNpub(account.accountIdHex),
                            seed = account.accountIdHex,
                            pictureUrl = appState.avatarUrl(account.accountIdHex),
                            onOpenAccountSelector = { showAccountSelector = true },
                            onOpenQr = { qrAccountId = account.accountIdHex },
                        )
                    }
                    SettingsRow(stringResource(R.string.profile), stringResource(R.string.profile_settings_subtitle)) { onOpenDetail(SettingsDetail.Profile) }
                    SettingsRow(
                        stringResource(R.string.identity_and_keys),
                        stringResource(R.string.identity_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Identity) }
                    SettingsRow(stringResource(R.string.relays), stringResource(R.string.relays_settings_subtitle)) { onOpenDetail(SettingsDetail.Relays) }
                    SettingsRow(
                        stringResource(R.string.key_packages),
                        stringResource(R.string.key_packages_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.KeyPackages) }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.app_preferences)) {
                    SettingsRow(
                        stringResource(R.string.appearance),
                        stringResource(R.string.appearance_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Appearance) }
                    SettingsRow(
                        stringResource(R.string.data_and_storage),
                        stringResource(R.string.data_and_storage_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Data) }
                    SettingsRow(
                        stringResource(R.string.notifications),
                        stringResource(R.string.notifications_settings_subtitle),
                    ) { onOpenDetail(SettingsDetail.Notifications) }
                    SettingsRow(stringResource(R.string.security_and_privacy), stringResource(R.string.security_privacy_settings_subtitle)) {
                        onOpenDetail(SettingsDetail.SecurityPrivacy)
                    }
                }
            }
            item {
                // Version footer. Marketing string only — the integer
                // `VERSION_CODE` is intentionally hidden from this
                // surface to keep the line uncluttered; triage that
                // needs the code can read it via `dumpsys package`,
                // logcat, or the Diagnostics screen.
                Text(
                    text =
                        stringResource(
                            R.string.settings_version_label,
                            BuildConfig.VERSION_NAME,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 24.dp),
                )
            }
        }
    }

    qrAccountId?.let { accountId ->
        ProfileQrSheet(
            appState = appState,
            accountIdHex = accountId,
            onDismiss = { qrAccountId = null },
        )
    }
    if (showAccountSelector) {
        AccountSelectorSheet(
            appState = appState,
            onDismiss = { showAccountSelector = false },
            onAddAccount = {
                showAccountSelector = false
                showAddIdentity = true
            },
            onAccountSwitched = onBackToChats,
        )
    }
    if (showAddIdentity) {
        AddIdentitySheet(appState = appState, onDismiss = { showAddIdentity = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.appearance)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.theme)) {
                    AppThemeMode.entries.forEach { mode ->
                        SelectableSettingsRow(
                            title = stringResource(mode.labelRes),
                            selected = appState.themeMode == mode,
                            onClick = { appState.updateThemeMode(mode) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.language)) {
                    languageOptions.forEach { option ->
                        SelectableSettingsRow(
                            title = stringResource(option.labelRes),
                            selected = appState.languageTag == option.tag,
                            onClick = { appState.updateLanguageTag(option.tag) },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.enter_key_behavior_title)) {
                    EnterKeyBehavior.entries.forEach { behavior ->
                        SelectableSettingsRow(
                            title = stringResource(behavior.labelRes),
                            selected = appState.enterKeyBehavior == behavior,
                            onClick = { appState.updateEnterKeyBehavior(behavior) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Settings -> Data and storage -> the per-type, per-network media
 * auto-download matrix (issue #407). Renders four grouped lists (one per
 * network type), each with four [SettingsSwitchRow] toggles. Toggles persist
 * immediately via [DarkMatterAppState.setMediaAutoDownload]; there is no Save
 * button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoDownloadDataScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.data_and_storage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(
                    text = stringResource(R.string.media_auto_download_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                MediaQualitySettingsCard(appState)
            }
            MediaAutoDownloadNetwork.entries.forEach { network ->
                item {
                    SectionCard(title = stringResource(network.labelRes)) {
                        MediaAutoDownloadType.entries.forEach { type ->
                            SettingsSwitchRow(
                                title = stringResource(type.labelRes),
                                subtitle = null,
                                checked = appState.mediaAutoDownloadMatrix.isEnabled(type, network),
                                onCheckedChange = { appState.setMediaAutoDownload(type, network, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private val MediaAutoDownloadNetwork.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadNetwork.WiFi -> R.string.network_wifi
            MediaAutoDownloadNetwork.Mobile -> R.string.network_mobile
            MediaAutoDownloadNetwork.Roaming -> R.string.network_roaming
            MediaAutoDownloadNetwork.Metered -> R.string.network_metered
        }

private val MediaAutoDownloadType.labelRes: Int
    @StringRes
    get() =
        when (this) {
            MediaAutoDownloadType.Image -> R.string.media_type_images
            MediaAutoDownloadType.Audio -> R.string.media_type_audio
            MediaAutoDownloadType.Video -> R.string.media_type_video
            MediaAutoDownloadType.Document -> R.string.media_type_documents
        }

@Composable
private fun SelectableSettingsRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

// A selectable row that also shows a supporting line (e.g. the approximate
// per-photo size delta) under the title. Mirrors [SelectableSettingsRow] but
// with a subtitle slot.
@Composable
private fun SelectableSettingsRowWithSubtitle(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = {
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun MediaQualitySettingsCard(appState: DarkMatterAppState) {
    SectionCard(title = stringResource(R.string.media_quality_title)) {
        Column(Modifier.selectableGroup()) {
            MediaQuality.entries.forEach { quality ->
                SelectableSettingsRowWithSubtitle(
                    title = stringResource(quality.labelRes),
                    subtitle = stringResource(quality.subtitleRes),
                    selected = appState.mediaQuality == quality,
                    onClick = { appState.updateMediaQuality(quality) },
                )
            }
        }
        // Privacy floor + video/audio carve-out. The size knob and metadata
        // strip are orthogonal: photos never send location/device metadata,
        // including Original's source-byte path. Video and picked audio
        // currently send as-is.
        Text(
            text = stringResource(R.string.media_quality_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    var pendingNotificationEnable by remember { mutableStateOf(false) }
    var pendingBackgroundConnectionEnable by remember { mutableStateOf(false) }
    var pendingNativePushEnable by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appState.refreshLocalNotificationPermission()
            if (granted && pendingNotificationEnable) {
                scope.launch { appState.setLocalNotificationsEnabled(true) }
            }
            if (granted && pendingBackgroundConnectionEnable) {
                scope.launch { appState.setBackgroundConnectionEnabled(true) }
            }
            if (granted && pendingNativePushEnable) {
                scope.launch { appState.setNativePushEnabled(true) }
            }
            if (!granted) {
                appState.present(R.string.toast_notification_permission_denied)
            }
            pendingNotificationEnable = false
            pendingBackgroundConnectionEnable = false
            pendingNativePushEnable = false
        }

    LaunchedEffect(appState.activeAccountRef) {
        appState.refreshLocalNotificationPermission()
        appState.refreshLocalNotificationSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.notifications)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.local_notifications), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.local_notifications_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.localNotificationSettings?.localNotificationsEnabled == true,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingNotificationEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch { appState.setLocalNotificationsEnabled(enabled) }
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.keep_connected), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.keep_connected_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = appState.backgroundConnectionEnabled,
                            enabled = appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingBackgroundConnectionEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch { appState.setBackgroundConnectionEnabled(enabled) }
                                }
                            },
                        )
                    }
                    val nativePushAvailable = appState.isNativePushAvailable()
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.native_push), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    if (nativePushAvailable) {
                                        R.string.native_push_subtitle
                                    } else {
                                        R.string.native_push_unavailable_subtitle
                                    },
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = nativePushAvailable && appState.localNotificationSettings?.nativePushEnabled == true,
                            enabled = nativePushAvailable && appState.activeAccountRef != null,
                            onCheckedChange = { enabled ->
                                if (enabled && !appState.localNotificationPermissionGranted) {
                                    pendingNativePushEnable = true
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scope.launch { appState.setNativePushEnabled(enabled) }
                                }
                            },
                        )
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    // Per-type controls (sound, vibration, importance, lockscreen,
                    // DND bypass) live in the OS notification details — Android's
                    // native per-channel UI. We deep-link there instead of
                    // duplicating those toggles in-app. See #288.
                    //
                    // Use the same plain Row layout as the toggle rows above (not a
                    // ListItem-based SettingsRow) so the leading edge lines up with
                    // them; ListItem injects its own ~16.dp leading inset that left
                    // the categories row indented further right than the settings
                    // above it. See #499.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { openAppNotificationSettings(context) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.notification_categories), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.notification_categories_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.Forward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// Open the OS app-notification details so the user gets native per-channel
// controls (sound, vibration, importance, lockscreen, DND bypass). Falls back
// to the generic app-details screen on the rare device that rejects the
// channel-settings action, and finally toasts if even that fails. See #288.
private fun openAppNotificationSettings(context: Context) {
    val appNotificationIntent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.tryStartActivity(appNotificationIntent)) return

    val appDetailsIntent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (context.tryStartActivity(appDetailsIntent)) return

    Toast.makeText(context, R.string.toast_notification_settings_unavailable, Toast.LENGTH_SHORT).show()
}

private fun Context.tryStartActivity(intent: Intent): Boolean = runCatching { startActivity(intent) }.isSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecurityPrivacyScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var telemetryBusy by remember { mutableStateOf(false) }
    var auditLogsBusy by remember { mutableStateOf(false) }

    LaunchedEffect(appState.runtimeGeneration) {
        appState.refreshSecurityPrivacySettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.security_and_privacy)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.security_and_privacy)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.force_incognito_keyboard),
                        subtitle = stringResource(R.string.force_incognito_keyboard_subtitle),
                        checked = appState.forceIncognitoKeyboard,
                        onCheckedChange = { appState.updateForceIncognitoKeyboard(it) },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.telemetry),
                        subtitle = stringResource(R.string.telemetry_settings_subtitle),
                        checked = appState.relayTelemetrySettings?.exportEnabled == true,
                        enabled = !telemetryBusy,
                        busy = telemetryBusy,
                        onCheckedChange = { enabled ->
                            telemetryBusy = true
                            scope.launch {
                                try {
                                    appState.setTelemetryEnabled(enabled)
                                } finally {
                                    telemetryBusy = false
                                }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    SettingsSwitchRow(
                        title = stringResource(R.string.audit_logs),
                        subtitle = stringResource(R.string.audit_logs_settings_subtitle),
                        checked = appState.auditLogSettings?.enabled == true,
                        enabled = !auditLogsBusy,
                        busy = auditLogsBusy,
                        onCheckedChange = { enabled ->
                            auditLogsBusy = true
                            appState.launchMutation {
                                try {
                                    appState.setAuditLogsEnabled(enabled)
                                } finally {
                                    auditLogsBusy = false
                                }
                            }
                        },
                    )
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    var deleteAuditLogsConfirmOpen by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !auditLogsBusy) { deleteAuditLogsConfirmOpen = true }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.delete_audit_logs),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                stringResource(R.string.delete_audit_logs_subtitle),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (deleteAuditLogsConfirmOpen) {
                        AlertDialog(
                            onDismissRequest = { deleteAuditLogsConfirmOpen = false },
                            title = { Text(stringResource(R.string.delete_audit_logs)) },
                            text = { Text(stringResource(R.string.delete_audit_logs_subtitle)) },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        deleteAuditLogsConfirmOpen = false
                                        auditLogsBusy = true
                                        appState.launchMutation {
                                            try {
                                                appState.deleteAuditLogs()
                                            } finally {
                                                auditLogsBusy = false
                                            }
                                        }
                                    },
                                ) {
                                    Text(
                                        stringResource(R.string.delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { deleteAuditLogsConfirmOpen = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.developer)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.developer_mode),
                        subtitle = stringResource(R.string.developer_mode_subtitle),
                        checked = appState.developerMode,
                        onCheckedChange = { appState.updateDeveloperMode(it) },
                    )
                    if (appState.developerMode) {
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        SettingsRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_settings_subtitle)) { onOpenDiagnostics() }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    busy: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
fun SettingsAccountHeader(
    title: String,
    subtitle: String,
    seed: String,
    pictureUrl: String?,
    onOpenAccountSelector: () -> Unit,
    onOpenQr: () -> Unit,
) {
    val switchAccountDescription = stringResource(R.string.switch_account)
    ListItem(
        modifier =
            Modifier
                .clickable(onClick = onOpenAccountSelector)
                .semantics { contentDescription = switchAccountDescription },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Avatar(
                title = title,
                seed = seed,
                size = 52.dp,
                pictureUrl = pictureUrl,
            )
        },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, fontFamily = FontFamily.Monospace) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ExpandMore, contentDescription = null)
                IconButton(onClick = onOpenQr) {
                    Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.my_qr_code))
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSelectorSheet(
    appState: DarkMatterAppState,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountSwitched: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        appState.refreshAccounts()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.switch_account), style = MaterialTheme.typography.titleLarge)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(appState.accounts, key = { it.label }) { account ->
                    val unreadCount = appState.unreadCountForAccount(account.label)
                    ListItem(
                        modifier =
                            Modifier.clickable {
                                scope.launch {
                                    appState.setActiveAccount(account.label)
                                    onDismiss()
                                    // Land on the newly-active account's chat list
                                    // instead of leaving the user on Settings (#316).
                                    onAccountSwitched()
                                }
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Avatar(
                                title = appState.displayName(account.accountIdHex),
                                seed = account.accountIdHex,
                                size = 44.dp,
                                pictureUrl = appState.avatarUrl(account.accountIdHex),
                            )
                        },
                        headlineContent = { Text(appState.displayName(account.accountIdHex)) },
                        supportingContent = {
                            Text(
                                appState.shortNpub(account.accountIdHex),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unreadCount > 0uL) {
                                    UnreadCountBadge(unreadCount)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (!account.localSigning) {
                                    Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.signedOut) {
                                    Text(stringResource(R.string.signed_out), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.label == appState.activeAccountRef) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.active),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
            OutlinedButton(
                onClick = onAddAccount,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_account))
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileQrSheet(
    appState: DarkMatterAppState,
    accountIdHex: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val npub = appState.npub(accountIdHex)
    val link = remember(npub) { ProfileLink(npub) }
    var copied by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shareProfileTitle = stringResource(R.string.share_profile)
    val notDarkMatterProfileQrError = stringResource(R.string.error_not_dark_matter_profile_qr)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                title = appState.displayName(accountIdHex),
                seed = accountIdHex,
                size = 120.dp,
                pictureUrl = appState.avatarUrl(accountIdHex),
            )
            Text(appState.displayName(accountIdHex), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(npub))
                    copied = true
                    appState.present(R.string.toast_copied_npub)
                },
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (copied) stringResource(R.string.copied) else IdentityFormatter.short(npub, prefix = 16, suffix = 14))
            }
            QrCodeImage(content = link.uri)
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        val sendIntent =
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, link.uri)
                        context.startActivity(Intent.createChooser(sendIntent, shareProfileTitle))
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCode, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.share))
                }
                Button(
                    onClick = {
                        scanError = null
                        showScanner = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scan))
                }
            }
        }
    }

    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = ProfileLink.parse(raw)
                if (scanned == null) {
                    scanError = notDarkMatterProfileQrError
                } else {
                    onDismiss()
                    appState.presentProfile(scanned.npub)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSheet(
    appState: DarkMatterAppState,
    npub: String,
    // (chat, justCreated): justCreated is true only on the path that just
    // created a brand-new DM with this person (issue #321), so the conversation
    // opens with the composer focused + keyboard up. Opening an existing DM or
    // a shared group passes false.
    onOpenGroup: (ChatListItem, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var hex by remember(npub) { mutableStateOf<String?>(null) }
    var fullPictureOpen by remember(npub) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val groupTitleCopy = rememberGroupTitleCopy()

    LaunchedEffect(npub) {
        val resolved = appState.accountIdHex(npub)
        hex = resolved
        if (resolved != null) appState.refreshProfile(resolved)
    }

    val profile = hex?.let { appState.userProfile(it) }
    val title = hex?.let { appState.displayName(it) } ?: IdentityFormatter.short(npub)
    val pictureUrl = hex?.let { appState.avatarUrl(it) } ?: ProfileSanitizer.imageUrl(profile?.picture)
    val about = ProfileSanitizer.about(profile?.about)
    val nip05 =
        profile
            ?.nip05
            ?.trim()
            ?.takeIf { ProfileFieldValidation.isAcceptableNip05(it) }
    val sharedGroups =
        hex
            ?.let { appState.sharedGroupsWith(it) }
            .orEmpty()
            // Only named, multi-member groups belong in this list: the 1:1 DM is
            // reached via the Message button, and an unnamed group would just
            // read as "Group of N people".
            .filter { it.memberCount > 2 && it.group.name.isNotBlank() }
    // The existing 1:1 DM with this person, if any — the confirmed two-member
    // group with them. Drives the Message button: open it when present,
    // otherwise start a new DM.
    val directMessageGroup = appState.existingDirectChat(npub)
    // True while a brand-new DM is being created+published, so the Message
    // button shows progress and we don't dismiss into a blank gap before the
    // conversation opens.
    var creatingChat by remember(npub) { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            enabled = pictureUrl != null,
                            onClickLabel = stringResource(R.string.profile_view_picture),
                            role = Role.Button,
                        ) { fullPictureOpen = true },
            ) {
                Avatar(
                    title = title,
                    seed = hex ?: npub,
                    size = 112.dp,
                    pictureUrl = pictureUrl,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (nip05 != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(nip05, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            CopyableValueRow(
                label = "npub",
                display = IdentityFormatter.short(npub, prefix = 16, suffix = 14),
                value = npub,
                clipboard = clipboard,
                appState = appState,
            )
            SectionCard(title = stringResource(R.string.about)) {
                Text(
                    about ?: stringResource(R.string.profile_no_bio),
                    color = if (about == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SectionCard(title = stringResource(R.string.profile_shared_groups)) {
                if (sharedGroups.isEmpty()) {
                    Text(stringResource(R.string.profile_no_shared_groups), color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    sharedGroups.forEachIndexed { index, group ->
                        ProfileSharedGroupRow(
                            item = group,
                            appState = appState,
                            titleCopy = groupTitleCopy,
                            onOpen = { onOpenGroup(group, false) },
                        )
                        if (index != sharedGroups.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            if (hex == null) {
                Text(stringResource(R.string.couldnt_read_profile_code), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                // Opens the existing 1:1 DM, or starts a new one with this
                // person when none exists yet. The create runs in the
                // process-lifetime mutation scope (Main.immediate) so the MLS
                // commit + Nostr publish finish regardless; we keep the sheet up
                // with a spinner until the conversation is ready, then navigate
                // straight in — no dismiss-into-a-blank-gap.
                onClick = {
                    val existing = directMessageGroup
                    if (existing != null) {
                        onOpenGroup(existing, false)
                    } else {
                        creatingChat = true
                        appState.launchMutation {
                            val groupId = appState.startProfileChat(npub)
                            val item = groupId?.let { appState.awaitChatListItem(it) }
                            if (item != null) onOpenGroup(item, true) else creatingChat = false
                        }
                    }
                },
                enabled = hex != null && !creatingChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (creatingChat) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.message))
                }
            }
        }
    }

    if (fullPictureOpen && pictureUrl != null) {
        ProfilePictureDialog(
            title = title,
            pictureUrl = pictureUrl,
            onDismiss = { fullPictureOpen = false },
        )
    }
}

@Composable
private fun ProfileSharedGroupRow(
    item: ChatListItem,
    appState: DarkMatterAppState,
    titleCopy: GroupTitleCopy,
    onOpen: () -> Unit,
) {
    val title = chatListItemDisplayTitle(item, appState, titleCopy)
    val subtitle =
        when {
            item.group.archived -> stringResource(R.string.archived)
            item.memberCount == 1 -> stringResource(R.string.one_member)
            item.memberCount > 1 -> stringResource(R.string.members_count, item.memberCount)
            else -> stringResource(R.string.members)
        }
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onOpen),
        leadingContent = {
            Avatar(
                title = title,
                seed = item.group.groupIdHex,
                size = 40.dp,
                pictureUrl = item.group.avatarUrl,
            )
        },
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun ProfilePictureDialog(
    title: String,
    pictureUrl: String,
    onDismiss: () -> Unit,
) {
    val imageState by produceState<ProfilePictureImageState>(
        initialValue =
            AvatarImageLoader.peek(pictureUrl)?.let(ProfilePictureImageState::Ready)
                ?: ProfilePictureImageState.Loading,
        key1 = pictureUrl,
    ) {
        if (value is ProfilePictureImageState.Ready) return@produceState
        value =
            AvatarImageLoader.load(pictureUrl)?.let(ProfilePictureImageState::Ready)
                ?: ProfilePictureImageState.Failed
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (val state = imageState) {
                ProfilePictureImageState.Loading -> CircularProgressIndicator(color = Color.White)
                ProfilePictureImageState.Failed ->
                    Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
                is ProfilePictureImageState.Ready ->
                    Image(
                        bitmap = state.image,
                        contentDescription = title,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
                        contentScale = ContentScale.Fit,
                    )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding(),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.dismiss), tint = Color.White)
            }
        }
    }
}

private sealed interface ProfilePictureImageState {
    data object Loading : ProfilePictureImageState

    data object Failed : ProfilePictureImageState

    data class Ready(
        val image: ImageBitmap,
    ) : ProfilePictureImageState
}

@Composable
private fun QrCodeImage(content: String) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, content) {
        value = withContext(Dispatchers.Default) { qrBitmap(content, 900).asImageBitmap() }
    }

    Surface(color = Color.White, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp) {
        if (image == null) {
            Box(Modifier.size(257.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Image(
                bitmap = image!!,
                contentDescription = stringResource(R.string.profile_qr_code),
                modifier = Modifier.size(257.dp).padding(16.dp),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private fun qrBitmap(
    content: String,
    size: Int,
): Bitmap {
    val pixels =
        QrCodeEncoder.pixels(
            content = content,
            size = size,
            onColor = android.graphics.Color.BLACK,
            offColor = android.graphics.Color.WHITE,
        )
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QrScannerSheet(
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
) {
    val context = LocalContext.current
    var scannerError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissionGranted = granted
        }

    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scan), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                }
            }
            if (permissionGranted) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(520.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    CameraQrScanner(onScan = onScan, onError = { scannerError = it })
                    Text(
                        scannerError ?: stringResource(R.string.point_camera_at_profile_qr),
                        color = Color.White,
                        modifier =
                            Modifier
                                .padding(
                                    16.dp,
                                ).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(24.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            } else {
                Text(stringResource(R.string.camera_access_required), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.allow_camera))
                }
            }
        }
    }
}

@Composable
private fun CameraQrScanner(
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = context.lifecycleOwner()
    val cameraLifecycleUnavailable = stringResource(R.string.camera_lifecycle_unavailable)
    val cameraUnavailable = stringResource(R.string.camera_unavailable)

    if (lifecycleOwner == null) {
        // Emit the error as a post-composition effect, not a side effect during
        // composition (which violates Compose's rules and can fire on every
        // recomposition). See #23.
        LaunchedEffect(cameraLifecycleUnavailable) {
            onError(cameraLifecycleUnavailable)
        }
        return
    }

    // Track the CameraX provider and ML Kit scanner so we can release them when
    // the QR sheet is dismissed. CameraX binds use cases to the host activity's
    // lifecycle, so without an explicit unbind the camera keeps streaming
    // (and the OS in-use indicator stays lit) until the activity stops. The
    // BarcodeScanner is Closeable and leaks native resources otherwise.
    //
    // `disposedRef` is a separate teardown signal so a late
    // ProcessCameraProvider.getInstance() callback (fired after the sheet
    // dismissed) can bail and clean up instead of binding into refs we just
    // nulled out. Using `null` to mean both "not yet set" and "torn down"
    // would let `compareAndSet(null, …)` succeed after onDispose, leaking the
    // camera again.
    val providerRef = remember { AtomicReference<ProcessCameraProvider?>(null) }
    val scannerRef = remember { AtomicReference<BarcodeScanner?>(null) }
    val disposedRef = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            disposedRef.set(true)
            runCatching { providerRef.getAndSet(null)?.unbindAll() }
            runCatching { scannerRef.getAndSet(null)?.close() }
        }
    }

    AndroidView(
        factory = { viewContext ->
            PreviewView(viewContext).also { previewView ->
                bindQrScannerCamera(
                    context,
                    lifecycleOwner,
                    previewView,
                    cameraUnavailable,
                    providerRef,
                    scannerRef,
                    disposedRef,
                    onScan,
                    onError,
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? =
    when (this) {
        is LifecycleOwner -> this
        is ContextWrapper -> baseContext.lifecycleOwner()
        else -> null
    }

// Compose's `LocalContext.current` is whatever the host wired in — often
// the Activity directly, but themed/wrapped contexts (test surfaces, custom
// theme wrappers) return a `ContextWrapper`. A direct `as? Activity` cast on
// those silently yields null, which for a FLAG_SECURE setter is the worst
// failure mode (looks like it works, doesn't). Walk the wrapper chain.
private tailrec fun Context.activity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activity()
        else -> null
    }

/**
 * Marks the host activity's window as secure for the duration of this
 * composition. `FLAG_SECURE` blocks the OS Recents/overview thumbnail,
 * screenshots, screen recording, and casting from capturing the window's
 * contents — the only practical protection for screens that handle the
 * nsec/private key, since `PasswordVisualTransformation` only masks
 * rendered glyphs. The flag is cleared on dispose so the rest of the
 * (non-sensitive) UI remains screenshottable as users expect.
 */
@Composable
private fun WindowSecureFlag() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = context.activity()?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun bindQrScannerCamera(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraUnavailable: String,
    providerRef: AtomicReference<ProcessCameraProvider?>,
    scannerRef: AtomicReference<BarcodeScanner?>,
    disposedRef: AtomicBoolean,
    onScan: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(context)
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener(
        {
            val provider =
                runCatching { cameraProviderFuture.get() }.getOrElse {
                    onError(cameraUnavailable)
                    return@addListener
                }
            // If the sheet dismissed before this listener fired, the caller's
            // onDispose already ran and nulled the refs. Without disposedRef,
            // compareAndSet(null, provider) would succeed here and bind a
            // camera that nothing will ever unbind. Bail and clean up locally.
            if (disposedRef.get() || !providerRef.compareAndSet(null, provider)) {
                runCatching { provider.unbindAll() }
                return@addListener
            }
            val preview =
                Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            val scanner =
                BarcodeScanning.getClient(
                    BarcodeScannerOptions
                        .Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build(),
                )
            if (disposedRef.get() || !scannerRef.compareAndSet(null, scanner)) {
                runCatching { scanner.close() }
                runCatching { provider.unbindAll() }
                providerRef.set(null)
                return@addListener
            }
            val didScan = AtomicBoolean(false)
            val analyzerBusy = AtomicBoolean(false)
            val analysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

            analysis.setAnalyzer(executor) { imageProxy ->
                if (!analyzerBusy.compareAndSet(false, true)) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val mediaImage = imageProxy.image
                if (mediaImage == null) {
                    analyzerBusy.set(false)
                    imageProxy.close()
                    return@setAnalyzer
                }
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                scanner
                    .process(image)
                    .addOnSuccessListener { codes ->
                        val raw = codes.firstOrNull { it.rawValue != null }?.rawValue
                        if (raw != null && didScan.compareAndSet(false, true)) onScan(raw)
                    }.addOnFailureListener {
                        onError(it.message ?: it.javaClass.simpleName)
                    }.addOnCompleteListener {
                        analyzerBusy.set(false)
                        imageProxy.close()
                    }
            }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                // Failed before lifecycle binding could take over — the
                // composable's onDispose has nothing to unbind, so release
                // provider + scanner here instead of leaking them until the
                // sheet dismisses.
                runCatching { scannerRef.getAndSet(null)?.close() }
                runCatching { providerRef.getAndSet(null)?.unbindAll() }
                onError(it.message ?: it.javaClass.simpleName)
            }
        },
        executor,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val active = appState.activeAccount
    var displayName by remember(active?.accountIdHex) { mutableStateOf("") }
    var about by remember(active?.accountIdHex) { mutableStateOf("") }
    var picture by remember(active?.accountIdHex) { mutableStateOf("") }
    var nip05 by remember(active?.accountIdHex) { mutableStateOf("") }
    var lud16 by remember(active?.accountIdHex) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    // Drives the avatar bottom sheet (pick-from-photos / paste-link / remove).
    // The picture URL no longer lives as a standalone editor row; it's edited
    // exclusively through this control so the editor reads like an app screen,
    // not a developer surface. See #286.
    var showPictureSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(active?.accountIdHex) {
        val profile = active?.accountIdHex?.let { appState.loadUserProfile(it) }
        if (profile != null) {
            displayName = profile.displayName ?: profile.name ?: ""
            about = profile.about ?: ""
            picture = profile.picture ?: ""
            nip05 = profile.nip05 ?: ""
            lud16 = profile.lud16 ?: ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(Dimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl),
        ) {
            item {
                // Live profile header — the avatar, name, and npub update as the
                // fields below are edited, so the user previews their card inline.
                Column(
                    Modifier.fillMaxWidth().padding(top = Dimens.spaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceSm),
                ) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // Tappable avatar control: opens the picture bottom
                        // sheet (paste-link / remove). A small edit badge
                        // signals it's interactive. Replaces the old standalone
                        // "Picture URL" text row. See #286.
                        val editPictureLabel = stringResource(R.string.profile_picture_edit)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .clip(CircleShape)
                                    .clickable(
                                        onClickLabel = editPictureLabel,
                                        role = Role.Button,
                                    ) { showPictureSheet = true },
                        ) {
                            Avatar(
                                title = displayName.ifBlank { appState.shortNpub(active.accountIdHex) },
                                seed = active.accountIdHex,
                                size = 96.dp,
                                pictureUrl = ProfileSanitizer.imageUrl(picture),
                            )
                            // Centered "tap to change" affordance: a dim scrim
                            // over the whole avatar with a camera glyph, so the
                            // image always reads as editable.
                            Box(
                                modifier =
                                    Modifier
                                        .size(96.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.32f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Text(
                            displayName.ifBlank { stringResource(R.string.anonymous) },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // Tap to copy the full npub (#287). Same affordance as
                        // the Identity screen npub row and member rows.
                        val copyNpubLabel = stringResource(R.string.copy)
                        Text(
                            appState.shortNpub(active.accountIdHex),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .minimumInteractiveComponentSize()
                                    .clickable(
                                        onClickLabel = copyNpubLabel,
                                        role = Role.Button,
                                    ) {
                                        clipboard.setText(AnnotatedString(appState.npub(active.accountIdHex)))
                                        appState.present(R.string.toast_copied_npub)
                                    },
                        )
                        // Surface an invalid stored picture URL right on the
                        // avatar control. The inline Picture URL row is gone, so
                        // without this an unsafe/malformed `picture` would
                        // silently disable Publish with no on-screen reason; the
                        // caption tells the user to tap the avatar to fix it.
                        // See #286.
                        if (picture.isNotBlank() && !ProfileFieldValidation.isAcceptablePictureUrl(picture)) {
                            Text(
                                stringResource(R.string.profile_picture_invalid),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            if (active != null) {
                item {
                    // Public-profile notice (#380): kind:0 metadata is broadcast
                    // unencrypted to relays, so warn before the editable fields
                    // that everything here is visible to the whole network. Copy
                    // mirrors Whitenoise Flutter (profileIsPublic /
                    // profilePublicDescription) for cross-client parity.
                    ProfilePublicWarning()
                }
            }
            item {
                SectionCard(title = stringResource(R.string.profile)) {
                    // Borderless fields: drop the filled container so each input
                    // reads as a label + underline row on the white panel, leaving
                    // the indicator line to carry focus/error state.
                    val profileFieldColors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                        )
                    TextField(
                        colors = profileFieldColors,
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        colors = profileFieldColors,
                        value = about,
                        onValueChange = { about = it },
                        label = { Text(stringResource(R.string.about)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Client-side validation: flag a malformed picture URL or
                    // nip-05 and block publish so we don't push junk — or an
                    // SSRF-prone avatar URL — to relays. The picture URL is now
                    // edited via the avatar control above (no inline row), but
                    // the same guard still gates publish in case a bad value was
                    // pasted there. See #69, #286.
                    val pictureValid = ProfileFieldValidation.isAcceptablePictureUrl(picture)
                    val nip05Valid = ProfileFieldValidation.isAcceptableNip05(nip05)
                    val lud16Valid = ProfileFieldValidation.isAcceptableLud16(lud16)
                    TextField(
                        colors = profileFieldColors,
                        value = nip05,
                        onValueChange = { nip05 = it },
                        label = { Text(stringResource(R.string.nip_05)) },
                        singleLine = true,
                        isError = !nip05Valid,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (nip05Valid) R.string.profile_nip05_hint else R.string.profile_nip05_invalid,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    TextField(
                        colors = profileFieldColors,
                        value = lud16,
                        onValueChange = { lud16 = it },
                        label = { Text(stringResource(R.string.lightning)) },
                        singleLine = true,
                        isError = !lud16Valid,
                        supportingText = {
                            Text(
                                stringResource(
                                    if (lud16Valid) R.string.profile_lightning_hint else R.string.profile_lightning_invalid,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    )
                    Button(
                        onClick = {
                            busy = true
                            // Snapshot the field values now: the mutation outlives
                            // this composition, so reading them inside the lambda
                            // would publish whatever is on screen when it runs.
                            val metadata =
                                UserProfileMetadataFfi(
                                    name = displayName.trim().ifBlank { null },
                                    displayName = displayName.trim().ifBlank { null },
                                    about = about.trim().ifBlank { null },
                                    picture = picture.trim().ifBlank { null },
                                    nip05 = nip05.trim().ifBlank { null },
                                    lud16 = lud16.trim().ifBlank { null },
                                )
                            appState.launchMutation {
                                try {
                                    appState.publishProfile(metadata)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = !busy && active != null && pictureValid && nip05Valid && lud16Valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.publish_to_relays))
                    }
                }
            }
        }
    }

    if (showPictureSheet) {
        ImageSearchSheet(
            initialUrl = picture,
            header = stringResource(R.string.profile_picture_sheet_title),
            title = displayName.ifBlank { active?.let { appState.shortNpub(it.accountIdHex) }.orEmpty() },
            seed = active?.accountIdHex.orEmpty(),
            urlLabel = stringResource(R.string.profile_picture_hint),
            // The profile editor stages edits locally and persists on Publish,
            // so there's no in-flight mutation to gate on here.
            applyInFlight = false,
            onApply = { picked ->
                // picked is the sanitized URL (Apply) or null (Remove); either
                // way it's normalized by the sheet before reaching us.
                picture = picked.orEmpty()
                showPictureSheet = false
            },
            onDismiss = { showPictureSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIdentitySheet(
    appState: DarkMatterAppState,
    onDismiss: () -> Unit,
) {
    WindowSecureFlag()
    var identity by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val creatingIdentityDescription = stringResource(R.string.creating_identity)

    // ModalBottomSheet renders in its own window on Android, separate from
    // the host activity window — `WindowSecureFlag()` (which flags the
    // activity window) doesn't reach it. Set the sheet's own securePolicy
    // so the nsec field inside is also protected from Recents/screenshot
    // capture.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.add_account), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            appState.createIdentity()
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = creatingIdentityDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Key, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (busy) R.string.creating_identity_title else R.string.create_new_identity))
            }
            // Mask unless the value is unambiguously a public npub.
            // Treats partial / empty / unprefixed input as potentially secret,
            // so a pasted nsec is never rendered while the field is non-empty.
            // Keep `KeyboardType.Password` even when revealing the npub so the
            // IME stays opted out of suggestions / autofill / history.
            val maskSecret = !identity.trim().startsWith("npub1")
            OutlinedTextField(
                value = identity,
                onValueChange = { identity = it },
                label = { Text(stringResource(R.string.nsec_or_npub)) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (maskSecret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onDone = {
                            busy = true
                            scope.launch {
                                appState.importIdentity(identity)
                                busy = false
                            }
                        },
                    ),
            )
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        appState.importIdentity(identity)
                        busy = false
                    }
                },
                enabled = !busy && identity.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_existing_identity))
            }
        }
    }
}

/**
 * Whether the destructive "Sign Out & Wipe" path is wired to Marmot's
 * [dev.ipf.marmotkit.Marmot.signOutAndWipe] FFI.
 */
private const val WIPE_ENGINE_FFI_AVAILABLE = true

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val active = appState.activeAccount
    var showSignOutSheet by remember { mutableStateOf(false) }
    var showWipeSheet by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }
    var showEncryptedExportDialog by remember { mutableStateOf(false) }
    var encryptedExportPassphrase by remember { mutableStateOf("") }
    // Type-to-confirm input for the destructive wipe (#348). Reset whenever the
    // confirm dialog is dismissed so a previous match can't carry over into a
    // later open.
    var wipeConfirmInput by remember { mutableStateOf("") }
    val shareSecretKeyTitle = stringResource(R.string.share_secret_key)

    fun shareSecretKey(text: String) {
        val sendIntent =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(
            Intent.createChooser(sendIntent, shareSecretKeyTitle),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.identity)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.identity)) {
                    if (active == null) {
                        Text(stringResource(R.string.no_active_account_period), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        DiagnosticRow(stringResource(R.string.display_name), appState.displayName(active.accountIdHex))
                        CopyableValueRow(
                            label = stringResource(R.string.public_key),
                            display = IdentityFormatter.short(active.accountIdHex),
                            value = active.accountIdHex,
                            clipboard = clipboard,
                            appState = appState,
                        )
                        CopyableValueRow(
                            label = "npub",
                            display = appState.shortNpub(active.accountIdHex),
                            value = appState.npub(active.accountIdHex),
                            clipboard = clipboard,
                            appState = appState,
                        )
                        DiagnosticRow(stringResource(R.string.local_signing), stringResource(if (active.localSigning) R.string.yes else R.string.no))
                        DiagnosticRow(stringResource(R.string.status), stringResource(if (active.running) R.string.online else R.string.idle))
                    }
                }
            }
            if (active?.localSigning == true) {
                item {
                    SectionCard(title = stringResource(R.string.secret_key_backup)) {
                        Text(
                            stringResource(R.string.secret_key_backup_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    appState.exportActiveAccountNsec()?.let(::shareSecretKey)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Key, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export_nsec))
                        }
                        OutlinedButton(
                            onClick = {
                                encryptedExportPassphrase = ""
                                showEncryptedExportDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.export_encrypted_secret))
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.account_session)) {
                    Text(
                        stringResource(R.string.sign_out_session_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Primary, non-destructive sign-out. Opens an explanatory
                    // sheet; the sheet's button performs the actual sign-out so
                    // the user reads what stays vs. changes before committing.
                    Button(
                        onClick = { showSignOutSheet = true },
                        enabled = active != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_out))
                    }
                    // Destructive "Sign Out & Wipe". Gated on engine FFI
                    // availability (#348): the relay + MLS teardown is an engine
                    // sub-issue that doesn't exist on the binding surface yet, so
                    // we don't surface an affordance we can't honor.
                    if (WIPE_ENGINE_FFI_AVAILABLE) {
                        OutlinedButton(
                            onClick = { showWipeSheet = true },
                            enabled = active != null,
                            modifier = Modifier.fillMaxWidth(),
                            colors =
                                ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.sign_out_and_wipe))
                        }
                    }
                }
            }
        }
    }

    if (showSignOutSheet) {
        SignOutSheet(
            onConfirm = {
                showSignOutSheet = false
                scope.launch {
                    if (appState.signOutActiveAccount() != null) {
                        appState.present(R.string.toast_signed_out)
                    }
                }
            },
            onDismiss = { showSignOutSheet = false },
        )
    }

    if (showEncryptedExportDialog) {
        AlertDialog(
            onDismissRequest = {
                showEncryptedExportDialog = false
                encryptedExportPassphrase = ""
            },
            title = { Text(stringResource(R.string.export_encrypted_secret_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.export_encrypted_secret_body))
                    OutlinedTextField(
                        value = encryptedExportPassphrase,
                        onValueChange = { encryptedExportPassphrase = it },
                        label = { Text(stringResource(R.string.export_encrypted_secret_field_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Password,
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val passphrase = encryptedExportPassphrase
                        showEncryptedExportDialog = false
                        encryptedExportPassphrase = ""
                        scope.launch {
                            appState.exportActiveAccountEncryptedSecretKey(passphrase)?.let(::shareSecretKey)
                        }
                    },
                    enabled = encryptedExportPassphrase.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.export_encrypted_secret))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showEncryptedExportDialog = false
                        encryptedExportPassphrase = ""
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (WIPE_ENGINE_FFI_AVAILABLE && showWipeSheet) {
        SignOutAndWipeSheet(
            onConfirm = {
                showWipeSheet = false
                wipeConfirmInput = ""
                showWipeConfirm = true
            },
            onDismiss = { showWipeSheet = false },
        )
    }

    if (WIPE_ENGINE_FFI_AVAILABLE && showWipeConfirm) {
        // Type-to-confirm gate (#348): the destructive confirm button stays
        // disabled until the user types the confirmation keyword. The match is
        // case-insensitive and ignores surrounding whitespace. This is the last
        // stop before the engine destroys the local DB, MLS state, keychain
        // entry, and relay key packages, so a single tap must not be enough.
        val confirmKeyword = stringResource(R.string.sign_out_and_wipe_confirm_keyword)
        val wipeConfirmed = wipeConfirmInput.trim().equals(confirmKeyword, ignoreCase = true)
        AlertDialog(
            onDismissRequest = {
                showWipeConfirm = false
                wipeConfirmInput = ""
            },
            title = { Text(stringResource(R.string.sign_out_and_wipe_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.sign_out_and_wipe_confirm_body))
                    Text(
                        stringResource(R.string.sign_out_and_wipe_confirm_instruction, confirmKeyword),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = wipeConfirmInput,
                        onValueChange = { wipeConfirmInput = it },
                        label = { Text(stringResource(R.string.sign_out_and_wipe_confirm_field_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                autoCorrectEnabled = false,
                            ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        wipeConfirmInput = ""
                        scope.launch {
                            val outcome = appState.signOutAndWipeActiveAccount()
                            if (outcome != null) {
                                appState.present(R.string.toast_signed_out_and_wiped)
                            } else {
                                appState.present(R.string.toast_couldnt_wipe_account)
                            }
                        }
                    },
                    enabled = wipeConfirmed,
                ) {
                    Text(
                        stringResource(R.string.wipe),
                        color =
                            if (wipeConfirmed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showWipeConfirm = false
                        wipeConfirmInput = ""
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Non-destructive sign-out sheet (#348). Explains what stays on device and what
 * changes, then performs the actual sign-out via [onConfirm]. Copy is
 * intentionally limited to what the current sign-out path actually does (push
 * off, relay KeyPackage deletion, in-memory caches cleared, identity retained).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOutSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sign_out_sheet_title), style = MaterialTheme.typography.titleLarge)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_sheet_stays_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.sign_out_sheet_stays_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_sheet_changes_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.sign_out_sheet_changes_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out))
            }
        }
    }
}

/**
 * Destructive "Sign Out & Wipe" sheet (#348). Lists exactly what gets destroyed
 * and that signing back in starts fresh, then hands off to a type-to-confirm
 * dialog via [onConfirm]. Only shown when the engine FFI exists
 * ([WIPE_ENGINE_FFI_AVAILABLE]); the copy describes the full teardown the engine
 * sub-issue will perform.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOutAndWipeSheet(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.sign_out_and_wipe_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.sign_out_and_wipe_sheet_intro))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.sign_out_and_wipe_sheet_destroyed_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_db))
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_keychain))
                WipeBullet(stringResource(R.string.sign_out_and_wipe_sheet_destroyed_relays))
            }
            Text(
                stringResource(R.string.sign_out_and_wipe_sheet_fresh_start),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sign_out_and_wipe))
            }
        }
    }
}

@Composable
private fun WipeBullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("\u2022", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CopyableValueRow(
    label: String,
    display: String,
    value: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    appState: DarkMatterAppState,
) {
    Row(
        Modifier.fillMaxWidth().clickable {
            clipboard.setText(AnnotatedString(value))
            appState.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
        },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(display, fontFamily = FontFamily.Monospace)
            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RelaysScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    var pendingUrl by remember { mutableStateOf("") }
    var lists by remember(appState.activeAccountRef) { mutableStateOf<AccountRelayListsFfi?>(null) }
    var selectedKind by remember { mutableStateOf(RelayListKind.Nip65) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun reloadLists() {
        lists = appState.accountRelayLists()
    }

    LaunchedEffect(appState.activeAccountRef) {
        reloadLists()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { reloadLists() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                SectionCard(title = stringResource(R.string.account_relay_lists)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        relayListKinds.forEach { option ->
                            FilterChip(
                                selected = selectedKind == option,
                                onClick = { selectedKind = option },
                                label = { Text(stringResource(option.labelRes)) },
                            )
                        }
                    }

                    val currentRelays = lists?.relaysFor(selectedKind).orEmpty()
                    if (currentRelays.isEmpty()) {
                        Text(stringResource(R.string.no_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    currentRelays.forEach { relay ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(relay, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
                            IconButton(
                                onClick = {
                                    saving = true
                                    appState.launchMutation {
                                        try {
                                            lists = appState.setAccountRelays(selectedKind, currentRelays - relay) ?: appState.accountRelayLists()
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                                enabled = !saving && currentRelays.size > 1,
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_relay))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = pendingUrl,
                            onValueChange = { pendingUrl = it },
                            label = { Text("wss://relay.example.com") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions =
                                KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Uri,
                                ),
                        )
                        IconButton(
                            onClick = {
                                val trimmed = pendingUrl.trim()
                                saving = true
                                appState.launchMutation {
                                    try {
                                        lists = appState.setAccountRelays(selectedKind, currentRelays + trimmed) ?: appState.accountRelayLists()
                                        pendingUrl = ""
                                    } finally {
                                        saving = false
                                    }
                                }
                            },
                            modifier = Modifier.size(48.dp),
                            enabled =
                                pendingUrl.trim().let {
                                    !saving &&
                                        appState.activeAccountRef != null &&
                                        isAcceptableRelayUrl(it) &&
                                        !currentRelays.contains(it)
                                },
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_relay))
                        }
                    }
                }
            }
            item {
                PublishedRelayLists(lists)
            }
        }
    }
}

private val relayListKinds =
    listOf(
        RelayListKind.Nip65,
        RelayListKind.Inbox,
    )

private val RelayListKind.labelRes: Int
    get() =
        when (this) {
            RelayListKind.Nip65 -> R.string.nip_65
            RelayListKind.Inbox -> R.string.inbox
        }

private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
    when (kind) {
        RelayListKind.Nip65 -> nip65.relays
        RelayListKind.Inbox -> inbox.relays
    }

@Composable
private fun PublishedRelayLists(lists: AccountRelayListsFfi?) {
    SectionCard(title = stringResource(R.string.published_relay_lists)) {
        if (lists == null) {
            Text(stringResource(R.string.no_relay_projection), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        RelayListRow(stringResource(R.string.nip_65), lists.nip65)
        RelayListRow(stringResource(R.string.inbox), lists.inbox)
        if (lists.complete) {
            Text(stringResource(R.string.all_relay_lists_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.missing_relay_lists, lists.missing.joinToString(", ")), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun RelayListRow(
    title: String,
    list: RelayListFfi,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text("${list.relays.size}", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.relays.isEmpty()) {
            Text(stringResource(R.string.not_published), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            list.relays.forEach { relay ->
                Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyPackagesScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var packages by remember(appState.activeAccountRef) { mutableStateOf<List<AccountKeyPackageFfi>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var loaded by remember(appState.activeAccountRef) { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AccountKeyPackageFfi?>(null) }

    suspend fun reload(refreshFromNetwork: Boolean = false) {
        loading = true
        try {
            packages = appState.fetchKeyPackages(refreshFromNetwork = refreshFromNetwork)
            loaded = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(appState.activeAccountRef) {
        if (appState.activeAccountRef != null) reload()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.key_packages)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { scope.launch { reload(refreshFromNetwork = true) } },
                        enabled = !loading && !working && appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = stringResource(R.string.publishing)) {
                    Text(
                        stringResource(R.string.key_package_publishing_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                working = true
                                appState.launchMutation {
                                    try {
                                        appState.republishKeyPackage()
                                        reload(refreshFromNetwork = true)
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = !working && !loading && appState.activeAccountRef != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.republish))
                        }
                        Button(
                            onClick = {
                                working = true
                                appState.launchMutation {
                                    try {
                                        appState.publishNewKeyPackage()
                                        reload(refreshFromNetwork = true)
                                    } finally {
                                        working = false
                                    }
                                }
                            },
                            enabled = !working && !loading && appState.activeAccountRef != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.publish_new))
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.published), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (loading) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (loaded && packages.isEmpty() && !loading) {
                item {
                    SectionCard(title = stringResource(R.string.no_key_packages_found)) {
                        Text(
                            stringResource(R.string.no_key_packages_found_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(packages, key = { index, kp -> "${kp.eventIdHex}:$index" }) { _, kp ->
                KeyPackageCard(
                    kp = kp,
                    busy = working,
                    appState = appState,
                    onDelete = { pendingDelete = kp },
                )
            }
        }
    }

    pendingDelete?.let { kp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_key_package_question)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.delete_key_package_help),
                    )
                    Text(
                        stringResource(R.string.event_value, IdentityFormatter.short(kp.eventIdHex)),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val target = pendingDelete ?: return@Button
                    pendingDelete = null
                    working = true
                    scope.launch {
                        try {
                            appState.deleteKeyPackage(target.eventIdHex, target.sourceRelays)
                            reload(refreshFromNetwork = true)
                        } finally {
                            working = false
                        }
                    }
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun KeyPackageCard(
    kp: AccountKeyPackageFfi,
    busy: Boolean,
    appState: DarkMatterAppState,
    onDelete: () -> Unit,
) {
    val localLabel = stringResource(R.string.local)
    val relayLabel = stringResource(R.string.relay)
    val unknownLabel = stringResource(R.string.unknown)
    val clipboard = LocalClipboardManager.current
    val copyKeyPackageLabel = stringResource(R.string.copy)
    val keyPackageLabelText = stringResource(R.string.key_package)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Tap the shortened key-package id header to copy the full value.
                    Text(
                        IdentityFormatter.short(kp.keyPackageId),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier.clickable(
                                onClickLabel = copyKeyPackageLabel,
                                role = Role.Button,
                            ) {
                                clipboard.setText(AnnotatedString(kp.keyPackageId))
                                appState.presentText(
                                    AppText.Resource(R.string.toast_copied_value, listOf(keyPackageLabelText)),
                                )
                            },
                    )
                    Text(
                        formatPublishedAt(kp.publishedAt, stringResource(R.string.unknown_publish_time), stringResource(R.string.published_at)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete, enabled = !busy) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_key_package))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                keyPackageSourceLabels(kp, localLabel, relayLabel, unknownLabel).forEach { label ->
                    AssistChip(onClick = {}, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
                }
            }
            DiagnosticRow(
                stringResource(R.string.event),
                IdentityFormatter.short(kp.eventIdHex),
                copyValue = kp.eventIdHex,
                appState = appState,
            )
            DiagnosticRow(
                stringResource(R.string.ref),
                IdentityFormatter.short(kp.keyPackageRefHex),
                copyValue = kp.keyPackageRefHex,
                appState = appState,
            )
            DiagnosticRow(stringResource(R.string.size), stringResource(R.string.bytes_count, kp.keyPackageBytes.toLong()))
            if (kp.sourceRelays.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.source_relays), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    kp.sourceRelays.forEach { relay ->
                        Text(relay, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun keyPackageSourceLabels(
    kp: AccountKeyPackageFfi,
    localLabel: String,
    relayLabel: String,
    unknownLabel: String,
): List<String> {
    val out = mutableListOf<String>()
    if (kp.local) out += localLabel
    if (kp.relay) out += relayLabel
    if (out.isEmpty()) out += unknownLabel
    return out
}

private val publishedAtFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault())

private fun formatPublishedAt(
    unixSeconds: ULong,
    unknown: String,
    format: String,
): String {
    if (unixSeconds == 0uL) return unknown
    // ULong > Long.MAX_VALUE wraps to a negative epoch; Instant then rejects
    // anything below Instant.MIN. Garbage from a malicious relay shouldn't
    // crash the KeyPackage screen — fall back to "unknown" instead.
    if (unixSeconds > Long.MAX_VALUE.toULong()) return unknown
    val instant = runCatching { Instant.ofEpochSecond(unixSeconds.toLong()) }.getOrNull() ?: return unknown
    return String.format(format, publishedAtFormatter.format(instant))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    appState: DarkMatterAppState,
    onBack: () -> Unit,
) {
    // Claim back so it returns to Settings rather than falling through
    // to the Activity and closing the app.
    BackHandler { onBack() }
    var health by remember { mutableStateOf<RelayHealthFfi?>(null) }
    var entries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    var streaming by remember { mutableStateOf(false) }
    var sendingPing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sentPingFormat = stringResource(R.string.diagnostic_sent_ping_to_self)
    val sendToSelfFailedFormat = stringResource(R.string.diagnostic_send_to_self_failed)

    fun appendLog(text: String) {
        entries = (entries + DiagnosticLogEntry(text = text)).takeLast(500)
    }

    LaunchedEffect(appState.activeAccountRef, appState.runtimeGeneration) {
        streaming = true
        val subscription = appState.marmotIo { subscribeEvents() }
        try {
            while (true) {
                val event =
                    withContext(Dispatchers.IO) {
                        subscription.next()
                    } ?: break
                entries = (entries + DiagnosticLogEntry(text = DiagnosticFormatter.describe(event))).takeLast(500)
            }
        } catch (throwable: Throwable) {
            // A re-key (account/runtime change) cancels this effect; let that
            // propagate instead of logging it as a stream failure.
            if (throwable is CancellationException) throw throwable
            entries = (entries + DiagnosticLogEntry(text = "event stream failed: ${throwable.message ?: throwable.javaClass.simpleName}")).takeLast(500)
        } finally {
            streaming = false
            withContext(Dispatchers.IO) {
                subscription.destroy()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { health = appState.marmotIo { relayHealth() } }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = {
                            if (sendingPing) return@OutlinedButton
                            sendingPing = true
                            scope.launch {
                                val account = appState.activeAccountRef
                                if (account == null) {
                                    sendingPing = false
                                    return@launch
                                }
                                try {
                                    runCatching {
                                        val groupId =
                                            appState.marmotIo {
                                                createGroup(
                                                    account,
                                                    "diagnostic-${System.currentTimeMillis() / 1000L}",
                                                    emptyList(),
                                                    null,
                                                )
                                            }
                                        appState.marmotIo { sendText(account, groupId, "ping at ${System.currentTimeMillis() / 1000L}") }
                                        // Archive the throwaway group so the chat list doesn't accumulate orphans.
                                        appState.marmotIo { setGroupArchived(account, groupId, true) }
                                        appendLog(String.format(sentPingFormat, IdentityFormatter.short(groupId)))
                                    }.onFailure {
                                        appendLog(String.format(sendToSelfFailedFormat, it.message ?: it.javaClass.simpleName))
                                    }
                                } finally {
                                    sendingPing = false
                                }
                            }
                        },
                        enabled = !sendingPing && appState.activeAccountRef != null,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.send_to_self))
                    }
                    OutlinedButton(onClick = { entries = emptyList() }) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.clear))
                    }
                    Spacer(Modifier.weight(1f))
                    Text(stringResource(if (streaming) R.string.live else R.string.idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard(title = stringResource(R.string.relay_health)) {
                    if (health == null) {
                        Text(stringResource(R.string.no_relay_snapshot_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { scope.launch { health = appState.marmotIo { relayHealth() } } }) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                    } else {
                        health?.let { relay ->
                            DiagnosticRow(stringResource(R.string.total), relay.totalRelays.toString())
                            DiagnosticRow(stringResource(R.string.connected), relay.connected.toString())
                            DiagnosticRow(stringResource(R.string.connecting), relay.connecting.toString())
                            DiagnosticRow(stringResource(R.string.disconnected), relay.disconnected.toString())
                            DiagnosticRow(stringResource(R.string.attempts), relay.connectionAttempts.toString())
                            DiagnosticRow(stringResource(R.string.successes), relay.connectionSuccesses.toString())
                        }
                    }
                }
            }
            item {
                SectionCard(title = stringResource(R.string.runtime)) {
                    DiagnosticRow(stringResource(R.string.active_account), appState.activeAccountRef ?: stringResource(R.string.none))
                    DiagnosticRow(stringResource(R.string.accounts), appState.accounts.size.toString())
                    DiagnosticRow(stringResource(R.string.bootstrap_relays), appState.bootstrapRelayCount().toString())
                }
            }
            // Event log: emitted as top-level lazy items (keyed by the entry's
            // id) rather than a forEach inside a single item, so the up-to-500
            // rows are actually virtualized instead of all composing at once.
            // See #35.
            item {
                Text(
                    stringResource(R.string.event_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (entries.isEmpty()) {
                item {
                    Text(stringResource(R.string.waiting_for_events), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            rememberedRelativeTime(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    copyValue: String? = null,
    appState: DarkMatterAppState? = null,
) {
    val clipboard = LocalClipboardManager.current
    // Opt-in tap-to-copy: a row becomes copyable only when both the full
    // value and an appState (for the confirmation toast) are supplied. Plain
    // numeric/status rows leave these null and stay non-interactive.
    val copyable = !copyValue.isNullOrEmpty() && appState != null
    val copyLabel = stringResource(R.string.copy)
    val rowModifier =
        if (copyable) {
            Modifier
                .fillMaxWidth()
                .clickable(
                    onClickLabel = copyLabel,
                    role = Role.Button,
                ) {
                    clipboard.setText(AnnotatedString(copyValue!!))
                    appState!!.presentText(AppText.Resource(R.string.toast_copied_value, listOf(label)))
                }
        } else {
            Modifier.fillMaxWidth()
        }
    Row(
        rowModifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (copyable) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    value,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// Section panels read as white cards on a recessed page in light, and as a
// raised neutral card over the darker page in dark/AMOLED — the same "panel
// one step above the page" relationship, resolved per theme by luminance since
// "lighter == elevated" can't be expressed the same way in both.
@Composable
private fun sectionPanelColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() > 0.5f) {
        MaterialTheme.colorScheme.surfaceContainerLowest
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SectionCardWithAction(
    title: String,
    action: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
    ) {
        Column(Modifier.fillMaxWidth().padding(Dimens.spaceLg), verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                action()
            }
            content()
        }
    }
}

@Composable
private fun ErrorContent(
    title: String,
    message: String,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val AvatarPalette =
    listOf(
        Color(0xFF006A6A),
        Color(0xFF8C4A00),
        Color(0xFF5B5FC7),
        Color(0xFF006D3B),
        Color(0xFF9A4055),
    )

@Composable
private fun Avatar(
    title: String,
    seed: String,
    size: androidx.compose.ui.unit.Dp,
    pictureUrl: String? = null,
) {
    val color = AvatarPalette[avatarPaletteIndex(seed.hashCode(), AvatarPalette.size)]
    // Seed from the in-memory cache so re-entering a screen shows an
    // already-loaded avatar immediately, with no placeholder flash and no
    // re-fetch. See #31.
    val image by produceState(AvatarImageLoader.peek(pictureUrl), pictureUrl) {
        val url =
            pictureUrl ?: run {
                value = null
                return@produceState
            }
        if (value == null) value = AvatarImageLoader.load(url)
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // Derive the font size from the avatar diameter so wide letter
            // pairs (e.g. "MW", "WW") fit inside the circle (#312). The 0.4
            // ratio keeps the worst-case 2-letter pair clear of the bounds at
            // every render size used in the app (36dp..96dp).
            //
            // The avatar's outer Modifier.size(...) is in dp and therefore
            // does NOT scale with the user's accessibility font scale, but a
            // raw `.sp` value would — pushing wide pairs back outside the
            // circle for users on large-font settings. Divide the derived
            // value by `fontScale` so the rendered size is constant in dp
            // and tracks the avatar's actual size, then cap at titleMedium so
            // the existing look is preserved on the large profile/group-detail
            // avatars where titleMedium already fits comfortably. The cap is
            // taken in the same dp-constant space, so it also resists font
            // scale.
            val fontScale = LocalDensity.current.fontScale
            val titleMediumSp = MaterialTheme.typography.titleMedium.fontSize
            val fittedFontSize =
                minOf(size.value * 0.4f, titleMediumSp.value * fontScale).sp / fontScale
            Text(
                IdentityFormatter.initials(title),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                fontSize = fittedFontSize,
                maxLines = 1,
            )
        }
    }
}

internal fun avatarPaletteIndex(
    seedHash: Int,
    paletteSize: Int,
): Int = Math.floorMod(seedHash, paletteSize)
