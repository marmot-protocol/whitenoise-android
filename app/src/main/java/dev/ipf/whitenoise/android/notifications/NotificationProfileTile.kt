package dev.ipf.whitenoise.android.notifications

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.state.AppLockController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import java.time.ZoneId

/** What the Quick Settings tile shows. */
internal sealed interface NotificationProfileTileModel {
    /** No profile exists or nobody is signed in: the tile cannot do anything. */
    data object Unavailable : NotificationProfileTileModel

    /** No profile is in force. */
    data object Off : NotificationProfileTileModel

    /** A profile is in force; [label] is its emoji and name, or null when App Lock keeps names off the panel. */
    data class On(
        val label: String?,
    ) : NotificationProfileTileModel
}

/** The tile for [state] at [nowMs]: the same answer the notification gate gives, never a second rule. */
internal fun notificationProfileTileModel(
    state: NotificationProfilesState,
    signedIn: Boolean,
    hideNames: Boolean,
    nowMs: Long,
    zone: ZoneId,
): NotificationProfileTileModel {
    if (!signedIn || state.profiles.isEmpty()) return NotificationProfileTileModel.Unavailable
    val active = activeNotificationProfile(state, nowMs, zone) ?: return NotificationProfileTileModel.Off
    return NotificationProfileTileModel.On(active.profile.label.takeUnless { hideNames })
}

/**
 * The chooser's single-choice rows: every profile by label, then Off. The checked row is the profile in force,
 * or Off when none is.
 */
internal data class NotificationProfileTileChoices(
    val profileIds: List<String>,
    val labels: List<String>,
    val checked: Int,
)

internal fun notificationProfileTileChoices(
    state: NotificationProfilesState,
    offLabel: String,
    nowMs: Long,
    zone: ZoneId,
): NotificationProfileTileChoices {
    val ids = state.profiles.map { it.id }
    val activeId = activeNotificationProfile(state, nowMs, zone)?.profile?.id
    val checked = ids.indexOf(activeId).takeIf { it >= 0 } ?: ids.size
    return NotificationProfileTileChoices(ids, state.profiles.map { it.label } + offLabel, checked)
}

/** Whether an account is signed in, from the running app when there is one, else from its saved choice. */
internal fun notificationProfileTileSignedIn(context: Context): Boolean {
    val running = (context.applicationContext as? WhiteNoiseApplication)?.initializedAppState()
    if (running != null) return running.activeAccountRef != null
    return appPreferences(context).getString(WhiteNoiseAppState.ACTIVE_ACCOUNT_KEY, null) != null
}

/** Whether White Noise App Lock is on, from the running app when there is one, else from its saved setting. */
internal fun notificationProfileTileAppLockRequired(context: Context): Boolean {
    val running = (context.applicationContext as? WhiteNoiseApplication)?.initializedAppState()
    if (running != null) return running.appLock.required
    return appPreferences(context).getBoolean(AppLockController.REQUIRE_APP_UNLOCK_KEY, false)
}

/** The app's main settings file, as WhiteNoiseAppState opens it. */
private fun appPreferences(context: Context) =
    context.applicationContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

/** How a request to add the tile ended. */
internal enum class NotificationProfileTileAddOutcome { ADDED, DECLINED, FAILED }

/** Maps StatusBarManager's add-tile result codes; added and already-added both mean the tile is there. */
internal fun notificationProfileTileAddOutcome(result: Int): NotificationProfileTileAddOutcome =
    when (result) {
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
        -> NotificationProfileTileAddOutcome.ADDED
        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> NotificationProfileTileAddOutcome.DECLINED
        else -> NotificationProfileTileAddOutcome.FAILED
    }

/** Asks Android 13+ to offer the tile for the panel; [onOutcome] runs on the main thread. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun requestAddNotificationProfileTile(
    context: Context,
    onOutcome: (NotificationProfileTileAddOutcome) -> Unit,
) {
    val manager = context.getSystemService(StatusBarManager::class.java)
    if (manager == null) {
        onOutcome(NotificationProfileTileAddOutcome.FAILED)
        return
    }
    val requested =
        runCatching {
            manager.requestAddTileService(
                ComponentName(context, NotificationProfileTileService::class.java),
                context.getString(R.string.notification_profile_tile_label),
                Icon.createWithResource(context, R.drawable.ic_notifications_paused),
                context.mainExecutor,
            ) { result -> onOutcome(notificationProfileTileAddOutcome(result)) }
        }
    if (requested.isFailure) onOutcome(NotificationProfileTileAddOutcome.FAILED)
}
