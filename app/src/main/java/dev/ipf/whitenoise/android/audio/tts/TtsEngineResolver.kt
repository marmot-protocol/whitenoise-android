package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.resume

enum class EngineTrust {
    Local,
    Unknown,
}

data class TtsEngineInfo(
    val packageName: String,
    val label: String,
    val trust: EngineTrust,
)

data class TtsEngineChoice(
    val defaultPackage: String?,
    val engines: List<TtsEngineInfo>,
) {
    val showEngineChooser: Boolean
        get() {
            if (engines.isEmpty()) return false
            val defaultInfo = engines.firstOrNull { it.packageName == defaultPackage }
            return defaultInfo?.trust != EngineTrust.Local
        }
}

data class TtsEngineHandle(
    val textToSpeech: TextToSpeech,
    val enginePackage: String,
    val trust: EngineTrust,
    val voiceResolution: TtsVoiceResolution = TtsVoiceResolution.Empty,
) {
    /** Shuts down the framework engine when this resolved handle is replaced. */
    fun release() {
        textToSpeech.shutdown()
    }
}

/** Stable framework-independent identity for one engine voice. */
data class TtsVoiceKey(
    val enginePackage: String,
    val voiceName: String,
    val localeTag: String,
)

/** Why a discovered same-language voice cannot be selected offline. */
enum class TtsVoiceUnavailableReason {
    InvalidIdentity,
    NotInstalled,
    RequiresNetwork,
    Ambiguous,
}

/** One resolver-considered voice, including visible unavailable entries. */
data class TtsVoiceOption(
    val key: TtsVoiceKey,
    val label: String,
    val localeTag: String,
    val unavailableReason: TtsVoiceUnavailableReason?,
) {
    val selectable: Boolean
        get() = unavailableReason == null
}

/** Current catalog, saved choice, and actual effective offline voice. */
data class TtsVoiceResolution(
    val localeTag: String,
    val options: List<TtsVoiceOption>,
    val requestedKey: TtsVoiceKey?,
    val effectiveKey: TtsVoiceKey?,
    internal val preferredVoice: Voice? = null,
) {
    val isUsingRequestedVoice: Boolean
        get() = requestedKey != null && requestedKey == effectiveKey

    internal companion object {
        val Empty = TtsVoiceResolution("", emptyList(), null, null)
    }
}

data class TtsResolutionResult(
    val status: Int,
    val engines: List<TtsEngineInfo>,
    val defaultEnginePackage: String?,
    val handle: TtsEngineHandle?,
) {
    val hasUsableEngine: Boolean
        get() = status == TextToSpeech.SUCCESS && engines.isNotEmpty()

    /** Projects the resolved catalog into the settings screen's engine choice. */
    fun engineChoice(): TtsEngineChoice =
        TtsEngineChoice(
            defaultPackage = defaultEnginePackage,
            engines = engines,
        )
}

fun interface TtsFactory {
    fun create(
        context: Context,
        listener: TextToSpeech.OnInitListener,
        engine: String?,
    ): TextToSpeech
}

