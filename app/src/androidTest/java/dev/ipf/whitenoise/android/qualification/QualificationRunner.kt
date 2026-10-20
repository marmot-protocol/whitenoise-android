package dev.ipf.whitenoise.android.qualification

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.runner.AndroidJUnitRunner
import dev.ipf.whitenoise.android.WhiteNoiseApplication

/** Substitutes bootstrap only during instrumentation; ordinary MainActivity launches use the manifest Application. */
class QualificationRunner : AndroidJUnitRunner() {
    /** A production or personal-device package can never receive this synthetic database fixture. */
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        check(context.packageName == "dev.ipf.whitenoise.android.mdkqualification")
        return super.newApplication(cl, QualificationApplication::class.java.name, context)
    }
}

/** Prevents a scheduled cold preflight from opening the fixture root before its measured migration phase. */
class QualificationApplication : WhiteNoiseApplication() {
    /** The hosted driver must disable only this disposable package's background entry points before instrumentation. */
    override fun onCreate() {
        check(applicationInfo.flags and ApplicationInfo.FLAG_TEST_ONLY != 0)
        val components =
            listOf(
                "androidx.work.impl.background.systemjob.SystemJobService",
                "dev.ipf.whitenoise.android.notifications.BackgroundConnectionBootReceiver",
            )
        for (name in components) {
            val state = packageManager.getComponentEnabledSetting(ComponentName(this, name))
            // PackageManager may normalize disable-user to DISABLED for individual components.
            check(
                state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            ) { "Fixture background component $name must be disabled; observed state=$state" }
        }
        // No production onCreate: the native fixture owns files/Marmot for this instrumentation process.
    }
}
