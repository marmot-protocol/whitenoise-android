package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    fun release() {
        textToSpeech.shutdown()
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
) {
    private val speechAudioAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private val resolutionMutex = Mutex()

    fun classify(packageName: String): EngineTrust =
        if (packageName in LOCAL_ENGINE_PACKAGES) {
            EngineTrust.Local
        } else {
            EngineTrust.Unknown
        }

    suspend fun resolve(enginePackage: String? = null): TtsResolutionResult =
        resolutionMutex.withLock {
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

    private fun unusableResolution(status: Int): TtsResolutionResult =
        TtsResolutionResult(
            status = status,
            engines = emptyList(),
            defaultEnginePackage = null,
            handle = null,
        )

    private fun buildResolution(
        tts: TextToSpeech,
        status: Int,
        requestedEnginePackage: String?,
    ): TtsResolutionResult {
        val defaultEnginePackage = engineCatalog.defaultEnginePackage(tts)
        val rawEngines = engineCatalog.installedEngines(tts)
        val engines = rawEngines.toTtsEngineInfos()
        val activePackage = requestedEnginePackage ?: defaultEnginePackage
        if (engines.isEmpty() || activePackage == null || engines.none { it.packageName == activePackage }) {
            tts.shutdown()
            return TtsResolutionResult(
                status = status,
                engines = engines,
                defaultEnginePackage = defaultEnginePackage,
                handle = null,
            )
        }
        if (!configureResolvedEngine(tts)) {
            tts.shutdown()
            return TtsResolutionResult(
                status = status,
                engines = engines,
                defaultEnginePackage = defaultEnginePackage,
                handle = null,
            )
        }
        val trust = classify(activePackage)
        return TtsResolutionResult(
            status = status,
            engines = engines,
            defaultEnginePackage = defaultEnginePackage,
            handle =
                TtsEngineHandle(
                    textToSpeech = tts,
                    enginePackage = activePackage,
                    trust = trust,
                ),
        )
    }

    private fun configureResolvedEngine(tts: TextToSpeech): Boolean {
        if (tts.setAudioAttributes(speechAudioAttributes) != TextToSpeech.SUCCESS) return false
        val currentVoice = tts.voice ?: return true
        val preferredVoice = preferOfflineVoice(currentVoice, tts.voices.orEmpty())
        if (preferredVoice !== currentVoice) tts.voice = preferredVoice
        return true
    }

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
            voices: Set<Voice>,
        ): Voice {
            val offlineVoices =
                voices.filter { voice ->
                    !voice.isNetworkConnectionRequired &&
                        voice.locale.language == currentVoice.locale.language
                }
            return offlineVoices.maxByOrNull { it.quality } ?: currentVoice
        }
    }
}
