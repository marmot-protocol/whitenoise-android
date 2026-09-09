package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.tracing.Trace
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.ProductEventModeFfi
import dev.ipf.marmotkit.ProductEventSchemaFfi
import dev.ipf.marmotkit.ProductPropertyKindFfi
import dev.ipf.marmotkit.ProductPropertySchemaFfi
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object MarmotTraceSection {
    const val CREATE_GROUP = "WhiteNoise.marmot.createGroup"
    const val ACCEPT_GROUP_INVITE = "WhiteNoise.marmot.acceptGroupInvite"
    const val REFRESH_GROUP_ROSTER = "WhiteNoise.marmot.refreshMembers.roster"
    const val INVITE_MEMBERS = "WhiteNoise.marmot.inviteMembers"
    const val REMOVE_MEMBERS = "WhiteNoise.marmot.removeMembers"
    const val PROMOTE_ADMIN = "WhiteNoise.marmot.promoteAdmin"
    const val DEMOTE_ADMIN = "WhiteNoise.marmot.demoteAdmin"
    const val SELF_DEMOTE_ADMIN = "WhiteNoise.marmot.selfDemoteAdmin"
    const val TEXT_SEND = "WhiteNoise.marmot.sendText"
    const val TEXT_REPLY = "WhiteNoise.marmot.replyToMessage"
    const val MEDIA_UPLOAD = "WhiteNoise.marmot.uploadMedia"
    const val MEDIA_SEND = "WhiteNoise.marmot.sendMediaAttachments"
    const val MEDIA_DOWNLOAD = "WhiteNoise.marmot.downloadMedia"
    const val MEDIA_LIST = "WhiteNoise.marmot.listMedia"
    const val TIMELINE_READ = "WhiteNoise.marmot.timelineMessages"
    const val MESSAGE_SEARCH = "WhiteNoise.marmot.searchMessages"
    const val CHAT_LIST_READ = "WhiteNoise.marmot.chatList"
    const val CHAT_ROW_READ = "WhiteNoise.marmot.chatListRow"
    const val MEMBER_IDS_READ = "WhiteNoise.marmot.groupMemberIdsPage"
    const val PROFILE_READ = "WhiteNoise.marmot.userProfile"
    const val DISPLAY_NAME_READ = "WhiteNoise.marmot.displayName"
    const val ACCOUNT_LIST = "WhiteNoise.marmot.listAccounts"
    const val UNREAD_SUMMARY = "WhiteNoise.marmot.accountUnreadSummary"
    const val CATCH_UP = "WhiteNoise.marmot.catchUpAccounts"
    const val MESSAGE_EDIT = "WhiteNoise.marmot.editMessage"
    const val MESSAGE_REACT = "WhiteNoise.marmot.reactToMessage"

    // The same finite catalogue drives admission and native schema registration.
    val hostTimingNames =
        mapOf(
            CREATE_GROUP to "app_group_create",
            ACCEPT_GROUP_INVITE to "app_invite_accept",
            REFRESH_GROUP_ROSTER to "app_group_roster",
            INVITE_MEMBERS to "app_members_invite",
            REMOVE_MEMBERS to "app_members_remove",
            PROMOTE_ADMIN to "app_admin_promote",
            DEMOTE_ADMIN to "app_admin_demote",
            SELF_DEMOTE_ADMIN to "app_admin_self_demote",
            TEXT_SEND to "app_text_send",
            TEXT_REPLY to "app_text_reply",
            MEDIA_UPLOAD to "app_media_upload",
            MEDIA_SEND to "app_media_send",
            MEDIA_DOWNLOAD to "app_media_download",
            MEDIA_LIST to "app_media_list",
            TIMELINE_READ to "app_timeline_read",
            MESSAGE_SEARCH to "app_message_search_page",
            CHAT_LIST_READ to "app_chat_list_read",
            CHAT_ROW_READ to "app_chat_row_read",
            MEMBER_IDS_READ to "app_member_ids_read",
            PROFILE_READ to "app_profile_read",
            DISPLAY_NAME_READ to "app_display_name_read",
            ACCOUNT_LIST to "app_account_list",
            UNREAD_SUMMARY to "app_unread_summary",
            CATCH_UP to "app_catch_up",
            MESSAGE_EDIT to "app_message_edit",
            MESSAGE_REACT to "app_message_react",
        )

    val hostTimingRegistry =
        hostTimingNames.values.map { name ->
            ProductEventSchemaFfi(
                name = name,
                mode = ProductEventModeFfi.AGGREGATE,
                properties =
                    listOf(
                        ProductPropertySchemaFfi("elapsed", ProductPropertyKindFfi.DURATION_BUCKET, emptyList()),
                        ProductPropertySchemaFfi("outcome", ProductPropertyKindFfi.ENUM, listOf("success", "failure")),
                    ),
            )
        }
}

internal interface AsyncTraceBackend {
    fun isEnabled(): Boolean

    fun beginAsyncSection(
        sectionName: String,
        cookie: Int,
    )

    fun endAsyncSection(
        sectionName: String,
        cookie: Int,
    )
}

private object AndroidxAsyncTraceBackend : AsyncTraceBackend {
    override fun isEnabled(): Boolean = Trace.isEnabled()

    override fun beginAsyncSection(
        sectionName: String,
        cookie: Int,
    ) = Trace.beginAsyncSection(sectionName, cookie)

    override fun endAsyncSection(
        sectionName: String,
        cookie: Int,
    ) = Trace.endAsyncSection(sectionName, cookie)
}

/**
 * Emits process-wide async slices around suspending Marmot bridge calls.
 *
 * Async sections are intentional: a coroutine can resume on another IO worker,
 * while synchronous trace sections must begin and end on the same thread.
 */
internal class MarmotBridgeTracer(
    private val backend: AsyncTraceBackend = AndroidxAsyncTraceBackend,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private val nextCookie = AtomicInteger()
    private val timingRejected = AtomicBoolean()

    suspend fun <T> trace(
        sectionName: String,
        recordTiming: ((String, Long, HostPerformanceOutcomeFfi) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        val eventName = MarmotTraceSection.hostTimingNames[sectionName]
        val measure = recordTiming != null && eventName != null && !timingRejected.get()
        val startedAt = if (measure) nowMs() else 0L
        val traceEnabled = backend.isEnabled()
        val cookie =
            if (traceEnabled) {
                nextCookie.updateAndGet { current -> if (current == Int.MAX_VALUE) 1 else current + 1 }
            } else {
                0
            }
        if (traceEnabled) {
            backend.beginAsyncSection(sectionName, cookie)
        }
        var outcome = HostPerformanceOutcomeFfi.FAILURE
        return try {
            block().also { outcome = HostPerformanceOutcomeFfi.SUCCESS }
        } finally {
            val durationMs = if (measure) (nowMs() - startedAt).coerceAtLeast(0L) else 0L
            if (traceEnabled) {
                backend.endAsyncSection(sectionName, cookie)
            }
            if (measure) {
                try {
                    checkNotNull(recordTiming)(checkNotNull(eventName), durationMs, outcome)
                } catch (_: MarmotKitException) {
                    // Disable this recorder on schema/runtime rejection, preserving the operation's result.
                    if (timingRejected.compareAndSet(false, true)) {
                        Log.w("MarmotBridgeTracer", "Host timing recorder disabled after native rejection")
                    }
                }
            }
        }
    }
}
