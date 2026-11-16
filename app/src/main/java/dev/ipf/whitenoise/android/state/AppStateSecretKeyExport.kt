package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R

/** Reveals the active account's nsec for export, or returns null after a scrubbed failure toast. */
suspend fun WhiteNoiseAppState.exportActiveAccountNsec(): String? {
    val accountRef = activeAccountRef ?: return null
    return runCatchingCancellable {
        marmotIo { revealNsec(accountRef) }
    }.onFailure {
        // Secret-key export holds the nsec in hand and the toast is not
        // behind FLAG_SECURE — scrub the FFI message before showing it (#846).
        presentFailure(R.string.toast_couldnt_export_nsec, "SECRET_KEY_EXPORT", it)
    }.getOrNull()
}

/** Encrypts the active account's secret key with [passphrase]; returns null after a failure toast. */
suspend fun WhiteNoiseAppState.exportEncryptedSecretKeyBackup(passphrase: String): String? {
    val account = activeAccountRef ?: return null
    return runCatchingCancellable {
        marmotIo { exportEncryptedSecretKey(account, passphrase) }
    }.onSuccess {
        presentTransient(R.string.toast_encrypted_backup_created)
    }.onFailure {
        appStateDebug { "encrypted backup failed: ${it.javaClass.simpleName}" }
        presentFailure(R.string.toast_couldnt_create_encrypted_backup, "ENCRYPTED_BACKUP_EXPORT", it)
    }.getOrNull()
}
