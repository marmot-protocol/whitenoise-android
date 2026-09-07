package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Android boundary used to establish a restartable persistent-delivery fallback. */
internal interface NativePushFallbackPlatform {
    /** Re-commits the desired global transport state to durable storage. */
    fun persistBackgroundConnectionEnabled(isStillDesired: () -> Boolean): Boolean

    /** Queues one request whose generation must be acknowledged by the running service. */
    fun startBackgroundConnection(requestGeneration: Long): Boolean

    /** Durably records server-registration cleanup before native delivery is disabled. */
    fun recordPendingRegistrationClear(accountRef: String): Boolean
}

/** Production implementation of the capability-fallback persistence and service boundary. */
internal class AndroidNativePushFallbackPlatform(
    context: Context,
) : NativePushFallbackPlatform {
    private val appContext = context.applicationContext

    override fun persistBackgroundConnectionEnabled(isStillDesired: () -> Boolean): Boolean =
        BackgroundConnectionPreferences.setEnabledDurablyIf(appContext, true, isStillDesired)

    override fun startBackgroundConnection(requestGeneration: Long): Boolean =
        NotificationStreamForegroundService.start(
            context = appContext,
            trigger = ForegroundStartTrigger.CapabilityFallback,
            capabilityFallbackGeneration = requestGeneration,
        )

    override fun recordPendingRegistrationClear(accountRef: String): Boolean =
        PushTokenStore
            .create(appContext)
            .recordPendingClear(accountRef)
}

/** One exact AppState owner waiting for a service-supervisor success boundary. */
private data class NativePushFallbackRuntimeRequest(
    val generation: Long,
    val owner: NativePushFallbackOwner,
)

/**
 * Latest-wins bridge between AppState reconciliation and asynchronous service
 * lifecycle callbacks. Readiness is reusable only by the same account/runtime
 * owner, including its account-switch epoch, so A-to-B-to-A cannot revive it.
 */
internal class NativePushFallbackRuntimeReadiness {
    private val lock = Any()

    // staleness-exempt: monotonic opaque request identity owned by this latest-wins coordinator.
    private var sequence = 0L
    private var pending: NativePushFallbackRuntimeRequest? = null
    private var ready: NativePushFallbackRuntimeRequest? = null

    /** Returns true only for an acknowledged request owned by [owner]. */
    fun isReady(owner: NativePushFallbackOwner): Boolean =
        synchronized(lock) {
            ready?.owner?.matches(owner) == true
        }

    /** Returns the current same-owner request or replaces stale ownership with a new generation. */
    fun request(owner: NativePushFallbackOwner): Long =
        synchronized(lock) {
            pending?.takeIf { it.owner.matches(owner) }?.generation
                ?: nextGeneration().also { generation ->
                    pending = NativePushFallbackRuntimeRequest(generation, owner)
                    ready = null
                }
        }

    /**
     * Marks the exact pending generation ready only while its captured owner is
     * still authoritative, returning that owner so the caller can schedule sync.
     */
    fun acknowledge(
        generation: Long,
        ownerIsCurrent: (NativePushFallbackOwner) -> Boolean,
    ): NativePushFallbackOwner? =
        synchronized(lock) {
            val request = pending?.takeIf { it.generation == generation } ?: return@synchronized null
            pending = null
            if (!ownerIsCurrent(request.owner)) return@synchronized null
            ready = request
            request.owner
        }

    /** Invalidates a matching pending or ready request without disturbing a newer generation. */
    fun invalidate(generation: Long) {
        synchronized(lock) {
            if (pending?.generation == generation) pending = null
            if (ready?.generation == generation) ready = null
        }
    }

    /** Invalidates readiness across explicit stop and runtime teardown boundaries. */
    fun invalidateAll() {
        synchronized(lock) {
            pending = null
            ready = null
        }
    }

    /** Advances the opaque identity while reserving zero for a missing request. */
    private fun nextGeneration(): Long {
        sequence = if (sequence == Long.MAX_VALUE) 1L else sequence + 1L
        return sequence
    }
}

/** Active-first, failure-aware snapshot of a multi-account notification-settings sweep. */
internal data class NativePushFallbackSettingsSnapshot(
    val active: NotificationSettingsFfi?,
    val knownByAccount: Map<String, NotificationSettingsFfi>,
    val allAccountsRead: Boolean,
) {
    /** True when any authoritative account still relies on unavailable native delivery. */
    val requiresPersistentConnection: Boolean
        get() = knownByAccount.values.any { it.requiresNativeDelivery() }

    /** True when a known non-active account remains native-enabled and cannot be mutated here. */
    fun hasUnreconciledAccountOutside(activeAccountRef: String): Boolean =
        knownByAccount.any { (accountRef, settings) ->
            accountRef != activeAccountRef && settings.requiresNativeDelivery()
        }
}

