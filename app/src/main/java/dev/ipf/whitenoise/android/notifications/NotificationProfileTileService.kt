package dev.ipf.whitenoise.android.notifications

import android.app.AlertDialog
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ipf.whitenoise.android.R
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TILE_REFRESH_MAX_MS = 60_000L

/**
 * Quick Settings tile for notification profiles (#3601). It shows whether a profile is on and opens a chooser
 * that turns one on until turned off, or turns profiles off. Users opt in by adding it from the tile editor.
 * Writes go through the shared store off the main thread.
 */
class NotificationProfileTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { updateTile() }
    private val writes: ExecutorService = Executors.newSingleThreadExecutor()
    private val store get() = NotificationProfilePreferences.forContext(this)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onStopListening() {
        handler.removeCallbacks(refresh)
        super.onStopListening()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        write { store.setTileAdded(true) }
    }

    override fun onTileRemoved() {
        write { store.setTileAdded(false) }
        super.onTileRemoved()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        writes.shutdown()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (currentModel() == NotificationProfileTileModel.Unavailable) return
        if (isLocked && (isSecure || notificationProfileTileAppLockRequired(this))) {
            unlockAndRun(::showChooser)
        } else {
            showChooser()
        }
    }

    /** Every profile and Off as one list; choosing applies at once and closes it. */
    private fun showChooser() {
        val choices =
            notificationProfileTileChoices(
                store.state.value,
                getString(R.string.notification_profiles_value_off),
                System.currentTimeMillis(),
                ZoneId.systemDefault(),
            )
        val dialog =
            AlertDialog
                .Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.notification_profiles_title)
                .setSingleChoiceItems(choices.labels.toTypedArray(), choices.checked) { shown, which ->
                    shown.dismiss()
                    val profileId = choices.profileIds.getOrNull(which)
                    write { if (profileId != null) store.turnOn(profileId, null) else store.turnOff() }
                }.setNegativeButton(R.string.cancel, null)
                .create()
        showDialog(dialog)
    }

    private fun write(change: () -> Boolean) {
        runCatching {
            writes.execute {
                runCatching(change)
                handler.post(refresh)
            }
        }
    }

    private fun currentModel(): NotificationProfileTileModel =
        notificationProfileTileModel(
            state = store.state.value,
            signedIn = notificationProfileTileSignedIn(this),
            hideNames = notificationProfileTileAppLockRequired(this),
            nowMs = System.currentTimeMillis(),
            zone = ZoneId.systemDefault(),
        )

    /** Paints the tile and schedules the next repaint at the next schedule or manual transition. */
    private fun updateTile() {
        handler.removeCallbacks(refresh)
        val tile = qsTile ?: return
        val model = currentModel()
        tile.label = getString(R.string.notification_profile_tile_label)
        tile.state =
            when (model) {
                NotificationProfileTileModel.Unavailable -> Tile.STATE_UNAVAILABLE
                NotificationProfileTileModel.Off -> Tile.STATE_INACTIVE
                is NotificationProfileTileModel.On -> Tile.STATE_ACTIVE
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle =
                when (model) {
                    NotificationProfileTileModel.Unavailable -> null
                    NotificationProfileTileModel.Off -> getString(R.string.notification_profiles_value_off)
                    is NotificationProfileTileModel.On -> model.label ?: getString(R.string.notification_profile_on)
                }
        }
        tile.updateTile()
        val now = System.currentTimeMillis()
        val next = nextNotificationProfileTransitionMs(store.state.value, now, ZoneId.systemDefault())
        handler.postDelayed(refresh, ((next ?: Long.MAX_VALUE) - now).coerceIn(1L, TILE_REFRESH_MAX_MS))
    }
}
