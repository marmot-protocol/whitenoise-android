package dev.ipf.whitenoise.android.audio

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Keeps Send and Paste delivering past silent final tails without ever dropping possible speech. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationDictationFinalTailCoverageTest {
    /** A capture-silent final chunk after Send is released on its first empty result. */
    @Test
    fun silentFinalTailAfterSendDelivers() {
        ConversationDictationControllerTest().silentFinalTailAfterSendDeliversOnFirstEmptyResult()
    }

    /** A speech-bearing final chunk that stays empty is retried and never sends the prefix alone. */
    @Test
    fun speechBearingFinalTailIsNeverDropped() {
        ConversationDictationControllerTest().fullyFedFinalNoMatchNeverSendsThePrefixAndRetryIncludesTheTail()
    }

    /** Focus loss lets an explicit Send finish but never sends after automatic silence completion. */
    @Test
    fun focusLossContinuesOnlyExplicitChoices() {
        ConversationDictationControllerTest().focusInterruptionAfterSendStillSendsOnce()
        ConversationDictationControllerTest().focusInterruptionDuringAutomaticSendNeverSends()
    }
}
