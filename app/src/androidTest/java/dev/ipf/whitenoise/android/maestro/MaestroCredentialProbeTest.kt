package dev.ipf.whitenoise.android.maestro

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import androidx.biometric.BiometricManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.state.isAppLockCredentialAvailable
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only OS observation; does not launch an Activity or claim accessibility. */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class MaestroCredentialProbeTest {
    @Test
    fun observeCredential() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
        requireMaestroEmulator()
        val arguments = InstrumentationRegistry.getArguments()
        val generation = checkNotNull(arguments.getString("fixtureGeneration"))
        val stage = checkNotNull(arguments.getString("credentialStage"))
        require(generation.matches(Regex("[a-f0-9]{32}")))
        require(stage in setOf("baseline", "installed", "before-clear", "restored"))
        val biometric = BiometricManager.from(context)
        val strong = biometric.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val noBiometric =
            strong == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ||
                strong == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        val keyguard = checkNotNull(context.getSystemService(KeyguardManager::class.java))
        val row =
            JSONObject()
                .put("schema", 1)
                .put("generation", generation)
                .put("stage", stage)
                .put("package", context.packageName)
                .put("user", UserHandle.getUserHandleForUid(Process.myUid()).identifier)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("qemu", true) // requireMaestroEmulator independently checked the actual property.
                .put("secure", keyguard.isDeviceSecure)
                .put("credentialAvailable", isAppLockCredentialAvailable(context))
                .put("noBiometricAlternative", noBiometric)
        instrumentation.sendStatus(0, Bundle().apply { putString("maestroCredential", row.toString()) })
    }
}
