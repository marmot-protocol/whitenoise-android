package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatMutePreferencesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Before
    fun clearPreferences() {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsToAllMessages() {
        val prefs = ChatMutePreferences(context)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
    }

    @Test
    fun legacyMutedConversationLoadsAsNothing() {
        context
            .getSharedPreferences("whitenoise.chat_mute", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("mutedConversations", setOf("account-a|group-a"))
            .commit()

        val prefs = ChatMutePreferences(context)

        assertEquals(ChatNotifyMode.NONE, prefs.mode("account-a", "group-a"))
        assertTrue(prefs.isMuted("account-a", "group-a"))
    }

    @Test
    fun mutePersistsPerAccountGroup() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)

        assertTrue(prefs.isMuted("account-a", "group-a"))
        assertFalse(prefs.isMuted("account-a", "group-b"))
        assertFalse(prefs.isMuted("account-b", "group-a"))
        assertEquals(mapOf("account-a|group-a" to ChatNotifyMode.NONE), prefs.state.value.notificationModes)

        val reloaded = ChatMutePreferences(context)
        assertTrue(reloaded.isMuted("account-a", "group-a"))
    }

    @Test
    fun unmuteRemovesCompositeKey() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted("account-a", "group-a", muted = true)
        prefs.setMuted("account-a", "group-a", muted = false)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
    }

    @Test
    fun mentionOnlyModePersistsPerAccountGroup() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)

        assertEquals(ChatNotifyMode.MENTIONS_ONLY, prefs.mode("account-a", "group-a"))
        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-b"))
        assertFalse(prefs.isMuted("account-a", "group-a"))

        val reloaded = ChatMutePreferences(context)
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, reloaded.mode("account-a", "group-a"))
    }

    @Test
    fun changingModeReplacesThePreviousOverride() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)
        prefs.setMode("account-a", "group-a", ChatNotifyMode.NONE)
        prefs.setMode("account-a", "group-a", ChatNotifyMode.ALL)

        assertEquals(ChatNotifyMode.ALL, prefs.mode("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
    }

    @Test
    fun compositeKeyTrimsBlankIds() {
        val prefs = ChatMutePreferences(context)

        prefs.setMuted(" account-a ", " group-a ", muted = true)

        assertTrue(prefs.isMuted("account-a", "group-a"))
    }

    @Test
    fun blankAccountOrGroupIsIgnored() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("", "group-a", ChatNotifyMode.NONE)
        prefs.setMode("account-a", "   ", ChatNotifyMode.MENTIONS_ONLY)

        assertFalse(prefs.isMuted("account-a", "group-a"))
        assertEquals(emptyMap<String, ChatNotifyMode>(), prefs.state.value.notificationModes)
    }

    @Test
    fun notificationModesAndMutedKeysPublishAsOneState() {
        val prefs = ChatMutePreferences(context)

        prefs.setMode("account-a", "group-a", ChatNotifyMode.MENTIONS_ONLY)
        prefs.setMode("account-a", "group-b", ChatNotifyMode.NONE)

        val state = prefs.state.value
        assertEquals(
            mapOf(
                "account-a|group-a" to ChatNotifyMode.MENTIONS_ONLY,
                "account-a|group-b" to ChatNotifyMode.NONE,
            ),
            state.notificationModes,
        )
        assertEquals(setOf("account-a|group-b"), state.mutedConversations)
    }

    @Test
    fun mutationAndPersistenceStayInsideOneSerializedStateUpdate() {
        val source = chatMutePreferencesSource().readText()
        val setMode = source.functionBody("setMode")
        val publishLocked = source.functionBody("publishLocked")

        assertTrue("mode updates must be serialized", "synchronized(mutationLock)" in setMode)
        assertTrue("mutations must route through the atomic publisher", "publishLocked(" in setMode)
        assertTrue(
            "mode and muted projections must publish together",
            "_state.value = ChatNotificationState(modes)" in publishLocked,
        )
        assertFalse("there must not be a second mutable muted flow", "_mutedConversations" in source)
    }

    @Test
    fun concurrentModeUpdatesRetainEveryStateAndPersistedKey() {
        val prefs = ChatMutePreferences(context)
        val writerCount = 32
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val expectedModes =
            (0 until writerCount).associate { index ->
                "account-a|group-$index" to
                    if (index % 2 == 0) ChatNotifyMode.NONE else ChatNotifyMode.MENTIONS_ONLY
            }
        val futures =
            (0 until writerCount).map { index ->
                executor.submit {
                    start.await()
                    prefs.setMode(
                        accountRef = "account-a",
                        groupIdHex = "group-$index",
                        mode = expectedModes.getValue("account-a|group-$index"),
                    )
                }
            }

        try {
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        val expectedMuted = expectedModes.filterValues { it == ChatNotifyMode.NONE }.keys
        assertEquals(expectedModes, prefs.state.value.notificationModes)
        assertEquals(expectedMuted, prefs.state.value.mutedConversations)

        val reloaded = ChatMutePreferences(context)
        assertEquals(expectedModes, reloaded.state.value.notificationModes)
        assertEquals(expectedMuted, reloaded.state.value.mutedConversations)
    }

    @Test
    fun resolveExpiredMutesRestoresElapsedEntriesOnly() {
        val modes = mapOf("k1" to ChatNotifyMode.NONE, "k2" to ChatNotifyMode.NONE)
        val expiries =
            mapOf(
                "k1" to MuteExpiry(100L, ChatNotifyMode.ALL),
                "k2" to MuteExpiry(300L, ChatNotifyMode.MENTIONS_ONLY),
            )
        val (resolved, remaining, changed) = resolveExpiredMutes(modes, expiries, now = 200L)
        assertTrue(changed)
        // k1 elapsed → restored to ALL (dropped from the modes map); k2 stays.
        assertEquals(mapOf("k2" to ChatNotifyMode.NONE), resolved)
        assertEquals(mapOf("k2" to MuteExpiry(300L, ChatNotifyMode.MENTIONS_ONLY)), remaining)
    }

    @Test
    fun resolveExpiredMutesLeavesUnelapsedUntouched() {
        val modes = mapOf("k" to ChatNotifyMode.NONE)
        val expiries = mapOf("k" to MuteExpiry(500L, ChatNotifyMode.ALL))
        val (resolved, remaining, changed) = resolveExpiredMutes(modes, expiries, now = 100L)
        assertFalse(changed)
        assertEquals(modes, resolved)
        assertEquals(expiries, remaining)
    }

    @Test
    fun muteForSilencesNowAndRestoresPriorModeAfterExpiry() {
        var clock = 1_000L
        val prefs = ChatMutePreferences(context, now = { clock })
        prefs.setMode("a", "g", ChatNotifyMode.MENTIONS_ONLY)

        prefs.muteFor("a", "g", durationMillis = 500L)
        assertTrue(prefs.isMuted("a", "g"))
        assertEquals(1_500L, prefs.muteExpiryMillis("a", "g"))

        clock = 1_600L
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, prefs.mode("a", "g"))
        assertFalse(prefs.isMuted("a", "g"))
        assertEquals(null, prefs.muteExpiryMillis("a", "g"))
    }

    @Test
    fun permanentMuteHasNoExpiry() {
        val prefs = ChatMutePreferences(context, now = { 0L })
        prefs.muteFor("a", "g", durationMillis = 0L)
        assertTrue(prefs.isMuted("a", "g"))
        assertEquals(null, prefs.muteExpiryMillis("a", "g"))
    }

    @Test
    fun explicitModeChoiceCancelsAPendingTimedMute() {
        val prefs = ChatMutePreferences(context, now = { 0L })
        prefs.muteFor("a", "g", durationMillis = 10_000L)
        assertEquals(10_000L, prefs.muteExpiryMillis("a", "g"))

        prefs.setMode("a", "g", ChatNotifyMode.ALL)
        assertEquals(null, prefs.muteExpiryMillis("a", "g"))
        assertEquals(ChatNotifyMode.ALL, prefs.mode("a", "g"))
    }

    @Test
    fun extendingATimedMuteKeepsTheOriginalRestoreMode() {
        var clock = 0L
        val prefs = ChatMutePreferences(context, now = { clock })
        prefs.setMode("a", "g", ChatNotifyMode.MENTIONS_ONLY)
        prefs.muteFor("a", "g", durationMillis = 100L)
        // Re-mute while still muted — restore mode must remain MENTIONS_ONLY.
        prefs.muteFor("a", "g", durationMillis = 1_000L)

        clock = 2_000L
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, prefs.mode("a", "g"))
    }

    @Test
    fun timedMuteExpiryPersistsAndRestoresAcrossReload() {
        var clock = 1_000L
        ChatMutePreferences(context, now = { clock }).muteFor("a", "g", durationMillis = 500L)

        // A fresh instance simulates a restart; the clock is now past expiry.
        clock = 2_000L
        val reloaded = ChatMutePreferences(context, now = { clock })
        assertEquals(ChatNotifyMode.ALL, reloaded.mode("a", "g"))
        assertFalse(reloaded.isMuted("a", "g"))
    }

    @Test
    fun timedMuteOnAPermanentlyMutedChatUnmutesAfterExpiry() {
        var clock = 0L
        val prefs = ChatMutePreferences(context, now = { clock })
        prefs.setMuted("a", "g", true) // permanent NONE

        prefs.muteFor("a", "g", durationMillis = 1_000L)
        assertTrue(prefs.isMuted("a", "g"))

        clock = 2_000L
        // A timed mute must not restore to permanent mute — it unmutes.
        assertEquals(ChatNotifyMode.ALL, prefs.mode("a", "g"))
        assertFalse(prefs.isMuted("a", "g"))
    }

    @Test
    fun restoreModePersistsByNameAndLegacyOrdinalStillDecodes() {
        // New writes encode the stable mode name, never the reorder-fragile ordinal.
        val encoded =
            ChatMutePreferences.encodeMuteExpiry(
                mapOf("a|g" to MuteExpiry(10_000L, ChatNotifyMode.MENTIONS_ONLY)).entries.first(),
            )
        assertTrue("expected the mode name in the blob", encoded.contains("MENTIONS_ONLY"))

        // A legacy ordinal-encoded blob (ordinal 1 = MENTIONS_ONLY) still decodes.
        // The persisted fields are NUL-delimited (a separator no account label can
        // contain); an isolated prefs keeps a sibling test's async write from racing.
        val sep = "\u0000"
        val legacyPrefs = context.getSharedPreferences("test.legacy.mute", Context.MODE_PRIVATE)
        legacyPrefs
            .edit()
            .clear()
            .putStringSet("mutedConversations", setOf("a|g2"))
            .putStringSet("muteExpiries", setOf("5000${sep}1${sep}a|g2"))
            .commit()
        val reloaded = ChatMutePreferences(context, preferences = legacyPrefs, now = { 9_000L })
        assertEquals(ChatNotifyMode.MENTIONS_ONLY, reloaded.mode("a", "g2"))
    }

    @Test
    fun stateEmitsWhenATimedMuteElapsesViaScheduler() =
        runTest {
            val prefs = ChatMutePreferences(context, now = { testScheduler.currentTime }, scope = backgroundScope)
            prefs.muteFor("a", "g", durationMillis = 1_000L)
            assertTrue(
                prefs.state.value.mutedConversations
                    .contains("a|g"),
            )

            advanceTimeBy(1_001L)
            runCurrent()
            // The flow itself reflects the restore — no mode()/isMuted() call.
            assertFalse(
                "the state flow must emit the restore without a getter call",
                prefs.state.value.mutedConversations
                    .contains("a|g"),
            )
        }

    @Test
    fun foregroundActivationResolvesAMuteThatElapsedWhileTheSchedulerWasFrozen() =
        runTest {
            // `now` is the wall clock; the scheduler's delay runs on virtual time.
            // Advancing one without the other models device deep sleep: the mute
            // elapses in real time while Handler-uptime delay stays frozen.
            var clock = 0L
            val prefs = ChatMutePreferences(context, now = { clock }, scope = backgroundScope)
            prefs.muteFor("a", "g", durationMillis = 1_000L)
            assertTrue("a|g" in prefs.state.value.mutedConversations)

            clock = 2_000L
            runCurrent()
            assertTrue(
                "a frozen scheduler leaves the mute visibly active",
                "a|g" in prefs.state.value.mutedConversations,
            )

            prefs.resolveExpiredNow()
            assertFalse(
                "resolveExpiredNow must restore state from the wall clock on resume",
                "a|g" in prefs.state.value.mutedConversations,
            )
        }

    @Test
    fun systemTimeChangeResolvesAForwardWallClockJumpWithoutSchedulerTimeAdvancing() =
        runTest {
            var clock = 0L
            val prefs = ChatMutePreferences(context, now = { clock }, scope = backgroundScope)
            prefs.muteFor("a", "g", durationMillis = 8_000L)
            assertTrue("a|g" in prefs.state.value.mutedConversations)

            // ACTION_TIME_CHANGED routes to resolveExpiredNow(). Model automatic
            // time sync while the app remains foreground by advancing only wall
            // time, leaving the uptime-backed coroutine delay untouched.
            clock = 9_000L
            prefs.resolveExpiredNow()

            assertFalse(
                "a wall-clock change past expiry must restore the raw state flow",
                "a|g" in prefs.state.value.mutedConversations,
            )
        }

    @Test
    fun aTimerWakingBeforeExpiryReArmsInsteadOfDroppingTheMute() =
        runTest {
            var clock = 0L
            val prefs = ChatMutePreferences(context, now = { clock }, scope = backgroundScope)
            prefs.muteFor("a", "g", durationMillis = 1_000L)

            // The delay fires, but the wall clock moved backward so the expiry is
            // not actually reached — the timer must re-arm, not exit.
            clock = -5_000L
            advanceTimeBy(1_001L)
            runCurrent()
            assertTrue(
                "an early wake must not drop the still-pending mute",
                "a|g" in prefs.state.value.mutedConversations,
            )

            clock = 1_500L
            advanceTimeBy(7_000L)
            runCurrent()
            assertFalse(
                "the re-armed timer must restore the state once the expiry passes",
                "a|g" in prefs.state.value.mutedConversations,
            )
        }

    private fun chatMutePreferencesSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/ChatMutePreferences.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/ChatMutePreferences.kt"),
        ).firstOrNull(File::exists) ?: error("Missing ChatMutePreferences.kt")
}
