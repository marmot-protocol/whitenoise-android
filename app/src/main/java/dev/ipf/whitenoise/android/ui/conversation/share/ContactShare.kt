package dev.ipf.whitenoise.android.ui.conversation.share

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

private const val TAG = "WNContactShare"

/**
 * The only fields extracted from a picked contact — never the address book.
 * Isolated from the send path so a structured contact card can replace the
 * text fallback without touching the picker flow.
 */
internal data class SharedContact(
    val name: String?,
    val phone: String?,
    val email: String?,
) {
    val isEmpty: Boolean get() = name == null && phone == null && email == null
}

/** Text fallback until a structured contact message kind exists. */
internal fun formatContactShareText(contact: SharedContact): String = listOfNotNull(contact.name, contact.phone, contact.email).joinToString("\n")

/**
 * Picks one phone entry from the system contact picker. Unlike a whole-contact
 * pick, the returned data row itself carries name + number, so the picker's
 * temporary URI grant is enough to read them — the whole-contact flow needs a
 * second query on an entity sub-URI the grant does not cover on stock Android,
 * which is what broke the first on-device attempt.
 */
internal class PickContactPhoneRow : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(
        context: Context,
        input: Unit,
    ): Intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Uri? = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

/** Reads name + number from the granted phone data row; email is best-effort. */
internal fun readSharedContact(
    resolver: ContentResolver,
    phoneRowUri: Uri,
): SharedContact? {
    var name: String? = null
    var phone: String? = null
    var contactId: Long? = null
    runCatching {
        resolver
            .query(
                phoneRowUri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                    phone = cursor.getString(1)?.takeIf { it.isNotBlank() }
                    contactId = if (cursor.isNull(2)) null else cursor.getLong(2)
                }
            }
    }.onFailure { Log.w(TAG, "picked phone row query failed", it) }
    val email = contactId?.let { readPrimaryEmail(resolver, it) }
    return SharedContact(name = name, phone = phone, email = email).takeUnless { it.isEmpty }
}

// The email table sits outside the picker's URI grant, so on stock Android
// this raises SecurityException without READ_CONTACTS — expected, and it just
// means the share goes out without an email line.
private fun readPrimaryEmail(
    resolver: ContentResolver,
    contactId: Long,
): String? =
    runCatching {
        var email: String? = null
        var emailIsPrimary = false
        resolver
            .query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY,
                ),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val value = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val primary = cursor.getInt(1) != 0
                    if (email == null || (primary && !emailIsPrimary)) {
                        email = value
                        emailIsPrimary = primary
                    }
                }
            }
        email
    }.getOrNull()

@Composable
internal fun ContactSharePreviewDialog(
    contact: SharedContact,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_contact_title)) },
        text = { Text(formatContactShareText(contact)) },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
