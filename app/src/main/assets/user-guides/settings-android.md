# Android settings

Open **Settings** from the main screen. Its rows sit in three unlabeled groups. The first holds **Account**, the second **Notifications**, **Chats**, **Appearance**, **Voice & language**, **Privacy & Security** and **Data & storage**, and the third **AI Agents** and **Help & support**. Each setting says whether it applies to the active profile or to every profile on this device.

## Settings layout

Your profile and avatar appear at the top, with **Edit profile picture** and **Open Share & Connect**. The same group holds **Account** and **Add Profile**, which becomes **Switch Profile** once you have two or more profiles. The other settings follow in groups, then **Sign Out** and the app version. Search is the **Search settings** icon in the toolbar. Touch and hold a supported row or section heading for details about what the setting does.

### Search settings

Type in **Search settings** to find a setting by name. Choosing a result opens that screen. If nothing matches, the app shows **No settings found. Try another word.**

### App updates

On builds installed outside an app store, the **App updates** row shows **Up to date**, a note that a newer version is available on Zapstore, or **Check for updates**. If a check fails, the row shows **Couldn't check for updates. Tap to retry.** When a newer version is available, the row moves up below your profile group. Otherwise it sits near the version at the bottom.

### Add or switch a profile

**Add Profile** signs in another profile on this device. With two or more profiles, the row becomes **Switch Profile**, which changes the active profile. See [Profiles](/docs/using/profiles/android).

### Sign Out

Signing out keeps your groups, message history and drafts on this device. **Sign Out & Wipe** is different. It permanently deletes this profile's messages and group encryption data and removes its key from this device. It also removes its invitation keys from relays. Back up your private key first. See [Identity and keys](/docs/settings/identity).

## Account

**Account** contains **Profile**, **Profile Keys** and **Relays**.

### Profile

Edit your name, picture and other profile details. See [Profiles](/docs/using/profiles/android).

### Share & connect

**Share & Connect** has these controls.

| Control | Effect |
|---|---|
| Share profile URL | Opens Android sharing with a link to your profile. |
| Share profile card | Shares an image card that says: On White Noise? Message me here. |
| Save to Gallery | Saves the profile card image. On failure the app shows: Couldn't save image. |
| Copy public key | Copies your public key. |
| Profile QR code | Someone can scan this code to connect with you. |
| Scan QR Code | Scans another person's code. Only codes that contain a White Noise profile are accepted. |

### Profile keys

**Profile Keys** shows your public and private keys together with the account session status. The private key rows below appear only for a profile that signs with its own key. A profile signed in with Amber keeps its key in Amber.

