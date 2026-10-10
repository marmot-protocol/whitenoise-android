package dev.ipf.whitenoise.android.maestro

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.runner.AndroidJUnitRunner
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionBootReceiver
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Selected only by maestro-runtime.init.gradle; ordinary instrumentation is unchanged. */
class MaestroFixtureRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        check(context.packageName == FIXTURE_PACKAGE) { "Isolated Maestro package required" }
        return super.newApplication(cl, MaestroFixtureApplication::class.java.name, context)
    }

    companion object {
        const val FIXTURE_PACKAGE = "dev.ipf.whitenoise.android.maestrolab"
    }
}

/** Supplies generated state without running the ordinary Application bootstrap. */
class MaestroFixtureApplication : WhiteNoiseApplication() {
    internal lateinit var fixtureState: WhiteNoiseAppState

    override val appState: WhiteNoiseAppState
        get() = fixtureState

    // Intentionally omit WhiteNoiseApplication.onCreate: it schedules production work.
    override fun onCreate() {
        check(applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0) {
            "The isolated native fixture must be built as a test-only APK"
        }
        check(
            packageManager.getComponentEnabledSetting(
                ComponentName(this, BackgroundConnectionBootReceiver::class.java),
            ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        ) { "Production boot receiver must be disabled for the isolated native fixture" }
        DictationDiagnostics.attach(this)
        PerformanceDiagnostics.bind(this)
        VoicePlaybackController.attach(this)
    }
}
