package dev.ipf.whitenoise.android.notifications

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
    private var listening: Job? = null
    private val store get() = NotificationProfilePreferences.forContext(this)

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening = CoroutineScope(Dispatchers.Main.immediate).launch { store.state.collect { updateTile() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
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
        listening?.cancel()
        listening = null
        handler.removeCallbacks(refresh)
        writes.shutdown()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (currentModel() == NotificationProfileTileModel.Unavailable) return
        if (isLocked) unlockAndRun(::openChooser) else openChooser()
    }

    /** Hand off to the normal app entry point when its lock protects profile names and controls. */
    @Suppress("DEPRECATION")
    private fun openChooser() {
        if (currentModel() == NotificationProfileTileModel.Unavailable) return
        if (notificationProfileTileAppLockRequired(this)) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            } else {
                startActivityAndCollapse(intent)
            }
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
                    write {
                        if (notificationProfileTileAppLockRequired(this) || !notificationProfileTileSignedIn(this)) {
                            false
                        } else if (profileId != null) {
                            store.turnOn(profileId, null)
                        } else {
                            store.turnOff()
                        }
                    }
                }.setNegativeButton(R.string.cancel, null)
                .create()
        showDialog(dialog)
    }

    private fun write(change: () -> Boolean) {
        runCatching {
            writes.execute {
                runCatching(change)
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
