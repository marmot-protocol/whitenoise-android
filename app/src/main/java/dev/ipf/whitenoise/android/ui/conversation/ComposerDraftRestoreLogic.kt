package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.Snapshot
import dev.ipf.whitenoise.android.state.ComposerDraftSnapshot
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.shouldFocusComposerOnDraftRestore
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.composer.composerDraftOwnerKey

/**
 * Reads the persisted draft only when ownership or an external hydration revision changes,
 * using [Snapshot.withoutReadObservation] so per-keystroke draft writes never subscribe the
 * enclosing screen composition scope.
 */
@Composable
internal fun rememberRestoredDraftSnapshot(
    appState: WhiteNoiseAppState,
    draftAccountRef: String?,
    groupIdHex: String,
    externalRevision: Any?,
): ComposerDraftSnapshot? =
    remember(appState, composerDraftOwnerKey(draftAccountRef, groupIdHex), externalRevision) {
        Snapshot.withoutReadObservation {
            appState.draftSnapshotFor(draftAccountRef, groupIdHex)
        }
    }

/**
 * Captures the composer's entry edit revision, once per [entryKey] and [composerTextState], without
 * subscribing the enclosing screen scope to later per-keystroke increments of
 * [ComposerTextState.userTextEditRevision].
 */
@Composable
internal fun rememberUserTextEditRevisionOnEntry(
    entryKey: Any?,
    composerTextState: ComposerTextState,
): Long =
    remember(entryKey, composerTextState) {
        Snapshot.withoutReadObservation { composerTextState.userTextEditRevision }
    }

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
