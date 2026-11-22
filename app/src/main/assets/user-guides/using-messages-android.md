# Messages

Use this page to start chats and send messages, then work with any message in a conversation.

## Start a chat

1. Tap **New Message** to open the screen where you pick who to talk to.
2. Search in **Name or npub**, which accepts an npub, a profile link or a hex public key, or tap **Connect with QR** to scan someone's code.
3. Choose a person from the results, and the chat opens with a **Chat created** confirmation.

If the person is new to White Noise, tap **Invite a friend** and share the invite, which includes the White Noise download link.

## Type and send

1. Type in **Message**, then tap **Send** when your text is ready to go.
2. For a long message, tap **Expand message composer** or drag the composer to resize it, and tap **Collapse message composer** to make it small again.
3. In groups, mention someone by choosing them from the mention picker, where each entry reads **Mention** *name*.

To add something other than text, tap **Add attachment**. The menu offers **Camera**, **Photos and videos**, **Files**, **Location**, **Contact**, **Device contact**, and **Create poll**. For media, see [Media and downloads](/docs/using/media/android). Location and contact items share those details with everyone in the chat, so check them before you send. **Location** sends the spot pinned on the map once, as a map link. The pin starts at your current location, and you can move it before sending. Sharing then stops.

**Dictate text** turns your speech into text that you can review and then **Paste** or **Send**. Only the text goes into the chat, and the speech service you pick handles the audio. To send audio, hold the voice control instead, as described in Media and downloads. See [Dictate text](/docs/settings/android/dictation) for provider setup and dictation options.

### Customize the composer

The composer toolbar starts with **Sending as**, **Add attachment** and **Open emoji picker**. Other shortcuts are available from **Add attachment** or by adding them yourself: **Paste from clipboard**, **Dictate text**, **Translate**, **Hold to record voice message**, **Stickers**, and the attachment items above.

To change the toolbar, open **Customize composer** ("Shortcuts, order and layout"). There you can:

- Set **Layout** to **Automatic** or **Always expanded**.
- Use **Add shortcut**, **Remove shortcut**, **Move earlier** and **Move later**.
- Place a shortcut on the **Left** or **Right**.
- Choose where the voice note control sits. While the composer is empty, the voice note control takes the **Send** position, unless you pin it to a side of the toolbar.
- Tap **Reset to default** to restore the original toolbar.

### Emoji shortcodes

Type a colon and at least two letters, such as `:smi`, to show a strip of up to 12 suggestions above the composer. The colon must follow a space or start the message. Your own custom emoji appear first, then built-in emoji ranked by name. Tap one to insert it. Typing a closing colon, or a time such as `12:30`, ends the suggestions.

The emoji picker has **Search emoji**.

### Custom emoji and emoji sets

Before you install an emoji set, know that loading a preview or installing a set shares your IP address with the hosts of its images.

Custom emoji turn images into `:shortcode:` emoji stored on this device. Built-in emoji take precedence over a custom emoji with the same shortcode. When you send a message, reply or reaction that uses one, its image is uploaded, encrypted, to that chat. An edit sends only the shortcode.

Open **Settings** > **Chats** > **Custom emoji** to manage them. To add a set, tap **Emoji sets**, type a search or paste a set link in **Search or paste a set link**, and tap **Find sets**. Open a set and tap **Install set**. When an installed set shows **Update available**, tap **Update set**.

### Stickers

Before you open a sticker picker, know that stickers load from servers each pack's author chose, and those servers can see your IP address. Sticker packs are public, and White Noise leaves their content unreviewed. Your installed packs are listed publicly on your relays.

1. Tap the **Stickers** shortcut in the composer. An empty picker shows **No stickers**, so tap **Browse sticker packs** to find some.
2. On the sticker packs screen, the **White Noise** section at the top offers **White Noise Originals** and **Marmot**. Below it, use **Installed** and **Discover**, **Search sticker packs**, or **Add by link** and **Open link**.
3. Open a pack, tap **Add** to install it, then tap the sticker you want to send. Nothing installs until you tap **Add**, and an added White Noise pack moves out of that section.

A sticker that has not loaded shows **Tap to load** or **Tap to retry**. A sent sticker stays as sent.

