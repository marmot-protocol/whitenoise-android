package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences

/**
 * Schema version of the per-account auto-download matrix.
 *
 * Version 1 is every matrix written before #2699, when the built-in default enabled
 * `Image × Metered`. Version 2 is that same default with Metered off throughout.
 */
private const val METERED_SAFE_MATRIX_VERSION = 2

/** Suffix of the per-account key holding the matrix schema version. */
private const val MATRIX_VERSION_KEY_SUFFIX = ":version"

/**
 * Converts an untouched pre-#2699 default to the metered-safe one, exactly once per account.
 *
 * The migration is keyed on a stored schema version rather than the matrix contents, so it runs
 * once and then leaves the account alone: a later deliberate metered opt-in survives a restart and
 * an account reload even though it can reproduce the old default's cells. Any matrix that is not
 * the old default — including the legacy `always` selection, which enables every cell — is a
 * configuration its owner arrived at and is preserved untouched.
 */
internal fun migratedMediaAutoDownloadMatrix(
    preferences: SharedPreferences,
    matrixKey: String,
    stored: MediaAutoDownloadMatrix,
): MediaAutoDownloadMatrix {
    val versionKey = matrixKey + MATRIX_VERSION_KEY_SUFFIX
    if (preferences.getInt(versionKey, 1) >= METERED_SAFE_MATRIX_VERSION) return stored
    val migrated =
        if (stored == MediaAutoDownloadMatrix.LEGACY_METERED_IMAGE_DEFAULT) {
            MediaAutoDownloadMatrix.DEFAULT
        } else {
            stored
        }
    preferences
        .edit()
        .putInt(versionKey, METERED_SAFE_MATRIX_VERSION)
        .putString(matrixKey, migrated.toPreference())
        .apply()
    return migrated
}

/**
 * Persists a seeded or edited matrix together with the current schema version.
 *
 * Stamping the version on every write is what keeps [migratedMediaAutoDownloadMatrix] off an
 * account that has already been seeded or edited, so a deliberate metered opt-in survives a
 * restart even when the resulting cells happen to match the old default.
 */
internal fun persistMediaAutoDownloadMatrix(
    preferences: SharedPreferences,
    matrixKey: String,
    matrix: MediaAutoDownloadMatrix,
) {
    editMediaAutoDownloadMatrix(preferences.edit(), matrixKey, matrix).apply()
}

/** Adds one matrix and its schema stamp to a caller-owned durable editor transaction. */
internal fun editMediaAutoDownloadMatrix(
    editor: SharedPreferences.Editor,
    matrixKey: String,
    matrix: MediaAutoDownloadMatrix,
): SharedPreferences.Editor =
    editor
        .putInt(matrixKey + MATRIX_VERSION_KEY_SUFFIX, METERED_SAFE_MATRIX_VERSION)
        .putString(matrixKey, matrix.toPreference())
