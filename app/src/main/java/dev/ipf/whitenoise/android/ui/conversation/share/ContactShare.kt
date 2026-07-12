package dev.ipf.whitenoise.android.ui.conversation.share

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract

private const val TAG = "WNContactShare"

/** MIME type for the portable vCard attachment carried by a contact share. */
internal const val VCARD_MIME_TYPE = "text/vcard"

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

    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: phone ?: email ?: ""
}

/** Human-readable body carried as the message caption (also the text fallback). */
internal fun formatContactShareText(contact: SharedContact): String = listOfNotNull(contact.name, contact.phone, contact.email).joinToString("\n")

/**
 * Recovers a contact from a shared-contact message's caption so the bubble can
 * draw a card without fetching the vCard blob. Heuristic by design: a line with
 * `@` is the email, a mostly-digit / `+`-prefixed line is the phone, and the
 * first remaining line is the name.
 */
internal fun parseSharedContactFromText(text: String): SharedContact? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val email = lines.firstOrNull { it.contains('@') && !it.contains(' ') }
    val phone =
        lines.firstOrNull { line ->
            line != email && (line.startsWith("+") || line.count { it.isDigit() } >= 6) && !line.contains('@')
        }
    val name = lines.firstOrNull { it != email && it != phone }
    val contact = SharedContact(name = name, phone = phone, email = email)
    return contact.takeUnless { it.isEmpty }
}

private fun vcardEscape(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

/** Minimal RFC 6350 vCard (3.0 for the broadest importer support). */
internal fun buildVCard(contact: SharedContact): String =
    buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        append("FN:${vcardEscape(contact.displayName)}\r\n")
        contact.phone?.let { append("TEL;TYPE=CELL:${vcardEscape(it)}\r\n") }
        contact.email?.let { append("EMAIL:${vcardEscape(it)}\r\n") }
        append("END:VCARD\r\n")
    }

/** A filesystem-safe `.vcf` name derived from the contact's display name. */
internal fun contactVCardFileName(contact: SharedContact): String {
    val base =
        contact.displayName
            .ifBlank { "contact" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifBlank { "contact" }
    return "$base.vcf"
}

/**
 * Picks one phone entry from the system contact picker. Unlike a whole-contact
 * pick, the returned data row itself carries name + number, so the picker's
 * temporary URI grant is enough to read them — the whole-contact flow needs a
 * second query on an entity sub-URI the grant does not cover on stock Android.
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