class TtsEngineResolver(
    private val appContext: Context,
    private val ttsFactory: TtsFactory = DefaultTtsFactory,
    private val engineCatalog: TtsEngineCatalog = AndroidTtsEngineCatalog,
    private val initTimeoutMs: Long = TTS_INIT_TIMEOUT_MS,
    private val selectedVoice: (String) -> TtsVoiceKey? = { null },
) {
    private val speechAudioAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val resolutionMutex = Mutex()

    /** Classifies an installed engine without treating unknown packages as trusted. */
    fun classify(packageName: String): EngineTrust =
        if (packageName in LOCAL_ENGINE_PACKAGES) {
            EngineTrust.Local
        } else {
            EngineTrust.Unknown
        }

    /** Initializes one engine and returns its verified package, trust, and offline voices. */
    suspend fun resolve(enginePackage: String? = null): TtsResolutionResult =
        resolutionMutex.withLock {
            try {
                withTimeout(initTimeoutMs) {
                    suspendCancellableCoroutine { cont ->
                        val initLock = Any()
                        var created: TextToSpeech? = null
                        var pendingStatus: Int? = null
                        var callbackHandled = false
                        var cancelled = false

                        fun completeInit(
                            tts: TextToSpeech,
                            status: Int,
                        ) {
                            val result =
                                runCatching {
                                    if (status != TextToSpeech.SUCCESS) {
                                        tts.shutdown()
                                        unusableResolution(status)
                                    } else {
                                        buildResolution(tts, status, enginePackage)
                                    }
                                }.getOrElse {
                                    tts.shutdown()
                                    unusableResolution(TextToSpeech.ERROR)
                                }
                            if (cont.isActive) {
                                cont.resume(result)
                            } else {
                                result.handle?.release()
                            }
                        }

                        val listener =
                            TextToSpeech.OnInitListener { status ->
                                val candidate =
                                    synchronized(initLock) {
                                        when {
                                            cancelled || callbackHandled -> null
                                            created == null -> {
                                                if (pendingStatus == null) pendingStatus = status
                                                null
                                            }
                                            else -> {
                                                callbackHandled = true
                                                created
                                            }
                                        }
                                    }
                                candidate?.let { completeInit(it, status) }
                            }

                        cont.invokeOnCancellation {
                            val candidate =
                                synchronized(initLock) {
                                    cancelled = true
                                    created
                                }
                            candidate?.shutdown()
                        }

                        val candidate =
                            try {
                                ttsFactory.create(appContext.applicationContext, listener, enginePackage)
                            } catch (_: Throwable) {
                                if (cont.isActive) cont.resume(unusableResolution(TextToSpeech.ERROR))
                                return@suspendCancellableCoroutine
                            }

                        val (cancelledAfterCreate, statusAfterCreate) =
                            synchronized(initLock) {
                                created = candidate
                                val status =
                                    if (!cancelled && !callbackHandled && pendingStatus != null) {
                                        callbackHandled = true
                                        pendingStatus
                                    } else {
                                        null
                                    }
                                cancelled to status
                            }
                        if (cancelledAfterCreate) {
                            candidate.shutdown()
                        } else {
                            statusAfterCreate?.let { completeInit(candidate, it) }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                unusableResolution(TextToSpeech.ERROR)
            }
        }

    /** Chooses an explicit installed override, safe local default, or sole engine. */
    fun preferredEnginePackage(
        engines: List<TtsEngineInfo>,
        defaultPackage: String?,
        selectedOverride: String?,
    ): String? {
        if (engines.isEmpty()) return null
        if (selectedOverride != null) {
            selectedOverride.takeIf { selected -> engines.any { it.packageName == selected } }?.let { return it }
        }
        val defaultInfo = engines.firstOrNull { it.packageName == defaultPackage }
        return when {
            defaultInfo?.trust == EngineTrust.Local -> defaultPackage
            engines.size == 1 -> engines.single().packageName
            else -> null
        }
    }

    /** Represents a failed initialization without exposing a partially initialized handle. */
    private fun unusableResolution(status: Int): TtsResolutionResult =
        TtsResolutionResult(
            status = status,
            engines = emptyList(),
            defaultEnginePackage = null,
            handle = null,
        )

    /** Verifies the connected engine and packages its current-locale offline voice catalog. */
    private fun buildResolution(
        tts: TextToSpeech,
        status: Int,
        requestedEnginePackage: String?,
    ): TtsResolutionResult {
        val defaultEnginePackage = engineCatalog.defaultEnginePackage(tts)
        val rawEngines = engineCatalog.installedEngines(tts)
        val engines = rawEngines.toTtsEngineInfos()
        val verifiedPackage = engineCatalog.connectedEnginePackage(tts, requestedEnginePackage)
        val activePackage = verifiedPackage ?: requestedEnginePackage ?: defaultEnginePackage
        if (engines.isEmpty() || activePackage == null || engines.none { it.packageName == activePackage }) {
            tts.shutdown()
            return TtsResolutionResult(
                status = status,
                engines = engines,
                defaultEnginePackage = defaultEnginePackage,
                handle = null,
            )
        }
        val voiceResolution = configureResolvedEngine(tts, activePackage, selectedVoice(activePackage))
        if (voiceResolution == null) {
            tts.shutdown()
            return TtsResolutionResult(
                status = status,
                engines = engines,
                defaultEnginePackage = defaultEnginePackage,
                handle = null,
            )
        }
        val trust = resolveHandleTrust(requestedEnginePackage, verifiedPackage, activePackage)
        return TtsResolutionResult(
            status = status,
            engines = engines,
            defaultEnginePackage = defaultEnginePackage,
            handle =
                TtsEngineHandle(
                    textToSpeech = tts,
                    enginePackage = activePackage,
                    trust = trust,
                    voiceResolution = voiceResolution,
                ),
        )
    }

    /** Trusts only a framework-verified default; explicit selections remain unknown. */
    private fun resolveHandleTrust(
        requestedEnginePackage: String?,
        verifiedPackage: String?,
        activePackage: String,
    ): EngineTrust =
        when {
            requestedEnginePackage != null -> EngineTrust.Unknown
            verifiedPackage != null -> classify(verifiedPackage)
            else -> EngineTrust.Unknown
        }

    /** Applies a valid saved voice or the deterministic installed offline fallback. */
    private fun configureResolvedEngine(
        tts: TextToSpeech,
        enginePackage: String,
        requestedVoice: TtsVoiceKey?,
    ): TtsVoiceResolution? {
        if (tts.setAudioAttributes(speechAudioAttributes) != TextToSpeech.SUCCESS) return null
        val configuredLocales = appContext.resources.configuration.locales
        val locale = if (configuredLocales.isEmpty) Locale.getDefault() else configuredLocales[0]
        val voices = tts.voices.orEmpty().toList()
        return applyResolvedVoice(tts, enginePackage, locale, voices, requestedVoice)
    }

    /** Normalizes framework engine metadata into a stable, label-sorted catalog. */
    private fun List<TextToSpeech.EngineInfo>.toTtsEngineInfos(): List<TtsEngineInfo> =
        map { info ->
            val label = info.label.ifBlank { info.name }
            TtsEngineInfo(
                packageName = info.name,
                label = label,
                trust = classify(info.name),
            )
        }.sortedBy { it.label.lowercase() }

    private object DefaultTtsFactory : TtsFactory {
        override fun create(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String?,
        ): TextToSpeech = TextToSpeech(context, listener, engine)
    }

    internal companion object {
        val LOCAL_ENGINE_PACKAGES =
            setOf(
                "app.grapheneos.speechservices",
                "com.github.olga_yakovleva.rhvoice.android",
                "com.reecedunn.espeak",
            )

        fun preferOfflineVoice(
            currentVoice: Voice,
            voices: Collection<Voice>,
        ): Voice = offlineVoiceCandidates(currentVoice, voices).firstOrNull() ?: currentVoice

        /** Uses the framework's current locale for the established fallback path. */
        fun offlineVoiceCandidates(
            currentVoice: Voice,
            voices: Collection<Voice>,
        ): List<Voice> = offlineVoiceCandidates(currentVoice.locale, voices)

        /** Orders installed offline candidates deterministically for an utterance locale. */
        internal fun offlineVoiceCandidates(
            locale: Locale,
            voices: Collection<Voice>,
        ): List<Voice> =
            voices
                .filter { it.isOfflineInstalledFor(locale) }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenBy { it.name }
                        .thenBy { it.locale.toLanguageTag() },
                )

        /** Resolves only an exact, unique, installed offline key for this engine and language. */
        internal fun resolveVoiceSelection(
            enginePackage: String,
            locale: Locale,
            voices: Collection<Voice>,
            requestedKey: TtsVoiceKey?,
        ): TtsVoiceResolution {
            val sameLanguage = voices.filter { it.locale.matchesLanguage(locale) }
            val keyCounts = sameLanguage.groupingBy { it.toVoiceKey(enginePackage) }.eachCount()
            val options =
                sameLanguage
                    .map { voice ->
                        val key = voice.toVoiceKey(enginePackage)
                        val unavailableReason =
                            when {
                                voice.name.isBlank() -> TtsVoiceUnavailableReason.InvalidIdentity
                                voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) ->
                                    TtsVoiceUnavailableReason.NotInstalled
                                voice.isNetworkConnectionRequired -> TtsVoiceUnavailableReason.RequiresNetwork
                                keyCounts[key] != 1 -> TtsVoiceUnavailableReason.Ambiguous
                                else -> null
                            }
                        TtsVoiceOption(
                            key,
                            voice.name.ifBlank { voice.locale.toLanguageTag() },
                            voice.locale.toLanguageTag(),
                            unavailableReason,
                        )
                    }.sortedWith(compareBy<TtsVoiceOption> { it.localeTag }.thenBy { it.label })
            val preferred =
                requestedKey
                    ?.takeIf { it.enginePackage == enginePackage }
                    ?.takeIf { exact -> keyCounts[exact] == 1 }
                    ?.let { exact ->
                        sameLanguage.singleOrNull { voice ->
                            voice.toVoiceKey(enginePackage) == exact && voice.isOfflineInstalledFor(locale)
                        }
                    }
            return TtsVoiceResolution(
                localeTag = locale.toLanguageTag(),
                options = options,
                requestedKey = requestedKey,
                effectiveKey = null,
                preferredVoice = preferred,
            )
        }

        /** Resolves, applies, and reports one deterministic installed offline voice. */
        internal fun applyResolvedVoice(
            tts: TextToSpeech,
            enginePackage: String,
            locale: Locale,
            voices: Collection<Voice>,
            requestedKey: TtsVoiceKey?,
        ): TtsVoiceResolution {
            val resolution = resolveVoiceSelection(enginePackage, locale, voices, requestedKey)
            val preferred = resolution.preferredVoice
            val appliedVoice =
                preferred?.takeIf { tts.setVoice(it) == TextToSpeech.SUCCESS }
                    ?: offlineVoiceCandidates(locale, voices)
                        .asSequence()
                        .filterNot { it == preferred }
                        .firstOrNull { tts.setVoice(it) == TextToSpeech.SUCCESS }
            val effective =
                (appliedVoice ?: tts.voice?.takeIf { it.isOfflineInstalledFor(locale) })
                    ?.toVoiceKey(enginePackage)
            return resolution.copy(effectiveKey = effective)
        }

        /** Converts framework identity into the exact persisted three-part key. */
        private fun Voice.toVoiceKey(enginePackage: String) = TtsVoiceKey(enginePackage, name, locale.toLanguageTag())

        /** Enforces language, installation, and network invariants together. */
        private fun Voice.isOfflineInstalledFor(locale: Locale): Boolean =
            name.isNotBlank() &&
                this.locale.matchesLanguage(locale) &&
                !isNetworkConnectionRequired &&
                !features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

        /** Matches equivalent ISO-639-1 and ISO-639-2 language codes without trusting malformed locale data. */
        private fun Locale.matchesLanguage(other: Locale): Boolean =
            when {
                language.isBlank() || other.language.isBlank() -> false
                language.equals(other.language, ignoreCase = true) -> true
                else -> {
                    val iso3Language = runCatching { getISO3Language() }.getOrNull()
                    val otherIso3Language = runCatching { other.getISO3Language() }.getOrNull()
                    !iso3Language.isNullOrBlank() &&
                        !otherIso3Language.isNullOrBlank() &&
                        iso3Language.equals(otherIso3Language, ignoreCase = true)
                }
            }

        const val TTS_INIT_TIMEOUT_MS = 30_000L
    }
}
