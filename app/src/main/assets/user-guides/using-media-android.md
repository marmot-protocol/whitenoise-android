# Media and downloads

Send photos, videos, files, and voice messages, view what you receive, and choose what downloads automatically and how long it stays on this device.

## Before you share

- Photos always have location and device details removed, even at **Original** quality.
- Before a video is attached, White Noise removes private details such as location. If it can't, it doesn't attach the video and shows "Couldn't remove private details like location from this video, so it wasn't attached." The same applies to video files.
- Other files, including imported audio, are sent unchanged, along with any location or device details stored in them.
- **Share** and **Open in another app** give the content you choose to that app.
- GIFs marked **via GIPHY** are loaded from GIPHY when you download them.

## Send attachments from the composer

1. In the composer, tap **Add attachment**, then choose **Camera** to take something new or pick existing items from **Photos and videos** or **Files**.
2. Review the preview, where you can:
   - Write a caption in **Add a caption…** if you want text alongside the media.
   - Tap **Add more** to pick from **Photo library** or **Document**.
   - Mark each item **Included** or **Excluded**, and check its **Send position**.
   - Play a video with **Play**, **Back 10 seconds**, **Forward 10 seconds**, **Playback Speed**, and **Mute**.
   - Tap **Remove attachment** to drop an item.
   - Tap a PDF, HTML, Markdown or text file to read it before sending. This preview has no share or save actions, and it closes when you remove or send the file.
3. Tap **Send** and watch progress move from **Preparing** and **Encrypting** to **Uploading**. If you see **Upload failed**, tap **Retry**.

One send holds up to 100 items. Items beyond 100 are left out, and White Noise tells you how many didn't fit. Large selections are sent as several messages of up to 10 items each. If an item can't be prepared, it isn't sent and White Noise reports how many were skipped.

### Photo editing

In the preview, tap **Edit photo** to open the editor:

- **Crop**: drag the handles, or choose **Free**, **Original**, **1:1**, **4:3**, **3:4**, **16:9**, or **9:16**. **Adjust crop** sets the edges precisely.
- **Rotate clockwise 90 degrees**.
- **Draw**: pick a colour and a size. **Erase** removes drawing marks only. Drawings stop at a limit ("Drawing limit reached. Undo or reset to continue."), and a long stroke may be simplified.
- **Undo** and **Redo** step through your edits, while **Reset** starts over.
- **Photo quality**: **Low**, **Standard**, **High**, or **Original**. An edited photo set to **Original** is sent at **High**. The original image is kept so you can edit it again.

Tap **Save** when you're finished. Closing the editor with unsaved changes asks **Discard changes?**, and **Keep editing** lets you continue. If saving fails, the previous attachment stays unchanged. If you see **This image cannot be edited safely.**, send the photo without editing it.

## Voice messages

Voice messages send audio, while **Dictate text** sends text instead (see [Messages](/docs/using/messages/android)). A recording stops at five minutes, and White Noise warns you 30 seconds before.

1. Touch and hold **Hold to record voice message** to start recording. If you release too soon, it asks you to **Hold longer to record**.
2. Release to review, or slide off to cancel. While the recording is **Locked**, tap **Stop recording** when you're done.
3. In **Review voice message**, tap **Play voice message** to listen back, then tap **Send** or **Record again**, or **Discard** the recording.

When another app is using the microphone, close it there and try again. An interruption that stops the recording still leaves the captured audio to review before sending. **Recording unavailable** means the take couldn't be restored, so tap **Record again**.

### Transcribe a received voice message

Under a downloaded voice message from someone else, tap **Transcribe**, read the notice, then tap **Transcribe** again. Offline Speech to Text processes the recording on your device. It needs Android 13 or later and Offline Speech to Text 0.21.0 or later with a speech model set up. If it isn't installed, tap **Get Offline Speech to Text**.

The **Audio transcript** is labeled **Generated transcript · not the sender's text**. It may be inaccurate, and it is cleared when you close it or leave the screen. White Noise doesn't record your microphone or send a message.

## Viewing and saving received media

