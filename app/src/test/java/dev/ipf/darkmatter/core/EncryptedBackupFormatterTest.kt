package dev.ipf.darkmatter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedBackupFormatterTest {
    @Test
    fun groupedEncryptedBackupGroupsInFours() {
        assertEquals(
            "ncry ptse c1ab cdef g",
            groupedEncryptedBackup("ncryptsec1abcdefg"),
        )
    }

    @Test
    fun passphraseInputsRequireMinimumLengthAndExactConfirmation() {
        assertFalse(encryptedBackupPassphraseInputsValid("short", "short"))
        assertFalse(encryptedBackupPassphraseInputsValid("correct horse", "correct-house"))
        assertTrue(encryptedBackupPassphraseInputsValid("correct horse", "correct horse"))
    }

    @Test
    fun passphraseStrengthClassifiesLengthAndVariety() {
        assertEquals(
            EncryptedBackupPassphraseStrength.TooShort,
            encryptedBackupPassphraseStrength("test123"),
        )
        assertEquals(
            EncryptedBackupPassphraseStrength.Weak,
            encryptedBackupPassphraseStrength("aaaaaaaaaaaa"),
        )
        assertEquals(
            EncryptedBackupPassphraseStrength.Fair,
            encryptedBackupPassphraseStrength("Correcthorse7"),
        )
        assertEquals(
            EncryptedBackupPassphraseStrength.Strong,
            encryptedBackupPassphraseStrength("correct Horse 7!"),
        )
    }

    @Test
    fun passphraseStrengthPenalizesGuessablePatterns() {
        assertNotEquals(
            EncryptedBackupPassphraseStrength.Strong,
            encryptedBackupPassphraseStrength("Password1234!!!!"),
        )
        assertEquals(
            EncryptedBackupPassphraseStrength.Weak,
            encryptedBackupPassphraseStrength("abcdefghijkl"),
        )
    }
}