- **Copy Private Key** puts the key on the clipboard. Any app that reads the clipboard can see it, and the app clears the clipboard after about 60 seconds, though Android may run the clear late.
- **Export Encrypted Private Key** and **Export Private Key** save a backup. The plain-text export shows a warning first, because anyone who has the key controls your account.
- **Sign Out** and **Sign Out & Wipe**, described under [Sign Out](#sign-out).

Some of these actions are permanent, so read [Identity and keys](/docs/settings/identity) before you use them.

### Relays

This screen controls where your profile publishes information and receives invitations. See [Relays](/docs/settings/relays).

## Chats

**Chats** contains **Folders**, **Swipe actions**, **Composer**, **Chat header shortcuts**, **Enter key behavior**, **Custom emoji** and **Quick account switching**.

| Control | Effect |
|---|---|
| Enter key behavior | **Send message** or **New line**. On a hardware keyboard, Shift+Enter always inserts a new line. |
| Quick account switching | Shows your other profiles beside your avatar so you can switch with one tap. |

### Composer

**Composer** is described as "Shortcuts, order and layout". It sets what the buttons beside the message box do.

- **Layout** is **Automatic** or **Always expanded**.
- Each side has its own shortcuts. Use **Move earlier**, **Move later**, **Add shortcut** and **Remove shortcut** to arrange them.
- Available shortcuts are Emoji, Paste, Dictation, Translate, Voice note, Camera, Gallery, Files, Location, Person, Contact, Poll and Sticker. Location sends your position once, as a map link.
- **Voice note** sets where the voice control sits.
- **Reset to default** restores the layout.

### Chat header shortcuts

**Chat header shortcuts** adds buttons beside the chat name at the top of every chat. None are on at first. Check any of **Search in chat**, **Mute / unmute**, **Read messages aloud**, **Disappearing messages**, **Members** and **Add Members**. The header shows as many as fit beside the chat name, in the order you check them. Touch and hold a shortcut row to read how it works.

A button appears only in chats where it applies. **Members** shows in groups, **Disappearing messages** and **Add Members** show where you're allowed to change them, and **Read messages aloud** needs a speech engine on the device. Muting asks for how long, while unmuting applies right away. The choice applies to this device only.

### Custom emoji

Images you add here become :shortcode: emoji that you can use in messages and reactions, and they are stored on this device.

1. Open **Settings > Chats > Custom emoji**, then tap **Choose from Photos** or **Choose from Files** to pick the image you want to add.
2. Enter a shortcode made of letters, numbers and the _ symbol, then tap **Save**. If the shortcode is already in use, the app warns that the new image will replace your current one. Built-in emoji take precedence for their own shortcodes.

The image must be PNG, GIF, WebP or JPEG and 1 MB or smaller. When a message, reply or reaction uses a custom emoji, the image is encrypted and uploaded to that chat so its members can see it. An edit sends only the shortcode. Removing an emoji deletes it from this device, while messages you already sent keep the image that was uploaded with them. An emoji marked **Display only, can't be sent** can be tapped to remove.

Emoji sets can add many emoji at once. Loading a set preview or installing a set shares your IP address with the servers that host the images. Use **Install set**, **Update set** and **Check for update** to manage them.

### Swipe actions

The **Messages** and **Chats** sections each have a **Swipe left** and a **Swipe right** row. Left and right describe the direction your finger moves, so an explicit choice stays the same when you change the app language. **Default** follows the reply direction for your language, and the row shows the action it currently resolves to, such as Default: Reply. Chat swipes are off by default.

Choices are Default, Off, Reply, React, Forward, Mute / unmute, Pin / unpin and Read / unread. Each message or chat row still decides whether the chosen action applies to it. **Pull down to show unread chats** is on by default. When it is on, pulling down on the chat list shows only unread chats, and **Show all** returns to the full list. **Reset to default** restores the defaults.

### Folders

Folders sort your chat list into groups of chats, and deleting a folder leaves your chats where they are.

- **New Folder** creates a folder.
- **Move Up** and **Move Down** change the folder order.
- **Restore Default Folders** adds back any missing default folders (Chats, Unread, Archived and Groups) and keeps your changes to folders you already have.

### Edit a folder

| Field | Effect |
|---|---|
| Folder name, Description | Labels for the folder. |
| Include all active chats | Includes every active chat. |
| Always include chats / Included Chats | Chats you add here stay in the folder even when they fall outside the rules. |
| Automatic rules or Filters | Chats are matched automatically. See below. |
| Show when empty | Keeps the folder visible when no chats match. |
| Sort chats by | Most recent activity, Name A–Z or Unread first. Invitations and pinned chats always appear first, and pinned chats keep the order you set. |
| Preview | Shows which chats match before you save. |
| Delete folder | Removes the folder and keeps your chats. |

If you leave with unsaved changes, the app asks whether to **Discard Changes** or **Keep Editing**. If saving fails, your edits stay on screen so you can try again.

**Filters** can use read status, unread mentions, people (any, all or excluded), draft, pending outgoing messages, muted, archived, accepted chats, conversation type, title contains, and pinned. You can also add public keys as npub or hex. Conditions can be grouped with **Match all** or **Match any**, and **NOT** excludes chats that match a group. A rule set can have up to 4 levels and 64 conditions or groups. Presets include **All read** and **Unread mentions**. All read means every message has been read, which tells you nothing about whether an agent has finished its work.

Some folders use older saved rules. For these, chats that match any person or keyword must also meet every selected category, and a removed chat that still matches comes back. If a rule set can't be read, automatic matching is turned off until you replace the rules. **Replace rules** keeps the folder name and included chats.

## Appearance

**Appearance** has **Theme & colors** and **Text & language** sections, with **Date and time** under Text & language, and a readability preview.

| Control | Options | Scope and effect |
|---|---|---|
| Theme | System default, Light, Dark, AMOLED | System default follows your device. AMOLED uses pure-black backgrounds with customizable bubble outlines. |
| Action color | One accent per theme | Applies to the active profile and leaves backgrounds and neutral surfaces as they are. It is hidden in AMOLED, where action buttons are always white. **Reset to default** undoes your change. |
| Chat bubble colors | Your messages, Other messages | Applies to the active profile and sets default bubble colors for the current theme. In AMOLED these colors apply to outlines, and bubble interiors stay pure black. A single chat can override these colors in every theme, and **Reset to global colors** removes that override. |
| App font | System and other fonts | Sets the app font. |
| Font size | Small, Default, Large, Extra large | Applied on top of your device font-size setting. |
| Date and time | Automatic, Date, Time; 12-hour or 24-hour | **Automatic follows the app language and your region.** |

The color picker has Hue, Saturation and Brightness sliders and a Hex color field that takes six hexadecimal digits, for example 1D4ED8 after the # sign.

The readability preview shows sample text. Its switches for right-to-left layout and larger text change only the preview, and **Reset preview** clears them.

## Voice & language

**Voice & language** contains **Language**, **Read Aloud**, **Dictation** and **Translate**.

### Language

**Language** switches White Noise on this device, and the change applies immediately. **System** follows the language set in your Android settings.

### Read aloud

> **Privacy:** A separate speech engine may send decrypted message text to its provider. For on-device speech, the app recommends [RHVoice](https://f-droid.org/en/packages/com.github.olga_yakovleva.rhvoice.android/) or [eSpeak NG](https://f-droid.org/packages/com.reecedunn.espeak/).

1. Open **Settings > Voice & language > Read Aloud** and choose an **Engine**.
2. Choose an installed offline **Voice**, or use **Automatic offline voice**. **Open Android Settings** takes you to system speech settings when you need to configure the engine.
3. Return to a conversation, touch and hold a message and choose **Read Aloud**. See [playback controls](/docs/using/messages/android#read-a-message-aloud).

**Read Aloud** has these controls.

| Control | Effect |
|---|---|
| Engine | The installed speech engine, labeled **On-device engine** or **May use network speech**. |
| Voice | **Automatic offline voice** uses the best installed offline voice for the message language. You can select a voice when it is installed and works offline. If your saved voice is unavailable, the app tells you which voice it is using instead. |
| Speech rate | **System** follows your device setting. You can also pick a preset, or choose **Custom** and enter a rate from 0.1× to 10.0×. |
| Read messages aloud by default | When a chat opens, unread messages are read aloud unless that chat has its own setting. |
| Speak over playing media | Speech plays over other audio that is playing. |
| Speech-over-media volume | Available only while **Speak over playing media** is on. Quiet, Medium or Loud: 35, 60 or 85 percent of the speech engine volume. |
| Refresh | Looks for engines and voices again. |

### Translate

Use [Translate messages](/docs/settings/android/translation) to install Offline Translator, choose languages and manage automatic translation.

### Dictation

Use [Dictate text](/docs/settings/android/dictation) to set up a speech provider and choose how recognized text enters your draft.

## Notifications

If notification permission is off, tap **Allow notifications**. You can also turn notifications on in Android Settings.

- **Delivery & battery** chooses **Push notifications** or **Local notifications**. Push uses Google servers (Firebase Cloud Messaging) to briefly wake White Noise, which then checks securely for messages. Local keeps a persistent connection open while notifications are on. Battery policy (Optimized, Unrestricted, Restricted or Unavailable) can delay delivery with either option. Push is unavailable when the build has no push server, when Firebase isn't configured or can't start, and on devices without compatible Google Play services.
- **Sounds & vibration** previews the **White Noise Ding** and **White Noise Pulse** tones and has **Add to Android sounds**. Choose the sound for each category in Android. **Quiet during podcasts** starts off on new installs, and your saved choice is kept. When it is on, messages arrive silently while a podcast or audiobook plays. Mentions still alert you.
- **Notification categories** are Direct messages, Group messages, Mentions, Reactions, Invites, Group membership changes, Agent activity, App updates and Notification summary. Android controls the sound, vibration and importance of each one. **Android notification settings** opens them.

Message previews and the other notification options are covered in [Notifications](/docs/using/notifications/android).

## Privacy & Security

**Privacy & Security** has these controls.

| Control | Effect |
|---|---|
| Screen security | Blocks screenshots and Recents previews. Turn it off to allow screenshots. |
| Incognito keyboard | Asks your keyboard not to learn from what you type. Some keyboards ignore this, so it can't be guaranteed. |
| Require device authentication | Asks for your device screen lock when you return to White Noise. Set up a screen lock first, using **Open Android security settings**. |
| Auto-lock | Sets how long White Noise can stay in the background before it asks for your screen lock again, from immediately to 15 minutes. |
| Blocked users | Lists people you blocked. You can unblock them there. |
| Default disappearing messages | Applies to new chats and groups this profile starts. Existing conversations keep their setting, and you can change each one later. |
| Diagnostics & Improvements | Optional usage sharing and technical logs. See [Android diagnostics](/docs/settings/android/diagnostics). |

Screen security, Incognito keyboard and device authentication are device settings. See [Privacy](/docs/using/privacy/android).

## Data & storage

Touch and hold a storage or download setting for details. Removal warnings and confirmations appear when you choose an action.

**Data & storage** controls downloads, sent media quality and the space attachments use on this device.

### Manage storage

**Manage storage** shows how much space each kind of data uses and has a **Free up space** section.

- **Largest attachments** lists the biggest files so you can remove them.
- **Remove older attachments** removes attachments older than 1 week, 1 month, 3 months, 6 months or 1 year.
- **Clear pictures and temporary files** removes cached copies.
- **Keep media** is **Forever**, **1 year**, **6 months** or **30 days**. Choosing a shorter period asks you to confirm first.
- Each chat has **Remove all attachments**. An attachment shared in several chats counts in each.

Removed attachments stay in their messages, and you can download them again while the sender's copy is still available.

### Downloads and media

**Automatic downloads** let you set a separate rule for each media type: Photos, Audio, Videos and Files. Each rule covers Wi-Fi, Mobile data, Roaming and Metered network, or you can choose Never. A download starts only if every network rule that applies allows it, and you can always download media yourself.

- **Download queue**: **Stop automatic downloads** clears waiting automatic downloads for this profile while active downloads and downloads you started yourself keep going. **Restart automatic downloads** turns them back on.
- **Reset download settings** restores the default rule for every media type.
- **Inline image previews** is off by default. When on, direct Blossom JPEG links in a chat show an image preview. Loading a preview may reveal your IP address to the image host. When off, the link stays an ordinary link.
- **Media quality** for sent media: Low (about 150 KB per photo), Standard (about 500 KB), High (about 1.5 MB) or Original. Quality applies to new photos and voice messages.

Photos always have location and device details removed, even at Original. Videos lose file tags, and imported audio is sent unchanged. See [Media](/docs/using/media/android).

## AI Agents

Use **AI Agents** to connect an assistant that runs on a computer or server you control. Each agent has its own account, and you chat with it like any other end-to-end encrypted chat. **Connectors** lists the options.

1. Choose a connector, then tap **Copy prompt**. Nothing is copied until you tap it.
2. Paste the prompt into that agent. It shares only your public key and asks the agent to get your approval before installing. Use it only with an agent you trust.
3. When the agent gives you its npub, add it as a contact and start a chat. **Copy public key** copies yours.

**Agent connector documentation** opens install scripts, health checks and troubleshooting in your browser. Use a separate group for each project.

## Help & support

| Row | Effect |
|---|---|
| Learn White Noise | Opens the optional walkthrough. |
| Documentation | Searches and opens the bundled Android guides offline. |
| Help > Report a bug | Opens a review screen. Nothing is attached, and GitHub issues are public, so review what you add. Tap **Open GitHub** to open the issue form in your browser. See [Report a problem](/docs/troubleshooting/report-problem#android-bug-report-screen). |
| Help > About & licenses | Shows the version, build and MarmotKit version, open source licenses and the privacy policy. |
| Chat with support | Starts a chat with White Noise Support. It appears only while a profile is active, and that profile needs a published receiving relay first. If it lacks one, tap **Open Relays**. |
| Donate | Opens the donation page in your browser. Donating is optional, and the app sends no data to that page. |
| Developer Tools | See below. |

## Developer tools

**Developer Tools** are for development and testing only, because they can expose technical information and change how the app behaves. Open **Settings > Help & support > Developer Tools**. To show the **Developer** row in **About & licenses**, tap **Version** seven times. The app confirms that the tools are unlocked.

- **Developer mode** is a switch. Turning it on adds **Quarantined groups**, **Streaming debug** and **Diagnostics**.
- **Key Packages** is always shown. It lists your published invitation keys. See [Identity and keys](/docs/settings/identity).
- **Quarantined groups** lists groups that couldn't be loaded safely. **Retry recovery** keeps your history and leaves your group membership unchanged.
- **Streaming debug** shows agent-stream and MLS signaling events inside conversations.
- **Diagnostics** shows relay health and performance logs. See [Android diagnostics](/docs/settings/android/diagnostics).