- Tap **Tap to download** on media that hasn't downloaded yet, then tap it to open. The full-screen viewer shows **Media 1 of 3** and offers **Save**, **Share**, **Forward message**, **Go to message**, and **More options**. Group admins may also see **Set as group picture**.
- Photos: use **Zoom in**, **Zoom out**, **Fit image**, or **Actual size (1:1)**. **Show or hide controls** toggles the toolbar.
- Voice messages: **Play voice message**, with an adjustable playback speed.
- Files: tap **Open**. If you see **No app installed to open this file**, save the file instead. Text files open in a reader with **Read Aloud**, **Copy text**, **Save**, and **Open in another app**.
- PDF and HTML files open in White Noise's own reader. Tap **Share**, or open **More options** for **Save** and **Open in another app**. Pinch or double-tap a PDF page to zoom. HTML opens on **Rendered**, without images, styles or embedded content, and **Source** shows the file's code. A link in the file asks for confirmation before it opens. If a file can't be previewed, tap **Retry** or **Open in another app**.
- **Too large to preview**: save or share the file to view it in another app.
- APK files: only install them from a source you trust. If you see **This build can't install APK attachments.**, save the file and open it from a trusted installer.

## Browse shared media

Open a conversation's details and find **Shared in Chat**. Choose **Photos and videos**, **Documents** or **Voice**; **Links** appears when links are available. Photos and videos can be filtered by **All**, **Images** or **Videos**.

Only messages already loaded in this chat are shown. Use **Load more** to browse older attachments when offered, or **Retry** after a loading failure. A partial or loading result means more history may remain, and **End of local attachment history** means there is nothing further on this device. Open an item to view or play it, or tap **Go to message** to jump to it. Opening an external link contacts that site.

## Find files and media across all chats

On the chat list, open search and choose a mode under the search bar: **Photos & videos**, **Files**, **Audio**, or **All attachments**. Each result shows who sent it and the chat it came from. Choose **All** to return to message search. These modes list attachments only, so some other filters can't be combined with them ("These filters can't be used to browse files and media").

## Download controls

While a download is running, tap **Cancel download**. After **Download cancelled. Tap to download again** or **Tap to retry**, tap the item again. The labels **Queued for download** and **Download paused** come from your Data & storage settings, as does **Download blocked by policy**. Tapping an item downloads it even when automatic downloads are off, as long as the sender's copy is still available.

## Data & storage settings

Open **Settings**, then **Data & storage**.

- **Automatic downloads**: for **Photos**, **Audio**, **Videos**, and **Files**, choose which networks are allowed (**Wi-Fi**, **Mobile data**, **Roaming**, or **Metered network**), then tap **Done**. A download starts automatically only if every rule that matches your connection allows it. **Reset download settings** restores the default for every media type.
- **Download queue**: **Stop automatic downloads** clears waiting automatic downloads for this profile, while active and manual downloads keep going. **Restart automatic downloads** resumes the queue.
- **Sent media**: **Media quality** applies to new photos and voice messages. Videos and audio files are never re-encoded. Photo sizes are **Low** (about 150 KB), **Standard** (about 500 KB), **High** (about 1.5 MB), and **Original** (full resolution, largest files).

## Free up space on this device

Removing attachments deletes them from this device only. Messages stay in their chats, and you can download an attachment again only while the sender's copy is still available. If it is gone, it can't be recovered.

Open **Settings**, then **Data & storage**, then **Manage storage**. It shows what this profile uses on this device, split into **Attachments**, **Messages and chat data**, **Unused database space**, **Profile and group pictures**, and **Temporary files**. Temporary files are shared by all profiles on the device.

Under **Free up space**:

- **Largest attachments**: pick a chat, then select attachments, largest first, and tap **Remove**. An attachment shared in more than one chat counts in each. In a chat, **Remove all attachments** clears every attachment there.
- **Remove older attachments**: choose **Older than 1 week**, **1 month**, **3 months**, **6 months**, or **1 year**, and confirm. This applies to every chat.
- **Clear pictures and temporary files**: frees cache space. Pictures download again when you need them.

Under **Keep media**, choose **30 days**, **6 months**, **1 year**, or **Forever**. Attachments older than that period are removed from this device now and as they age. Downloading one again keeps it for the full period.

## Related

- [Messages](/docs/using/messages/android)
- [Media and downloads on iOS](/docs/using/media/ios)
