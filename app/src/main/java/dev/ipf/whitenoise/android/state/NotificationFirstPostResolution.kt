package dev.ipf.whitenoise.android.state

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.text.SpanStyle
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.ui.markdownDocumentMentionBech32s
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewAnnotatedString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Privacy-safe completion state for the detached late-correction lane. */
internal enum class NotificationLateCorrectionOutcome(
    val timingValue: String,
) {
    ContentPosted("content_posted"),
    AvatarPosted("avatar_posted"),
    Unchanged("unchanged"),
    Stale("stale"),
    Failed("failed"),
}

/** Resolves one NIP-19 mention from local state and schedules hydration only after a local miss. */
internal suspend fun resolveNotificationMentionDisplayName(
    bech32: String,
    accountIdHex: suspend (String) -> String?,
    profileDisplayName: (String) -> String?,
    readDisplayName: suspend (String) -> String?,
    requestProfile: (String) -> Unit,
): String? =
    accountIdHex(bech32)?.let { id ->
        profileDisplayName(id) ?: readDisplayName(id)?.let(ProfileSanitizer::displayName).also { displayName ->
            if (displayName == null) requestProfile(id)
        }
    }

/** Applies contact-first then sanitized local-profile precedence for a sender label. */
internal fun notificationSenderNameOverride(
    contactNickname: String?,
    localProfileName: String?,
): String? =
    ProfileSanitizer.displayName(contactNickname)
        ?: ProfileSanitizer.displayName(localProfileName)

/** Accepts a sanitized payload hint only when it is not an identity fallback. */
internal fun notificationDisplayNameHint(raw: String?): String? {
    val displayName = ProfileSanitizer.displayName(raw)
    return displayName?.takeUnless(IdentityFormatter::isNostrIdentityFallback)
}

/** Prefers a locally persisted profile name over the notification payload hint. */
internal fun resolvedProfileDisplayName(
    profileDisplayName: String?,
    notificationDisplayNameHint: String?,
): String? =
    ProfileSanitizer.displayName(profileDisplayName)
        ?: notificationDisplayNameHint(notificationDisplayNameHint)

/** Combines bootstrap and account relays without changing their first-seen order. */
internal fun profileLookupRelays(
    bootstrapRelays: List<String>,
    activeAccountRelays: List<String>,
): List<String> = (bootstrapRelays + activeAccountRelays).distinct()

/** Flattens a parsed notification body while resolving each distinct mention once. */
internal suspend fun resolveNotificationPreviewText(
    raw: String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    mentionDisplayName: suspend (String) -> String?,
): String? =
    raw?.takeIf { it.isNotBlank() }?.let { text ->
        val document = parseMarkdown(text).takeIf { it.blocks.isNotEmpty() } ?: return@let null
        val mentionNames = mutableMapOf<String, String?>()
        for (bech32 in markdownDocumentMentionBech32s(document)) {
            mentionNames[bech32] = mentionDisplayName(bech32)
        }
        markdownDocumentToPreviewAnnotatedString(
            document = document,
            codeStyle = SpanStyle(),
            mentionDisplayName = mentionNames::get,
        ).text.takeIf { it.isNotBlank() }
    }

/** Resolved localized group-system title and body for one notification. */
internal data class NotificationSystemText(
    val title: String?,
    val body: String,
)

/** Small collaborator bundle that keeps notification projection outside the app-state owner. */
internal data class NotificationContentResolutionServices(
    val identity: NotificationIdentityResolver,
    val firstPost: NotificationFirstPostResolver,
)

/** Canonical text/subtext projection matching the presenter's formatter inputs. */
internal fun notificationContentPresentation(
    context: Context,
    update: NotificationUpdateFfi,
    content: NotificationFirstPostContent?,
    shortNpub: (String) -> String,
): NotificationContentPresentation =
    NotificationContentPresentation(
        content =
            LocalNotificationFormatter.content(
                update = update,
                context = context,
                conversationTitleOverride = content?.conversationTitle,
                senderNameOverride = content?.senderName,
                previewTextOverride = content?.previewText,
                reactedToPreviewOverride = content?.reactedToPreview,
                mediaKind = content?.mediaKind ?: ReplyMediaKind.None,
                shortNpub = shortNpub,
            ),
        recipientAccountSubtext = content?.recipientAccountSubtext,
    )

