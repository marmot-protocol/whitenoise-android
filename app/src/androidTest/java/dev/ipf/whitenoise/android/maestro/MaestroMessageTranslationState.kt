package dev.ipf.whitenoise.android.maestro

import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.translation.ConversationTranslationSession
import dev.ipf.whitenoise.android.translation.OFFLINE_TRANSLATOR_PACKAGE
import dev.ipf.whitenoise.android.translation.OfflineTranslatorRoute
import dev.ipf.whitenoise.android.translation.TranslationEngine
import dev.ipf.whitenoise.android.translation.TranslationEngineIdentity
import dev.ipf.whitenoise.android.translation.TranslationException
import dev.ipf.whitenoise.android.translation.TranslationFailure
import dev.ipf.whitenoise.android.translation.TranslationRendition
import dev.ipf.whitenoise.android.translation.TranslationSource
import dev.ipf.whitenoise.android.translation.TranslationTrust
import dev.ipf.whitenoise.android.translation.offlineTranslatorRoute
import kotlinx.coroutines.CoroutineScope

internal class MaestroMessageTranslationState(
    private val fixture: MaestroPresentationFixture,
    scope: CoroutineScope,
) {
    val source = TranslationSource("fixture-message", "Fixture source text", "en")
    var selected by mutableStateOf<String?>(null)
        private set
    var showing by mutableStateOf(true)
        private set
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
        check(offlineTranslatorRoute() == OfflineTranslatorRoute.GITHUB_RELEASE) {
            "Translation recovery expects the reviewed GitHub acquisition route"
        }
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

    fun targetSelected(target: String) {
        check(selected == null)
        selected = target
        fixture.record("target-selected")
    }

    fun dismiss() {
        if (selected == null) {
            check(attempts.isEmpty()) { "Cancellation reached the engine" }
            fixture.finish("dismiss")
        } else {
            fixture.record("dialog-dismiss")
            showing = false
        }
    }

    fun verifyRendition(rendition: TranslationRendition?) {
        if (showing || rendition == null) return
        val target = if (fixture.scenario.endsWith("target")) "ar" else "en"
        val from = if (fixture.scenario.endsWith("source")) "ar" else ""
        check(selected == target && attempts == listOf(from to target))
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
