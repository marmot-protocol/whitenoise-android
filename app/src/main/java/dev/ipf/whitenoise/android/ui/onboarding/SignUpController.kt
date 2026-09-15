package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.media.ImageUploadDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A local profile draft; no identity or publication exists until Submit is accepted. */
internal data class SignUpProfileDraft(
    val name: String,
    val about: String,
    val photo: ImageUploadDraft? = null,
)

/** The runtime and account that must still own an operation before a native stage starts. */
internal data class SignUpOwner(
    val runtime: Int,
    val accountRef: String?,
)

/** Failed profile stages retain the accepted identity and can never return to identity creation. */
internal enum class SignUpStage {
    Editing,
    Creating,
    UploadingPhoto,
    Publishing,
    CreateFailed,
    PhotoFailed,
    PublishFailed,
    OwnerChanged,
    Complete,
    ;

    val busy: Boolean get() = this == Creating || this == UploadingPhoto || this == Publishing
}

/**
 * Process-owned create/upload/publish transaction with explicit stage retry. Native identity creation is not
 * rolled back;
 * upload or publication failure retains the accepted identity and exact submitted draft until retry or explicit
 * Continue.
 */
internal class SignUpController(
    private val scope: CoroutineScope,
    private val currentOwner: () -> SignUpOwner,
    private val ownerAvailable: () -> Boolean,
    private val create: suspend () -> AccountSummaryFfi,
    private val accept: (AccountSummaryFfi, SignUpOwner) -> Boolean,
    private val upload: suspend (String, ImageUploadDraft) -> String,
    private val publish: suspend (String, UserProfileMetadataFfi) -> Boolean,
    private val finish: (AccountSummaryFfi, SignUpOwner) -> Boolean,
) {
    var stage by mutableStateOf(SignUpStage.Editing)
        private set
    var draft by mutableStateOf<SignUpProfileDraft?>(null)
        private set
    var acceptedIdentity by mutableStateOf<AccountSummaryFfi?>(null)
        private set
    private var discarded = false
    private val openingOwner = currentOwner()
    private var owner: SignUpOwner? = null
    private var uploadedPhotoUrl: String? = null

    /** The owned form stays routable even when completed bootstrap restores the app phase to Ready. */
    fun canPresent(): Boolean {
        val retired = discarded || stage == SignUpStage.Complete || stage == SignUpStage.OwnerChanged
        if (retired || !ownerAvailable()) {
            return false
        }
        val captured = owner ?: openingOwner
        val expected = acceptedIdentity?.let { captured.copy(accountRef = it.label) } ?: captured
        return currentOwner() == expected
    }

    /** Keeps non-secret form state through view recreation without creating or publishing anything. */
    fun stageDraft(value: SignUpProfileDraft) {
        if (canPresent() && acceptedIdentity == null && !stage.busy) draft = value
    }

    /** Accept a fresh form only before native identity acceptance; rapid second taps see busy immediately. */
    fun submit(value: SignUpProfileDraft) {
        if (stage != SignUpStage.Editing && stage != SignUpStage.CreateFailed) return
        // An old captured callback cannot adopt another account or revive a discarded route.
        if (!canPresent()) return
        check(acceptedIdentity == null)
        owner = currentOwner()
        draft = value.copy(photo = value.photo?.let { it.copy(plaintext = it.plaintext.copyOf()) })
        stage = SignUpStage.Creating
        scope.launch { advance() }
    }

    /** Retry exactly the failed upload/publication stage for the already-created identity. */
    fun retry() {
        if (stage != SignUpStage.PhotoFailed && stage != SignUpStage.PublishFailed) return
        if (!ownsAcceptedIdentity()) {
            stage = SignUpStage.OwnerChanged
            return
        }
        stage =
            if (draft?.photo != null && uploadedPhotoUrl == null) SignUpStage.UploadingPhoto else SignUpStage.Publishing
        scope.launch { advance() }
    }

    /** The user explicitly keeps the created identity without claiming that its profile was published. */
    fun continueWithoutProfile() {
        if (stage != SignUpStage.PhotoFailed && stage != SignUpStage.PublishFailed) return
        complete()
    }

    /** Back before accepted creation discards only the local draft and never issues a native command. */
    fun discardUnsubmitted(): Boolean {
        if (stage.busy || acceptedIdentity != null) return false
        discarded = true
        draft = null
        owner = null
        stage = SignUpStage.Editing
        return true
    }

    /**
     * Check the live owner before and after every suspend boundary; another account is never activated or
     * published.
     */
    private fun ownsAcceptedIdentity(): Boolean {
        val expected = owner?.let { captured -> acceptedIdentity?.let { captured.copy(accountRef = it.label) } }
        return expected != null && ownerAvailable() && currentOwner() == expected
    }

    /** Runs only uncommitted stages; failures keep the accepted identity and uploaded image URL. */
    private suspend fun advance() {
        try {
            if (ensureAcceptedIdentity() && ownsAcceptedIdentity()) {
                uploadAndPublish()
            } else {
                stage = SignUpStage.OwnerChanged
            }
        } catch (cancelled: CancellationException) {
            // Interrupted native transactions must never automatically repeat after runtime shutdown.
            stage = SignUpStage.OwnerChanged
            throw cancelled
        } catch (_: Exception) {
            stage =
                when (stage) {
                    SignUpStage.Creating ->
                        if (acceptedIdentity == null) SignUpStage.CreateFailed else SignUpStage.OwnerChanged
                    SignUpStage.UploadingPhoto -> SignUpStage.PhotoFailed
                    else -> SignUpStage.PublishFailed
                }
        }
    }

    /** Keep the native receipt before activation; retrying a later stage never creates another identity. */
    private suspend fun ensureAcceptedIdentity(): Boolean {
        if (acceptedIdentity != null) return true
        val captured = checkNotNull(owner)
        return if (ownerAvailable() && currentOwner() == captured) {
            val created = create()
            acceptedIdentity = created
            accept(created, captured)
        } else {
            false
        }
    }

    /** Upload once and publish only while the accepted account still owns each suspension boundary. */
    private suspend fun uploadAndPublish() {
        val identity = checkNotNull(acceptedIdentity)
        val submitted = checkNotNull(draft)
        if (submitted.photo != null && uploadedPhotoUrl == null) {
            stage = SignUpStage.UploadingPhoto
            uploadedPhotoUrl = ProfileSanitizer.androidOwnedHttpsImageUrl(upload(identity.label, submitted.photo))
                ?: throw IllegalStateException("profile image upload returned an unsafe URL")
        }
        if (!ownsAcceptedIdentity()) {
            stage = SignUpStage.OwnerChanged
            return
        }
        stage = SignUpStage.Publishing
        val metadata =
            UserProfileMetadataFfi(
                name = submitted.name.trim().ifBlank { null },
                displayName = submitted.name.trim().ifBlank { null },
                about = submitted.about.trim().ifBlank { null },
                picture = uploadedPhotoUrl,
                banner = null,
                nip05 = null,
                lud16 = null,
            )
        val published = publish(identity.label, metadata)
        if (!ownsAcceptedIdentity()) {
            stage = SignUpStage.OwnerChanged
        } else if (published) {
            complete()
        } else {
            stage = SignUpStage.PublishFailed
        }
    }

    /** Only a still-owned native account can become Chats-ready; never manufacture readiness from form values. */
    private fun complete() {
        if (!ownsAcceptedIdentity()) {
            stage = SignUpStage.OwnerChanged
            return
        }
        val succeeded = finish(checkNotNull(acceptedIdentity), checkNotNull(owner))
        stage = if (succeeded) SignUpStage.Complete else SignUpStage.OwnerChanged
        if (succeeded) draft = null
    }
}
