package dev.ipf.whitenoise.android.audio.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsSyllablesTest {
    @Test
    fun countsVowelGroups() {
        assertEquals(1, TtsSyllables.inWord("strengths"))
        // Adjacent vowels merge into one group; the heuristic is allowed to be
        // off by one where real syllabification splits them ("i-de-a").
        assertEquals(2, TtsSyllables.inWord("idea"))
        assertEquals(2, TtsSyllables.inWord("about"))
        assertEquals(4, TtsSyllables.inWord("calibration"))
    }

    @Test
    fun silentEndingEIsNotASyllable() {
        assertEquals(1, TtsSyllables.inWord("make"))
        assertEquals(1, TtsSyllables.inWord("makes"))
        assertEquals(1, TtsSyllables.inWord("house"))
    }

    @Test
    fun voicedEndingsKeepTheirSyllable() {
        assertEquals(2, TtsSyllables.inWord("table"))
        assertEquals(2, TtsSyllables.inWord("boxes"))
        assertEquals(2, TtsSyllables.inWord("houses"))
    }

    @Test
    fun silentEdIsNotASyllable() {
        assertEquals(1, TtsSyllables.inWord("walked"))
        assertEquals(1, TtsSyllables.inWord("played"))
    }

    @Test
    fun voicedEdKeepsItsSyllable() {
        assertEquals(2, TtsSyllables.inWord("wanted"))
        assertEquals(2, TtsSyllables.inWord("needed"))
    }

    @Test
    fun yCarriesASyllableAtWordEndButNotAtOnset() {
        assertEquals(1, TtsSyllables.inWord("sky"))
        assertEquals(1, TtsSyllables.inWord("rhythm"))
        assertEquals(1, TtsSyllables.inWord("yes"))
        assertEquals(2, TtsSyllables.inWord("beyond"))
        assertEquals(2, TtsSyllables.inWord("happy"))
    }

    @Test
    fun tokensWithoutLettersHaveNoSyllables() {
        assertEquals(0, TtsSyllables.inWord("123"))
        assertEquals(0, TtsSyllables.inWord("—"))
        assertEquals(0, TtsSyllables.inWord(""))
    }

    @Test
    fun everyLetteredTokenHasAtLeastOneSyllable() {
        assertEquals(1, TtsSyllables.inWord("hmm"))
        assertEquals(1, TtsSyllables.inWord("b2b"))
    }
}