/** Resolves and sanitizes a post-deadline sender avatar URL from local profile or payload. */
internal suspend fun notificationSenderAvatarUrl(
    update: NotificationUpdateFfi,
    loadUserProfile: suspend (String) -> UserProfileMetadataFfi?,
): String? =
    ProfileSanitizer.protocolImageUrl(loadUserProfile(update.sender.accountIdHex)?.picture)
        ?: ProfileSanitizer.protocolImageUrl(update.sender.pictureUrl)

/** Local identity and Markdown reads shared by first-draw and late notification resolution. */
@Suppress("LongParameterList")
internal class NotificationIdentityResolver(
    private val contactNickname: (String?, String) -> String?,
    private val readDisplayName: suspend (String) -> String?,
    private val displayNameHint: (String) -> String?,
    private val cachedShortNpub: (String) -> String,
    private val hydratedDisplayName: (String) -> String?,
    private val requestProfile: (String) -> Unit,
    private val accountIdHex: suspend (String) -> String?,
    private val parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    private val recipientAccountIdHex: (String) -> String?,
) {
    /** Reads the best local sender label without starting network hydration. */
    suspend fun senderName(update: NotificationUpdateFfi): String? {
        val senderIdHex = update.sender.accountIdHex
        if (senderIdHex.isBlank()) return null
        val nickname = notificationSenderNameOverride(contactNickname(update.accountRef, senderIdHex), null)
        val localName = if (nickname == null) bestEffortDisplayName(senderIdHex) else null
        return nickname ?: notificationSenderNameOverride(null, localName) ?: displayNameHint(senderIdHex)
    }

    /** Returns the best current label and optionally starts post-deadline profile hydration. */
    suspend fun displayNameForAccount(
        accountRef: String?,
        accountIdHex: String,
        requestMissingProfile: Boolean,
    ): String {
        val localName = localDisplayNameForAccount(accountRef, accountIdHex)
        val hydratedName = if (requestMissingProfile) hydratedDisplayName(accountIdHex) else null
        if (requestMissingProfile && hydratedName == null) requestProfile(accountIdHex)
        return hydratedName ?: localName
    }

    /** Resolves the recipient label used only when notification subtext is needed. */
    suspend fun recipientName(
        accountRef: String,
        localOnly: Boolean,
    ): String? =
        recipientAccountIdHex(accountRef)?.let { id ->
            displayNameForAccount(accountRef, id, requestMissingProfile = !localOnly)
        }

    /** Flattens Markdown through the in-app mention path without remote first-draw work. */
    suspend fun previewText(
        raw: String?,
        requestMissingProfiles: Boolean,
    ): String? =
        resolveNotificationPreviewText(
            raw = raw,
            parseMarkdown = parseMarkdown,
            mentionDisplayName = { mentionDisplayName(it, requestMissingProfiles) },
        )

    /** Applies contact, cached/persisted profile, payload-hint, then npub fallback precedence. */
    private suspend fun localDisplayNameForAccount(
        accountRef: String?,
        accountIdHex: String,
    ): String {
        val nickname = contactNickname(accountRef, accountIdHex)
        val localName = if (nickname == null) bestEffortDisplayName(accountIdHex) else null
        return nickname
            ?: localName?.let(ProfileSanitizer::displayName)
            ?: displayNameHint(accountIdHex)
            ?: cachedShortNpub(accountIdHex)
    }

    /** Resolves one NIP-19 mention and starts hydration only on the post-deadline pass. */
    private suspend fun mentionDisplayName(
        bech32: String,
        requestMissingProfile: Boolean,
    ): String? =
        resolveNotificationMentionDisplayName(
            bech32 = bech32,
            accountIdHex = accountIdHex,
            profileDisplayName = hydratedDisplayName,
            readDisplayName = ::bestEffortDisplayName,
            requestProfile = { if (requestMissingProfile) requestProfile(it) },
        )

    /** Converts binding failures to a missing local label while preserving cancellation. */
    private suspend fun bestEffortDisplayName(accountIdHex: String): String? =
        hydratedDisplayName(accountIdHex) ?: try {
            readDisplayName(accountIdHex)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
}

/** Localized structured group-system projection for notifications. */
internal class NotificationGroupSystemTextResolver(
    private val context: Context,
    private val timelineRecord: suspend (NotificationUpdateFfi) -> TimelineMessageRecordFfi?,
    private val displayNameForAccount: suspend (String?, String, Boolean) -> String,
) {
    /** Resolves structured group-system text, never the raw event payload. */
    suspend fun resolve(
        update: NotificationUpdateFfi,
        senderName: String?,
        localOnly: Boolean,
    ): NotificationSystemText? =
        timelineRecord(update)
            ?.takeIf { MessageProjector.isGroupSystemKind(it.kind) }
            ?.let { record ->
                GroupSystemEvents.resolve(record)?.let { event ->
                    val diff = GroupSystemEvents.renameDiffNames(event)
                    val actorHex = GroupSystemEvents.actorHex(event, record.sender)
                    val actorName = actorName(update, actorHex, senderName, localOnly)
                    val subjectHex = event.subject
                    val subjectName = subjectName(update, subjectHex, localOnly)
                    NotificationSystemText(
                        title = if (diff != null) context.getString(R.string.notification_group_renamed) else null,
                        body =
                            if (diff != null) {
                                context.getString(
                                    R.string.notification_group_renamed_body,
                                    actorName,
                                    diff.oldName,
                                    diff.newName,
                                )
                            } else {
                                GroupSystemEvents.summary(
                                    event = event,
                                    actorName = actorName,
                                    subjectName = subjectName,
                                    actorIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, actorHex),
                                    subjectIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, subjectHex),
                                    copy = notificationGroupSystemCopy(context),
                                )
                            },
                    )
                }
            }

    /** Resolves a system-event actor without letting a profile failure discard the event. */
    private suspend fun actorName(
        update: NotificationUpdateFfi,
        actorHex: String?,
        senderName: String?,
        localOnly: Boolean,
    ): String =
        when {
            GroupSystemEvents.isSelf(update.accountIdHex, actorHex) -> context.getString(R.string.you)
            !senderName.isNullOrBlank() -> senderName
            !actorHex.isNullOrBlank() -> bestEffortName(update.accountRef, actorHex, localOnly)
            else -> null
        } ?: context.getString(R.string.group_system_someone)

    /** Resolves an optional system-event subject under the same local-only policy. */
    private suspend fun subjectName(
        update: NotificationUpdateFfi,
        subjectHex: String?,
        localOnly: Boolean,
    ): String? =
        when {
            GroupSystemEvents.isSelf(update.accountIdHex, subjectHex) -> context.getString(R.string.you)
            !subjectHex.isNullOrBlank() -> bestEffortName(update.accountRef, subjectHex, localOnly)
            else -> null
        }

    /** Converts non-cancellation identity failures to the localized unknown fallback. */
    private suspend fun bestEffortName(
        accountRef: String,
        accountIdHex: String,
        localOnly: Boolean,
    ): String? =
        try {
            displayNameForAccount(accountRef, accountIdHex, !localOnly)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }
}

