package dev.ipf.whitenoise.android.ui.conversation.share

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import dev.ipf.whitenoise.android.ui.conversation.media.attachmentOpenIntent
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

private const val MAX_RECEIVED_VCARD_BYTES = 1_048_576

/** Rejects oversized, malformed or multi-contact files before handing decrypted bytes to another app. */
@Suppress("ComplexCondition", "ReturnCount") // Explicit rejection keeps malformed vCard boundaries auditable.
internal fun validatedReceivedVCard(file: File): SharedContact? {
    val declaredSize = file.length()
    if (!file.isFile || declaredSize !in 1..MAX_RECEIVED_VCARD_BYTES.toLong()) return null
    val bytes =
        runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val content = ByteArray(declaredSize.toInt())
                input.readFully(content)
                if (input.read() != -1) null else content
            }
        }.getOrNull() ?: return null
    val body =
        runCatching {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
    if ('\u0000' in body) return null
    // Unfold physical lines before trimming: a leading space or tab marks a continuation.
    val lines =
        body
            .replace("\r\n", "\n")
            .replace(Regex("\n[ \t]"), "")
            .split('\n')
            .map(String::trim)
    if (lines.count { it.equals("BEGIN:VCARD", ignoreCase = true) } != 1 ||
        lines.count { it.equals("END:VCARD", ignoreCase = true) } != 1 ||
        !lines.firstOrNull().equals("BEGIN:VCARD", ignoreCase = true) ||
        !lines.lastOrNull { it.isNotEmpty() }.equals("END:VCARD", ignoreCase = true) ||
        lines.none { it.startsWith("VERSION:", ignoreCase = true) }
    ) {
        return null
    }

    fun field(name: String): String? =
        lines
            .firstOrNull { it.substringBefore(';').substringBefore(':').equals(name, ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.let(::decodeVCardEscapes)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    val contact = SharedContact(name = field("FN"), phone = field("TEL"), email = field("EMAIL"))
    return contact.takeUnless { it.isEmpty }
}

/** Decodes each vCard escape once so an escaped backslash cannot become a newline escape. */
private fun decodeVCardEscapes(value: String): String =
    buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                append(if (value[index + 1] == 'n' || value[index + 1] == 'N') '\n' else value[index + 1])
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }

/** View uses the platform vCard handler with only a temporary read grant. */
internal fun viewReceivedContactIntent(uri: Uri): Intent = attachmentOpenIntent(uri, VCARD_MIME_TYPE)

/** Add opens the system editor with fields from the validated VCF; no Contacts write permission is needed. */
internal fun addReceivedContactIntent(
    uri: Uri,
    contact: SharedContact,
): Intent =
    Intent(Intent.ACTION_INSERT).apply {
        type = ContactsContract.Contacts.CONTENT_TYPE
        contact.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        contact.phone?.let { putExtra(ContactsContract.Intents.Insert.PHONE, it) }
        contact.email?.let { putExtra(ContactsContract.Intents.Insert.EMAIL, it) }
        clipData = ClipData.newRawUri("contact", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/** Start only a user-requested system contact UI and report a missing handler for the card's Save fallback. */
internal fun launchReceivedContactIntent(
    intent: Intent,
    dispatch: (Intent) -> Unit,
): ContactLaunchResult =
    try {
        dispatch(intent)
        ContactLaunchResult.Started
    } catch (_: ActivityNotFoundException) {
        ContactLaunchResult.NoHandler
    } catch (_: SecurityException) {
        ContactLaunchResult.Failed
    } catch (_: IllegalArgumentException) {
        ContactLaunchResult.Failed
    }

internal enum class ContactLaunchResult { Started, NoHandler, Failed }
