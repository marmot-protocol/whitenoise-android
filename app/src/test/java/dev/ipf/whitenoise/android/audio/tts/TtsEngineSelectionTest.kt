package dev.ipf.whitenoise.android.audio.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsEngineSelectionTest {
    @Test
    fun adoptSelectionRetainsStateWhenCandidateHasNoHandle() {
        val current =
            TtsEngineSelectionSnapshot(
                resolution = discoveryResolution(handlePackage = "app.grapheneos.speechservices"),
                selectedOverride = "app.grapheneos.speechservices",
            )
        val candidate =
            discoveryResolution(handlePackage = "app.grapheneos.speechservices").copy(handle = null)

        val outcome =
            adoptTtsEngineSelection(
                current = current,
                candidate = candidate,
                requestedPackage = "com.google.android.tts",
            )

        assertTrue(outcome is TtsEngineSelectionResult.Retained)
        assertTrue((outcome as TtsEngineSelectionResult.Retained).releasedHandles.isEmpty())
    }

    @Test
    fun adoptSelectionPublishesOverrideAndReleasesPreviousHandle() {
        val previousHandle = fakeHandle("app.grapheneos.speechservices", EngineTrust.Local)
        val nextHandle = fakeHandle("com.google.android.tts", EngineTrust.Unknown)
        val current =
            TtsEngineSelectionSnapshot(
                resolution = discoveryResolution(handle = previousHandle),
                selectedOverride = "app.grapheneos.speechservices",
            )
        val currentResolution = requireNotNull(current.resolution)
        val candidate =
            TtsResolutionResult(
                status = TextToSpeech.SUCCESS,
                engines = currentResolution.engines,
                defaultEnginePackage = currentResolution.defaultEnginePackage,
                handle = nextHandle,
            )

        val outcome =
            adoptTtsEngineSelection(
                current = current,
                candidate = candidate,
                requestedPackage = "com.google.android.tts",
            )

        assertTrue(outcome is TtsEngineSelectionResult.Adopted)
        val adopted = outcome as TtsEngineSelectionResult.Adopted
        assertEquals("com.google.android.tts", adopted.selectedOverride)
        assertEquals(nextHandle, adopted.resolution.handle)
        assertEquals(listOf(previousHandle), adopted.releasedHandles)
    }

    @Test
    fun adoptSelectionRejectsVerifiedFallbackPackageMismatch() {
        val previousHandle = fakeHandle("app.grapheneos.speechservices", EngineTrust.Local)
        val fallbackHandle = fakeHandle("com.google.android.tts", EngineTrust.Unknown)
        val current =
            TtsEngineSelectionSnapshot(
                resolution = discoveryResolution(handle = previousHandle),
                selectedOverride = "app.grapheneos.speechservices",
            )
        val candidate = current.resolution!!.copy(handle = fallbackHandle)

        val outcome =
            adoptTtsEngineSelection(
                current = current,
                candidate = candidate,
                requestedPackage = "app.grapheneos.speechservices",
            )

        assertTrue(outcome is TtsEngineSelectionResult.Retained)
        assertEquals(previousHandle, requireNotNull(current.resolution).handle)
        assertEquals("app.grapheneos.speechservices", current.selectedOverride)
    }

    @Test
    fun resolvedHandleIsReleasedWhenCallerCancelsBeforeDispatcherReturn() =
        runBlocking {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                val jobReady = CompletableDeferred<Job>()
                val tts = TrackingTextToSpeech(org.robolectric.RuntimeEnvironment.getApplication())
                val resolution = discoveryResolution(TtsEngineHandle(tts, "com.google.android.tts", EngineTrust.Unknown))
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        resolveTtsOnDispatcher(dispatcher) {
                            // Cancel the caller from inside resolve, before
                            // returning: the dispatcher handoff back is then
                            // guaranteed to observe a cancelled caller instead
                            // of racing an external cancel against completion.
                            jobReady.await().cancel()
                            resolution
                        }
                    }
                jobReady.complete(job)
                job.join()

                assertEquals(1, tts.shutdownCount)
            } finally {
                dispatcher.close()
            }
        }

    @Test
    fun cancelledSelectionDoesNotPersistWhenCandidateNeverAdopted() {
        val current =
            TtsEngineSelectionSnapshot(
                resolution = discoveryResolution(handlePackage = "app.grapheneos.speechservices"),
                selectedOverride = "app.grapheneos.speechservices",
            )

        val outcome =
            adoptTtsEngineSelection(
                current = current,
                candidate = discoveryResolution(handlePackage = "app.grapheneos.speechservices").copy(handle = null),
                requestedPackage = "com.google.android.tts",
            )

        assertTrue(outcome is TtsEngineSelectionResult.Retained)
        assertEquals("app.grapheneos.speechservices", current.selectedOverride)
        assertEquals("app.grapheneos.speechservices", current.resolution?.handle?.enginePackage)
    }

    @Test
    fun runtimeTrustForSelectionWarningFailsClosedBeforeAdoptedHandleMatches() {
        assertEquals(
            EngineTrust.Unknown,
            runtimeTrustForSelectionWarning(
                enginePackage = "app.grapheneos.speechservices",
                adoptedHandle = null,
                selectedOverride = null,
            ),
        )
        assertEquals(
            EngineTrust.Unknown,
            runtimeTrustForSelectionWarning(
                enginePackage = "app.grapheneos.speechservices",
                adoptedHandle = fakeHandle("com.google.android.tts", EngineTrust.Unknown),
                selectedOverride = null,
            ),
        )
    }

    @Test
    fun runtimeTrustForSelectionWarningUsesAdoptedHandleWhenPackagesMatch() {
        val adopted = fakeHandle("app.grapheneos.speechservices", EngineTrust.Local)
        assertEquals(
            EngineTrust.Local,
            runtimeTrustForSelectionWarning(
                enginePackage = "app.grapheneos.speechservices",
                adoptedHandle = adopted,
                selectedOverride = "app.grapheneos.speechservices",
            ),
        )
        assertEquals(
            EngineTrust.Unknown,
            runtimeTrustForSelectionWarning(
                enginePackage = "app.grapheneos.speechservices",
                adoptedHandle = adopted,
                selectedOverride = "com.google.android.tts",
            ),
        )
    }

    @Test
    fun noEngineMessageRequiresCompletedDiscovery() {
        assertFalse(shouldReportNoTtsEngine(null))
        assertTrue(
            shouldReportNoTtsEngine(
                TtsResolutionResult(
                    status = TextToSpeech.SUCCESS,
                    engines = emptyList(),
                    defaultEnginePackage = null,
                    handle = null,
                ),
            ),
        )
        assertFalse(
            shouldReportNoTtsEngine(
                TtsResolutionResult(
                    status = TextToSpeech.SUCCESS,
                    engines = listOf(TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown)),
                    defaultEnginePackage = "com.google.android.tts",
                    handle = null,
                ),
            ),
        )
    }

    private fun discoveryResolution(handlePackage: String): TtsResolutionResult = discoveryResolution(fakeHandle(handlePackage, EngineTrust.Local))

    private fun discoveryResolution(handle: TtsEngineHandle): TtsResolutionResult =
        TtsResolutionResult(
            status = TextToSpeech.SUCCESS,
            engines =
                listOf(
                    TtsEngineInfo("app.grapheneos.speechservices", "GrapheneOS", EngineTrust.Local),
                    TtsEngineInfo("com.google.android.tts", "Google", EngineTrust.Unknown),
                ),
            defaultEnginePackage = "app.grapheneos.speechservices",
            handle = handle,
        )

    private fun fakeHandle(
        enginePackage: String,
        trust: EngineTrust,
    ): TtsEngineHandle =
        TtsEngineHandle(
            textToSpeech =
                org.robolectric.RuntimeEnvironment
                    .getApplication()
                    .let { android.speech.tts.TextToSpeech(it, {}) },
            enginePackage = enginePackage,
            trust = trust,
        )

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
