# Privacy and security settings

These controls limit what other people and apps can see on your phone. Open Settings, then **Privacy & Security**.

## Device protection

- **Screen security** blocks screenshots and hides White Noise from the preview in Android’s recent apps list. Turn it off if you want to allow screenshots. This is a device setting.
- **Incognito keyboard** asks your keyboard to skip learning from what you type in White Noise. Keyboards may ignore this request, so treat it as best effort.
- **Require device authentication** asks for your device screen lock when you return to White Noise after the **Auto-lock** period has passed.
- **Auto-lock** sets how long White Noise can stay in the background before it asks again: **Immediately**, **After 1 minute**, **After 5 minutes** or **After 15 minutes**; the option appears once device authentication is on.

### Turn on device authentication

1. Under **Device protection**, find **Require device authentication**.
2. If it says **Set a device screen lock first**, tap **Open Android security settings**, set a screen lock, and come back to White Noise.
3. Turn the setting on, then choose an **Auto-lock** period that suits you.

Expected result: once you’ve been away longer than the Auto-lock period, White Noise asks for your screen lock on return. A quick switch within that period may open the app without a prompt.

## Blocked users

Blocking stops a person from messaging you directly. Shared groups keep working, and group history stays on this device.

1. Under **Blocked users**, tap **Blocked users**.
2. Tap a person, then **Unblock**.

Sometimes White Noise saves your request before it can confirm it; repeating the same action finishes it. When the block list is unavailable, try again later. A failed change can be retried.

## Default disappearing messages

Under **Message privacy**, **Default disappearing messages** sets how long messages remain in chats and groups this profile starts. It applies to new conversations only, and you can change each conversation later.

> **Warning:** Disappearing messages limit how long messages stay in White Noise. Recipients can still keep copies, for example with screenshots or another camera, and expiry leaves anything already saved untouched.

## Diagnostics

Sharing is off until you choose it. Open **Privacy & Security** and, under the **Diagnostics** heading, tap **Diagnostics & Improvements**. The row summary shows On, Usage, Logs, or Off. Both switches cover all profiles on this device.

- **Share usage and telemetry** shares feature use and app performance, with a temporary session ID and an installation ID you can reset. It never includes account or group IDs.
- **Share group diagnostic logs** uploads technical logs about groups to the White Noise audit collector, run by the Internet Privacy Foundation. Turning it on may also upload logs already stored on this device. Logs can include group and message IDs, relay addresses, timestamps, pseudonymous account references, an installation ID, device model and app version. They never include message contents or private keys.

The servers delete group logs after 30 days, telemetry after 90 days and analytics after 180 days.

Turning a switch off stops new recording and uploads, though one already in progress may finish. It does not delete logs already on this device or on the servers. To remove the copies on this device, tap **Clear Diagnostic Logs** under **Stored Diagnostic Logs**, then confirm with **Clear Logs**. This permanently deletes every profile’s diagnostic logs from the device and leaves your sharing choices unchanged.

The choice you make during setup is saved without a confirmation popup. See [Install White Noise on Android](/docs/get-started/install/android).

## Related

- [Back up and recover your account](/docs/using/recovery/android)