/** Returns localized copy required by the structured group-system projector. */
internal fun notificationGroupSystemCopy(context: Context) =
    dev.ipf.whitenoise.android.core.GroupSystemCopy(
        memberAddedFormat = context.getString(R.string.group_system_member_added),
        memberAddedPassiveFormat = context.getString(R.string.group_system_member_added_passive),
        memberRemovedFormat = context.getString(R.string.group_system_member_removed),
        memberRemovedPassiveFormat = context.getString(R.string.group_system_member_removed_passive),
        memberLeftFormat = context.getString(R.string.group_system_member_left),
        adminAddedFormat = context.getString(R.string.group_system_admin_added),
        adminAddedPassiveFormat = context.getString(R.string.group_system_admin_added_passive),
        adminRemovedFormat = context.getString(R.string.group_system_admin_removed),
        adminRemovedPassiveFormat = context.getString(R.string.group_system_admin_removed_passive),
        renamedFormat = context.getString(R.string.group_system_renamed),
        renamedPassiveFormat = context.getString(R.string.group_system_renamed_passive),
        renamedDiffFormat = context.getString(R.string.group_system_renamed_diff),
        renamedDiffPassiveFormat = context.getString(R.string.group_system_renamed_diff_passive),
        namedFormat = context.getString(R.string.group_system_named),
        namedPassiveFormat = context.getString(R.string.group_system_named_passive),
        avatarChangedFormat = context.getString(R.string.group_system_avatar_changed),
        avatarChangedPassive = context.getString(R.string.group_system_avatar_changed_passive),
        youMemberAddedFormat = context.getString(R.string.group_system_you_member_added),
        memberAddedYouFormat = context.getString(R.string.group_system_member_added_you),
        memberAddedYouPassive = context.getString(R.string.group_system_member_added_you_passive),
        youMemberRemovedFormat = context.getString(R.string.group_system_you_member_removed),
        memberRemovedYouFormat = context.getString(R.string.group_system_member_removed_you),
        memberRemovedYouPassive = context.getString(R.string.group_system_member_removed_you_passive),
        youMemberLeft = context.getString(R.string.group_system_you_member_left),
        youAdminAddedFormat = context.getString(R.string.group_system_you_admin_added),
        adminAddedYouFormat = context.getString(R.string.group_system_admin_added_you),
        adminAddedYouPassive = context.getString(R.string.group_system_admin_added_you_passive),
        youAdminRemovedFormat = context.getString(R.string.group_system_you_admin_removed),
        adminRemovedYouFormat = context.getString(R.string.group_system_admin_removed_you),
        adminRemovedYouPassive = context.getString(R.string.group_system_admin_removed_you_passive),
        youRenamedFormat = context.getString(R.string.group_system_you_renamed),
        youRenamedDiffFormat = context.getString(R.string.group_system_you_renamed_diff),
        youNamedFormat = context.getString(R.string.group_system_you_named),
        youAvatarChanged = context.getString(R.string.group_system_you_avatar_changed),
        disappearingSetFormat = context.getString(R.string.group_system_disappearing_set),
        disappearingSetYouFormat = context.getString(R.string.group_system_disappearing_set_you),
        disappearingSetPassiveFormat = context.getString(R.string.group_system_disappearing_set_passive),
        disappearingOffFormat = context.getString(R.string.group_system_disappearing_off),
        disappearingOffYou = context.getString(R.string.group_system_disappearing_off_you),
        disappearingOffPassive = context.getString(R.string.group_system_disappearing_off_passive),
        someone = context.getString(R.string.group_system_someone),
        fallback = context.getString(R.string.group_system_fallback),
    )

