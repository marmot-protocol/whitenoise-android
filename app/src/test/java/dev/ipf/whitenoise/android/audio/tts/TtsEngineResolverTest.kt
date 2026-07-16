package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class TtsEngineResolverTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication().applicationContext

    @Test
    fun emptyEngineListIsNotUsable() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog = FakeTtsEngineCatalog(engines = emptyList()),
                )

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }
            factory.completeInit(TextToSpeech.SUCCESS)
            val result = deferred.await()

            assertFalse(result.hasUsableEngine)
            assertTrue(result.engines.isEmpty())
            assertNull(result.handle)
        }

    @Test
    fun successfulDiscoveryWithInstalledEnginesIsUsableBeforeSelection() {
        val result =
            TtsResolutionResult(
                status = TextToSpeech.SUCCESS,
                engines = listOf(TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown)),
                defaultEnginePackage = "com.google.android.tts",
                handle = null,
            )

        assertTrue(result.hasUsableEngine)
    }

    @Test
    fun initFailureIsNotUsableAndQuiet() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog =
                        FakeTtsEngineCatalog(
                            engines = listOf(engineInfo("com.google.android.tts", "Google")),
                        ),
                )

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }
            factory.completeInit(TextToSpeech.ERROR)
            val result = deferred.await()

            assertFalse(result.hasUsableEngine)
            assertNull(result.handle)
        }

    @Test
    fun concurrentResolutionRequestsInitializeOneEngineAtATime() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog =
                        FakeTtsEngineCatalog(
                            engines = listOf(engineInfo("app.grapheneos.speechservices", "GrapheneOS")),
                            defaultEngine = "app.grapheneos.speechservices",
                        ),
                )

            val first = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }
            val second = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }

            assertEquals(1, factory.creationCount)
            factory.completeInit(TextToSpeech.SUCCESS)
            first.await()
            yield()

            assertEquals(2, factory.creationCount)
            assertEquals(0, factory.instances.first().shutdownCount)
            factory.completeInit(TextToSpeech.SUCCESS)
            val secondResult = second.await()
            assertTrue(secondResult.hasUsableEngine)
            assertEquals(0, factory.instances.first().shutdownCount)
            first.await().handle?.release()
            secondResult.handle?.release()
            Unit
        }

    @Test
    fun classifiesKnownLocalEngines() {
        val resolver = TtsEngineResolver(context)

        assertEquals(EngineTrust.Local, resolver.classify("app.grapheneos.speechservices"))
        assertEquals(EngineTrust.Local, resolver.classify("com.github.olga_yakovleva.rhvoice.android"))
        assertEquals(EngineTrust.Local, resolver.classify("com.reecedunn.espeak"))
    }

    @Test
    fun classifiesUnknownEngines() {
        val resolver = TtsEngineResolver(context)

        assertEquals(EngineTrust.Unknown, resolver.classify("com.google.android.tts"))
        assertEquals(EngineTrust.Unknown, resolver.classify("com.example.tts"))
    }

    @Test
    fun successWithEnginesReturnsConfiguredHandle() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog =
                        FakeTtsEngineCatalog(
                            engines = listOf(engineInfo("com.github.olga_yakovleva.rhvoice.android", "RHVoice")),
                            defaultEngine = "com.github.olga_yakovleva.rhvoice.android",
                        ),
                )

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve(null) }
            factory.completeInit(TextToSpeech.SUCCESS)
            val result = deferred.await()

            assertTrue(result.hasUsableEngine)
            assertEquals(EngineTrust.Local, result.handle?.trust)
            assertEquals("com.github.olga_yakovleva.rhvoice.android", result.handle?.enginePackage)
            assertNotNull(result.handle?.textToSpeech)
            result.handle?.release()
            Unit
        }

    @Test
    fun preferredEnginePackageUsesLocalDefaultWithoutOverride() {
        val resolver = TtsEngineResolver(context)
        val engines =
            listOf(
                TtsEngineInfo("app.grapheneos.speechservices", "GrapheneOS", EngineTrust.Local),
                TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown),
            )

        assertEquals(
            "app.grapheneos.speechservices",
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "app.grapheneos.speechservices",
                selectedOverride = null,
            ),
        )
    }

    @Test
    fun preferredEnginePackageRequiresExplicitChoiceWhenDefaultIsUnknown() {
        val resolver = TtsEngineResolver(context)
        val engines =
            listOf(
                TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown),
                TtsEngineInfo("com.github.olga_yakovleva.rhvoice.android", "RHVoice", EngineTrust.Local),
            )

        assertNull(
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "com.google.android.tts",
                selectedOverride = null,
            ),
        )
        assertEquals(
            "com.github.olga_yakovleva.rhvoice.android",
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "com.google.android.tts",
                selectedOverride = "com.github.olga_yakovleva.rhvoice.android",
            ),
        )
        assertNull(
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "com.google.android.tts",
                selectedOverride = "com.example.uninstalled",
            ),
        )
    }

    @Test
    fun preferredEnginePackageFallsBackWhenStoredOverrideIsNoLongerInstalled() {
        val resolver = TtsEngineResolver(context)
        val engines =
            listOf(
                TtsEngineInfo("app.grapheneos.speechservices", "GrapheneOS", EngineTrust.Local),
                TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown),
            )

        assertEquals(
            "app.grapheneos.speechservices",
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "app.grapheneos.speechservices",
                selectedOverride = "com.example.uninstalled",
            ),
        )
    }

    @Test
    fun engineChoiceIsShownForUnknownDefaultIncludingSoleEngine() {
        val localDefault =
            TtsEngineChoice(
                defaultPackage = "app.grapheneos.speechservices",
                engines =
                    listOf(
                        TtsEngineInfo("app.grapheneos.speechservices", "GrapheneOS", EngineTrust.Local),
                        TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown),
                    ),
            )
        val unknownDefault = localDefault.copy(defaultPackage = "com.google.android.tts")
        val soleUnknownDefault =
            unknownDefault.copy(
                engines = listOf(TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown)),
            )

        assertFalse(localDefault.showEngineChooser)
        assertTrue(unknownDefault.showEngineChooser)
        assertTrue(soleUnknownDefault.showEngineChooser)
    }

    @Test
    fun preferredEnginePackageUsesOnlyInstalledEngineEvenWhenUnknown() {
        val resolver = TtsEngineResolver(context)
        val engines = listOf(TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown))

        assertEquals(
            "com.google.android.tts",
            resolver.preferredEnginePackage(
                engines = engines,
                defaultPackage = "com.google.android.tts",
                selectedOverride = null,
            ),
        )
    }

    @Test
    fun immediateInitFailureAfterCallbackShutsDownCandidate() =
        runBlocking {
            val factory = ImmediateStatusTtsFactory(TextToSpeech.ERROR)
            val resolver = TtsEngineResolver(context, ttsFactory = factory)

            val result = resolver.resolve()

            assertFalse(result.hasUsableEngine)
            assertNull(result.handle)
            assertEquals(1, factory.instance.shutdownCount)
        }

    @Test
    fun cancellationShutsDownPendingCandidate() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver = TtsEngineResolver(context, ttsFactory = factory)

            val resolution = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
            resolution.cancelAndJoin()

            assertEquals(1, factory.instances.single().shutdownCount)
        }

    @Test
    fun requestedEngineMissingKeepsSuccessfulDiscoveryUsableWithoutUsingDefault() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog =
                        FakeTtsEngineCatalog(
                            engines = listOf(engineInfo("app.grapheneos.speechservices", "GrapheneOS")),
                            defaultEngine = "app.grapheneos.speechservices",
                        ),
                )

            val deferred =
                async(start = CoroutineStart.UNDISPATCHED) {
                    resolver.resolve("com.example.missing")
                }
            factory.completeInit(TextToSpeech.SUCCESS)
            val result = deferred.await()

            assertTrue(result.hasUsableEngine)
            assertEquals("app.grapheneos.speechservices", result.defaultEnginePackage)
            assertEquals(1, result.engines.size)
            assertNull(result.handle)
            assertEquals(1, factory.instances.single().shutdownCount)
        }

    @Test
    fun missingDefaultKeepsSuccessfulDiscoveryUsableWithoutGuessingAnEngine() =
        runBlocking {
            val factory = ControllableTtsFactory()
            val resolver =
                TtsEngineResolver(
                    context,
                    ttsFactory = factory,
                    engineCatalog =
                        FakeTtsEngineCatalog(
                            engines =
                                listOf(
                                    engineInfo("com.example.zulu", "Zulu"),
                                    engineInfo("com.example.alpha", "Alpha"),
                                ),
                        ),
                )

            val deferred = async(start = CoroutineStart.UNDISPATCHED) { resolver.resolve() }
            factory.completeInit(TextToSpeech.SUCCESS)
            val result = deferred.await()

            assertTrue(result.hasUsableEngine)
            assertNull(result.handle)
            assertEquals(1, factory.instances.single().shutdownCount)
        }

    @Test
    fun offlineVoicePreferenceUsesHighestQualityVoiceInCurrentLanguage() {
        val current = voice("current-network", Locale.US, quality = 300, networkRequired = true)
        val lowerQualityEnglish = voice("english-low", Locale.UK, quality = 200)
        val higherQualityEnglish = voice("english-high", Locale.CANADA, quality = 400)
        val unrelated = voice("french-highest", Locale.FRANCE, quality = 500)

        val preferred =
            TtsEngineResolver.preferOfflineVoice(
                current,
                setOf(lowerQualityEnglish, higherQualityEnglish, unrelated),
            )

        assertSame(higherQualityEnglish, preferred)
    }

    @Test
    fun offlineVoicePreferenceRetainsCurrentWithoutSameLanguageOfflineVoice() {
        val current = voice("current", Locale.US, quality = 300, networkRequired = true)
        val unrelated = voice("french", Locale.FRANCE, quality = 500)

        assertSame(current, TtsEngineResolver.preferOfflineVoice(current, setOf(unrelated)))
    }

    @Test
    fun offlineVoicePreferenceRetainsCurrentForEmptyCandidates() {
        val current = voice("current", Locale.US, quality = 300, networkRequired = true)

        assertSame(current, TtsEngineResolver.preferOfflineVoice(current, emptySet()))
    }

    private fun engineInfo(
        name: String,
        label: String,
    ): TextToSpeech.EngineInfo =
        TextToSpeech.EngineInfo().apply {
            this.name = name
            this.label = label
        }

    private fun voice(
        name: String,
        locale: Locale,
        quality: Int,
        networkRequired: Boolean = false,
    ): Voice = Voice(name, locale, quality, 100, networkRequired, emptySet())

    private class FakeTtsEngineCatalog(
        private val engines: List<TextToSpeech.EngineInfo>,
        private val defaultEngine: String? = null,
    ) : TtsEngineCatalog {
        override fun installedEngines(tts: TextToSpeech): List<TextToSpeech.EngineInfo> = engines

        override fun defaultEnginePackage(tts: TextToSpeech): String? = defaultEngine
    }

    private class ControllableTtsFactory : TtsFactory {
        private var listener: TextToSpeech.OnInitListener? = null
        val instances = mutableListOf<TrackingTextToSpeech>()
        var creationCount: Int = 0
            private set

        override fun create(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String?,
        ): TextToSpeech {
            creationCount += 1
            this.listener = listener
            return TrackingTextToSpeech(context).also(instances::add)
        }

        fun completeInit(status: Int) {
            listener?.onInit(status)
        }
    }

    private class ImmediateStatusTtsFactory(
        private val status: Int,
    ) : TtsFactory {
        lateinit var instance: TrackingTextToSpeech

        override fun create(
            context: Context,
            listener: TextToSpeech.OnInitListener,
            engine: String?,
        ): TextToSpeech {
            instance = TrackingTextToSpeech(context)
            listener.onInit(status)
            return instance
        }
    }

    private class TrackingTextToSpeech(
        context: Context,
    ) : TextToSpeech(context, {}) {
        var shutdownCount = 0
            private set

        override fun shutdown() {
            shutdownCount += 1
            super.shutdown()
        }
    }
}
