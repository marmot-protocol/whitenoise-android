package dev.ipf.darkmatter.core

internal const val ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH = 12

internal enum class EncryptedBackupPassphraseStrength {
    TooShort,
    Weak,
    Fair,
    Strong,
}

internal fun groupedEncryptedBackup(value: String): String = value.chunked(4).joinToString(" ")

internal fun encryptedBackupPassphraseInputsValid(
    passphrase: String,
    confirmation: String,
): Boolean = passphrase.length >= ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH && passphrase == confirmation

internal fun encryptedBackupPassphraseStrength(passphrase: String): EncryptedBackupPassphraseStrength {
    if (passphrase.length < ENCRYPTED_BACKUP_MIN_PASSPHRASE_LENGTH) {
        return EncryptedBackupPassphraseStrength.TooShort
    }

    val classes =
        listOf(
            passphrase.any { it.isLowerCase() },
            passphrase.any { it.isUpperCase() },
            passphrase.any { it.isDigit() },
            passphrase.any { !it.isLetterOrDigit() },
        ).count { it }
    val uniqueChars = passphrase.toSet().size

    // Penalize patterns class-counting misses (repeats/sequences/common words);
    // a passphrase carrying any of them is never Strong.
    val weaknesses =
        listOf(
            hasRepeatedRun(passphrase),
            hasSequentialRun(passphrase),
            containsCommonPattern(passphrase),
        ).count { it }

    val score = classes + strengthBonus(passphrase.length >= 16) + strengthBonus(uniqueChars >= 10) - weaknesses

    return when {
        score >= 5 && weaknesses == 0 -> EncryptedBackupPassphraseStrength.Strong
        score >= 3 -> EncryptedBackupPassphraseStrength.Fair
        else -> EncryptedBackupPassphraseStrength.Weak
    }
}

private fun strengthBonus(condition: Boolean): Int = if (condition) 1 else 0

private fun hasRepeatedRun(value: String): Boolean {
    var run = 1
    for (i in 1 until value.length) {
        run = if (value[i] == value[i - 1]) run + 1 else 1
        if (run >= 3) return true
    }
    return false
}

private fun hasSequentialRun(value: String): Boolean {
    if (value.length < 4) return false
    var ascending = 1
    var descending = 1
    for (i in 1 until value.length) {
        val delta = value[i].code - value[i - 1].code
        ascending = if (delta == 1) ascending + 1 else 1
        descending = if (delta == -1) descending + 1 else 1
        if (ascending >= 4 || descending >= 4) return true
    }
    return false
}

private fun containsCommonPattern(value: String): Boolean {
    val lower = value.lowercase()
    return COMMON_PASSWORD_PATTERNS.any { it in lower }
}

private val COMMON_PASSWORD_PATTERNS =
    listOf("password", "passwd", "qwerty", "azerty", "letmein", "welcome", "admin", "iloveyou", "monkey", "dragon")