### Read a message aloud

Touch and hold a text message, then choose **Read Aloud**. Review [speech-engine settings](/docs/settings/android#read-aloud) first: a separate engine may receive decrypted message text.

Use **Pause reading** and **Resume reading** to pause or continue. The sentence arrows and message-skip buttons move between passages; the rate control changes playback speed. **Stop reading** ends playback. Tap the control bar’s body to return to the spoken message when it is still available.

### Translate a message

Translation runs on this device through Offline Translator. This feature translates written message text only. It does not transcribe voice messages or other audio. To transcribe a received voice message, see [Media and downloads](/docs/using/media/android#transcribe-a-received-voice-message).

The first time you translate, White Noise warns that Offline Translator can write message text and translations to Android system logs, and that White Noise cannot guarantee the security of text handled by another app. Tap **Accept** to continue.

1. Touch and hold the message, then tap **Translate**. You can also use the **Translate** button beside the message.
2. Wait while **Translating on device…** shows, and the translation then replaces the original text.
3. Tap **Translate** again to switch back to the original.

If Offline Translator is missing, the message says to install it. Use **Install from Zapstore** or **Install from GitHub**. For the target language, **Automatically translate messages** and the per-chat choices **Use global setting**, **On** and **Off**, see [Translate messages](/docs/settings/android/translation) in the Settings guide.

To translate what you write, add the **Translate** shortcut to the composer. A **Will send** line shows the translation as it is prepared, and the placeholder reads "Translation appears here". Nothing is sent until the translation is ready, and **Send** delivers the translation. Translation is off while you dictate. If the app can't detect the language, type a bit more.

## Create and vote in a poll

> Polls are not anonymous, and every group member can see who chose each option.

1. In a conversation, tap **Add attachment**, then **Create poll**.
2. Enter a question and between 2 and 10 options, making each option different.
3. Choose **Single choice** for one answer or **Multiple choice** for several answers.
4. Under **Poll closes**, choose **No deadline**, a preset, or **Custom time**. A custom time can be anywhere from 1 second to 30 days.
5. Tap **Post poll**.

Tap an option in the poll to send your vote right away. In a **Single choice** poll, tapping a different option replaces your previous choice. In a **Multiple choice** poll, tapping an option selects or deselects it, but you can't remove your last remaining choice. Tap **View votes** to see who chose each option. A closed poll no longer accepts votes.

## Act on a message

Touch and hold a message to open **Message actions**. The list depends on the message type and its state as well as your role, so you only see actions that apply. They can include:

- **Reply**: your next message quotes this one.
- **Edit**: see [Edit a message](#edit-a-message).
- **Select** starts multi-select, and **Select text** lets you highlight part of the text.
- **Copy**, and **Share**, which hands the message to the Android app you choose. That app is outside White Noise.
- **Save** saves the message's attachments to your device.
- **Read Aloud** and a code-reading option listed right after it.
- **Translate**: see [Translate a message](#translate-a-message).
- **Forward**: in **Forward to**, find chats with **Search chats** or **Recent chats**, or switch to **Select by folder**. Pick one or more chats, then tap **Forward**. Progress shows as, for example, **Forwarded to 2 of 3 chats**, where you can **Retry** or **Cancel**, or **Dismiss** the progress message. If an attachment has to finish uploading or be downloaded first, the action tells you.
- **Keep on screen**: pins the message as a **Kept on screen** card. **Kept message options** include **Go to Message**, **Move to top**, **Move to bottom**, **Move to other side**, **Remove this message**, and **Clear all kept messages**.
- **Show less**: collapses a long message you expanded.
- **Info**: opens **Message Details**, which shows **Status**, **Sent**, **Received**, **Disappears**, and **Reactions**, plus **Copy Message ID** and **Copy Sender Public Key**.
- **Report**: see [Report a message](#report-a-message).

Touch and hold some buttons and rows, such as composer shortcuts, **Add attachment** menu rows, message actions, chat actions and Settings rows, to read a short description of what they do. The control does not run when you hold it.

### Long messages

Long messages show **Read More**. Tap it to show the whole message in the chat. To shorten it again, touch and hold the message and tap **Show less**. In chat details, the **Collapse long messages** switch ("Show a preview of long messages in this chat.") controls this per chat. It is on by default.

### Nostr links

When a message contains a `nostr:` link to a public post, or a web link that includes one, White Noise shows a card for it under the message, up to three per message. A card loads when it appears in the chat, so White Noise contacts relays to fetch the post. It keeps a copy on this device, so a card you have seen before appears straight away.

The card shows its type, such as **Note**, **Article**, **Video**, **Image**, **File** or **Software release**, with the author and a preview. Depending on the type, tap **Read full note**, **Read article**, **Play video** or **View image**, or tap **View full event** to see everything. **Copy event reference** copies the link, and **Open event** opens it in another app.

If a card shows **Event not found**, **This event couldn’t be verified.** or **Couldn’t load this event**, check your connection and tap **Retry**.

### React to a message

Touch and hold a message, then tap a reaction. For others, tap **Open emoji picker** and use **Search emoji**. **Configure Reactions** changes your quick reactions: tap an emoji to replace it, or use **Add a reaction**. To see who reacted, open **Reactions**, filter with **All**, and tap **Tap to remove** next to your own reaction.

### Edit a message

You can edit your own text messages. You can't edit deleted messages, stickers, other people's messages, or messages in a chat where actions are read-only.

1. Touch and hold your message and choose **Edit**, or double-tap the text of your own message (not a link in it). While Read Aloud is active on that message, double-tap is unavailable.
2. The composer shows **Edit message**. Change the text and tap **Send**, or tap **Cancel edit** to stop.

Edited messages show **edited**, or **edited · N** after several edits. **Edit history** lists the **Original** and each **Revision**.

## Select several messages

Choose **Select**, then tap more messages to add them. The **Select messages** bar can offer **Reply**, **Info**, **Copy**, **Forward**, **Save**, and **Delete selected messages**. If **Forward unavailable** appears, it gives the reason. Tap **Close** to stop selecting.

## Removing messages

> **Before you delete:** deleting for everyone can't be undone, and it can't remove copies that others have already saved or forwarded, or captured. Disappearing messages have the same limits.

1. Choose **Delete** on the message you want to remove to open **Delete message?**.
2. Tap **Delete for me** to remove it from this device only, or tap **Delete for everyone**.

**Delete for everyone** is available for your own messages. In a group, admins can also use it on other members' messages. The prompt then reads **Delete message from** *name*. A message that is already deleted offers only **Delete for me**.

A deleted message leaves a placeholder that reads **You deleted this message.** for your own deletion, **An admin deleted this message.** for moderation, or **This message was deleted.** otherwise.

## Search

### Search in a chat

Tap the chat header to open chat details, then tap **Search**. To search from the chat itself, turn on **Search in chat** under **Settings > Chats > Chat header shortcuts**. Type in **Search this chat**. Use **Previous match** and **Next match** to move between results, which show a count such as 2/7. **Clear search** empties the box and **Close search** leaves search. **No matches** means the search found nothing.

The app searches older messages as well as the ones already loaded. While it does, you see **Searching all messages…**. If that fails you see **Couldn't search older messages** and a count of matches in loaded messages only. Tap **Retry search** to try again.

### Search all chats

In the chat list, tap the search field (**Search chats**). Use **Filters** to narrow results, and **Clear All** to reset them. The filter groups are **Chats**, **Senders**, **Date**, **Content**, **Folders** and **Chat type** (**Direct chats** or **Groups**).

- **Date**: **Last 7 Days**, **Last 30 Days** or **Custom Range**. The end date must be on or after the start date.
- **Content**: **Text**, **Links**, **Images & Video**, **Voice & Audio**, **Files & Documents** or **Any Attachment**.
- **By me**: shows messages you sent.
- A filter marked **Not in current scope** can't apply to the current search.

At the end of the list, **End of results** means there is nothing more. Tap **Search older messages** to look further back. A result that shows **This message is no longer available** can't be opened. If you paste an npub, the app shows **Looking up profile…** and then **Open profile**; an invalid one shows "That doesn't look like a valid npub". To find files and media across all chats, see [Media and downloads](/docs/using/media/android).

## Manage chats in the chat list

On a tablet, an unfolded foldable or another wide window, the chat list stays on the left while a chat is open, and the right side shows **Select a chat** until you pick one. Android's Back gesture leaves the open chat in place there, so use the back arrow in the chat header to close it.

Touch and hold a chat to open its actions. Depending on the chat, they include **Archive** or **Unarchive**, **Mark as read** or **Mark as unread**, **Mute** or **Unmute**, **Pin** or **Unpin**, **Move up** or **Move down**, **Add to folder**, **Exclude from this folder**, and **Select**.

Deleting a chat here removes it from this device. It does not remove it for anyone else:

- **Delete from this device**: removes the chat and its messages from this device. **This does not leave the chat.** Everyone else in it sees no change.
- **Leave and delete**: leaves the group and deletes its history from this device. Other members stay, and you need a new invitation to rejoin.
- **Delete group** or **Delete chat**: appears where it applies to that chat. For a direct chat, the other person is not notified.

To act on several chats, choose **Select**. The **Select chats** bar offers **Select all** or **Deselect all**, shows how many are selected, and has the same actions plus **Close**. Deleting several chats asks "Delete N chats? This does not leave them." Afterwards a results dialog reads "Processed N of M chats" and sorts them as **Changed**, **Already satisfied**, **Skipped (unavailable)**, **Failed**, **Not started** or **Unconfirmed**. Tap **Check results** to review the chats whose outcome is unclear.

## Home-screen widget

Add **Conversations widget** from your launcher's widget list to see up to eight recent chats on your home screen. When you place it, choose the profile it follows, which starts as your active one. Turn on **All accounts** to include every profile, or **Unread only** to list only unread chats, then tap **Save**.

Each row shows a chat's name and **Unread messages** or **Recent conversation**. Message text stays hidden. With **All accounts**, the profile name comes before each chat name. Tap a row to open that chat, tap **White Noise** to open the app, or tap **Retry** to refresh the list.

While your phone is locked or White Noise is waiting for device authentication, the widget shows **Conversations unavailable** in place of your chats. Your chats return after you get past the lock screen and any White Noise authentication.

## Share a chat link

In a group's chat details, tap **Copy chat link**. The link looks like `marmot://chat/` followed by the group ID, and **Copied** confirms it. It opens the chat for people who are already in it. It adds nobody. A direct chat shares the person's profile link instead.

If a link you open shows "That chat link isn't valid" or "Chat not found", ask the sender for a new one. With several profiles, **Open chat in** asks which one to use.

## Report a message

> Your report goes to the group's admins, encrypted like any other message, and they will see who reported it.

1. Touch and hold the message and choose **Report**.
2. In **Report this message**, choose a reason and add a note if you want.
3. Tap **Send report**.

You see "Report sent to the group's admins." If the chat is offline, the report is saved and sent once the chat is back online. In a chat where reporting is blocked, the app says so.

In some chats the sheet also offers **Send to White Noise** (**Report to White Noise team**). Your report and the sender's public key are sent privately to the White Noise team. The message itself isn't shared, and the sender isn't notified. If you send too many, the app asks you to try again later.

A reported message shows **Reported**. Admins see a **Reports** section with **Reported by** *name*, **Dismiss report** and **Dismissed**.

## Troubleshooting

- **Send failed** or **This message didn't reach the group**: tap **Retry**, or tap **Discard failed message**.
- Search: **No matches** means you should check the spelling, and after **Couldn't verify this address.** you can tap **Retry**. **Some network results may be missing.** means the list may not be complete.
- **Couldn't start chat**: check your connection and try again; **Copy** copies the error details.
- If you blocked the person, tap **Unblock** to message them again.
- A notice can replace the composer when you're no longer a member or the group was disbanded. It also appears while a group is temporarily frozen as White Noise repairs it.
- Group event rows: tap **Show details** or **Hide details**. To remove a row, use **Message actions** and tap **Delete for me**.

## Related

- [Media and downloads](/docs/using/media/android)
- [Groups](/docs/using/groups/android)
- [Messages on iOS](/docs/using/messages/ios)
