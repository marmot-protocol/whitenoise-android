package dev.ipf.whitenoise.android.ui.navigation

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A staged share's cross-account chat open survives lock disposal and process recreation only while it
 * still belongs to that share — a newer share or a long-delayed restore must never reopen the chat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PendingStagedShareOpenRestorationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = WhiteNoiseAppState(context, DraftStore(NoDrafts), { null }, emptyList(), "share-account")
    private val holders = mutableListOf<MainShellStateHolder>()

    /** Releases every holder and the app-owned coroutines this test created. */
    @After
    fun tearDown() {
        holders.forEach(MainShellStateHolder::release)
        app.mutationsScope.cancel()
    }

    /** A fresh token round-trips through a holder rebuilt from the same saved state. */
    @Test
    fun freshTokenRoundTripsThroughARebuiltHolder() {
        val saved = SavedStateHandle()
        val token = token(stagedAt = SystemClock.elapsedRealtime())
        holder(saved).pendingStagedShareOpen = token
        assertEquals(token, holder(saved).pendingStagedShareOpen)
    }

    /** A token restored after the handoff window is dropped and removed from saved state. */
    @Test
    fun expiredTokenIsDroppedOnRestore() {
        val saved = SavedStateHandle()
        val stagedAt = SystemClock.elapsedRealtime() - STAGED_SHARE_OPEN_MAX_AGE_MILLIS - 1L
        holder(saved).pendingStagedShareOpen = token(stagedAt = stagedAt)
        assertNull(holder(saved).pendingStagedShareOpen)
        assertNull("an expired token must not survive another restore", holder(saved).pendingStagedShareOpen)
    }

    /** A token from a later boot clock (a reboot reset elapsed time) is never trusted. */
    @Test
    fun tokenFromTheFutureIsDroppedOnRestore() {
        val saved = SavedStateHandle()
        holder(saved).pendingStagedShareOpen = token(stagedAt = SystemClock.elapsedRealtime() + 60_000L)
        assertNull(holder(saved).pendingStagedShareOpen)
    }

    /** A saved token without its share request identity (an older build's format) is discarded. */
    @Test
    fun tokenWithoutRequestIdentityIsDiscarded() {
        val legacy =
            Bundle().apply {
                putString("account", "share-account")
                putString("group", "share-group")
                putInt("other_count", 0)
            }
        assertNull(restoreStagedShareOpen(legacy))
    }

    /** A newer share request retires the older share's deferred chat open, while the same request keeps it. */
    @Test
    fun newerShareRequestRetiresTheOlderToken() {
        val holder = holder(SavedStateHandle())
        val token = token(stagedAt = SystemClock.elapsedRealtime())
        holder.pendingStagedShareOpen = token
        holder.acceptInboundShareRequest(request("share-request"))
        assertEquals("redelivery of the same share keeps its handoff", token, holder.pendingStagedShareOpen)
        holder.acceptInboundShareRequest(request("newer-request"))
        assertNull(holder.pendingStagedShareOpen)
    }

    /** The live freshness check that the shell effect applies before opening the chat. */
    @Test
    fun freshnessBoundsTheHandoffWindow() {
        val token = token(stagedAt = 1_000L)
        assertEquals(true, token.isFresh(1_000L + STAGED_SHARE_OPEN_MAX_AGE_MILLIS))
        assertEquals(false, token.isFresh(1_000L + STAGED_SHARE_OPEN_MAX_AGE_MILLIS + 1L))
        assertEquals(false, token.isFresh(999L))
    }

    /** Builds a holder over [saved], as the Activity's ViewModel factory does after recreation. */
    private fun holder(saved: SavedStateHandle) = MainShellStateHolder(app, saved).also(holders::add)

    /** A cross-account open for the fixture share, staged at [stagedAt]. */
    private fun token(stagedAt: Long) =
        PendingStagedShareOpen(
            requestId = "share-request",
            accountRef = "share-account",
            groupIdHex = "share-group",
            otherChatCount = 1,
            stagedAtElapsedMillis = stagedAt,
        )

    /** An inbound text share with [requestId]. */
    private fun request(requestId: String): ShareRequest {
        val payload = SharePayload("shared text", emptyList(), null, true)
        return ShareRequest(payload, null, requestId)
    }

    /** No persisted drafts for this fixture. */
    private object NoDrafts : DraftPersistence {
        /** Returns no saved drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
