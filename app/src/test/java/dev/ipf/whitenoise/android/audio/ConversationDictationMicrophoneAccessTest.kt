package dev.ipf.whitenoise.android.audio

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.media.AudioManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppOpsManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], shadows = [PrivacyGatedAppOps::class])
class ConversationDictationMicrophoneAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val platform = AndroidConversationDictationPlatform(context)
    private val appOps = context.getSystemService(AppOpsManager::class.java)

    @Before
    fun grantRuntimePermission() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun globalMicrophoneToggleLeavesGrantedRuntimePermissionUsableForNativeRecovery() {
        setMode(AppOpsManager.MODE_FOREGROUND)
        context.getSystemService(AudioManager::class.java).isMicrophoneMute = true

        // Android 16's effective check includes global sensor privacy, but creating the recognizer
        // is what gives Android the opportunity to present its native microphone-unblock prompt.
        assertEquals(
            AppOpsManager.MODE_IGNORED,
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName),
        )
        assertEquals(ConversationDictationMicrophoneAccess.Granted, platform.microphoneAccess())
    }

    @Test
    fun whileInUseAndAllowedGrantsRemainUsableWhenUnmuted() {
        for (mode in listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_FOREGROUND)) {
            setMode(mode)
            assertEquals(ConversationDictationMicrophoneAccess.Granted, platform.microphoneAccess())
        }
    }

    @Test
    fun globalMicrophonePrivacyMayReportRawAndEffectiveIgnoredForAGrantedPermission() {
        setMode(AppOpsManager.MODE_IGNORED)
        context.getSystemService(AudioManager::class.java).isMicrophoneMute = true

        // The GrapheneOS Android 17 fixture reports MODE_IGNORED from both queries while global
        // microphone privacy is active, so raw mode cannot distinguish that overlay from app policy.
        assertEquals(
            AppOpsManager.MODE_IGNORED,
            appOps.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName),
        )
        assertEquals(
            AppOpsManager.MODE_IGNORED,
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName),
        )
        assertEquals(ConversationDictationMicrophoneAccess.Granted, platform.microphoneAccess())
    }

    @Test
    fun hardAppOpDenialRemainsActionable() {
        setMode(AppOpsManager.MODE_ERRORED)

        assertEquals(ConversationDictationMicrophoneAccess.AppOpDenied, platform.microphoneAccess())
    }

    @Test
    fun missingRuntimePermissionStillRequestsAndroidPermission() {
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        setMode(AppOpsManager.MODE_FOREGROUND)

        assertEquals(ConversationDictationMicrophoneAccess.RuntimePermissionRequired, platform.microphoneAccess())
    }

    private fun setMode(mode: Int) {
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_RECORD_AUDIO, Process.myUid(), context.packageName, mode)
    }
}

/** Models the effective privacy denial observed on the physical Android 17 fixture. */
@Implements(AppOpsManager::class)
class PrivacyGatedAppOps : ShadowAppOpsManager() {
    @Implementation
    @Suppress("OVERRIDE_DEPRECATION")
    public override fun unsafeCheckOpNoThrow(
        op: String,
        uid: Int,
        packageName: String,
    ): Int {
        val context = ApplicationProvider.getApplicationContext<Application>()
        return if (context.getSystemService(AudioManager::class.java).isMicrophoneMute) {
            AppOpsManager.MODE_IGNORED
        } else {
            super.checkOpNoThrow(op, uid, packageName)
        }
    }
}
