# Notifications

Choose what notifications show, how White Noise receives them in the background, and which sounds they use. Open **Settings**, then **Notifications**. The screen has a **Notification previews** choice and three links: **Sounds & vibration**, **Delivery & battery** and **Android notification settings**.

## Allow notifications

1. Tap **Allow notifications**.
2. Accept the Android permission prompt.

If you decline, White Noise shows **Notification permission denied**. The screen then shows **Notifications are off**. Tap it to open Android Settings, turn notifications on for White Noise, then return.

## Choose what notifications show

Under **Notification previews**, pick one:

- **Name and message** shows the sender and the message text.
- **Name only** shows the sender and conversation, and hides the message text and reaction emoji.
- **Hidden** shows the White Noise name and a generic **New notification** line.

Until you accept a group invitation, only the invitation notifies you. It reads **Invitation to** and the group name, or **Open White Noise to view the invite** when the group has no name, and never names who invited you. Messages, reactions and admin changes in that group stay silent until you accept.

The preview choice applies to every account on this device. If saving fails, a **Retry** row appears with the note **Changes may be lost after restarting.** Tap **Retry** until it disappears. Hold a notification setting to see a short explanation of it.

## Pictures in notifications

With **Name and message** or **Name only**, a notification can show the contact's or group's picture. It falls back to a letter badge when there is none. A photo message reads "sent a picture" under Name and message, and **New message** under Name only. With **Hidden**, a notification shows only the White Noise name and **New notification**.

## Choose a delivery method

Open **Delivery & battery**. Under **Delivery**, pick one:

- **Push notifications** uses Google servers (Firebase Cloud Messaging) to briefly wake White Noise, which then checks securely for messages.
- **Local notifications** keeps a persistent connection while notifications are on and creates them on this device.

Push can be unavailable. The screen then shows why: **Unavailable: this build has no push server configured.**, **Requires compatible Google Play services.** or **Unavailable: Firebase isn't configured or failed to start.** Use **Local notifications** in those cases. The **Delivery & battery** row on the main screen shows the active method as **Push** or **Local**.

## Check Android controls

Under **Android controls**, **Delivery & battery** shows:

- **Notification permission**: **Allowed** or **Blocked**. Alerts appear only while it is allowed.
- **Battery policy**: **Optimized**, **Unrestricted**, **Restricted** or **Unavailable**. Battery policy may delay delivery in either mode.

Tap an item to open the matching Android settings. If White Noise reports **Couldn't open notification settings**, open Android Settings and find White Noise yourself. The values refresh when you return.

## Sounds and vibration

Open **Sounds & vibration**. Android sets sound, vibration and importance for each notification category, so tap a category to open its Android settings. The categories are Direct messages, Group messages, Mentions, Reactions, Invites, Group membership changes, Agent activity, App updates and Notification summary. Android also lets you set a sound for one conversation or person.

Under **White Noise sounds**:

- **White Noise Ding** and **White Noise Pulse**: tap one to hear it, and tap again to stop. Previewing follows notification volume, Silent and Do Not Disturb, and changes no alert.
- **Add to Android sounds** adds both tones to Android's sound picker. Then open a category and choose **Sound**. Some devices list them under My Sounds. Adding does not change any alert. Conversations and people with their own sound keep it.

### Quiet during podcasts

**Quiet during podcasts** is off by default, and White Noise keeps a choice you already made. When you turn it on, messages arrive silently while a podcast or audiobook plays, so it is not interrupted. Mentions still alert.

## Related

- [Media and downloads on Android](/docs/using/media/android)
- [Notifications on iOS](/docs/using/notifications/ios)
