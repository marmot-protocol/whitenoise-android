package dev.ipf.whitenoise.android.notifications

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.core.content.pm.ShortcutInfoCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationNotificationSettingsPreparationTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** One pass reuses the existing shortcut name and resolves exact active child versions. */
    @Test
    fun preparationPublishesOnceAndResolvesExactGroupTargets() =
        runTest {
            val platform = FakePlatform()
            val shortcutId = checkNotNull(conversationShortcutId("account-a", "group-a"))
            platform.shortcuts +=
                ShortcutInfoCompat
                    .Builder(context, shortcutId)
                    .setShortLabel("Green Orca")
                    .setLongLabel("Green Orca")
                    .setIntent(Intent("test.existing"))
                    .build()
            val preparer = preparer(platform)

            val result =
                preparer.prepare(
                    context,
                    request(
                        title = "npub1fallback",
                        requestedParents =
                            listOf(
                                NotificationChannelSpec.GROUP_MESSAGES,
                                NotificationChannelSpec.AGENT_ACTIVITY,
                            ),
                        vibrationPattern = ConversationVibrationPattern.DOUBLE,
                    ),
                ) as ConversationNotificationSettingsPreparation.Ready

            assertEquals(1, platform.lookups)
            assertEquals(1, platform.pushes)
            assertEquals(1, platform.updates)
            assertEquals("Green Orca", platform.lastPushed?.longLabel.toString())
            assertEquals(
                "${NotificationChannelSpec.GROUP_MESSAGES.id}:DOUBLE",
                result.targetsByParentChannelId.getValue(NotificationChannelSpec.GROUP_MESSAGES.id).channelId,
            )
            assertEquals(
                "${NotificationChannelSpec.AGENT_ACTIVITY.id}:SYSTEM_DEFAULT",
                result.targetsByParentChannelId.getValue(NotificationChannelSpec.AGENT_ACTIVITY.id).channelId,
            )
        }

    /** DM and group preparation select their distinct required message parents. */
    @Test
    fun preparationScopesRequiredMessageChannelToConversationKind() =
        runTest {
            val groupPlatform = FakePlatform()
            val dmPlatform = FakePlatform()

            val group = preparer(groupPlatform).prepare(context, request(isDm = false))
            val dm = preparer(dmPlatform).prepare(context, request(isDm = true))

            assertTrue(
                NotificationChannelSpec.GROUP_MESSAGES.id in
                    (group as ConversationNotificationSettingsPreparation.Ready).targetsByParentChannelId,
            )
            assertTrue(
                NotificationChannelSpec.DIRECT_MESSAGES.id in
                    (dm as ConversationNotificationSettingsPreparation.Ready).targetsByParentChannelId,
            )
        }

    /** Recreated UI owners repeat idempotent preparation against the same Android state. */
    @Test
    fun processRecreationReusesThePreparedShortcutAndChannelIdentity() =
        runTest {
            val platform = FakePlatform(persistPushedShortcut = true)
            val first =
                preparer(platform).prepare(context, request()) as ConversationNotificationSettingsPreparation.Ready
            val recreated =
                preparer(platform).prepare(context, request()) as ConversationNotificationSettingsPreparation.Ready

            assertEquals(2, platform.lookups)
            assertEquals(2, platform.pushes)
            assertEquals(1, platform.updates)
            assertEquals(1, platform.shortcuts.size)
            assertEquals(
                first.targetsByParentChannelId.getValue(NotificationChannelSpec.GROUP_MESSAGES.id),
                recreated.targetsByParentChannelId
                    .getValue(NotificationChannelSpec.GROUP_MESSAGES.id)
                    .copy(operationId = first.operationId),
            )
        }

    /** The launch boundary dispatches exact ids without invoking preparation dependencies. */
    @Test
    fun preparedTapOnlyLaunchesTheExactIntentAndLogsOpaqueTiming() =
        runTest {
            val platform = FakePlatform()
            val clock = TestClock()
            val logs = mutableListOf<String>()
            val trace = ConversationNotificationSettingsTrace(elapsedRealtime = clock::read, logger = logs::add)
            val preparer =
                ConversationNotificationSettingsPreparer(
                    platform = platform,
                    dispatcher = Dispatchers.Unconfined,
                    trace = trace,
                )
            val preparation = preparer.prepare(context, request()) as ConversationNotificationSettingsPreparation.Ready
            val target = preparation.targetsByParentChannelId.getValue(NotificationChannelSpec.GROUP_MESSAGES.id)
            val countersBeforeClick = platform.counters()
            val recordingContext = RecordingContext(context)

            clock.advance(7L)
            val launch = openPreparedConversationNotificationSettings(recordingContext, target, trace)

            assertTrue(launch.opened)
            assertEquals(countersBeforeClick, platform.counters())
            assertEquals(1, recordingContext.started.size)
            assertEquals(target.channelId, recordingContext.started.single().getStringExtra(Settings.EXTRA_CHANNEL_ID))
            assertEquals(
                target.conversationShortcutId,
                recordingContext.started.single().getStringExtra(Settings.EXTRA_CONVERSATION_ID),
            )
            assertTrue(logs.any { line -> "stage=click_received" in line })
            assertTrue(logs.any { line -> "stage=start_activity" in line })
            assertTrue(logs.all { line -> "account-a" !in line && "group-a" !in line && "Green Orca" !in line })
        }

    /** Preparation failure retains the app-settings/details fallback without retrying preparation. */
    @Test
    fun preparationFailureLaunchesOnlyTheFallbackChain() =
        runTest {
            val platform = FakePlatform(failLookup = true)
            val preparer = preparer(platform)
            val failed = preparer.prepare(context, request()) as ConversationNotificationSettingsPreparation.Failed
            val recordingContext = RecordingContext(context)

            val launch = openConversationNotificationSettingsFallback(recordingContext, failed.operationId)

            assertTrue(launch.opened)
            assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, recordingContext.started.single().action)
            assertEquals(1, platform.lookups)
            assertEquals(0, platform.pushes)
            assertEquals(0, platform.channelEnsures)
        }

    /** A rejected scoped intent reports the app-settings fallback so the UI can explain it. */
    @Test
    fun rejectedScopedIntentReportsFallbackUsage() {
        val recordingContext = FirstIntentRejectingContext(context)
        val target =
            PreparedConversationNotificationSettingsTarget(
                channelId = "active-channel",
                conversationShortcutId = "shortcut",
                operationId = 17L,
            )

        val launch = openPreparedConversationNotificationSettings(recordingContext, target)

        assertTrue(launch.opened)
        assertTrue(launch.usedFallback)
        assertEquals(2, recordingContext.attempts)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, recordingContext.started.single().action)
    }

    /** Cancellation between Binder-backed stages prevents later shortcut or channel mutations. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledPreparationDoesNotContinueIntoPushOrChannelEnsure() =
        runTest {
            val lookupStarted = CompletableDeferred<Unit>()
            val platform = FakePlatform(lookupStarted = lookupStarted, suspendLookup = true)
            val preparer =
                ConversationNotificationSettingsPreparer(
                    platform = platform,
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val job = launch { preparer.prepare(context, request()) }
            lookupStarted.await()

            job.cancelAndJoin()

            assertEquals(0, platform.pushes)
            assertEquals(0, platform.channelEnsures)
        }

    /** Rapid taps coalesce until resume, while dispatch failure permits an immediate retry. */
    @Test
    fun launchGateCoalescesRapidTapsAndResetsAtLifecycleBoundaries() {
        val gate = ConversationNotificationSettingsLaunchGate()

        assertTrue(gate.tryBegin())
        assertFalse(gate.tryBegin())
        gate.onResumed()
        assertTrue(gate.tryBegin())
        gate.onLaunchFailed()
        assertTrue(gate.tryBegin())
    }

    /** Builds a deterministic preparer that executes inline for focused unit tests. */
    private fun preparer(platform: FakePlatform) =
        ConversationNotificationSettingsPreparer(
            platform = platform,
            dispatcher = Dispatchers.Unconfined,
        )

    /** Creates the common scoped request while exposing dimensions varied by each test. */
    private fun request(
        isDm: Boolean = false,
        title: String = "Green Orca",
        requestedParents: List<NotificationChannelSpec> = emptyList(),
        vibrationPattern: ConversationVibrationPattern = ConversationVibrationPattern.SYSTEM_DEFAULT,
    ) = ConversationNotificationSettingsPreparationRequest(
        accountRef = "account-a",
        groupIdHex = "group-a",
        isDm = isDm,
        conversationTitle = title,
        conversationAvatarUrl = null,
        primaryVibrationPattern = vibrationPattern,
        requestedParents = requestedParents,
    )

    private data class PlatformCounters(
        val lookups: Int,
        val pushes: Int,
        val updates: Int,
        val channelEnsures: Int,
    )

    private class FakePlatform(
        private val failLookup: Boolean = false,
        private val persistPushedShortcut: Boolean = false,
        private val lookupStarted: CompletableDeferred<Unit>? = null,
        private val suspendLookup: Boolean = false,
    ) : ConversationNotificationSettingsPlatform {
        val shortcuts = mutableListOf<ShortcutInfoCompat>()
        var lookups = 0
        var pushes = 0
        var updates = 0
        var channelEnsures = 0
        var lastPushed: ShortcutInfoCompat? = null

        /** Records lookup order and optionally holds the cancellable Binder boundary. */
        override suspend fun dynamicShortcuts(context: Context): List<ShortcutInfoCompat> {
            lookups += 1
            lookupStarted?.complete(Unit)
            if (suspendLookup) awaitCancellation()
            check(!failLookup) { "lookup failed" }
            return shortcuts.toList()
        }

        /** Records whether the preparer selected first publication or repeat update. */
        override suspend fun publishShortcut(
            context: Context,
            shortcut: ShortcutInfoCompat,
            existing: Boolean,
        ) {
            pushes += 1
            if (existing) updates += 1
            lastPushed = shortcut
            if (persistPushedShortcut) {
                shortcuts.removeAll { existing -> existing.id == shortcut.id }
                shortcuts += shortcut
            }
        }

        /** Returns a deterministic active id encoding the requested vibration version. */
        override suspend fun ensureChannel(
            context: Context,
            parent: NotificationChannelSpec,
            shortcutId: String,
            conversationTitle: String,
            vibrationPattern: ConversationVibrationPattern,
        ): String {
            channelEnsures += 1
            return "${parent.id}:${vibrationPattern.name}"
        }

        /** Snapshots mutation counters so a pure tap can prove it did no preparation. */
        fun counters() = PlatformCounters(lookups, pushes, updates, channelEnsures)
    }

    private class RecordingContext(
        base: Context,
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()

        /** Captures the exact preferred intent while simulating a successful handler. */
        override fun startActivity(intent: Intent) {
            started += Intent(intent)
        }
    }

    private class FirstIntentRejectingContext(
        base: Context,
    ) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        var attempts = 0

        /** Rejects only the scoped intent, then accepts and records the fallback. */
        override fun startActivity(intent: Intent) {
            attempts += 1
            if (attempts == 1) throw ActivityNotFoundException("scoped settings unavailable")
            started += Intent(intent)
        }
    }

    private class TestClock {
        private var nowMs = 100L

        /** Supplies deterministic monotonic time to privacy-safe trace assertions. */
        fun read(): Long = nowMs

        /** Advances the deterministic clock without wall-clock sleeps. */
        fun advance(durationMs: Long) {
            nowMs += durationMs
        }
    }
}
