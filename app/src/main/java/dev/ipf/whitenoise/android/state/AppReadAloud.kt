package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.audio.AttachmentSpeechDestination
import dev.ipf.whitenoise.android.audio.tts.AndroidTtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineChoice
import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import dev.ipf.whitenoise.android.audio.tts.TtsEngineResolver
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionResult
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionSnapshot
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryPager
import dev.ipf.whitenoise.android.audio.tts.TtsHistorySession
import dev.ipf.whitenoise.android.audio.tts.TtsPlaybackForegroundService
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceKey
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceResolution
import dev.ipf.whitenoise.android.audio.tts.adoptTtsEngineSelection
import dev.ipf.whitenoise.android.audio.tts.resolveTtsOnDispatcher
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.Locale

/** App state that read-aloud reads at call time. Nothing here is read while [AppReadAloud] is constructed. */
internal interface AppReadAloudHost {
    val activeAccountRef: String?
    val accountSwitchHandoff: AccountSwitchLocalSnapshotHandoff
    val mutationsScope: CoroutineScope
    val appLockScreenVisible: Boolean

    /** Main-thread scope for the history session, created when that session is first used. */
    fun createHistoryScope(): CoroutineScope

    fun createAutoReadContinuation(): TtsAutoReadContinuation

    fun historyPager(
        accountRef: String?,
        groupIdHex: String,
    ): TtsHistoryPager?
}

/**
 * Process-wide read-aloud: preferences, engine discovery and selection, the speech controller, and which
 * account and conversation own the current speech. One speech owner at a time; removing its account or
 * turning off its conversation's auto-read ends the speech and every navigation credential it held.
 */
