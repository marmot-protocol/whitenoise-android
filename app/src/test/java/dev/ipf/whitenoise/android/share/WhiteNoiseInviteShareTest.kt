package dev.ipf.whitenoise.android.share

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WhiteNoiseInviteShareTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A named sender with a complete npub gets an invite the friend can act on after installing. */
    @Test
    fun namedSenderWithNpubSharesPersonalInvite() {
        val text = inviteMessageText(" Carol ", " $SENDER_NPUB ")

        assertEquals(AppText.Resource(R.string.invite_message_personal, listOf("Carol", SENDER_NPUB)), text)
        assertEquals(
            "I’m Carol on White Noise, a private messenger that doesn’t need a phone number. Install it, tap New " +
                "chat and paste my key to message me: $SENDER_NPUB\nDownload: https://www.whitenoise.chat/download",
            text.resolve(context),
        )
    }

    /** Without a visible name or a complete npub the generic invite stays. */
    @Test
    fun missingNameOrNpubFallsBackToGenericInvite() {
        val generic = AppText.Resource(R.string.invite_message)

        assertEquals(generic, inviteMessageText(null, SENDER_NPUB))
        assertEquals(generic, inviteMessageText("  ", SENDER_NPUB))
        assertEquals(generic, inviteMessageText("Carol", null))
        assertEquals(generic, inviteMessageText("Carol", ""))
        assertEquals(generic, inviteMessageText("Carol", "npub1carol…xyz"))
        assertEquals(generic, inviteMessageText("Carol", "ab".repeat(32)))
        assertEquals(generic, inviteMessageText("Carol", SENDER_NPUB.dropLast(1) + "b"))
    }

    private companion object {
        const val SENDER_NPUB = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"
    }
}
