package dev.ipf.whitenoise.android.notifications

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.state.AndroidKeystoreSecretKeyProvider
import dev.ipf.whitenoise.android.state.KeystoreSecureStore
import dev.ipf.whitenoise.android.state.LegacySecurePreferences
import dev.ipf.whitenoise.android.state.SecureStoreKeyProvider

private const val PREFS_NAME = "whitenoise.push.tokens"
private const val SECURE_PREFS_NAME = "whitenoise.push.tokens.secure"

// Plaintext prefs used only when the keystore-backed store can't be opened on
// this device. Distinct file so it never aliases the encrypted store's bytes.
private const val FALLBACK_PREFS_NAME = "whitenoise.push.tokens.fallback"
internal const val KEY_FCM_TOKEN = "fcm_token"
internal const val KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC = "pending_native_push_registration_sync"
internal const val KEY_PENDING_PUSH_WAKE_CATCH_UP = "pending_push_wake_catch_up"
internal const val KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION = "pending_push_wake_catch_up_generation"
internal const val KEY_PUSH_WAKE_CATCH_UP_SEQUENCE = "push_wake_catch_up_sequence"
internal const val KEY_WAKE_ATTEMPTS = "push_wake_attempts"
internal const val KEY_WAKE_RETRY_AT = "push_wake_retry_at"
internal const val PUSH_WAKE_MAX_ATTEMPTS = 4
internal const val PUSH_WAKE_BACKOFF_MS = 30_000L
private const val PUSH_WAKE_MAX_BACKOFF_MS = 240_000L
internal const val KEY_PENDING_CLEARS = "pending_clears"
internal const val KEY_PENDING_DISABLES = "pending_native_push_disables"
private const val NO_PENDING_PUSH_WAKE_CATCH_UP = 0L
private const val LEGACY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION = 1L

/** Snapshot needed to undo a reserved attempt that loses its lifecycle fence before native work starts. */
internal data class PushWakeAttemptClaim(
    val priorAttempts: Int,
    val priorRetryAtMs: Long,
)

/**
 * Persisted FCM token cache. The [MarmotFirebaseMessagingService] writes here
 * on every token rotation; [dev.ipf.whitenoise.android.state.WhiteNoiseAppState] reads
 * the last value when calling `upsertPushRegistration` so the registration
 * survives an app restart even before Firebase delivers a fresh
 * `onNewToken` callback.
 *
 * **Thread safety.** `FirebaseMessagingService.onNewToken` runs on a Firebase
 * background thread, not Main — so the old "every caller is on
 * `Dispatchers.Main.immediate`" confinement was false, and the
 * read-modify-write mutators below could lose updates or resurrect a
 * just-cleared token under concurrent sign-out. Every mutator (and the
 * read-modify-write read it depends on) now serializes through [lock], so the
 * store is safe to call from any thread. See #167.
 */