/** Chat-list-equivalent deterministic conversation title resolution. */
internal class NotificationConversationTitleResolver(
    private val context: Context,
    private val groupMembers: suspend (NotificationUpdateFfi) -> List<AppGroupMemberRecordFfi>,
    private val displayNameForAccount: suspend (String?, String, Boolean) -> String,
    private val cachedShortNpub: (String) -> String,
) {
    /** Resolves a group title locally; DMs let MessagingStyle own the title. */
    suspend fun resolve(
        update: NotificationUpdateFfi,
        localOnly: Boolean,
    ): String? =
        if (update.isDm) {
            null
        } else {
            update.groupName?.let(ProfileSanitizer::displayName) ?: groupMembers(update)
                .takeIf { it.isNotEmpty() }
                ?.let { members ->
                    val otherMemberAccount = GroupProjector.otherMemberAccount(members, update.accountIdHex)
                    val otherMemberTitle =
                        otherMemberAccount?.let {
                            displayNameForAccount(update.accountRef, it, !localOnly)
                        }
                    GroupProjector.displayTitle(
                        name = "",
                        pendingInviteAccount = null,
                        groupIdHex = update.groupIdHex,
                        otherMemberAccount = otherMemberAccount,
                        memberCount = GroupProjector.uniqueMemberCount(members),
                        memberTitle = { otherMemberTitle ?: cachedShortNpub(it) },
                        copy = notificationGroupTitleCopy(context),
                    )
                }
        }
}