/** AppState boundaries pinned to the owner captured before reconciliation suspended. */
internal data class NativePushFallbackBindings(
    val ownerIsCurrent: (NativePushFallbackOwner) -> Boolean,
    val readSettings: suspend (NativePushFallbackOwner, String) -> NotificationSettingsFfi,
    val publishActiveSettings: (NotificationSettingsFfi) -> Unit,
    val publishPersistentConnectionEnabled: () -> Unit,
    val publishComplete: () -> Unit,
    val removeFingerprint: (NativePushFallbackOwner) -> Unit,
    val setNativePushDisabled: suspend (NativePushFallbackOwner) -> NotificationSettingsFfi,
    val clearRegistration: suspend (NativePushFallbackOwner) -> Unit,
    val onFailure: (String) -> Unit,
)

/**
 * Coordinates the two-phase fallback: persist and supervise the global relay
 * transport first, then disable only the active account's unavailable native
 * path. Background/unknown accounts keep the global result incomplete.
 */
internal class NativePushFallbackCoordinator(
    private val platform: NativePushFallbackPlatform,
) {
    private val intentLifetime = StalenessGuard()
    private val readiness = NativePushFallbackRuntimeReadiness()

    /** Captures one exact AppState and user-intent owner for capability migration. */
    fun owner(
        accountRef: String?,
        runtime: AppMarmotRuntime?,
        runtimeGeneration: Int,
        accountSwitchGeneration: Long,
    ): NativePushFallbackOwner? =
        if (accountRef == null || runtime == null) {
            null
        } else {
            NativePushFallbackOwner(
                accountRef = accountRef,
                runtime = runtime,
                runtimeGeneration = runtimeGeneration,
                accountSwitchGeneration = accountSwitchGeneration,
                intentGeneration = intentLifetime.capture(),
            )
        }

    /** Reconciles one captured AppState owner without mutating background account preferences. */
    suspend fun reconcile(
        capability: dev.ipf.whitenoise.android.notifications.NativePushCapability,
        owner: NativePushFallbackOwner,
        accountRefs: List<String>,
        bindings: NativePushFallbackBindings,
    ): Boolean {
        val snapshot =
            readNativePushFallbackSettings(
                activeAccountRef = owner.accountRef,
                accountRefs = accountRefs,
                read = { account ->
                    withContext(Dispatchers.IO) { bindings.readSettings(owner, account) }
                },
                onFailure = { account, failure ->
                    bindings.onFailure(nativePushFallbackFailureMessage("settings read", account, failure))
                },
            )
        val ownerWasCurrent = bindings.ownerIsCurrent(owner)
        val persistentReady =
            ownerWasCurrent &&
                ensurePersistentFallback(owner, snapshot, bindings)
        val settings = snapshot.active?.takeIf { ownerWasCurrent && bindings.ownerIsCurrent(owner) }
        if (settings == null) return false
        bindings.publishActiveSettings(settings)
        val activeReconciled =
            reconcileUnavailableNativePushDelivery(
                capability = capability,
                nativePushEnabled = settings.requiresNativeDelivery(),
                ownerIsCurrent = { bindings.ownerIsCurrent(owner) },
                enablePersistentConnection = { persistentReady },
                disableNativePush = {
                    disableNativePushAfterFallbackReady(
                        owner = owner,
                        platform = platform,
                        ownerIsCurrent = { bindings.ownerIsCurrent(owner) },
                        fallbackIsReady = { readiness.isReady(owner) },
                        removeFingerprint = { bindings.removeFingerprint(owner) },
                        setNativePushDisabled = { bindings.setNativePushDisabled(owner) },
                        publishSettings = bindings.publishActiveSettings,
                        clearRegistration = { bindings.clearRegistration(owner) },
                        onFailure = {
                            bindings.onFailure(
                                nativePushFallbackFailureMessage("native disable", owner.accountRef, it),
                            )
                        },
                    )
                },
            )
        val complete =
            snapshot.allAccountsRead &&
                activeReconciled &&
                !snapshot.hasUnreconciledAccountOutside(owner.accountRef)
        if (complete) bindings.publishComplete()
        return complete
    }

    /** Establishes the global transport only when the settings snapshot requires fallback. */
    private suspend fun ensurePersistentFallback(
        owner: NativePushFallbackOwner,
        snapshot: NativePushFallbackSettingsSnapshot,
        bindings: NativePushFallbackBindings,
    ): Boolean =
        !snapshot.requiresPersistentConnection ||
            ensureNativePushFallbackRuntime(
                owner = owner,
                readiness = readiness,
                platform = platform,
                ownerIsCurrent = { bindings.ownerIsCurrent(owner) },
                intentIsCurrent = { intentLifetime.isCurrent(owner.intentGeneration) },
                publishEnabled = bindings.publishPersistentConnectionEnabled,
                onFailure = bindings::reportPreferenceCommitFailure,
            )

    /** Accepts one exact supervised service generation and returns its still-owned request. */
    fun acknowledge(
        generation: Long,
        ownerIsCurrent: (NativePushFallbackOwner) -> Boolean,
    ): NativePushFallbackOwner? = readiness.acknowledge(generation, ownerIsCurrent)

    /** Invalidates one service generation without disturbing a replacement request. */
    fun invalidate(generation: Long) = readiness.invalidate(generation)

    /** Invalidates readiness and every suspended write from the previous explicit delivery intent. */
    fun invalidateAll() {
        intentLifetime.advance()
        readiness.invalidateAll()
    }
}

