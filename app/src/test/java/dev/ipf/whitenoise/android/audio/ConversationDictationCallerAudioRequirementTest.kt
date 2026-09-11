package dev.ipf.whitenoise.android.audio

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/** Covers how installed state and recorded verdicts decide which dictation surface can capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationCallerAudioRequirementTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val platform = AndroidConversationDictationPlatform(context)

    @Test
    fun aSystemSelectedRecognizerOpensTheMicrophoneItselfSoCallerAudioNeverApplies() {
        installProvider()
        record("unsupported")
        Settings.Secure.putString(
            context.contentResolver,
            VOICE_RECOGNITION_SERVICE_SETTING,
            "$PROVIDER/$PROVIDER.Recognition",
        )

        assertEquals(
            ConversationDictationCallerAudioRequirement.NotNeeded,
            platform.callerAudioRequirement(),
        )
    }

    @Test
    fun aPreinstalledRecognizerSkipsTheProbeEvenWithoutASystemSelection() {
        installProvider(system = true)
        record("unsupported")

        assertEquals(
            ConversationDictationCallerAudioRequirement.NotNeeded,
            platform.callerAudioRequirement(),
        )
        assertEquals(listOf(ConversationDictationCallerAudioRequirement.NotNeeded), probeAnswers())
    }

    @Test
    fun anUnaskedProviderBuildLeavesTheAnswerUnestablished() {
        installProvider()

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            platform.callerAudioRequirement(),
        )
    }

    @Test
    fun aRecordedSupportedAnswerKeepsTheInAppControlsWithoutAskingAgain() {
        installProvider()
        record("supported")

        assertEquals(
            ConversationDictationCallerAudioRequirement.Supported,
            platform.callerAudioRequirement(),
        )
        assertEquals(
            listOf(ConversationDictationCallerAudioRequirement.Supported),
            probeAnswers(),
        )
    }

    @Test
    fun aRecordedRefusalRoutesToTheProvidersOwnRecognitionUi() {
        installProvider()
        record("unsupported")

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unsupported,
            platform.callerAudioRequirement(),
        )
        assertEquals(
            listOf(ConversationDictationCallerAudioRequirement.Unsupported),
            probeAnswers(),
        )
    }

    @Test
    fun aProviderVersionThatCannotBeReadStaysUnknownAndPersistsNoVerdict() {
        // The recognition service resolves, but its package is not readable, so no verdict can be
        // keyed to an installed build.
        installRecognitionService()

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            platform.callerAudioRequirement(),
        )
        assertEquals(
            listOf(ConversationDictationCallerAudioRequirement.Unknown),
            probeAnswers(),
        )
        assertTrue(recordedKeys().isEmpty())
    }

    @Test
    fun noInstalledProviderStaysUnknownAndPersistsNoVerdict() {
        assertEquals(
            ConversationDictationCallerAudioRequirement.Unknown,
            platform.callerAudioRequirement(),
        )
        assertEquals(
            listOf(ConversationDictationCallerAudioRequirement.Unknown),
            probeAnswers(),
        )
        assertTrue(recordedKeys().isEmpty())
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.S])
    fun beforeTiramisuThereAreNoCallerAudioExtrasSoOnlyProviderUiCanCapture() {
        installProvider()

        assertEquals(
            ConversationDictationCallerAudioRequirement.Unsupported,
            platform.callerAudioRequirement(),
        )
    }

    @Test
    fun aProviderThatReadsTheEmptyDescriptorAnswersSupportedAndIsAskedOnlyOnce() {
        installProvider()
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()

        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        // An empty utterance is the answer of a provider that read the caller descriptor.
        answerProbe(SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(listOf(ConversationDictationCallerAudioRequirement.Supported), answers)
        assertEquals(
            setOf("caller_audio_support_component:$PROVIDER/$PROVIDER.Recognition:$VERSION_CODE"),
            recordedKeys(),
        )

        handle.cancel()
        shadowOf(context.mainLooper).idle()

        // The recorded verdict answers the next start without binding the provider again.
        assertEquals(
            ConversationDictationCallerAudioRequirement.Supported,
            platform.callerAudioRequirement(),
        )
        assertEquals(listOf(ConversationDictationCallerAudioRequirement.Supported), answers)
    }

    @Test
    fun aProbeCancelledBeforeItsAnswerReportsNothingAndRecordsNoVerdict() {
        installProvider()
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()

        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        handle.cancel()
        answerProbe(SpeechRecognizer.ERROR_CLIENT)

        assertTrue(answers.isEmpty())
        assertTrue(recordedKeys().isEmpty())
    }

    @Test
    fun cancellingAfterTheTerminalAnswerBeforeDeliveryDoesNotCacheOrCallback() {
        installProvider()
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()
        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

        shadowOf(recognizer).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        handle.cancel()
        shadowOf(context.mainLooper).idle()

        assertTrue(answers.isEmpty())
        assertTrue(recordedKeys().isEmpty())
    }

    @Test
    fun aProviderUpgradeBeforeDeliveryInvalidatesTheProbeAnswer() {
        installProvider()
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()
        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadowOf(recognizer).triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        val packageInfo = context.packageManager.getPackageInfo(PROVIDER, 0)
        packageInfo.longVersionCode = VERSION_CODE + 1
        shadowOf(context.packageManager).installPackage(packageInfo)
        shadowOf(context.mainLooper).idle()

        assertEquals(listOf(ConversationDictationCallerAudioRequirement.Unknown), answers)
        assertTrue(recordedKeys().isEmpty())
        handle.cancel()
    }

    @Test
    fun aProbeTimeoutIsUnknownAndCannotBeReplacedByALateTerminalAnswer() {
        installProvider()
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()
        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadowOf(context.mainLooper).idleFor(java.time.Duration.ofSeconds(5))
        shadowOf(recognizer).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        shadowOf(context.mainLooper).idle()

        assertEquals(listOf(ConversationDictationCallerAudioRequirement.Unknown), answers)
        assertTrue(recordedKeys().isEmpty())
        handle.cancel()
    }

    /** Delivers one terminal provider answer to the running probe and drains the main looper. */
    private fun answerProbe(errorCode: Int) {
        val recognizer = checkNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        shadowOf(recognizer).triggerOnError(errorCode)
        shadowOf(context.mainLooper).idle()
    }

    /** Collects every answer one probe reports, so a double callback is visible. */
    private fun probeAnswers(): List<ConversationDictationCallerAudioRequirement> {
        val answers = mutableListOf<ConversationDictationCallerAudioRequirement>()
        val handle = platform.probeCallerAudioSupport { answers += it }
        shadowOf(context.mainLooper).idle()
        handle.cancel()
        return answers
    }

    private fun record(value: String) {
        preferences()
            .edit()
            .putString("caller_audio_support_component:$PROVIDER/$PROVIDER.Recognition:$VERSION_CODE", value)
            .apply()
    }

    private fun recordedKeys(): Set<String> =
        preferences()
            .all
            .keys
            .filter { it.startsWith("caller_audio_support_component:") }
            .toSet()

    private fun preferences() = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)

    private fun installProvider(system: Boolean = false) {
        installRecognitionService()
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = PROVIDER
                longVersionCode = VERSION_CODE
                applicationInfo =
                    ApplicationInfo().apply {
                        packageName = PROVIDER
                        if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
                    }
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun installRecognitionService() {
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            ResolveInfo().apply {
                serviceInfo =
                    ServiceInfo().apply {
                        permission = "android.permission.BIND_SPEECH_RECOGNITION_SERVICE"
                        packageName = PROVIDER
                        name = ComponentName(PROVIDER, "$PROVIDER.Recognition").className
                        enabled = true
                        exported = true
                        applicationInfo =
                            ApplicationInfo().apply {
                                enabled = true
                                packageName = PROVIDER
                            }
                    }
            },
        )
    }

    private companion object {
        const val PROVIDER = "org.offline"
        const val VERSION_CODE = 7L
    }
}