/** Returns localized copy shared by notification and shortcut group-title projection. */
internal fun notificationGroupTitleCopy(context: Context) =
    dev.ipf.whitenoise.android.core.GroupTitleCopy(
        inviteFromFormat = context.getString(R.string.group_title_invite_from),
        groupOfPeopleFormat = context.getString(R.string.group_title_people_count),
        unknownTitle = context.getString(R.string.unknown),
        soleMemberTitle = context.getString(R.string.just_you),
    )

/** Builds one complete notification content projection under the caller's deadline. */
internal class NotificationFirstPostResolver(
    private val identity: NotificationIdentityResolver,
    private val systemText: NotificationGroupSystemTextResolver,
    private val conversationTitle: NotificationConversationTitleResolver,
    private val mediaKind: suspend (NotificationUpdateFfi) -> ReplyMediaKind,
    private val signedInAccountCount: () -> Int,
) {
    /** Resolves all text/subtext fields without starting remote work when [localOnly]. */
    suspend fun resolve(
        update: NotificationUpdateFfi,
        localOnly: Boolean,
    ): NotificationFirstPostContent {
        val senderName = identity.senderName(update)
        val system = systemText.resolve(update, senderName, localOnly)
        val preview =
            system?.body ?: if (LocalNotificationFormatter.needsPreviewTextResolution(update)) {
                identity.previewText(update.previewText, requestMissingProfiles = !localOnly)
            } else {
                null
            }
        val reactedTo =
            if (LocalNotificationFormatter.needsReactedToPreviewResolution(update)) {
                identity.previewText(update.reactedToPreview, requestMissingProfiles = !localOnly)
            } else {
                null
            }
        val resolvedMediaKind =
            if (
                system == null &&
                LocalNotificationFormatter.needsPreviewTextResolution(update) &&
                preview.isNullOrBlank()
            ) {
                mediaKind(update)
            } else {
                ReplyMediaKind.None
            }
        return NotificationFirstPostContent(
            conversationTitle = system?.title ?: conversationTitle.resolve(update, localOnly),
            senderName = senderName,
            previewText = preview,
            reactedToPreview = reactedTo,
            mediaKind = resolvedMediaKind,
            recipientAccountSubtext =
                LocalNotificationFormatter.recipientAccountSubtext(
                    signedInAccountCount = signedInAccountCount(),
                    recipientLabel = identity.recipientName(update.accountRef, localOnly),
                ),
        )
    }
}

/** Constructs the identity, system-text, title, and first-post projection graph. */
@Suppress("LongParameterList")
internal fun createNotificationContentResolutionServices(
    context: Context,
    contactNickname: (String?, String) -> String?,
    readDisplayName: suspend (String) -> String?,
    displayNameHint: (String) -> String?,
    cachedShortNpub: (String) -> String,
    hydratedDisplayName: (String) -> String?,
    requestProfile: (String) -> Unit,
    accountIdHex: suspend (String) -> String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    recipientAccountIdHex: (String) -> String?,
    timelineRecord: suspend (NotificationUpdateFfi) -> TimelineMessageRecordFfi?,
    groupMembers: suspend (NotificationUpdateFfi) -> List<AppGroupMemberRecordFfi>,
    mediaKind: suspend (NotificationUpdateFfi) -> ReplyMediaKind,
    signedInAccountCount: () -> Int,
): NotificationContentResolutionServices {
    val identity =
        NotificationIdentityResolver(
            contactNickname = contactNickname,
            readDisplayName = readDisplayName,
            displayNameHint = displayNameHint,
            cachedShortNpub = cachedShortNpub,
            hydratedDisplayName = hydratedDisplayName,
            requestProfile = requestProfile,
            accountIdHex = accountIdHex,
            parseMarkdown = parseMarkdown,
            recipientAccountIdHex = recipientAccountIdHex,
        )
    val systemText = NotificationGroupSystemTextResolver(context, timelineRecord, identity::displayNameForAccount)
    val conversationTitle =
        NotificationConversationTitleResolver(context, groupMembers, identity::displayNameForAccount, cachedShortNpub)
    return NotificationContentResolutionServices(
        identity = identity,
        firstPost =
            NotificationFirstPostResolver(identity, systemText, conversationTitle, mediaKind, signedInAccountCount),
    )
}

