package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TtsAutoReadPolicyTest {
    @Test
    fun resolveConversationAutoReadMatrix() {
        val cases =
            listOf(
                Triple(false, null, false),
                Triple(false, TtsAutoReadOverride.ON, true),
                Triple(false, TtsAutoReadOverride.OFF, false),
                Triple(true, null, true),
                Triple(true, TtsAutoReadOverride.ON, true),
                Triple(true, TtsAutoReadOverride.OFF, false),
            )
        for ((globalDefault, override, expected) in cases) {
            assertEquals(
                "global=$globalDefault override=$override",
                expected,
                resolveConversationAutoRead(globalDefault, override),
            )
        }
    }
}
