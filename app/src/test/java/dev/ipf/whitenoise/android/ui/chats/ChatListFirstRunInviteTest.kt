package dev.ipf.whitenoise.android.ui.chats

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.topBarTestAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ChatListFirstRunInviteTest {
    @get:Rule val fileProviderCache = FileProviderStrategyCacheRule()

    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun firstRunInviteSharesDownloadCardAndNewChatRetainsMeasuredOrigin() {
        val activity = composeRule.activity
        val sharePackage = "test.invite.target"
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_SEND).setType("image/png"),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        applicationInfo = ApplicationInfo().apply { packageName = sharePackage }
                        packageName = sharePackage
                        name = "$sharePackage.ShareActivity"
                        exported = true
                    }
            },
        )
        val appState = topBarTestAppState(activity.applicationContext, "first-run", "ab".repeat(32))
        val newChat = mutableListOf<String>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                EmptyChats(
                    appState = appState,
                    onCreate = { newChat.add("open") },
                    onFirstRunHelp = {},
                    onCreateOrigin = { if (it != null) newChat.add("origin") },
                )
            }
        }

        composeRule.onNodeWithText(activity.getString(R.string.new_message_invite_friend)).performClick()
        val activityShadow = shadowOf(activity)
        composeRule.waitUntil(10_000) { activityShadow.peekNextStartedActivity() != null }
        val chooser = activityShadow.nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("image/png", send.type)
        assertEquals(activity.getString(R.string.invite_message), send.getStringExtra(Intent.EXTRA_TEXT))
        assertNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

        composeRule.onNodeWithText(activity.getString(R.string.first_run_new_chat)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("origin", "open"), newChat) }
    }
}