/** Post-first-draw remote avatar work with exact decoded bitmap handoff. */
internal class NotificationAvatarCoordinator(
    private val appLocked: () -> Boolean,
    private val shouldPost: (NotificationUpdateFfi, Boolean) -> Boolean,
    private val canPost: () -> Boolean,
    private val senderAvatarUrl: suspend (NotificationUpdateFfi) -> String?,
    private val groupAvatarUrl: suspend (NotificationUpdateFfi) -> String?,
) {
    /** Starts bounded remote work only after the first notification write. */
    suspend fun preWarm(
        update: NotificationUpdateFfi,
        engineMuted: Boolean,
    ): PreWarmedNotificationAvatars {
        val eligible =
            shouldPreWarmNotificationAvatars(
                update = update,
                shouldPost = shouldPost(update, engineMuted),
                canPost = canPost(),
            )
        if (!eligible) return PreWarmedNotificationAvatars(null, null)

        val target = notificationAvatarPreWarmTarget(update, appLocked())
        preWarmIfUnlocked(target.senderAvatarUrl)
        val sender =
            if (target.preWarmRemoteImages && target.senderAccountIdHex != null) {
                bestEffort { senderAvatarUrl(update) }
            } else {
                null
            }
        preWarmIfUnlocked(sender)
        val group = if (target.resolveGroupAvatar) bestEffort { groupAvatarUrl(update) } else null
        preWarmIfUnlocked(group)
        return PreWarmedNotificationAvatars(sender, group)
    }

    /** Awaits sender and group caches concurrently and returns the exact proven bitmaps. */
    suspend fun awaitReady(
        avatars: PreWarmedNotificationAvatars,
        shouldContinue: () -> Boolean,
    ): PreWarmedNotificationAvatars =
        coroutineScope {
            val sender = async { awaitCache(avatars.senderAvatarUrl, shouldContinue) }
            val group = async { awaitCache(avatars.groupAvatarUrl, shouldContinue) }
            avatars.copy(senderAvatarBitmap = sender.await(), groupAvatarBitmap = group.await())
        }

    /** Prevents decrypted imagery from entering memory while the app lock is visible. */
    private fun preWarmIfUnlocked(url: String?) {
        if (!appLocked()) AvatarImageLoader.preWarm(url)
    }

    /** Returns the exact decoded bitmap only while every post-generation fence remains valid. */
    private suspend fun awaitCache(
        url: String?,
        shouldContinue: () -> Boolean,
    ): Bitmap? =
        url
            ?.takeUnless(String::isBlank)
            ?.takeIf { !appLocked() && shouldContinue() }
            ?.let { candidate ->
                withTimeoutOrNull(AVATAR_CORRECTION_TIMEOUT_MILLIS) {
                    if (shouldContinue()) AvatarImageLoader.loadBitmap(candidate) else null
                }
            }?.takeIf { !appLocked() && shouldContinue() }

    /** Isolates optional avatar lookup failures without swallowing structured cancellation. */
    private suspend fun <T> bestEffort(block: suspend () -> T?): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private companion object {
        const val AVATAR_CORRECTION_TIMEOUT_MILLIS = 2_500L
    }
}
