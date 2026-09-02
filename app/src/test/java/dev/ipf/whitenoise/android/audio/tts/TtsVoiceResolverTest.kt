package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsVoiceResolverTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    /** Keeps unavailable same-language voices visible with explicit reasons. */
    @Test
    fun voiceCatalogKeepsUnavailableSameLanguageVoicesVisibleButUnselectable() {
        val installed = voice("Installed", Locale.US, quality = 300)
        val missing =
            voice(
                "Missing",
                Locale.UK,
                quality = 400,
                features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        val network = voice("Network", Locale.CANADA, quality = 500, networkRequired = true)
        val unrelated = voice("French", Locale.FRANCE, quality = 500)

        val result =
            TtsEngineResolver.resolveVoiceSelection(
                enginePackage = "engine.a",
                locale = Locale.US,
                voices = listOf(installed, missing, network, unrelated),
                requestedKey = null,
            )

        assertEquals(listOf("Network", "Missing", "Installed"), result.options.map { it.label })
        assertTrue(result.options.single { it.label == "Installed" }.selectable)
        assertEquals(
            TtsVoiceUnavailableReason.NotInstalled,
            result.options.single { it.label == "Missing" }.unavailableReason,
        )
        assertEquals(
            TtsVoiceUnavailableReason.RequiresNetwork,
            result.options.single { it.label == "Network" }.unavailableReason,
        )
    }

    /** Requires exact engine, name, and locale identity for a saved choice. */
    @Test
    fun exactUniqueOfflineVoiceResolvesWithoutFuzzyEngineOrLocaleMatching() {
        val selected = voice("Alice", Locale.US, quality = 300)
        val voices = listOf(selected, voice("Alice", Locale.UK, quality = 400))
        val exact = TtsVoiceKey("engine.a", "Alice", "en-US")

        assertSame(
            selected,
            TtsEngineResolver.resolveVoiceSelection("engine.a", Locale.US, voices, exact).preferredVoice,
        )
        assertNull(
            TtsEngineResolver
                .resolveVoiceSelection(
                    "engine.a",
                    Locale.US,
                    voices,
                    exact.copy(enginePackage = "engine.b"),
                ).preferredVoice,
        )
        assertNull(
            TtsEngineResolver
                .resolveVoiceSelection(
                    "engine.a",
                    Locale.US,
                    voices,
                    exact.copy(localeTag = "en-CA"),
                ).preferredVoice,
        )
    }

    /** Treats duplicate stable keys as ambiguous rather than choosing by quality. */
    @Test
    fun duplicateStableKeysAreAmbiguousAndNeverResolve() {
        val first = voice("Duplicate", Locale.US, quality = 300)
        val second = voice("Duplicate", Locale.US, quality = 500)
        val key = TtsVoiceKey("engine.a", "Duplicate", "en-US")

        val result =
            TtsEngineResolver.resolveVoiceSelection("engine.a", Locale.US, listOf(first, second), key)

        assertNull(result.preferredVoice)
        assertTrue(result.options.all { it.unavailableReason == TtsVoiceUnavailableReason.Ambiguous })
    }

    /** Rejects a duplicate key even when only one copy happens to be offline. */
    @Test
    fun mixedAvailabilityDuplicateKeyStillCannotResolve() {
        val offline = voice("Duplicate", Locale.US, quality = 300)
        val network = voice("Duplicate", Locale.US, quality = 500, networkRequired = true)
        val key = TtsVoiceKey("engine.a", "Duplicate", "en-US")

        val result =
            TtsEngineResolver.resolveVoiceSelection("engine.a", Locale.US, listOf(offline, network), key)

        assertNull(result.preferredVoice)
        assertTrue(result.options.any { it.unavailableReason == TtsVoiceUnavailableReason.Ambiguous })
    }

    /** Accepts an equivalent ISO-639-2 voice while excluding a different ISO-639-2 language. */
    @Test
    fun iso639EquivalentVoiceCodesResolveWithoutAdmittingOtherLanguages() {
        val english = voice("English ISO3", Locale.forLanguageTag("eng"), quality = 300)
        val french = voice("French ISO3", Locale.forLanguageTag("fra"), quality = 500)
        val englishKey = TtsVoiceKey("engine.a", "English ISO3", "eng")

        val result =
            TtsEngineResolver.resolveVoiceSelection(
                enginePackage = "engine.a",
                locale = Locale.US,
                voices = listOf(french, english),
                requestedKey = englishKey,
            )

        assertEquals(listOf("English ISO3"), result.options.map { it.label })
        assertSame(english, result.preferredVoice)
    }

    /** Discovers the device locale instead of inheriting an engine's stale language. */
    @Test
    fun initialVoiceCatalogUsesCurrentLocaleWhenEngineVoiceDiffers() =
        runBlocking {
            val previousLocale = Locale.getDefault()
            Locale.setDefault(Locale.US)
            try {
                val staleFrench = voice("French network", Locale.FRANCE, 500, networkRequired = true)
                val english = voice("English offline", Locale.US, 200)
                val factory = VoiceConfigurableTtsFactory(staleFrench, setOf(staleFrench, english))
                val resolver =
                    TtsEngineResolver(
                        context,
                        ttsFactory = factory,
                        engineCatalog =
                            FakeTtsEngineCatalog(
                                engines = listOf(engineInfo("com.google.android.tts", "Google")),
                                defaultEngine = "com.google.android.tts",
                            ),
                    )

                val deferred = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }
                factory.completeInit(TextToSpeech.SUCCESS)
                val result = deferred.await()

                assertEquals(
                    listOf("English offline"),
                    result.handle
                        ?.voiceResolution
                        ?.options
                        ?.map { it.label },
                )
                assertSame(english, factory.instance.voice)
                result.handle?.release()
            } finally {
                Locale.setDefault(previousLocale)
            }
            Unit
        }

    /** Builds deterministic voice fixtures without relying on the platform catalog. */
    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        networkRequired: Boolean = false,
        features: Set<String> = emptySet(),
    ): Voice = Voice(name, locale, quality, 100, networkRequired, features)

    /** Builds one installed-engine catalog entry. */
    private fun engineInfo(
        name: String,
        label: String,
    ): TextToSpeech.EngineInfo =
        TextToSpeech.EngineInfo().apply {
            this.name = name
            this.label = label
        }

    private class FakeTtsEngineCatalog(
        private val engines: List<TextToSpeech.EngineInfo>,
        private val defaultEngine: String?,
    ) : TtsEngineCatalog {
        override fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo> = engines

        override fun defaultEnginePackage(tts: TextToSpeech): String? = defaultEngine

        override fun connectedEnginePackage(
            tts: TextToSpeech,
            requestedPackage: String?,
        ): String? = requestedPackage ?: defaultEngine
    }

    private class VoiceConfigurableTtsFactory(
        private val currentVoice: Voice,
        private val voices: Set<Voice>,
    ) : TtsFactory {
        private var listener: TextToSpeech.OnInitListener? = null
        lateinit var instance: VoiceConfigurableTextToSpeech

        override fun create(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String?,
        ): TextToSpeech {
            this.listener = listener
            instance = VoiceConfigurableTextToSpeech(context, currentVoice, voices)
            return instance
        }

        /** Delivers the controlled framework initialization result. */
        fun completeInit(status: Int) {
            listener?.onInit(status)
        }
    }

    private class VoiceConfigurableTextToSpeech(
        context: Context,
        currentVoice: Voice,
        private val availableVoices: Set<Voice>,
    ) : TextToSpeech(context, {}) {
        private var activeVoice: Voice = currentVoice

        override fun getVoice(): Voice = activeVoice

        override fun getVoices(): MutableSet<Voice> = availableVoices.toMutableSet()

        override fun setVoice(voice: Voice?): Int {
            voice?.let { activeVoice = it }
            return TextToSpeech.SUCCESS
        }

        override fun setAudioAttributes(attributes: AudioAttributes?): Int = TextToSpeech.SUCCESS
    }
}