class PushTokenStore(
    private val preferences: SharedPreferences,
    private val recoveryStatus: () -> PushStorageRecovery = { PushStorageRecovery.Ready },
    private val retryImport: () -> Unit = {},
) {
    /** Observes retained storage without interpreting unavailable data as an empty cleanup queue. */
    internal fun recoveryState(retry: Boolean = false): PushStorageRecovery =
        synchronized(LOCK) {
            try {
                if (retry) retryImport()
                preferences.all // Resolve lazy migration before inspecting durable safety state.
                recoveryStatus()
            } catch (_: Exception) {
                PushStorageRecovery.StorageUnavailable
            }
        }

    /** Unknown legacy cleanup or an orphaned historical fence holds every native registration path. */
    internal fun registrationAllowed(): Boolean = recoveryState() == PushStorageRecovery.Ready

    /** Returns only a non-blank cached FCM token; absence requires fresh token acquisition. */
    fun lastToken(): String? = preferences.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }

    /** Serializes token rotation with cleanup across every store instance in this process. */
    fun setToken(token: String) {
        synchronized(LOCK) {
            preferences.edit().putString(KEY_FCM_TOKEN, token).apply()
        }
    }

    /** Removes only the token, retaining outstanding registration and push-wake retry obligations. */
    fun clear() {
        synchronized(LOCK) {
            preferences.edit().remove(KEY_FCM_TOKEN).apply()
        }
    }

    /**
     * True when a rotated token was persisted while the app runtime could not
     * be reached and the best-effort foreground-service nudge has not yet been
     * confirmed by a native-push registration sync. This is intentionally
     * process-durable: if Android rejects the service start, the next AppState
     * sync trigger can still drain the work instead of silently falling back to
     * "persist only". See #755.
     */
    fun nativePushRegistrationSyncPending(): Boolean = preferences.getBoolean(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC, false)

    /** Commits the retry marker before a token-rotation service nudge can be rejected or killed. */
    @SuppressLint("ApplySharedPref")
    fun recordPendingNativePushRegistrationSync() {
        synchronized(LOCK) {
            if (!preferences.edit().putBoolean(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC, true).commit()) {
                throw PushTokenStorageException()
            }
        }
    }

    /** Acknowledges a completed all-account registration sync without clearing unrelated push state. */
    fun clearPendingNativePushRegistrationSync() {
        synchronized(LOCK) {
            preferences.edit().remove(KEY_PENDING_NATIVE_PUSH_REGISTRATION_SYNC).apply()
        }
    }

    /**
     * True when a MIP-05 push wake arrived but Android rejected the foreground
     * stream start before the notification runtime could fetch/drain it. The
     * next runtime-start, connectivity, or foreground catch-up trigger retries
     * the fetch instead of losing that wake. See #1160.
     */
    fun pushWakeCatchUpPending(): Boolean = pendingPushWakeCatchUpGeneration() != NO_PENDING_PUSH_WAKE_CATCH_UP

    /**
     * Monotonic pending-marker generation observed by drains. A drain must clear
     * only the generation it saw before catch-up; if another rejected wake lands
     * during the relay fetch, the newer generation stays pending.
     */
    fun pendingPushWakeCatchUpGeneration(): Long = synchronized(LOCK) { pendingPushWakeCatchUpGenerationLocked() }

    /**
     * Durably records a distinct pending wake before the background service
     * returns. The retained sequence makes later wakes distinguishable even
     * after an earlier marker was cleared.
     */
    @SuppressLint("ApplySharedPref")
    fun recordPendingPushWakeCatchUp(): Boolean =
        synchronized(LOCK) {
            val previousGeneration =
                maxOf(
                    pendingPushWakeCatchUpGenerationLocked(),
                    preferences.getLong(KEY_PUSH_WAKE_CATCH_UP_SEQUENCE, NO_PENDING_PUSH_WAKE_CATCH_UP),
                )
            val nextGeneration = nextPushWakeCatchUpGeneration(previousGeneration)
            preferences
                .edit()
                .putLong(KEY_PUSH_WAKE_CATCH_UP_SEQUENCE, nextGeneration)
                .putLong(KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION, nextGeneration)
                .remove(KEY_PENDING_PUSH_WAKE_CATCH_UP)
                .commit()
        }

    /** Records a new wake and reopens an eligible exhausted episode in one durable transaction. */
    @SuppressLint("ApplySharedPref")
    internal fun recordPendingPushWakeCatchUpAndAdmitEpisode(nowMs: Long): Boolean =
        synchronized(LOCK) {
            val previousGeneration =
                maxOf(
                    pendingPushWakeCatchUpGenerationLocked(),
                    preferences.getLong(KEY_PUSH_WAKE_CATCH_UP_SEQUENCE, NO_PENDING_PUSH_WAKE_CATCH_UP),
                )
            val nextGeneration = nextPushWakeCatchUpGeneration(previousGeneration)
            preferences
                .edit()
                .putLong(KEY_PUSH_WAKE_CATCH_UP_SEQUENCE, nextGeneration)
                .putLong(KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION, nextGeneration)
                .remove(KEY_PENDING_PUSH_WAKE_CATCH_UP)
                .apply {
                    if (pushWakeAttempts() >= PUSH_WAKE_MAX_ATTEMPTS && pushWakeRetryDelay(nowMs) == 0L) {
                        remove(KEY_WAKE_ATTEMPTS)
                        remove(KEY_WAKE_RETRY_AT)
                    }
                }.commit()
        }

    /** Clears the current pending wake while retaining its sequence for future distinct wake identities. */
    fun clearPendingPushWakeCatchUp() {
        synchronized(LOCK) {
            clearPendingPushWakeCatchUpLocked()
        }
    }

    /** Clears only the observed wake generation so a later wake cannot be acknowledged by stale work. */
    fun clearPendingPushWakeCatchUp(generation: Long): Boolean {
        if (generation == NO_PENDING_PUSH_WAKE_CATCH_UP) return false
        synchronized(LOCK) {
            if (pendingPushWakeCatchUpGenerationLocked() != generation) return false
            return clearPendingPushWakeCatchUpLocked()
        }
    }

    /** Removes both legacy and generation-based markers while the caller holds the process-wide lock. */
    @SuppressLint("ApplySharedPref")
    private fun clearPendingPushWakeCatchUpLocked(): Boolean =
        preferences
            .edit()
            .remove(KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION)
            .remove(KEY_PENDING_PUSH_WAKE_CATCH_UP)
            .remove(KEY_WAKE_ATTEMPTS)
            .remove(KEY_WAKE_RETRY_AT)
            .commit()

    /** Returns the shared service/worker budget; changing owners never resets it. */
    internal fun pushWakeAttempts(): Int = synchronized(LOCK) { preferences.getInt(KEY_WAKE_ATTEMPTS, 0) }

    /** Reports a finite retry delay, tolerating wall-clock rollback without an indefinite lockout. */
    internal fun pushWakeRetryDelay(nowMs: Long): Long =
        synchronized(LOCK) {
            (preferences.getLong(KEY_WAKE_RETRY_AT, 0L) - nowMs).takeIf { it in 1L..PUSH_WAKE_MAX_BACKOFF_MS } ?: 0L
        }

    /** Reports whether durable attempt admission succeeded, was ineligible, or failed to persist. */
    internal sealed interface PushWakeAttemptReservation {
        data class Claimed(
            val claim: PushWakeAttemptClaim,
        ) : PushWakeAttemptReservation

        data object Rejected : PushWakeAttemptReservation

        data object PersistenceFailed : PushWakeAttemptReservation
    }

    /** Reserves one native attempt while keeping an ineligible wake distinct from a failed commit. */
    @SuppressLint("ApplySharedPref")
    internal fun reservePushWakeAttempt(nowMs: Long): PushWakeAttemptReservation =
        synchronized(LOCK) {
            val priorAttempts = pushWakeAttempts()
            val exhausted = priorAttempts >= PUSH_WAKE_MAX_ATTEMPTS
            if (!pushWakeCatchUpPending() || exhausted || pushWakeRetryDelay(nowMs) > 0L) {
                return@synchronized PushWakeAttemptReservation.Rejected
            }
            val priorRetryAtMs = preferences.getLong(KEY_WAKE_RETRY_AT, 0L)
            val committed =
                preferences
                    .edit()
                    .putInt(KEY_WAKE_ATTEMPTS, priorAttempts + 1)
                    .apply {
                        if (priorAttempts + 1 == PUSH_WAKE_MAX_ATTEMPTS) {
                            putLong(KEY_WAKE_RETRY_AT, nowMs + PUSH_WAKE_MAX_BACKOFF_MS)
                        }
                    }.commit()
            if (committed) {
                PushWakeAttemptReservation.Claimed(PushWakeAttemptClaim(priorAttempts, priorRetryAtMs))
            } else {
                PushWakeAttemptReservation.PersistenceFailed
            }
        }

    /** Compatibility helper for callers that only need the reserved attempt, if any. */
    internal fun claimPushWakeAttempt(nowMs: Long): PushWakeAttemptClaim? =
        when (val reservation = reservePushWakeAttempt(nowMs)) {
            is PushWakeAttemptReservation.Claimed -> reservation.claim
            else -> null
        }

    /** Restores the exact budget snapshot when lifecycle changed before the reserved native attempt began. */
    @SuppressLint("ApplySharedPref")
    internal fun releasePushWakeAttempt(claim: PushWakeAttemptClaim): Boolean =
        synchronized(LOCK) {
            if (pushWakeAttempts() != claim.priorAttempts + 1) return@synchronized false
            preferences
                .edit()
                .apply {
                    if (claim.priorAttempts == 0) {
                        remove(KEY_WAKE_ATTEMPTS)
                    } else {
                        putInt(KEY_WAKE_ATTEMPTS, claim.priorAttempts)
                    }
                    if (claim.priorRetryAtMs == 0L) {
                        remove(KEY_WAKE_RETRY_AT)
                    } else {
                        putLong(KEY_WAKE_RETRY_AT, claim.priorRetryAtMs)
                    }
                }.commit()
        }

    /** Persists backoff for every owner, including pushes arriving during a failing episode. */
    @SuppressLint("ApplySharedPref")
    internal fun deferPushWakeRetry(nowMs: Long): Boolean =
        synchronized(LOCK) {
            val delayMs = PUSH_WAKE_BACKOFF_MS * (1L shl (pushWakeAttempts() - 1).coerceIn(0, 3))
            preferences.edit().putLong(KEY_WAKE_RETRY_AT, nowMs + delayMs).commit()
        }

    /** Successful native settlement ends the failing episode even if a newer wake still needs a fresh fetch. */
    @SuppressLint("ApplySharedPref")
    internal fun completePushWakeAttempt(): Boolean =
        synchronized(LOCK) {
            preferences
                .edit()
                .remove(KEY_WAKE_ATTEMPTS)
                .remove(KEY_WAKE_RETRY_AT)
                .commit()
        }

    /** Only an external wake/lifecycle edge may reopen an exhausted episode after its cooldown. */
    @SuppressLint("ApplySharedPref")
    internal fun admitPushWakeEpisode(nowMs: Long): Boolean =
        synchronized(LOCK) {
            if (pushWakeAttempts() < PUSH_WAKE_MAX_ATTEMPTS || pushWakeRetryDelay(nowMs) > 0L) return@synchronized true
            preferences
                .edit()
                .remove(KEY_WAKE_ATTEMPTS)
                .remove(KEY_WAKE_RETRY_AT)
                .commit()
        }

    /** Interprets a legacy pending Boolean as the first generation until a newer durable wake is recorded. */
    private fun pendingPushWakeCatchUpGenerationLocked(): Long {
        val generation = preferences.getLong(KEY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION, NO_PENDING_PUSH_WAKE_CATCH_UP)
        if (generation > NO_PENDING_PUSH_WAKE_CATCH_UP) return generation
        return if (preferences.getBoolean(KEY_PENDING_PUSH_WAKE_CATCH_UP, false)) {
            LEGACY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION
        } else {
            NO_PENDING_PUSH_WAKE_CATCH_UP
        }
    }

    /** Advances the durable wake sequence while reserving zero for no pending work. */
    private fun nextPushWakeCatchUpGeneration(current: Long): Long =
        if (current in LEGACY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION until Long.MAX_VALUE) {
            current + 1
        } else {
            LEGACY_PENDING_PUSH_WAKE_CATCH_UP_GENERATION
        }

    /**
     * Account refs whose `clearPushRegistration` FFI call previously failed.
     * The next [syncNativePushRegistrationIfEnabled]-style drain should retry
     * them; sign-out / disable that succeeded never enters this set.
     *
     * Returns a defensive copy — `SharedPreferences.getStringSet` may share
     * its backing instance, and mutating that is undefined behavior.
     */
    fun pendingClears(): Set<String> = preferences.getStringSet(KEY_PENDING_CLEARS, emptySet())?.toSet() ?: emptySet()

    /**
     * Durably marks [account] before native delivery is disabled.
     *
     * Re-recording intentionally commits again: a failed commit can update the
     * in-memory preference view, so skipping an already-present value would
     * incorrectly treat the cleanup marker as process-durable.
     */
    @SuppressLint("ApplySharedPref")
    fun recordPendingClear(account: String): Boolean {
        if (account.isBlank()) return false
        return synchronized(LOCK) {
            val current = pendingClears()
            preferences.edit().putStringSet(KEY_PENDING_CLEARS, current + account).commit()
        }
    }

    /** Acknowledges one account's registration cleanup without removing another account's retry. */
    fun clearPending(account: String) {
        if (account.isBlank()) return
        synchronized(LOCK) {
            val current = pendingClears()
            if (account !in current) return
            preferences.edit().putStringSet(KEY_PENDING_CLEARS, current - account).apply()
        }
    }

    /** Returns account refs whose failed sign-out disable must be retried before registration is allowed. */
    fun pendingDisables(): Set<String> = preferences.getStringSet(KEY_PENDING_DISABLES, emptySet())?.toSet() ?: emptySet()

    /** Commits an account-specific disable obligation before sign-out admission, even after a failed prior commit. */
    @SuppressLint("ApplySharedPref")
    fun recordPendingDisable(account: String): Boolean {
        if (account.isBlank()) return false
        return synchronized(LOCK) {
            val current = pendingDisables()
            preferences.edit().putStringSet(KEY_PENDING_DISABLES, current + account).commit()
        }
    }

    /** Clears only the matching account's disable obligation after completion or an explicit re-enable. */
    fun clearPendingDisable(account: String) {
        if (account.isBlank()) return
        synchronized(LOCK) {
            val current = pendingDisables()
            if (account !in current) return
            preferences.edit().putStringSet(KEY_PENDING_DISABLES, current - account).apply()
        }
    }

    @Suppress("DEPRECATION")
    companion object {
        // Process-wide, NOT per-instance: callers construct fresh stores over
        // the same prefs file (onNewToken does PushTokenStore.create(...) on a
        // Firebase background thread while sign-out uses another instance), so
        // an instance lock would serialize nothing across them. See #167.
        private val LOCK = Any()
        private const val KEYSTORE_ALIAS = "whitenoise.push.tokens.keystore.key"
        private var keyProvider: SecureStoreKeyProvider = AndroidKeystoreSecretKeyProvider(KEYSTORE_ALIAS)

        /** JVM fixtures supply an AES key; null restores the production Keystore boundary. */
        @VisibleForTesting
        internal fun installKeyProvider(provider: SecureStoreKeyProvider?) {
            synchronized(LOCK) {
                keyProvider = provider ?: AndroidKeystoreSecretKeyProvider(KEYSTORE_ALIAS)
            }
        }

        private enum class LegacyImportResult(
            val quarantined: Boolean = false,
        ) {
            IMPORTED,
            PENDING,
            QUARANTINED(true),
            BLOCKED(true),
        }

        /** Quarantine never writes a receipt or removes source keys; target failures remain retryable. */
        private fun importSource(
            context: Context,
            source: String,
            secure: PushTokenPreferences,
        ): LegacyImportResult {
            val receipt = secure.hasImported(source)
            val legacy =
                runCatching {
                    if (source == SECURE_PREFS_NAME) {
                        LegacySecurePreferences.openForImport(context, source)
                    } else {
                        context.getSharedPreferences(source, Context.MODE_PRIVATE)
                    }
                }
            return when {
                receipt -> {
                    // Cleanup failure cannot invalidate a durable target receipt.
                    runCatching { legacy.getOrNull()?.let(secure::cleanupImportedSource) }
                    LegacyImportResult.IMPORTED
                }
                legacy.isFailure -> LegacyImportResult.BLOCKED
                else ->
                    try {
                        legacy.getOrNull()?.let { secure.importLegacy(source, it) }
                        LegacyImportResult.IMPORTED
                    } catch (malformed: MalformedLegacyPushSource) {
                        if (malformed.cleanupRequired) LegacyImportResult.BLOCKED else LegacyImportResult.QUARANTINED
                    }
            }
        }

        /** Constructs lazily so a locked Keystore cannot crash AppState initialization. */
        @Suppress("LongMethod") // One serialized import and registration-safety boundary for three legacy sources.
        fun create(context: Context): PushTokenStore =
            synchronized(LOCK) {
                val appContext = context.applicationContext
                val safety = appContext.getSharedPreferences("whitenoise.push.safety", Context.MODE_PRIVATE)
                var initialized = false
                var nextImportAttemptMs = 0L
                lateinit var secure: PushTokenPreferences
                secure =
                    PushTokenPreferences(
                        KeystoreSecureStore(
                            appContext,
                            "whitenoise.push.tokens.keystore",
                            keyProvider,
                        ),
                        LOCK,
                        prepare = {
                            if (!initialized && android.os.SystemClock.elapsedRealtime() >= nextImportAttemptMs) {
                                val results =
                                    listOf(SECURE_PREFS_NAME, FALLBACK_PREFS_NAME, PREFS_NAME).associateWith { source ->
                                        runCatching { importSource(appContext, source, secure) }.getOrDefault(LegacyImportResult.PENDING)
                                    }
                                val pending = results.containsValue(LegacyImportResult.PENDING)
                                val quarantined = results.filterValues { it.quarantined }.keys
                                check(
                                    safety
                                        .edit()
                                        .putBoolean("migration_unknown", pending)
                                        .putStringSet("quarantined_sources", quarantined)
                                        .putBoolean(
                                            "quarantine_cleanup_unknown",
                                            results.containsValue(LegacyImportResult.BLOCKED),
                                        )
                                        .commit(),
                                )
                                initialized = !pending && quarantined.isEmpty()
                                if (!initialized) {
                                    android.util.Log.w("PushTokenStorage", "legacy_import_deferred")
                                    nextImportAttemptMs = android.os.SystemClock.elapsedRealtime() + 30_000L
                                }
                            }
                        },
                    )
                // Initialization happens at the first operation, under LOCK. Any unread legacy
                // cleanup or unavailable Keystore fails closed instead of appearing empty.
                PushTokenStore(
                    secure,
                    recoveryStatus = {
                        when {
                            safety.getBoolean("cleanup_unknown", false) ||
                                safety.getBoolean("quarantine_cleanup_unknown", false) ->
                                PushStorageRecovery.CleanupReconciliationRequired
                            safety.getBoolean("migration_unknown", true) -> PushStorageRecovery.LegacyImportPending
                            else -> PushStorageRecovery.Ready
                        }
                    },
                    retryImport = {
                        initialized = false
                        nextImportAttemptMs = 0L
                    },
                )
            }
    }
}
