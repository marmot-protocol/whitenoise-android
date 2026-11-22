# Groups

Groups let you chat with several people together.

## Create a group

1. Tap **New Message**, then tap **New Group** to start choosing members.
2. Find people by searching with **Name, Nostr address, or npub**. You can also tap **Paste** or **Scan QR Code**. An unconfirmed address shows **Address not verified**.
3. Review **Selected people** and tap **Continue**. To start alone, tap **Create without other members**.
4. On **Set Up Group**, enter a **Group name**, an optional **Description**, and a **Disappearing messages** setting. Tap **Add Photo** for an image. A photo or GIF is uploaded to a public image server, and anyone with its link can view it; White Noise asks you to confirm with **Use this photo for the group?** before uploading.
5. Tap **Create Group**.

Expected result: **Creating group…** appears, then **Chat created**. When setup fails, check your connection and tap **Retry**. To skip one person who is holding up setup, tap **Remove this person and continue**. A new chat can take a moment to reach your chat list; wait, then tap **Open chat**.

## Open group details

Open the conversation and tap its header. Group details show **Group · N members** and quick actions for **Mute** (**Unmute** once muted), **Disappearing**, and **Search**. Admins also see management options.

Tap **Group relays** to view and copy the relays this chat is configured to use. The list doesn't show whether each relay is connected right now. Only admins can change these relays, and editing them isn't available in White Noise yet.

## Edit group details

Only admins can edit these details, and every member sees the result.

> A group photo is uploaded to a public image server. Anyone with its link can view it. A GIF is uploaded in full, without cropping. The group's **Group photo** detail says whether the current image is encrypted (only group members can see it) or on a public image server.

Web search sends your query and IP address to DuckDuckGo and image hosts.

1. In group details, tap **Edit group name and description**.
2. On **Edit Group**, change **Group name** or **Description (optional)**.
3. Tap **Add Photo** or **Change Photo**, then pick an image from Photos or Files. You can also tap **Find Image on Web**, which sends your search and IP address to DuckDuckGo and to the image hosts, or tap **Create from emoji** and pick one or two emoji. **Remove Photo** clears the current image.
4. Tap **Save Group**.

When an image fails to upload, try a PNG or JPEG.

## Manage members and admins

> Removed members lose access to the chat and aren't notified. Removal can't erase messages they already received.

- **Add:** tap **Add Members** and choose people. A review screen shows the group you are adding to, the people selected and your acting account. Tap **Add member** or **Add members** to send the invitations, or **Cancel**. In groups of 150 or more, additional invitations might not be delivered reliably; you can still continue.
- **Remove:** on a member, choose **Remove member** and confirm.
- **Make or remove an admin:** on a member, choose **Make Admin** or **Remove Admin**.
- **Transfer admin:** tap **Transfer administration** and pick a member. You step down right away.
- **Step down:** tap **Step down as admin**. Another admin can restore you later.

Rows show **Admin** or **Member**, and **Updating member…** during a change. The chat shows **Invite pending** or **Removal pending** until it completes. Tap **Retry** when members fail to load. From a person's profile, you can also open **Groups in Common** and tap **Add to a group** or **Add to another group**.

If some people can't be added, White Noise shows **Some people weren't added** and lists **People not added**. A person who is already a member, or whose signing key conflicts with an existing member, can't be added again.

### Warnings before a change

Before you add, remove or change members, White Noise may warn you first. Read the warning, because continuing can leave some members out of sync:

- **This chat may be out of date.** Wait for it to catch up, or tap **Continue anyway** or **Invite anyway**.
- **Your last change is still being delivered.** Tap **Wait**, or continue anyway.
- **A relay refused your last change.** At least one of the group's relays refused it for good. Members who use only that relay may not get it or be able to follow the group if you continue. Waiting won't fix this, and continuing dismisses the warning in this chat.

A **Group change refused** banner can also appear after a relay refuses a change you made.

## Invitations to a group

An invitation shows who invited you (**Invited to chat by**) and, when you have several profiles, **Invited as** with the profile name. Tap **Accept** to join or **Decline** to refuse. **Invitation pending** shows while it waits. If it shows **Couldn't check invitation**, tap **Retry**.

> If the invitation makes you an admin, declining asks **Step down and decline?** The group's members see that you stepped down.

## Notifications and disappearing messages

Tap **Sounds & notifications**. Under **Notify for**, choose **All messages**, **Only mentions**, or **Nothing**, and set a **Vibration pattern** if you like. To silence the chat for a while, tap **Mute** and pick a duration or **Custom time**. Direct mentions can still notify. These settings apply to this device.