/**
 * Re-commits the global fallback preference and requests one exact service
 * generation. Queueing is not readiness; only a later supervisor callback can
 * make the next reconciliation return true.
 */
internal suspend fun ensureNativePushFallbackRuntime(
    owner: NativePushFallbackOwner,
    readiness: NativePushFallbackRuntimeReadiness,
    platform: NativePushFallbackPlatform,
    ownerIsCurrent: () -> Boolean,
    intentIsCurrent: () -> Boolean,
    publishEnabled: () -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    val persisted =
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                platform.persistBackgroundConnectionEnabled(intentIsCurrent)
            }
        }.onFailure(onFailure)
            .getOrDefault(false)
    return if (!persisted || !ownerIsCurrent()) {
        false
    } else {
        publishEnabled()
        if (readiness.isReady(owner)) {
            true
        } else {
            val generation = readiness.request(owner)
            if (!ownerIsCurrent() || !platform.startBackgroundConnection(generation)) {
                readiness.invalidate(generation)
            }
            false
        }
    }
}

/**
 * Durably queues server cleanup before dispatching the native preference
 * mutation. Cancellation or stale ownership after the native call therefore
 * cannot strand an untracked server registration.
 */
internal suspend fun disableNativePushAfterFallbackReady(
    owner: NativePushFallbackOwner,
    platform: NativePushFallbackPlatform,
    ownerIsCurrent: () -> Boolean,
    fallbackIsReady: () -> Boolean,
    removeFingerprint: () -> Unit,
    setNativePushDisabled: suspend () -> NotificationSettingsFfi,
    publishSettings: (NotificationSettingsFfi) -> Unit,
    clearRegistration: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean {
    val cleanupQueued =
        runCatchingCancellable {
            withContext(Dispatchers.IO) { platform.recordPendingRegistrationClear(owner.accountRef) }
        }.onFailure(onFailure)
            .getOrDefault(false)
    currentCoroutineContext().ensureActive()
    return if (!cleanupQueued || !ownerIsCurrent() || !fallbackIsReady()) {
        false
    } else {
        removeFingerprint()
        currentCoroutineContext().ensureActive()
        val settings =
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    if (ownerIsCurrent() && fallbackIsReady()) setNativePushDisabled() else null
                }
            }.onFailure(onFailure)
                .getOrNull()
        if (settings == null || !ownerIsCurrent()) {
            false
        } else {
            publishSettings(settings)
            if (settings.nativePushEnabled) {
                false
            } else {
                clearRegistration()
                ownerIsCurrent()
            }
        }
    }
}

/**
 * Reads the active account first, then isolates each background read failure so
 * one unavailable account cannot prevent a known active account from falling
 * back safely. Cancellation remains fatal to the whole reconciliation.
 */
internal suspend fun readNativePushFallbackSettings(
    activeAccountRef: String,
    accountRefs: List<String>,
    read: suspend (String) -> NotificationSettingsFfi,
    onFailure: (accountRef: String, Throwable) -> Unit,
): NativePushFallbackSettingsSnapshot {
    val settings = linkedMapOf<String, NotificationSettingsFfi>()
    var allAccountsRead = true
    val orderedAccounts = listOf(activeAccountRef) + accountRefs.filterNot { it == activeAccountRef }
    orderedAccounts.distinct().forEach { accountRef ->
        runCatchingCancellable { read(accountRef) }
            .onSuccess { settings[accountRef] = it }
            .onFailure {
                allAccountsRead = false
                onFailure(accountRef, it)
            }
    }
    return NativePushFallbackSettingsSnapshot(
        active = settings[activeAccountRef],
        knownByAccount = settings,
        allAccountsRead = allAccountsRead,
    )
}

/** Uses runtime identity, not data equality, when comparing asynchronous owners. */
internal fun NativePushFallbackOwner.matches(other: NativePushFallbackOwner): Boolean =
    accountRef == other.accountRef &&
        runtime === other.runtime &&
        runtimeGeneration == other.runtimeGeneration &&
        accountSwitchGeneration == other.accountSwitchGeneration &&
        intentGeneration == other.intentGeneration

/** True only when this account intentionally receives locally and still expects native wake delivery. */
private fun NotificationSettingsFfi.requiresNativeDelivery(): Boolean = localNotificationsEnabled && nativePushEnabled

/** Reports a durable-fallback preference failure through the owning AppState boundary. */
private fun NativePushFallbackBindings.reportPreferenceCommitFailure(failure: Throwable) {
    onFailure(nativePushFallbackFailureMessage("preference commit", null, failure))
}

/** Prefixes a debug diagnostic with a truncated account reference. */
private fun nativePushFallbackFailureMessage(
    operation: String,
    accountRef: String?,
    failure: Throwable,
): String {
    val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName
    return "native-push fallback $operation account=${accountRef?.take(NATIVE_PUSH_ACCOUNT_LOG_PREFIX_LENGTH)}: $reason"
}

private const val NATIVE_PUSH_ACCOUNT_LOG_PREFIX_LENGTH = 8
