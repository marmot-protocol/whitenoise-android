package dev.ipf.whitenoise.android.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.ShortcutManagerCompat
import dev.ipf.whitenoise.android.notifications.InboundIntentRouting
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.routeInboundIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareRequestTest {
    private val noPending = InboundIntentRouting(null, null, null)

    @Test
    fun routeInboundIntent_shareRequestWinsOverProfileLink() {
        val payload = SharePayload(text = "hi", streamUris = emptyList(), intentMimeType = "text/plain")
        val share = ShareRequest(payload, shortcutId = null)
        val routed =
            routeInboundIntent(
                parsedTarget = null,
                shareRequest = share,
                dataString = "whitenoise://profile/npub1",
                current = noPending,
            )
        assertEquals(share, routed.shareRequest)
        assertNull(routed.profilePayload)
    }

    @Test
    fun routeInboundIntent_notificationWinsOverShare() {
        val target = NotificationTarget("acct", "g1", null, NotificationTargetKind.MESSAGE)
        val share =
            ShareRequest(
                SharePayload("hi", emptyList(), "text/plain"),
                shortcutId = null,
            )
        val routed =
            routeInboundIntent(
                parsedTarget = target,
                shareRequest = share,
                dataString = null,
                current = noPending,
            )
        assertEquals(target, routed.notificationTarget)
        assertNull(routed.shareRequest)
    }

    @Test
    fun routeInboundIntent_bareRelaunchPreservesPendingShare() {
        val share =
            ShareRequest(
                SharePayload("pending", emptyList(), "text/plain"),
                shortcutId = null,
                requestId = "pending-share",
            )

        val routed =
            routeInboundIntent(
                parsedTarget = null,
                shareRequest = null,
                dataString = null,
                current = noPending.copy(shareRequest = share),
            )

        assertEquals(share, routed.shareRequest)
    }

    @Test
    fun resolveDirectShareGroupId_matchesShortcutWithinActiveChatsOnly() {
        val accountRef = "acct-a"
        val groupId = "group-1"
        val shortcutId = conversationShortcutId(accountRef, groupId)!!
        val request =
            ShareRequest(
                payload = SharePayload(null, listOf(Uri.parse("content://x")), "image/*"),
                shortcutId = shortcutId,
            )
        assertEquals(
            groupId,
            resolveShareDirectGroupId(request, accountRef, setOf(groupId, "group-2")),
        )
        assertNull(resolveShareDirectGroupId(request, accountRef, setOf("group-2")))
    }

    @Test
    fun resolveDirectShareGroupId_ignoresForgeableGroupIdExtras() {
        val accountRef = "acct-a"
        val shortcutId = conversationShortcutId(accountRef, "group-1")!!
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "shared")
                putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, shortcutId)
                putExtra("dev.ipf.whitenoise.android.extra.DIRECT_SHARE_GROUP_ID", "forged-group")
            }
        val request = parseShareRequest(intent)
        assertEquals(
            "group-1",
            resolveShareDirectGroupId(request!!, accountRef, setOf("group-1")),
        )
        assertNull(
            resolveShareDirectGroupId(
                request,
                accountRef,
                setOf("forged-group"),
            ),
        )
    }

    @Test
    fun parseShareRequest_readsShortcutIdOnly() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "shared")
                putExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID, "conversation-abc")
            }
        val request = parseShareRequest(intent)
        assertEquals("conversation-abc", request?.shortcutId)
    }

    @Test
    fun parseShareRequest_assignsEachInboundShareANewIdentity() {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "shared")
            }

        val first = parseShareRequest(intent)
        val second = parseShareRequest(intent)

        assertNotEquals(first?.requestId, second?.requestId)
    }
}
