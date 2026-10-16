package dev.ipf.whitenoise.android.translation

import dev.davidv.translator.ErrorType
import dev.davidv.translator.ITranslationCallback
import dev.davidv.translator.ITranslationService
import dev.davidv.translator.TranslationError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BoundTranslatorRequestTest {
    @Test
    fun coldCatalogCannotSilentlyUseEnglishDefaultForSpanishTarget() =
        runBlocking {
            var catalogLoaded = false
            val service =
                FakeTranslator { submission, callback ->
                    val resolvedTarget = if (catalogLoaded) submission[2] else "en"
                    catalogLoaded = true
                    if (submission[0] == "") {
                        callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                    } else {
                        callback.onTranslationResult(if (resolvedTarget == "es") "Hola mundo" else "Hello world")
                    }
                }
            assertEquals("Hola mundo", request(service))
            assertEquals(listOf(listOf("", "", ""), listOf("Hello world", "en", "es")), service.submissions)
        }

    @Test
    fun automaticDetectionKeepsSelectedTargetAfterReadiness() =
        runBlocking {
            val service = readyTranslator("Bonjour le monde")
            assertEquals("Bonjour le monde", request(service, source = "", target = "fr"))
            assertEquals(listOf("Hello world", "", "fr"), service.submissions.last())
        }

    @Test
    fun emptySuccessAlsoCompletesTheCatalogBarrier() =
        runBlocking {
            val service =
                FakeTranslator { submission, callback ->
                    callback.onTranslationResult(if (submission[0] == "") "" else "Hola mundo")
                }
            assertEquals("Hola mundo", request(service))
            assertEquals(2, service.submissions.size)
        }

    @Test
    fun messageWaitsForReadinessCallbackAndRevalidatesIdentity() =
        runBlocking {
            val probe = CompletableDeferred<ITranslationCallback>()
            var validations = 0
            val service =
                FakeTranslator { submission, callback ->
                    if (submission[0] == "") probe.complete(callback) else callback.onTranslationResult("Hola mundo")
                }
            val pending = async { request(service, beforeSubmission = { validations += 1 }) }
            val callback = withTimeout(2_000L) { probe.await() }
            assertFalse(pending.isCompleted)
            assertEquals(listOf(listOf("", "", "")), service.submissions)
            callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
            assertEquals("Hola mundo", withTimeout(2_000L) { pending.await() })
            assertEquals(2, validations)
        }

    @Test
    fun unknownCatalogCodesReceiveNoProbeOrMessage() =
        runBlocking {
            val service = readyTranslator("unexpected")
            assertEquals(TranslationFailure.INVALID_REQUEST, failureOf { request(service, target = "zz") })
            assertEquals(TranslationFailure.INVALID_REQUEST, failureOf { request(service, source = "eng") })
            assertTrue(service.submissions.isEmpty())
        }

    /** Slovak reaches Binder as the engine's own `en`/`sk` and `sk`/`en` pairs, and after detection. */
    @Test
    fun slovakPairsReachBinderUnchangedInBothDirections() =
        runBlocking {
            val service = readyTranslator("Ahoj svet")
            for ((source, target) in listOf("en" to "sk", "sk" to "en", "" to "sk")) {
                assertEquals("Ahoj svet", request(service, source = source, target = target))
                assertEquals(listOf("Hello world", source, target), service.submissions.last())
            }
        }

    /** `zh_hant` is an engine identifier, not a BCP 47 tag, and is submitted exactly as the catalog spells it. */
    @Test
    fun traditionalChineseEngineCodeReachesBinderUnchanged() =
        runBlocking {
            val service = readyTranslator("你好世界")
            assertEquals("你好世界", request(service, source = "en", target = "zh_hant"))
            assertEquals(listOf("Hello world", "en", "zh_hant"), service.submissions.last())
            assertEquals("你好世界", request(service, source = "zh_hant", target = "nb"))
            assertEquals(listOf("Hello world", "zh_hant", "nb"), service.submissions.last())
            val probesAndMessages = service.submissions.size
            for (displayTag in listOf("zh-Hant", "zh-TW", "sk-SK")) {
                val failure = failureOf { request(service, target = displayTag) }
                assertEquals(TranslationFailure.INVALID_REQUEST, failure)
            }
            assertEquals(probesAndMessages, service.submissions.size)
        }

    /** Georgian reaches only releases whose bundled catalog lists it, never the engine default on 0.8.3. */
    @Test
    fun georgianIsSubmittedOnlyToReleasesWhoseCatalogListsIt() =
        runBlocking {
            val older = readyTranslator("unexpected")
            val failure = failureOf { request(older, source = "en", target = "ka", engineVersion = "0.8.3") }
            assertEquals(TranslationFailure.ENGINE_UPDATE_REQUIRED, failure)
            assertEquals(
                TranslationFailure.ENGINE_UPDATE_REQUIRED,
                failureOf { request(older, source = "ka", target = "en", engineVersion = "0.8.3") },
            )
            assertTrue(older.submissions.isEmpty())
            for (version in listOf("0.8.4", "0.8.5")) {
                val newer = readyTranslator("გამარჯობა")
                assertEquals("გამარჯობა", request(newer, source = "en", target = "ka", engineVersion = version))
                assertEquals(listOf("Hello world", "en", "ka"), newer.submissions.last())
            }
        }

    /** Languages with only an into-English model can be sources, but never targets the engine cannot reach. */
    @Test
    fun sourceOnlyLanguagesCannotBecomeTargets() =
        runBlocking {
            val service = readyTranslator("Hello")
            for (code in listOf("be", "mr", "nn", "ur")) {
                assertEquals(TranslationFailure.INVALID_REQUEST, failureOf { request(service, target = code) })
            }
            assertTrue(service.submissions.isEmpty())
            assertEquals("Hello", request(service, source = "be", target = "en"))
            assertEquals(listOf("Hello world", "be", "en"), service.submissions.last())
        }

    /** A release outside the reviewed catalogs receives neither the probe nor the message. */
    @Test
    fun unreviewedEngineReleaseReceivesNoProbeOrMessage() =
        runBlocking {
            val service = readyTranslator("unexpected")
            val failure = failureOf { request(service, engineVersion = "0.8.6") }
            assertEquals(TranslationFailure.INCOMPATIBLE_ENGINE, failure)
            assertTrue(service.submissions.isEmpty())
        }

    @Test
    fun malformedProbeOrEngineErrorNeverReceivesMessage() =
        runBlocking {
            val malformed = FakeTranslator { _, callback -> callback.onTranslationResult("not empty") }
            assertEquals(TranslationFailure.ENGINE, failureOf { request(malformed) })
            assertEquals(listOf(listOf("", "", "")), malformed.submissions)
            val unavailable =
                FakeTranslator { _, callback -> callback.onTranslationError(failure(ErrorType.UNEXPECTED)) }
            assertEquals(TranslationFailure.ENGINE, failureOf { request(unavailable) })
            assertEquals(1, unavailable.submissions.size)
        }

    @Test
    fun actualDetectionOrMissingPackErrorsAreNotRetried() =
        runBlocking {
            val failures =
                listOf(
                    ErrorType.COULD_NOT_DETECT_LANGUAGE to TranslationFailure.DETECTION,
                    ErrorType.DETECTED_BUT_UNAVAILABLE to TranslationFailure.SOURCE_MODEL,
                    ErrorType.UNEXPECTED to TranslationFailure.ENGINE,
                )
            for ((type, expected) in failures) {
                val service =
                    FakeTranslator { submission, callback ->
                        val error = if (submission[0] == "") ErrorType.COULD_NOT_DETECT_LANGUAGE else type
                        callback.onTranslationError(failure(error))
                    }
                assertEquals(expected, failureOf { request(service) })
                assertEquals(2, service.submissions.size)
            }
        }

    @Test
    fun changedEngineIdentityReceivesNoMessageAfterProbe() =
        runBlocking {
            val service = readyTranslator("unexpected")
            var validations = 0
            val observed =
                failureOf {
                    request(service, beforeSubmission = {
                        validations += 1
                        if (validations == 2) throw TranslationException(TranslationFailure.UNVERIFIED_ENGINE)
                    })
                }
            assertEquals(TranslationFailure.UNVERIFIED_ENGINE, observed)
            assertEquals(listOf(listOf("", "", "")), service.submissions)
        }

    @Test
    fun cancellingCatalogWaitPreventsLateMessageSubmission() =
        runBlocking {
            val probe = CompletableDeferred<ITranslationCallback>()
            var active: CompletableDeferred<String>? = null
            val service = FakeTranslator { _, callback -> probe.complete(callback) }
            val pending = async { request(service, activeResult = { active = it }) }
            val callback = withTimeout(2_000L) { probe.await() }
            withTimeout(2_000L) { pending.cancelAndJoin() }
            callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
            assertTrue(pending.isCancelled)
            assertTrue(checkNotNull(active).isCancelled)
            assertEquals(listOf(listOf("", "", "")), service.submissions)
        }

    @Test
    fun cancelledTranslationCannotDeliverALateResult() =
        runBlocking {
            val resultCallback = CompletableDeferred<ITranslationCallback>()
            var active: CompletableDeferred<String>? = null
            val service =
                FakeTranslator { submission, callback ->
                    if (submission[0] == "") {
                        callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
                    } else {
                        resultCallback.complete(callback)
                    }
                }
            val pending = async { request(service, activeResult = { active = it }) }
            val callback = withTimeout(2_000L) { resultCallback.await() }
            withTimeout(2_000L) { pending.cancelAndJoin() }
            callback.onTranslationResult("Late synthetic output")
            assertTrue(pending.isCancelled)
            assertTrue(checkNotNull(active).isCancelled)
            assertEquals(2, service.submissions.size)
        }

    private fun readyTranslator(output: String): FakeTranslator =
        FakeTranslator { submission, callback ->
            if (submission[0] == "") {
                callback.onTranslationError(failure(ErrorType.COULD_NOT_DETECT_LANGUAGE))
            } else {
                callback.onTranslationResult(output)
            }
        }

    /** Submits the fixed message through the production adapter as the given reviewed engine release. */
    private suspend fun request(
        service: ITranslationService,
        source: String = "en",
        target: String = "es",
        engineVersion: String = "0.8.5",
        beforeSubmission: suspend () -> Unit = {},
        activeResult: (CompletableDeferred<String>) -> Unit = {},
    ): String =
        translateOnBoundTranslator(service, engineVersion, "Hello world", source, target) {
            activeResult(it)
            beforeSubmission()
        }

    private suspend fun failureOf(operation: suspend () -> String): TranslationFailure =
        (runCatching { operation() }.exceptionOrNull() as TranslationException).failure

    private fun failure(type: Byte): TranslationError = TranslationError().apply { this.type = type }

    private class FakeTranslator(
        private val deliver: (List<String?>, ITranslationCallback) -> Unit,
    ) : ITranslationService.Stub() {
        val submissions = CopyOnWriteArrayList<List<String?>>()

        override fun translate(
            text: String?,
            source: String?,
            target: String?,
            callback: ITranslationCallback?,
        ) {
            val submission = listOf(text, source, target)
            submissions.add(submission)
            deliver(submission, checkNotNull(callback))
        }
    }
}
