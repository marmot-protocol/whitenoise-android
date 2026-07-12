package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.GroupSystemCopy
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ConversationControllerCopy
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun rememberGroupTitleCopy(): GroupTitleCopy =
    GroupTitleCopy(
        inviteFromFormat = stringResource(R.string.group_title_invite_from),
        groupOfPeopleFormat = stringResource(R.string.group_title_people_count),
        unknownTitle = stringResource(R.string.unknown),
    )

@Composable
internal fun rememberMessageTextCopy(): MessageTextCopy =
    MessageTextCopy(
        reactedFormat = stringResource(R.string.message_reacted),
        reactionFallback = stringResource(R.string.message_reaction_fallback),
        deleted = stringResource(R.string.message_deleted_preview),
        invalidated = stringResource(R.string.message_invalidated_preview),
        agentStreamStarted = stringResource(R.string.agent_stream_started),
        streamFinished = stringResource(R.string.stream_finished),
        mediaAttachment = stringResource(R.string.media_attachment),
        mediaPhoto = stringResource(R.string.reply_media_photo),
        mediaVideo = stringResource(R.string.reply_media_video),
        mediaVoice = stringResource(R.string.reply_media_voice),
        mediaDocument = stringResource(R.string.reply_media_document),
        message = stringResource(R.string.generic_message),
        groupSystem = rememberGroupSystemCopy(),
    )

@Composable
internal fun rememberGroupSystemCopy(): GroupSystemCopy =
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
        renamedDiffFormat = stringResource(R.string.group_system_renamed_diff),
        renamedDiffPassiveFormat = stringResource(R.string.group_system_renamed_diff_passive),
        namedFormat = stringResource(R.string.group_system_named),
        namedPassiveFormat = stringResource(R.string.group_system_named_passive),
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
        youRenamedDiffFormat = stringResource(R.string.group_system_you_renamed_diff),
        youNamedFormat = stringResource(R.string.group_system_you_named),
        youAvatarChanged = stringResource(R.string.group_system_you_avatar_changed),
        disappearingSetFormat = stringResource(R.string.group_system_disappearing_set),
        disappearingSetYouFormat = stringResource(R.string.group_system_disappearing_set_you),
        disappearingSetPassiveFormat = stringResource(R.string.group_system_disappearing_set_passive),
        disappearingOffFormat = stringResource(R.string.group_system_disappearing_off),
        disappearingOffYou = stringResource(R.string.group_system_disappearing_off_you),
        disappearingOffPassive = stringResource(R.string.group_system_disappearing_off_passive),
        someone = stringResource(R.string.group_system_someone),
        fallback = stringResource(R.string.group_system_fallback),
    )

@Composable
internal fun rememberConversationControllerCopy(): ConversationControllerCopy =
    ConversationControllerCopy(
        waitingForStream = stringResource(R.string.waiting_for_stream),
        streamFailedFormat = stringResource(R.string.stream_failed_format),
        couldntAddMemberDuplicateFormat = stringResource(R.string.toast_couldnt_add_member_duplicate_detail),
    )

@Composable
private fun rememberRelativeTimeCopy(): dev.ipf.whitenoise.android.core.RelativeTimeCopy {
    val future = stringResource(R.string.relative_time_future)
    val now = stringResource(R.string.relative_time_now)
    val yesterday = stringResource(R.string.relative_time_yesterday)
    // Resolve the sub-day unit strings through getQuantityString so inflected
    // locales render the correct grammatical form for the count. Past 24h,
    // IdentityFormatter switches to localized day/date labels with no time.
    val resources = LocalContext.current.resources
    return remember(future, now, yesterday, resources) {
        dev.ipf.whitenoise.android.core.RelativeTimeCopy(
            future = future,
            now = now,
            yesterday = yesterday,
            minutes = { count ->
                resources.getQuantityString(R.plurals.relative_time_minutes, count, count)
            },
            hours = { count ->
                resources.getQuantityString(R.plurals.relative_time_hours, count, count)
            },
        )
    }
}

@Composable
internal fun rememberedRelativeTime(epochSeconds: ULong): String {
    val copy = rememberRelativeTimeCopy()
    val locale = LocalConfiguration.current.locales[0]
    val currentTime = rememberRelativeTimeNow()
    return remember(epochSeconds, copy, locale, currentTime) {
        IdentityFormatter.relativeTime(
            epochSeconds = epochSeconds,
            copy = copy,
            locale = locale,
            now = currentTime,
        )
    }
}

@Composable
private fun rememberRelativeTimeNow(): Instant {
    val lifecycleOwner = LocalContext.current.lifecycleOwner()
    var currentTime by remember { mutableStateOf(Instant.now()) }
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner == null || lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
        )
    }
    DisposableEffect(lifecycleOwner) {
        if (lifecycleOwner == null) {
            onDispose { }
        } else {
            val observer =
                LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            currentTime = Instant.now()
                            resumed = true
                        }
                        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> resumed = false
                        else -> Unit
                    }
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        while (true) {
            delay(relativeTimeRefreshDelayMillis(Instant.now()))
            currentTime = Instant.now()
        }
    }
    return currentTime
}

internal fun relativeTimeRefreshDelayMillis(now: Instant): Long = (60_000L - (now.toEpochMilli() % 60_000L)).coerceAtLeast(1L)

// Clock time only (locale-aware short form, e.g. "3:28 PM" / "15:28"). The
// transcript groups messages under day separators, so a bubble footer doesn't
// need the date — just the time. The full date stays available in message
// details.
@Composable
internal fun rememberedClockTime(epochSeconds: ULong): String {
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
