package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.EffectiveConversationVibration
import dev.ipf.whitenoise.android.notifications.NotificationChannelWriteGuard
import dev.ipf.whitenoise.android.notifications.ProfileNotificationChannels
import dev.ipf.whitenoise.android.notifications.ProfileNotificationMode
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverride
import dev.ipf.whitenoise.android.notifications.ProfileNotificationOverridePreferences
import dev.ipf.whitenoise.android.notifications.notificationChannelSettingsIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Presentation state for a single mounted profile's local Android alert controls. */
internal data class ProfileNotificationOverrideState(
    val selection: ProfileNotificationOverride = ProfileNotificationOverride(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    val choosingVibration: Boolean = false,
    val effectiveVibration: EffectiveConversationVibration? = null,
)

/** Account-bound screen owner; disposal or identity changes reject late writes and OS launches. */
internal class ProfileNotificationOverrideController(
    context: Context,
    private val preferences: ProfileNotificationOverridePreferences,
    private val account: String,
    private val author: String,
    private val scope: CoroutineScope,
    private val ownerIsCurrent: () -> Boolean,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val context = context.applicationContext
    private val channels = ProfileNotificationChannels(context)

    @Volatile private var active = true
    private var generation = 0L
    private val mutableState = MutableStateFlow(ProfileNotificationOverrideState(preferences.get(account, author)))
    val state = mutableState.asStateFlow()

    /** End this page's authority before any suspended platform preparation may return. */
    fun dispose() {
        active = false
    }

    /** Re-read Android-owned settings on resume and refresh only an already-existing channel label. */
    fun refresh(title: String) {
        if (!current() || mutableState.value.busy) return
        val request = ++generation
        val writeEpoch = NotificationChannelWriteGuard.captureEpoch()
        val writeAllowed = { current() && NotificationChannelWriteGuard.isCurrent(writeEpoch, account) }
        scope.launch {
            val selection = preferences.get(account, author)
            val effective =
                withContext(ioDispatcher) {
                    if (selection.mode == ProfileNotificationMode.CUSTOM && writeAllowed()) {
                        channels.existing(
                            accountRef = account,
                            author = author,
                            pattern = selection.vibration,
                            title = title,
                            isWriteAllowed = writeAllowed,
                        )
                        channels.effectiveVibration(account, author, selection.vibration)
                    } else {
                        null
                    }
                }
            if (writeAllowed() && request == generation && !mutableState.value.busy) {
                mutableState.value = mutableState.value.copy(selection = selection, effectiveVibration = effective)
            }
        }
    }

    /**
     * Default/mute changes only local routing so dormant OS settings survive routine resets,
     * while destructive account Sign Out and Sign Out & Wipe explicitly delete the leaving
     * account's profile channels.
     */
    fun selectMode(mode: ProfileNotificationMode) =
        change { writeAllowed ->
            check(mode != ProfileNotificationMode.CUSTOM)
            val selection = preferences.get(account, author).copy(mode = mode)
            check(preferences.set(account, author, selection, writeAllowed))
            null
        }

    /** User intent explicitly authorizes lazy channel creation and the exact channel deep link. */
    fun customize(
        title: String,
        pattern: ConversationVibrationPattern? = null,
        openSettings: Boolean = true,
    ) = change { writeAllowed ->
        val previous = preferences.get(account, author)
        val selectedPattern = pattern ?: previous.vibration
        val channelId =
            channels.customize(
                accountRef = account,
                author = author,
                title = title,
                pattern = selectedPattern,
                previous = previous.vibration,
                isWriteAllowed = writeAllowed,
            )
        check(writeAllowed())
        check(
            preferences.set(
                account,
                author,
                ProfileNotificationOverride(ProfileNotificationMode.CUSTOM, selectedPattern),
                writeAllowed,
            ),
        )
        channelId.takeIf { openSettings }
    }

    /** Dialog visibility is transient screen state, separate from the persisted alert choice. */
    fun chooseVibration(show: Boolean) {
        val state = mutableState.value
        if (current() && !state.busy) mutableState.value = state.copy(choosingVibration = show)
    }

    /** Run blocking platform/storage work off-main, then validate ownership again before launching Android UI. */
    private fun change(operation: (() -> Boolean) -> String?) {
        if (!current() || mutableState.value.busy) return
        val writeEpoch = NotificationChannelWriteGuard.captureEpoch()
        val writeAllowed = { current() && NotificationChannelWriteGuard.isCurrent(writeEpoch, account) }
        generation += 1
        mutableState.value = mutableState.value.copy(busy = true, failed = false, choosingVibration = false)
        scope.launch {
            try {
                val (channel, selection, vibration) =
                    withContext(ioDispatcher) {
                        check(writeAllowed())
                        val target = operation(writeAllowed)
                        val selected = preferences.get(account, author)
                        val effective =
                            if (selected.mode == ProfileNotificationMode.CUSTOM) {
                                channels.effectiveVibration(account, author, selected.vibration)
                            } else {
                                null
                            }
                        Triple(target, selected, effective)
                    }
                if (writeAllowed()) {
                    mutableState.value = mutableState.value.copy(selection = selection, effectiveVibration = vibration)
                    if (channel != null) context.startActivity(notificationChannelSettingsIntent(context, channel))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                if (writeAllowed()) mutableState.value = mutableState.value.copy(failed = true)
            } finally {
                if (current()) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    /** Checks both entry disposal and the live account/profile owner before any queued side effect. */
    private fun current(): Boolean = active && ownerIsCurrent()
}
