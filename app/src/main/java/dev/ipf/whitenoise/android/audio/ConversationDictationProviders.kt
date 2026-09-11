package dev.ipf.whitenoise.android.audio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.speech.RecognitionService
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat

internal enum class ConversationDictationSurfaceKind { Service, Activity, Keyboard }

internal enum class ConversationDictationProviderCapability { InApp, Activity, KeyboardOnly, Unknown }

internal data class ConversationDictationSurface(
    val component: ComponentName,
    val versionCode: Long,
    val appName: String,
    val engineName: String,
    val kind: ConversationDictationSurfaceKind,
)

/** Exact installed platform identity. Labels and probe answers are refreshed, never trusted as identity. */
internal data class ConversationDictationProviderChoice(
    val packageName: String,
    val versionCode: Long,
    val appName: String,
    val engineName: String,
    val service: ComponentName? = null,
    val activity: ComponentName? = null,
    val keyboard: ComponentName? = null,
    val callerAudio: ConversationDictationCallerAudioRequirement = ConversationDictationCallerAudioRequirement.Unknown,
) {
    val component: ComponentName get() = requireNotNull(service ?: activity ?: keyboard)
    val capability: ConversationDictationProviderCapability
        get() =
            when {
                service != null && callerAudio == ConversationDictationCallerAudioRequirement.Supported ->
                    ConversationDictationProviderCapability.InApp
                activity != null -> ConversationDictationProviderCapability.Activity
                service == null && keyboard != null -> ConversationDictationProviderCapability.KeyboardOnly
                else -> ConversationDictationProviderCapability.Unknown
            }

    fun sameInstallation(other: ConversationDictationProviderChoice): Boolean =
        packageName == other.packageName &&
            versionCode == other.versionCode &&
            service == other.service &&
            activity == other.activity &&
            keyboard == other.keyboard
}

internal data class ConversationDictationProvider(
    val packageName: String,
    val appName: String,
    val choices: List<ConversationDictationProviderChoice>,
)

// Exact compatibility exclusion: FUTO's placeholder is not a transcription engine. Do not infer
// that other providers' classes containing "Dummy", "Test", etc. are unusable.
private val FUTO_PLACEHOLDER = ComponentName("org.futo.voiceinput", "org.futo.voiceinput.DummyService")
private const val BIND_RECOGNITION_SERVICE = "android.permission.BIND_RECOGNITION_SERVICE"

@Suppress("MaxLineLength") // The named surface and provider types keep this one-argument transformation explicit.
internal fun conversationDictationProviders(surfaces: List<ConversationDictationSurface>): List<ConversationDictationProvider> =
    surfaces
        .filterNot { it.kind == ConversationDictationSurfaceKind.Service && it.component == FUTO_PLACEHOLDER }
        .distinctBy { it.component to it.kind }
        .groupBy { it.component.packageName }
        .map { (pkg, entries) ->
            val services = entries.filter { it.kind == ConversationDictationSurfaceKind.Service }
            val activities = entries.filter { it.kind == ConversationDictationSurfaceKind.Activity }
            val keyboards = entries.filter { it.kind == ConversationDictationSurfaceKind.Keyboard }
            val primary =
                if (services.isEmpty()) {
                    activities.ifEmpty { keyboards }
                } else {
                    services + activities.takeIf { it.size > 1 }.orEmpty()
                }
            val choices =
                primary.map { entry ->
                    ConversationDictationProviderChoice(
                        packageName = pkg,
                        versionCode = entry.versionCode,
                        appName = entry.appName,
                        engineName = entry.engineName,
                        service = entry.component.takeIf { entry.kind == ConversationDictationSurfaceKind.Service },
                        activity =
                            if (entry.kind == ConversationDictationSurfaceKind.Activity) {
                                entry.component
                            } else {
                                activities.singleOrNull()?.component
                            },
                        keyboard =
                            if (entry.kind == ConversationDictationSurfaceKind.Keyboard) {
                                entry.component
                            } else {
                                keyboards.singleOrNull()?.component
                            },
                    )
                }
            ConversationDictationProvider(pkg, entries.first().appName, choices)
        }.sortedBy { it.appName.lowercase() }

internal fun resolveConversationDictationProvider(
    androidSelected: ComponentName?,
    saved: ConversationDictationProviderChoice?,
    choices: List<ConversationDictationProviderChoice>,
): ConversationDictationProviderChoice? =
    choices.firstOrNull { androidSelected != null && it.service == androidSelected }
        ?: saved?.let { stored -> choices.firstOrNull(stored::sameInstallation) }
        ?: choices.singleOrNull()

/** Short-lived discovery snapshot, with explicit enabled/exported/permission and version checks. */
@Suppress("DEPRECATION")
internal fun discoverConversationDictationProviders(context: Context): List<ConversationDictationProvider> {
    val pm = context.packageManager

    fun surface(
        info: ComponentInfo,
        kind: ConversationDictationSurfaceKind,
    ): ConversationDictationSurface? =
        runCatching {
            if (!pm.isRunnableDictationComponent(info)) return@runCatching null
            val version = PackageInfoCompat.getLongVersionCode(pm.getPackageInfo(info.packageName, 0))
            ConversationDictationSurface(
                ComponentName(info.packageName, info.name),
                version,
                info.applicationInfo.loadLabel(pm).toString(),
                info.loadLabel(pm).toString(),
                kind,
            )
        }.getOrNull()
    val services =
        pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).mapNotNull { resolved ->
            resolved.serviceInfo
                ?.takeIf { it.permission == BIND_RECOGNITION_SERVICE }
                ?.let { surface(it, ConversationDictationSurfaceKind.Service) }
        }
    val activities =
        pm.queryIntentActivities(conversationDictationRecognitionActivityIntent(), 0).mapNotNull { resolved ->
            resolved.activityInfo
                ?.takeIf {
                    it.permission == null ||
                        ContextCompat.checkSelfPermission(context, it.permission) == PackageManager.PERMISSION_GRANTED
                }?.let { surface(it, ConversationDictationSurfaceKind.Activity) }
        }
    val keyboards =
        context.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty().mapNotNull { ime ->
            // Gboard exposes voice typing through its keyboard, not a public RecognitionService.
            // Other IMEs must advertise an explicit voice subtype; installation alone is no proof.
            val voice =
                ime.packageName == "com.google.android.inputmethod.latin" ||
                    (0 until ime.subtypeCount).any { ime.getSubtypeAt(it).mode == "voice" }
            if (voice) surface(ime.serviceInfo, ConversationDictationSurfaceKind.Keyboard) else null
        }
    val prefs = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    val verdicts = ConversationDictationCallerAudioVerdicts({ prefs.getString(it, null) }, { _, _ -> })
    return conversationDictationProviders(services + activities + keyboards).map { provider ->
        provider.copy(
            choices =
                provider.choices.map { choice ->
                    choice.copy(
                        callerAudio =
                            choice.service?.let { verdicts.recorded(it, choice.versionCode) }
                                ?: ConversationDictationCallerAudioRequirement.Unknown,
                    )
                },
        )
    }
}

private fun PackageManager.isRunnableDictationComponent(info: ComponentInfo): Boolean {
    val component = ComponentName(info.packageName, info.name)
    val setting = getComponentEnabledSetting(component)
    val enabled =
        setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (setting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.enabled)
    val appSetting = getApplicationEnabledSetting(info.packageName)
    val appEnabled =
        appSetting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
            (appSetting == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && info.applicationInfo.enabled)
    return enabled &&
        appEnabled &&
        info.exported &&
        info.applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY == 0
}
