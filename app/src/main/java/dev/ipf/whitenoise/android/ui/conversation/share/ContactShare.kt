package dev.ipf.whitenoise.android.ui.conversation.share

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R

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
 * Reads name plus primary phone/email for one picked contact through the
 * picker's temporary URI grant — no READ_CONTACTS permission involved, and
 * only the granted contact's entity rows are touched.
 */
internal fun readSharedContact(
    resolver: ContentResolver,
    contactUri: Uri,
): SharedContact? =
    runCatching {
        var name: String? = null
        resolver
            .query(
                contactUri,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)?.takeIf { it.isNotBlank() }
                }
            }
        var phone: String? = null
        var phoneIsPrimary = false
        var email: String? = null
        var emailIsPrimary = false
        val entityUri = Uri.withAppendedPath(contactUri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
        resolver
            .query(
                entityUri,
                arrayOf(
                    ContactsContract.Contacts.Entity.MIMETYPE,
                    ContactsContract.Contacts.Entity.DATA1,
                    ContactsContract.Contacts.Entity.IS_SUPER_PRIMARY,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(0) ?: continue
                    val value = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val primary = cursor.getInt(2) != 0
                    when (mime) {
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE ->
                            if (phone == null || (primary && !phoneIsPrimary)) {
                                phone = value
                                phoneIsPrimary = primary
                            }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE ->
                            if (email == null || (primary && !emailIsPrimary)) {
                                email = value
                                emailIsPrimary = primary
                            }
                    }
                }
            }
        SharedContact(name = name, phone = phone, email = email)
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
