package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.chats.newchat.shouldAutoSelectResolvedIdentifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactPickerAutoSelectTest {
    @Test
    fun autoSelectsOnlyWhenOptedInIdleAddableAndUnselected() {
        assertTrue(
            shouldAutoSelectResolvedIdentifier(
                autoSelectResolvedIdentifier = true,
                busy = false,
                alreadyMember = false,
                isSelected = false,
            ),
        )
    }

    @Test
    fun doesNotAutoSelectWhenPickerDidNotOptIn() {
        assertFalse(
            shouldAutoSelectResolvedIdentifier(
                autoSelectResolvedIdentifier = false,
                busy = false,
                alreadyMember = false,
                isSelected = false,
            ),
        )
    }

    @Test
    fun doesNotAutoSelectWhileBusy() {
        assertFalse(
            shouldAutoSelectResolvedIdentifier(
                autoSelectResolvedIdentifier = true,
                busy = true,
                alreadyMember = false,
                isSelected = false,
            ),
        )
    }

    @Test
    fun doesNotAutoSelectAlreadyMember() {
        assertFalse(
            shouldAutoSelectResolvedIdentifier(
                autoSelectResolvedIdentifier = true,
                busy = false,
                alreadyMember = true,
                isSelected = false,
            ),
        )
    }

    @Test
    fun doesNotRepeatForAlreadySelectedCandidate() {
        assertFalse(
            shouldAutoSelectResolvedIdentifier(
                autoSelectResolvedIdentifier = true,
                busy = false,
                alreadyMember = false,
                isSelected = true,
            ),
        )
    }
}
