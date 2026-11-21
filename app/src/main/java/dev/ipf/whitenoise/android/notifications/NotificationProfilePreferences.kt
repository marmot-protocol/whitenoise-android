package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.ZoneId
import java.util.Locale

/**
 * Device-local notification profiles (#3601): names, schedules and allowed chat ids, with the user's latest
 * manual on/off choice. Holds no protocol data. Reads are in memory so the notification gate can consult it
 * synchronously; every write commits durably before publishing and returns false on failure. The first read
 * on a device that has never stored any state adds the starter profiles once.
 */
@Suppress("TooManyFunctions")
internal class NotificationProfilePreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow(load(context))
    val state: StateFlow<NotificationProfilesState> = _state.asStateFlow()

    /** The profile in force now, if any. */
    fun active(
        nowMs: Long = clock(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ActiveNotificationProfile? = activeNotificationProfile(_state.value, nowMs, zone)

    /**
     * Inserts or replaces [profile] by id; call off the main thread. Rejects a blank name, an enabled invalid
     * schedule or an eleventh profile. A pause ends only when this save changes a schedule whose old or new
     * window is open now, so that edit applies at once while other edits keep the paused window quiet.
     */
    fun save(
        profile: NotificationProfile,
        nowMs: Long = clock(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean =
        mutate { current ->
            val name = normalizedNotificationProfileName(profile.name)
            val schedule = profile.schedule
            val isNew = current.profiles.none { it.id == profile.id }
            val invalid =
                name.isEmpty() ||
                    (schedule != null && schedule.enabled && !schedule.isValid) ||
                    (isNew && current.profiles.size >= MAX_NOTIFICATION_PROFILES)
            if (invalid) return@mutate null
            val saved =
                profile.copy(
                    name = name,
                    allowedChats = profile.allowedChats.map(::normalizedChat).toSet(),
                    allowedPeople = profile.allowedPeople.map(::normalizedPerson).toSet(),
                    emoji = normalizedNotificationProfileEmoji(profile.emoji),
                )
            val others = current.profiles.map { if (it.id == saved.id) saved else it }
            val profiles = if (isNew) current.profiles + saved else others
            val previous = current.profiles.firstOrNull { it.id == saved.id }?.schedule
            val endsPause =
                current.manual is NotificationProfileManual.Paused &&
                    previous != saved.schedule &&
                    listOfNotNull(previous, saved.schedule).any { scheduleWindowContaining(it, nowMs, zone) != null }
            current.copy(profiles = profiles, manual = if (endsPause) null else current.manual)
        }

    /** Removes [profileId] and any manual choice that names it; call off the main thread. */
    fun delete(profileId: String): Boolean =
        mutate { current ->
            if (current.profiles.none { it.id == profileId }) return@mutate null
            val manual = current.manual.takeUnless { it is NotificationProfileManual.On && it.profileId == profileId }
            current.copy(profiles = current.profiles.filterNot { it.id == profileId }, manual = manual)
        }

    /** Turns [profileId] on until [untilMs], or until turned off when null; call off the main thread. */
    fun turnOn(
        profileId: String,
        untilMs: Long?,
        nowMs: Long = clock(),
    ): Boolean =
        mutate { current ->
            val valid = current.profiles.any { it.id == profileId } && (untilMs == null || untilMs > nowMs)
            if (!valid) return@mutate null
            current.copy(manual = NotificationProfileManual.On(profileId, untilMs))
        }

    /**
     * Turns profiles off now; call off the main thread. Inside an open scheduled window the schedules pause
     * until the latest open window ends, so the next window still starts on time.
     */
    fun turnOff(
        nowMs: Long = clock(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean =
        mutate { current ->
            val openUntil =
                current.profiles
                    .mapNotNull { profile -> profile.schedule?.let { scheduleWindowContaining(it, nowMs, zone) } }
                    .maxOfOrNull { it.endMs }
            current.copy(manual = openUntil?.let { NotificationProfileManual.Paused(it, nowMs) })
        }

    /** Shows or hides the chat-list profile button; call off the main thread. */
    fun setShowInChatList(show: Boolean): Boolean = mutate { it.copy(showInChatList = show) }

    /** Records whether the Quick Settings tile is in the user's panel, so the add offer can step aside. */
    fun setTileAdded(added: Boolean): Boolean = mutate { it.copy(tileAdded = added) }

    /**
     * Drops allowed chats and people of local accounts that no longer exist; profiles themselves stay. Publishes
     * at once with best-effort asynchronous persistence, like the other account-retention hooks.
     */
    fun retainAccounts(accountRefs: Collection<String>) {
        val retained = accountRefs.toSet()
        synchronized(mutationLock) {
            val current = _state.value
            val profiles =
                current.profiles.map { profile ->
                    profile.copy(
                        allowedChats = profile.allowedChats.filterTo(HashSet()) { it.accountRef in retained },
                        allowedPeople = profile.allowedPeople.filterTo(HashSet()) { it.accountRef in retained },
                    )
                }
            val updated = current.copy(profiles = profiles)
            if (updated == current) return
            preferences.edit().putString(STATE_KEY, encodeProfiles(updated).toString()).apply()
            _state.value = updated
        }
    }

    /** Applies [change] to the current state and commits it; a null result rejects the change. */
    private fun mutate(change: (NotificationProfilesState) -> NotificationProfilesState?): Boolean =
        synchronized(mutationLock) {
            val current = _state.value
            val updated = change(current)?.withoutExpiredManual(clock()) ?: return@synchronized false
            val committed =
                runCatching {
                    preferences.edit().putString(STATE_KEY, encodeProfiles(updated).toString()).commit()
                }.getOrDefault(false)
            if (committed) {
                _state.value = updated
            } else {
                // commit can change SharedPreferences memory before its disk write fails.
                runCatching { preferences.edit().putString(STATE_KEY, encodeProfiles(current).toString()).apply() }
            }
            committed
        }

    /**
     * Reads the stored state. Only a state that was never stored gets the starter profiles; unreadable state
     * fails open to no profiles and is left untouched, so it never turns into a fresh seed.
     */
    private fun load(context: Context): NotificationProfilesState {
        val stored = runCatching { preferences.getString(STATE_KEY, null) }
        val raw = stored.getOrNull()
        return when {
            raw != null -> runCatching { decodeProfiles(JSONObject(raw)) }.getOrNull() ?: NotificationProfilesState()
            stored.isFailure || preferences.contains(STATE_KEY) -> NotificationProfilesState()
            else -> seedPresets(context)
        }
    }

    /** Stores the starter profiles with the seeded marker; publishing does not wait for the disk write. */
    private fun seedPresets(context: Context): NotificationProfilesState {
        val seeded =
            runCatching {
                val names = NotificationProfilePresetNames.from(context)
                NotificationProfilesState(notificationProfilePresets(names, clock()), presetsSeeded = true)
            }.getOrNull() ?: return NotificationProfilesState()
        runCatching { preferences.edit().putString(STATE_KEY, encodeProfiles(seeded).toString()).apply() }
        return seeded
    }

    companion object {
        const val PREFERENCES_NAME = "whitenoise.notification_profiles"
        private const val STATE_KEY = "state.v1"
        private val instanceLock = Any()
        private var instance: Pair<Context, NotificationProfilePreferences>? = null

        /**
         * The one store of this process, shared by the app, the notification gate and the Quick Settings tile so
         * a change from any of them is seen by all. Keyed by application context, like the other shared stores.
         */
        fun forContext(context: Context): NotificationProfilePreferences {
            val app = context.applicationContext
            return synchronized(instanceLock) {
                instance?.takeIf { it.first === app }?.second
                    ?: NotificationProfilePreferences(app)
                        .also { instance = app to it }
            }
        }
    }
}

internal fun normalizedChat(chat: NotificationProfileChat): NotificationProfileChat {
    val groupIdHex = chat.groupIdHex.trim().lowercase(Locale.ROOT)
    return chat.copy(groupIdHex = groupIdHex)
}

internal fun normalizedPerson(person: NotificationProfilePerson): NotificationProfilePerson {
    val accountIdHex = person.accountIdHex.trim().lowercase(Locale.ROOT)
    return person.copy(accountIdHex = accountIdHex)
}

private fun NotificationProfilesState.withoutExpiredManual(nowMs: Long): NotificationProfilesState {
    val live =
        when (val choice = manual) {
            is NotificationProfileManual.On -> {
                val unexpired = choice.untilMs == null || choice.untilMs > nowMs
                choice.takeIf { unexpired && profiles.any { it.id == choice.profileId } }
            }
            is NotificationProfileManual.Paused -> choice.takeIf { it.untilMs > nowMs }
            null -> null
        }
    return if (live == manual) this else copy(manual = live)
}