@Suppress("TooManyFunctions") // AppState delegates each read-aloud entry point here so screens are unchanged.
internal class AppReadAloud(
    private val appContext: Context,
    internal val host: AppReadAloudHost,
) {
    internal val ttsRefreshMutex = Mutex()
    internal val ttsWarningPreferences = TtsWarningPreferences(appContext)
    internal val ttsPlaybackTrust = TtsPlaybackTrustConsent(this) { attachedTtsHandle }

    /** Reports whether the connected engine still requires playback consent. */
    internal fun ttsEngineNeedsAcknowledgment(): Boolean = ttsPlaybackTrust.needsAcknowledgment()

    /** Checks engine consent and the playback owner before any text can be sent. */
    internal suspend fun confirmTtsPlaybackTrust(current: () -> Boolean): Boolean = ttsPlaybackTrust.confirm(current)

    internal val ttsEnginePreferences = TtsEnginePreferences(appContext)
    internal val ttsVoicePreferences = TtsVoicePreferences(appContext)
    internal val ttsEngineResolver =
        TtsEngineResolver(appContext, selectedVoice = ttsVoicePreferences::selectedVoice)
    internal val ttsRatePreferences = TtsRatePreferences(appContext)
    internal val ttsMediaMixPreferences = TtsMediaMixPreferences(appContext)

    // Process-wide read-aloud playback: survives navigation between chats and
    // back to the chat list, matching VoicePlaybackController's lifetime.
    val ttsController = createAppTtsController(appContext, ttsRatePreferences, ttsMediaMixPreferences)
    var ttsResolution by mutableStateOf<TtsResolutionResult?>(null)
        private set
    var ttsVoiceResolution by mutableStateOf(TtsVoiceResolution.Empty)
        private set

    // The (account, conversation) pair that owns the current auto-read
    // session, or null when speech is manual or idle. Live continuation
    // appends only for the owner: a manual Speak aloud replaces the queue and
    // ends the session; another chat's — or another ACCOUNT'S view of the
    // same group — must never extend it.
    internal var ttsAutoReadSessionKey by mutableStateOf<String?>(null)
        private set
    private val ttsAutoReadContinuation by lazy { host.createAutoReadContinuation() }

    // Manual speech deliberately has no auto-read session key, but it still
    // owns decrypted text that must stop when its account is removed.
    internal var ttsSpeechAccountRef: String? = null
        private set
    internal val attachmentSpeechDestination = MutableStateFlow<AttachmentSpeechDestination?>(null)

    /** Captures a conversation's account before any suspending speech projection. */
    internal fun captureTtsAccountOwner(account: String?): () -> Boolean {
        val generation = host.accountSwitchHandoff.capture()
        return {
            account != null && account == host.activeAccountRef && host.accountSwitchHandoff.isCurrent(generation)
        }
    }

    /** Reader controls may only address speech owned by the current local account. */
    internal fun ownsCurrentAccountSpeech(): Boolean = ttsSpeechAccountRef?.let { it == host.activeAccountRef } == true

    fun ownsTtsAutoReadSession(groupIdHex: String): Boolean {
        val key = ttsAutoReadSessionKey ?: return false
        return key == ttsAutoReadKey(host.activeAccountRef, groupIdHex)
    }

    /** Starts read-aloud for one or more speakable messages after any required approval. */
    fun speakAloud(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val started =
            synchronized(ttsController) {
                val previousGeneration = ttsController.state.value.messageProgressGeneration
                val previousCallbackGeneration = ttsController.playbackCallbackGeneration()
                val accepted =
                    !ttsEngineNeedsAcknowledgment() && ttsController.speak(entries, locale, startSentenceIndex)
                // A pre-playback refusal (offline-language) tears down the queue without a new message generation.
                if (accepted ||
                    ttsController.state.value.messageProgressGeneration != previousGeneration ||
                    ttsController.playbackCallbackGeneration() != previousCallbackGeneration
                ) {
                    clearTtsPlaybackOwnership()
                }
                if (accepted) ttsSpeechAccountRef = host.activeAccountRef
                accepted
            }
        if (!started) return false

        // Stop private speech if its foreground service fails to provide a visible stop control.
        val foregroundStarted = TtsPlaybackForegroundService.start(appContext)
        if (!foregroundStarted) stopSpeaking()
        return foregroundStarted
    }

    /** [speakAloud] for an auto-read backlog, marking the owning conversation. */
    fun speakAloudAutoRead(
        groupIdHex: String,
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val owner = ttsAutoReadKey(host.activeAccountRef, groupIdHex) ?: return false
        val started = speakAloud(entries, locale, startSentenceIndex)
        if (started) {
            ttsAutoReadSessionKey = owner
            ttsHistorySession.onConversationSessionStarted(host.activeAccountRef, groupIdHex)
            host.activeAccountRef?.let { ttsAutoReadContinuation.start(it, groupIdHex, locale) }
        }
        return started
    }

    /**
     * Prepares reader text off the controller lock, preserving caller/account ownership through playback commit.
     * Account-switch generation prevents an A-B-A return from reviving old work. Cleanup is session-scoped so
     * a revoked or cancelled request cannot clear a replacement queue's ownership.
     * Accepting a new queue also revokes prior history/attachment source ownership.
     */
    suspend fun speakAloudPrepared(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
        startRenderedHit: PreparedRenderedHit? = null,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        if (ttsEngineNeedsAcknowledgment()) return false
        val ownerAccount = host.activeAccountRef
        val ownerGeneration = host.accountSwitchHandoff.capture()
        var preparingSessionId: Long? = null
        try {
            val started =
                ttsController.speakAsync(
                    entries,
                    locale,
                    startSentenceIndex,
                    startRenderedHit,
                    isCurrent = {
                        isCurrent() &&
                            !ttsEngineNeedsAcknowledgment() &&
                            host.activeAccountRef == ownerAccount &&
                            host.accountSwitchHandoff.isCurrent(ownerGeneration)
                    },
                ) {
                    preparingSessionId = ttsController.state.value.sessionId
                    ttsSpeechAccountRef = ownerAccount
                    TtsPlaybackForegroundService.start(appContext)
                }
            if (started) {
                ttsSpeechAccountRef = ownerAccount
                ttsAutoReadSessionKey = null
                ttsHistorySession.onSessionCleared()
            }
            return started
        } finally {
            // Check and clear under the same monitor as queue starts, not against a stale Idle snapshot.
            synchronized(ttsController) {
                val state = ttsController.state.value
                if (state is TtsState.Idle &&
                    state.sessionId == preparingSessionId &&
                    ttsSpeechAccountRef == ownerAccount
                ) {
                    clearTtsPlaybackOwnership()
                }
            }
        }
    }

    suspend fun speakAloudAutoRead(
        groupIdHex: String,
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
        startRenderedHit: PreparedRenderedHit? = null,
        backgroundPreparation: Boolean,
        canCommit: () -> Boolean = { true },
    ): Boolean {
        if (!canCommit()) return false
        val ownerAccount = host.activeAccountRef
        return if (!backgroundPreparation) {
            speakAloudAutoRead(groupIdHex, entries, locale, startSentenceIndex)
        } else {
            ttsAutoReadKey(ownerAccount, groupIdHex)?.let { owner ->
                speakAloudPrepared(
                    entries,
                    locale,
                    startSentenceIndex,
                    startRenderedHit,
                    isCurrent = canCommit,
                ).also { started ->
                    if (started) {
                        ttsAutoReadSessionKey = owner
                        ttsHistorySession.onConversationSessionStarted(ownerAccount, groupIdHex)
                        ownerAccount?.let { ttsAutoReadContinuation.start(it, groupIdHex, locale) }
                    }
                }
            } ?: false
        }
    }

    /**
     * History paging for the read-aloud transport. The backing conversation
     * controller is resolved per edge request, so a controller recreated by
     * navigation keeps serving the same playback session.
     */
    val ttsHistorySession: TtsHistorySession by lazy {
        TtsHistorySession(
            controller = ttsController,
            scope = host.createHistoryScope(),
            resolvePager = host::historyPager,
        )
    }

    internal val ttsAutoReadPreferences = TtsAutoReadPreferences(appContext)

    fun isConversationAutoRead(groupIdHex: String): Boolean {
        val accountRef = host.activeAccountRef ?: return false
        return conversationAutoReadFor(accountRef, groupIdHex)
    }

    internal fun conversationAutoReadFor(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = ttsAutoReadPreferences.isConversationAutoRead(accountRef, groupIdHex)

    fun setTtsAutoReadGlobalDefault(enabled: Boolean) {
        ttsAutoReadPreferences.setGlobalDefaultEnabled(enabled)
        reconcileOwnedTtsAutoReadSession()
    }

    fun setConversationAutoReadOverride(
        groupIdHex: String,
        override: TtsAutoReadOverride?,
    ) {
        val accountRef = host.activeAccountRef ?: return
        if (override == null) {
            ttsAutoReadPreferences.clearConversationOverride(accountRef, groupIdHex)
        } else {
            ttsAutoReadPreferences.setConversationOverride(accountRef, groupIdHex, override)
        }
        reconcileOwnedTtsAutoReadSession()
    }

    private fun reconcileOwnedTtsAutoReadSession() {
        val sessionKey = ttsAutoReadSessionKey ?: return
        val separator = sessionKey.indexOf('|')
        if (separator <= 0 || separator == sessionKey.lastIndex) return
        val accountRef = sessionKey.substring(0, separator)
        val groupIdHex = sessionKey.substring(separator + 1)
        if (!conversationAutoReadFor(accountRef, groupIdHex)) {
            stopOwnedTtsAutoReadSession()
        }
    }

    /** Stops an owned conversation auto-read queue and revokes its account/history navigation ownership. */
    internal fun stopOwnedTtsAutoReadSession() {
        if (ttsAutoReadSessionKey == null) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    /** Account removal ends any owned speech so its decrypted text stops being spoken. */
    internal fun stopTtsForRemovedAccount(accountRef: String) {
        val speechOwner = ttsSpeechAccountRef
        if (speechOwner != null && speechOwner != accountRef) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    /** Live continuation for auto-read: extends an active read-aloud queue. */
    fun appendSpeech(
        entry: TtsSpeakableEntry,
        locale: Locale,
    ): Boolean {
        // A session paged away from the live tail must not have arrivals
        // spliced next to unrelated older history — they stay reachable
        // through next-message paging instead.
        if (ttsEngineNeedsAcknowledgment() || !ttsHistorySession.allowsLiveAppend()) return false
        return ttsController.appendSpeech(entry, locale)
    }

    /** Stops the active speech queue and immediately revokes every transient source-navigation credential. */
    fun stopSpeaking() {
        ttsPlaybackTrust.cancel()
        ttsController.stop()
        clearTtsPlaybackOwnership()
    }

    /** Clears the account, auto-read, history and attachment ownership of a retired speech session. */
    internal fun clearTtsPlaybackOwnership() {
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    fun setTtsRateOverride(rate: Float?) {
        ttsRatePreferences.setRateOverride(rate)
        ttsController.onSpeechRateChanged()
    }

    /** Enables the explicitly opted-in active-media mixing mode. */
    internal fun setTtsMediaMixEnabled(enabled: Boolean) {
        ttsMediaMixPreferences.setEnabled(enabled)
    }

    /** Applies a bounded mix level at the next queued sentence boundary. */
    internal fun setTtsMediaMixVolume(volume: TtsMediaMixVolume) {
        ttsMediaMixPreferences.setVolume(volume)
        ttsController.onMediaMixVolumeChanged()
    }

    internal var attachedTtsHandle: TtsEngineHandle? = null
        private set

    /** Publishes discovery state and atomically replaces the controller's engine adapter. */
    internal fun publishTtsResolution(resolution: TtsResolutionResult?) {
        ttsResolution = resolution
        val handle = resolution?.handle
        // Reattaching the same healthy handle would stop speech and reset its current voice.
        if (handle === attachedTtsHandle) return
        ttsPlaybackTrust.onHandleReplaced()
        clearTtsPlaybackOwnership()
        ttsVoiceResolution = handle?.voiceResolution ?: TtsVoiceResolution.Empty
        attachedTtsHandle = handle
        if (handle != null) {
            ttsController.attachEngine(
                AndroidTtsSpeechEngine(
                    textToSpeech = handle.textToSpeech,
                    enginePackage = handle.enginePackage,
                    selectedVoice = { ttsVoicePreferences.selectedVoice(handle.enginePackage) },
                    onVoiceResolved = { voiceResolution -> ttsVoiceResolution = voiceResolution },
                    requireOfflineVoice = handle.voiceResolution.effectiveKey != null,
                    onConnectionUncertain = { retireFailedTtsConnection(handle) },
                    voiceCatalogue = handle.voiceCatalogue,
                ),
                engineKey = handle.enginePackage,
            )
        } else {
            ttsController.detachEngine()
        }
    }

    /** Clears approval and releases a suspect connection before discovering an engine again. */
    private fun retireFailedTtsConnection(handle: TtsEngineHandle) {
        host.mutationsScope.launch {
            // The synchronous adapter is already retired. Defer controller replacement until its current call unwinds.
            yield()
            val retired =
                ttsRefreshMutex.withLock {
                    if (attachedTtsHandle !== handle) return@withLock false
                    publishTtsResolution(ttsResolution?.copy(handle = null))
                    runCatching { handle.release() }
                    true
                }
            if (retired) refreshTtsAvailability()
        }
    }

    val ttsDiscoveryComplete: Boolean
        get() = ttsResolution != null
    val ttsHasUsableEngine: Boolean
        get() = ttsResolution?.hasUsableEngine == true

    /** Returns the resolved platform engine list and default used by the settings surface. */
    @Suppress("MaxLineLength")
    internal fun ttsEngineChoice(): TtsEngineChoice = ttsResolution?.engineChoice() ?: TtsEngineChoice(null, emptyList())

    /** Resolves the package currently selected by the user/default-engine policy. */
    internal fun resolvedTtsEnginePackage(): String? =
        ttsEngineResolver.preferredEnginePackage(
            engines = ttsEngineChoice().engines,
            defaultPackage = ttsEngineChoice().defaultPackage,
            selectedOverride = ttsEnginePreferences.selectedEngine(),
        )

    /** Saves a voice for the active engine and safely swaps in a newly configured handle. */
    internal fun selectTtsVoice(voice: TtsVoiceKey?) {
        val enginePackage = resolvedTtsEnginePackage() ?: return
        if (voice != null && voice.enginePackage != enginePackage) return
        host.mutationsScope.launch {
            ttsVoicePreferences.setSelectedVoice(enginePackage, voice)
            selectTtsEngineLocked(enginePackage)
        }
    }

    fun acknowledgeTtsTrustWarning(enginePackage: String) {
        ttsWarningPreferences.acknowledge(enginePackage)
    }

    fun selectTtsEngine(enginePackage: String) {
        host.mutationsScope.launch {
            selectTtsEngineLocked(enginePackage)
        }
    }

    internal suspend fun selectTtsEngineLocked(enginePackage: String) {
        ttsRefreshMutex.withLock {
            val current =
                TtsEngineSelectionSnapshot(
                    resolution = ttsResolution,
                    selectedOverride = ttsEnginePreferences.selectedEngine(),
                )
            val candidate = resolveTtsOnDispatcher { ttsEngineResolver.resolve(enginePackage) }
            when (val outcome = adoptTtsEngineSelection(current, candidate, enginePackage)) {
                is TtsEngineSelectionResult.Adopted -> {
                    ttsEnginePreferences.setSelectedEngine(outcome.selectedOverride)
                    publishTtsResolution(outcome.resolution)
                    outcome.releasedHandles.forEach { handle -> runCatching { handle.release() } }
                }
                is TtsEngineSelectionResult.Retained -> {
                    withContext(Dispatchers.IO) {
                        candidate.handle?.let { handle -> runCatching { handle.release() } }
                    }
                }
            }
        }
    }

    suspend fun refreshTtsAvailability() =
        ttsRefreshMutex.withLock {
            val previousHandle = ttsResolution?.handle
            val candidateHandles = mutableListOf<TtsEngineHandle>()
            var adoptedHandle: TtsEngineHandle? = null
            var replacementPublished = false
            try {
                val bootstrap = resolveTtsOnDispatcher { ttsEngineResolver.resolve(enginePackage = null) }
                bootstrap.handle?.let(candidateHandles::add)
                val selectedOverride = ttsEnginePreferences.selectedEngine()
                val preferredPackage =
                    ttsEngineResolver.preferredEnginePackage(
                        engines = bootstrap.engines,
                        defaultPackage = bootstrap.defaultEnginePackage,
                        selectedOverride = selectedOverride,
                    )
                val replacement =
                    when {
                        preferredPackage == null -> bootstrap.copy(handle = null)
                        preferredPackage == bootstrap.handle?.enginePackage -> bootstrap
                        else -> {
                            val selected = resolveTtsOnDispatcher { ttsEngineResolver.resolve(preferredPackage) }
                            selected.handle?.let(candidateHandles::add)
                            if (selected.handle?.enginePackage == preferredPackage) {
                                selected
                            } else {
                                bootstrap.copy(handle = null)
                            }
                        }
                    }

                // Same engine package resolved again: keep the ATTACHED
                // handle, so an availability refresh (settings ON_RESUME,
                // foreground) never swaps engines under in-flight speech.
                // resolve() always mints a new handle, so identity alone
                // can't provide this — the freshly resolved duplicate is
                // released with the other unadopted candidates below.
                val published =
                    if (previousHandle != null && previousHandle.enginePackage == replacement.handle?.enginePackage) {
                        replacement.copy(handle = previousHandle)
                    } else {
                        replacement
                    }
                adoptedHandle = published.handle
                publishTtsResolution(published)
                replacementPublished = true
            } finally {
                candidateHandles
                    .filter { it !== adoptedHandle }
                    .forEach { handle -> runCatching { handle.release() } }
                if (replacementPublished && previousHandle !== adoptedHandle) {
                    runCatching { previousHandle?.release() }
                }
            }
        }
}
