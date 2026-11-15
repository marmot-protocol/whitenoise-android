package dev.ipf.whitenoise.android.maestro

import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.translation.ConversationTranslationSession
import dev.ipf.whitenoise.android.translation.OFFLINE_TRANSLATOR_PACKAGE
import dev.ipf.whitenoise.android.translation.OfflineTranslatorLanguageCatalog
import dev.ipf.whitenoise.android.translation.OfflineTranslatorRoute
import dev.ipf.whitenoise.android.translation.TranslationEngine
import dev.ipf.whitenoise.android.translation.TranslationEngineIdentity
import dev.ipf.whitenoise.android.translation.TranslationException
import dev.ipf.whitenoise.android.translation.TranslationFailure
import dev.ipf.whitenoise.android.translation.TranslationRendition
import dev.ipf.whitenoise.android.translation.TranslationSource
import dev.ipf.whitenoise.android.translation.TranslationTrust
import dev.ipf.whitenoise.android.translation.offlineTranslatorRoute
import dev.ipf.whitenoise.android.translation.translateMessage
import kotlinx.coroutines.CoroutineScope

internal class MaestroMessageTranslationState(
    private val fixture: MaestroPresentationFixture,
    scope: CoroutineScope,
) {
    private val fallback = fixture.scenario == "modal-translation-fallback"

    /** The fallback scenario starts from an unsupported chat target so the request must use the device language. */
    val source = TranslationSource("fixture-message", "Fixture source text", if (fallback) "xx" else "en")
    var translated by mutableStateOf<TranslationSource?>(null)
        private set
    var noticeOpen by mutableStateOf(false)
        private set
    private var noticeAccepted = false
    private var originalShown = false
    private val attempts = mutableListOf<Pair<String, String>>()
    val failure =
        when (fixture.scenario) {
            "modal-translation-missing" -> TranslationFailure.MISSING_ENGINE
            "modal-translation-unverified" -> TranslationFailure.UNVERIFIED_ENGINE
            "modal-translation-detection" -> TranslationFailure.DETECTION
            "modal-translation-model" -> TranslationFailure.SOURCE_MODEL
            "modal-translation-engine" -> TranslationFailure.ENGINE
            else -> null
        }
    val session = ConversationTranslationSession(scope, { engine() }, { true })

    init {
        if (failure != null) requireAcquisitionPrecondition()
    }

    /** Fail before the UI check when this device has an engine or the reviewed acquisition route changed. */
    @Suppress("DEPRECATION") // The int-flags lookup also works on the fixture's minimum Android API.
    private fun requireAcquisitionPrecondition() {
        val packages = InstrumentationRegistry.getInstrumentation().targetContext.packageManager
        val installed =
            try {
                packages.getPackageInfo(OFFLINE_TRANSLATOR_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        check(!installed) { "Translation recovery requires an absent Offline Translator package" }
        val route = offlineTranslatorRoute()
        check(route == OfflineTranslatorRoute.GITHUB_RELEASE) {
            "Translation recovery expects the reviewed GitHub acquisition route"
        }
        fixture.recordTranslationAcquisition(enginePackageAbsent = !installed, acquisitionRoute = route)
        Log.i(
            "Maestro",
            "translation-precondition scenario=${fixture.scenario} " +
                "enginePackageAbsent=true acquisitionRoute=GITHUB_RELEASE",
        )
    }

    private fun engine(): TranslationEngine {
        if (failure != null) throw TranslationException(failure)
        return object : TranslationEngine {
            override val identity = TranslationEngineIdentity("fixture.local", "fixture", 1)
            override val trust = TranslationTrust.LOCAL

            override suspend fun translate(
                text: String,
                source: String,
                target: String,
            ): String {
                check(text == "Fixture source text")
                attempts += source to target
                return "Fixture translated text"
            }
        }
    }

    /** Only the default scenario starts from a fresh install, so only it asks for the notice first. */
    fun requestTranslate() {
        if (fixture.scenario.endsWith("default") && !noticeAccepted) noticeOpen = true else translate()
    }

    fun acceptNotice() {
        check(attempts.isEmpty())
        noticeOpen = false
        noticeAccepted = true
        fixture.record("notice-accepted")
        translate()
    }

    fun dismissNotice() {
        noticeOpen = false
    }

    private fun translate() {
        check(translated == null)
        translated = session.translateMessage(source)
        fixture.record("translate-tapped")
    }

    fun finishNotice() {
        check(failure != null && session.failure == failure && attempts.isEmpty())
        fixture.finish("notice-shown")
    }

    fun verifyRendition(rendition: TranslationRendition?) {
        if (rendition == null) return
        val target = if (fallback) OfflineTranslatorLanguageCatalog.systemTarget() else "en"
        check(translated?.target == target && attempts == listOf("" to target))
        check(rendition.text == "Fixture translated text")
        if (fixture.scenario.endsWith("toggle")) {
            if (!rendition.showTranslation) {
                originalShown = true
                fixture.record("original-shown")
            } else if (originalShown) {
                fixture.finish("translation-shown")
            }
        } else {
            fixture.finish("translated")
        }
    }
}
