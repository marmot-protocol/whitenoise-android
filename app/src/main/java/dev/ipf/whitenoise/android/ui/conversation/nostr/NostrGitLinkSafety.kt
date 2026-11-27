package dev.ipf.whitenoise.android.ui.conversation.nostr

import dev.ipf.whitenoise.android.ui.isOpenableMarkdownLink

/**
 * Whether the reader may offer [link] for Copy: it uses only plain URL characters, so pasting it unquoted into a
 * shell cannot expand, glob or end anything. Links that fail this can still be opened behind a confirmation when
 * they are https.
 */
internal fun isShellSafeGitLink(link: String): Boolean = shellPlainLink.matches(link)

/** Whether the reader may offer [link] for Open behind a confirmation: an https link the link opener accepts. */
internal fun isOpenableGitLink(link: String): Boolean {
    val https = link.startsWith("https://", ignoreCase = true)
    return https && isOpenableMarkdownLink(link)
}

/**
 * Accepts a git address whose user name cannot be read as an option and whose ssh, git or nostr path is plain. Shell
 * metacharacters in http and ws addresses are left to [isShellSafeGitLink], which decides whether one may be copied.
 */
internal fun isCopyableCloneAddress(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return scpCloneAddress.matches(value)
    val scheme = uri.scheme?.lowercase()
    val userInfo = uri.rawUserInfo
    val userAllowed =
        when {
            userInfo == null -> true
            scheme == "nostr" -> nip05LocalPart.matches(userInfo)
            scheme == "ssh" -> sshUserName.matches(userInfo)
            else -> false
        }
    // Git transports carry no query or fragment, so their whole path must be plain.
    val pathAllowed =
        scheme !in plainPathSchemes ||
            (uri.rawQuery == null && uri.rawFragment == null && plainGitPath.matches(uri.rawPath.orEmpty()))
    return uri.host != null && scheme in gitUrlSchemes && userAllowed && pathAllowed
}

private val gitUrlSchemes = setOf("https", "http", "wss", "ws", "ssh", "git", "nostr")
private val plainPathSchemes = setOf("ssh", "git", "nostr")
private val plainGitPath = Regex("[A-Za-z0-9._~%/+@:-]*")

/** Characters no shell expands, globs or splits on anywhere after a URL scheme. Everything else is refused. */
private val shellPlainLink = Regex("[A-Za-z0-9._~%/+@:-]+")

/** A NIP-05 local part, which cannot start with `-` and so cannot be read as a `git` option. */
private val nip05LocalPart = Regex("[a-z0-9_][a-z0-9_.-]*")

/** A username without a password, as in `ssh://git@host/path`; copy-only, never opened. */
private val sshUserName = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*")

/** SCP-style `user@host:path` clone address; copy-only. User, host and path cannot start with `-` or `/`. */
private val scpCloneAddress =
    Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*@[A-Za-z0-9][A-Za-z0-9.-]*:[A-Za-z0-9_.~][A-Za-z0-9_.~/+-]*")
