package dev.ipf.whitenoise.android.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ART re-verifies our bytecode on device, and D8 can emit debug-flavor code
 * that the verifier rejects even though every JVM-side gate passed — the
 * release pipeline has a CI guard for this, the debug pipeline did not.
 * Loading the class here forces verification, so a miscompiled conversation
 * screen fails this test instead of crashing the first chat a user opens.
 */
@RunWith(AndroidJUnit4::class)
class ConversationScreenBytecodeVerifyTest {
    @Test
    fun conversationScreenClassPassesBytecodeVerification() {
        Class.forName("dev.ipf.whitenoise.android.ui.conversation.ConversationScreenKt")
    }
}