> Admins set the disappearing timer for everyone. New messages are permanently deleted after the chosen time; unread ones start when read. The timer can't stop saved copies or screenshots.

1. Tap **Disappearing**.
2. Pick a preset or **Custom time**, then tap **Save**. To turn the timer off, pick **Off** and save.
3. Tap **Set timer** to confirm.

## Report a message

To report a message, choose **Report this message**, pick a reason such as **Spam**, **Abusive language** or **Something else**, add an optional note and tap **Send report**.

> Your report goes to this group's admins, encrypted like any other message. They will see who reported it.

Admins see **Reported by** on the message and can tap **Dismiss report**. You can also turn on **Send to White Noise** to send a second copy to the White Noise team. That copy includes your report and the sender's public key. The message itself isn't shared, and the sender isn't notified. If the chat is offline, the report is saved and sent when the chat is back online.

## Export a conversation transcript

You can share or save a copy of a conversation as a JSON file.

> **Warning:** The transcript file is not encrypted. It contains the conversation's message content and identifiers as plain text. Anyone who receives or opens the file can read it. Only share it with, or save it to, a place you trust.

The file contains the conversation's decrypted inner Marmot/Nostr events, including message content, in chronological order. Use it to read or process the conversation. It is not a backup. You can't use it to restore the conversation or the group's encryption state.

**To share the transcript with another app:**

1. Open the conversation details.
2. Tap **Export Conversation Transcript**.
3. Choose the app you want to share the file with.

**To save the transcript to a location you choose:**

1. Open the conversation details.
2. Tap **Save transcript**.
3. In the Android file picker, choose where to save the file.

## Leave or remove a group

> Leaving removes you, and you need a new invitation to rejoin. If you're the only member, the group is deleted. If you're the only admin, transfer admin first; White Noise can transfer and leave in one step.

- **Leave:** tap **Leave group**, then **Leave**. If you're the only admin, choose a new admin if asked, then tap **Transfer admin and leave**. If leaving doesn't finish, you can reset the group on this device instead, as described below.
- **Archive:** tap **Archive Chat** to hide it. **Unarchive Chat** restores it.
- **Leave and delete:** in the chat list, press and hold a chat and choose **Leave and delete** to leave and remove its history from this device.
- **Delete:** **Delete group** appears in group details after you are no longer a member, or once the group has been disbanded. Tap it, then **Delete**. This removes the chat from this device only; nothing changes for anyone else. For a direct chat the row says **Delete chat**.

> Disbanding permanently ends the group for everyone. Nobody can send or receive new messages. It can't be undone.

Admins can tap **Enable disbanding**, then **Disband group**, then **Disband**. If it fails, check your connection and try again.

## Advanced: recover a group

Use these steps only when a group stops syncing. When it happens, the chat shows **This chat stopped syncing. Messages you send will wait until it catches up.** and retries (**Retrying…**). If it still can't sync, it says **This chat still isn't syncing. Try again later.** For other diagnostics, see [Settings](/docs/settings/android).

**Rejoin.** After **Unable to restore group synchronization.**, some invitations retry automatically. Re-invite anyone still missing from group details. When an invitation arrives, tap **Review invitation from**.

> Rejoining replaces this device's encryption data for the group. History stays. Only rejoin if you trust the inviter; a newer group version alone isn't proof.

Tap **Rejoin group** or **Decline invitation**, and tap **Retry** when the check fails. **Dismiss** closes a notice about missing earlier messages.

**Reset this device's copy.**

> Reset erases this group's history and encryption state on this device only. Nothing is sent to the group.

In group details, tap **Reset and wait for a new invite**, then **Reset**. You rejoin when an admin sends an invite created after the reset.

For a direct chat, the same control is **Delete from this device**, then **Abandon**. It erases the chat's history and encryption state for this account on this device. Nothing is sent, so the other person may still see you in the chat, and your other devices keep their copy. To talk again, start a new chat or accept a new invite.

**Quarantined groups.** A group is quarantined when its stored encryption state can't load safely. Turn on developer mode by tapping **Version** seven times in **Settings > Help & support > Help > About & licenses**. Then go to **Settings > Help & support > Developer Tools > Quarantined groups** and tap **Retry recovery**. Tap **Refresh groups** when the list fails to load. Recovery reruns every check and keeps history; it doesn't rejoin you. On success the group returns to your chat list. If it stays quarantined, history is still kept; try again later.

**Restart White Noise.** This notice appears once per chat when a group could not be restored after reading its earlier state. Force stop White Noise in Android settings or restart your phone, then open White Noise.

## Related

- [Messages](/docs/using/messages/android)
- [Settings](/docs/settings/android)
- [Groups on iOS](/docs/using/groups/ios)
