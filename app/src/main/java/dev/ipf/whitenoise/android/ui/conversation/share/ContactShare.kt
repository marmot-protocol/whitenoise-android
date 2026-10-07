package dev.ipf.whitenoise.android.ui.conversation.share

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import dev.ipf.whitenoise.android.state.PendingAttachment

private const val TAG = "WNContactShare"

/** MIME type for the portable vCard attachment carried by a contact share. */
internal const val VCARD_MIME_TYPE = "text/vcard"

/** MIME types contact apps and file providers report for an exported `.vcf`. */
private val VCARD_SOURCE_MIME_TYPES = setOf(VCARD_MIME_TYPE, "text/x-vcard")

/** A caption line made only of phone-number characters, e.g. `+1 (555) 010-0100`. */
private val PHONE_LINE = Regex("""\+?[\d\s()./-]+""")

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
 * `@` is the email, a line of only phone characters with six digits or a `+`
 * prefix is the phone, and the first remaining line is the name. Prose such as
 * `Please call 555-0100` is not a phone line, so it stays the sender's caption.
 */
internal fun parseSharedContactFromText(text: String): SharedContact? {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return null
    val email = lines.firstOrNull { it.contains('@') && !it.contains(' ') }
    val phone =
        lines.firstOrNull { line ->
            line != email && PHONE_LINE.matches(line) && (line.startsWith("+") || line.count { it.isDigit() } >= 6)
        }
    val name = lines.firstOrNull { it != email && it != phone }
    // A generic caption on an externally-authored .vcf is not enough to prove
    // it is our contact-share fallback. Require one actionable contact field so
    // ordinary captions remain visible beside the file attachment.
    if (phone == null && email == null) return null
    return SharedContact(name = name, phone = phone, email = email)
}

/**
 * True when [text] is exactly the caption a contact share generates for [contact], so the card may
 * replace it. Any other line, such as `Call Ada` above the number, is the sender's own text and stays.
 */
internal fun isContactShareCaption(
    text: String,
    contact: SharedContact,
): Boolean {
    val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
    // A picked contact without a name ships its number as the vCard's FN, while its caption omits the name.
    val unnamed = contact.copy(name = contact.name.takeUnless { it == contact.phone || it == contact.email })
    return lines == formatContactShareText(contact).lines() || lines == formatContactShareText(unnamed).lines()
}

/**
 * Recovers the contact from a raw `.vcf` the user attached, so the file can go
 * out exactly like a picker share and draw the same card. Null for other files,
 * multi-contact files, and cards whose generated caption would not parse back to
 * exactly this contact (a short `TEL`, or a name that looks like a phone number).
 */
internal fun attachedVCardContact(attachment: PendingAttachment): SharedContact? {
    val vcard =
        attachment.mediaType in VCARD_SOURCE_MIME_TYPES ||
            attachment.fileName.endsWith(".vcf", ignoreCase = true)
    val contact = if (vcard) parseSingleVCard(attachment.plaintextBytes) else null
    return contact?.takeIf { parseSharedContactFromText(formatContactShareText(it)) == it }
}

private fun vcardEscape(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

/** Minimal RFC 2426 vCard 3.0 for broad importer support. */
internal fun buildVCard(contact: SharedContact): String =
    buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        // vCard 3.0 requires both N and FN. We only receive one display-name
        // field from the privacy-scoped system picker, so keep it intact in the
        // family-name component rather than guessing how to split it.
        append("N:${vcardEscape(contact.displayName)};;;;\r\n")
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
    }.onFailure { Log.w(TAG, "contact_phone_query_failed") }
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
