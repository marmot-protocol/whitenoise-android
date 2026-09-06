package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import dev.ipf.whitenoise.android.state.ComposerDraftSnapshot
import dev.ipf.whitenoise.android.state.shouldFocusComposerOnDraftRestore

/**
 * Restored drafts focus once on genuine conversation entry, not when an
 * in-place dictation completion rehydrates the same composer's persisted text.
 */
internal fun shouldAutoFocusComposerOnDraftRestore(
    snapshot: ComposerDraftSnapshot?,
    dictationRevisionOnEntry: Int,
    currentDictationRevision: Int,
): Boolean =
    dictationRevisionOnEntry == currentDictationRevision &&
        shouldFocusComposerOnDraftRestore(snapshot)

/** Keeps the navigation-entry revision stable while the same conversation composition is remounted. */
@Composable
internal fun rememberComposerDictationRevisionOnEntry(
    groupIdHex: String,
    currentRevision: Int,
): Int = rememberSaveable(groupIdHex) { currentRevision }
