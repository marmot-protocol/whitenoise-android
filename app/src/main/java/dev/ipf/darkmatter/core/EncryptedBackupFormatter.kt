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
    val score = classes + strengthBonus(passphrase.length >= 16) + strengthBonus(uniqueChars >= 8)

    return when {
        score >= 5 -> EncryptedBackupPassphraseStrength.Strong
        score >= 4 -> EncryptedBackupPassphraseStrength.Fair
        else -> EncryptedBackupPassphraseStrength.Weak
    }
}

private fun strengthBonus(condition: Boolean): Int = if (condition) 1 else 0
