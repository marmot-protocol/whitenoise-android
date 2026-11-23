# Troubleshoot notifications

**Outcome:** New messages trigger an alert while White Noise runs in the background.

Notifications are alerts only. A missing alert does not mean a message failed, and an alert does not prove delivery.

## Diagnose

1. Open White Noise and look for the expected message. If it appears, focus on the alert settings below. If it is missing, check [message delivery](/docs/troubleshooting/delivery) too.
2. Make sure your operating system allows notifications for White Noise. You manage this permission in your device's system settings. On Android, **Settings > Notifications > Delivery & battery** shows **Notification permission** as Allowed or Blocked under **Android controls**, and **Android notification settings** on the Notifications screen opens the system page.
3. Check background conditions. Battery saving, data saving, focus modes and background app restrictions can delay or hide alerts. On Android, **Settings > Notifications > Delivery & battery** shows whether alerts arrive by **Push** or **Local**, and the **Battery policy** as Optimized, Unrestricted or Restricted.
4. Make sure the device stays connected in the background. VPNs and restrictive networks can block the services that alerts depend on.
5. Check the chat’s mute and mention settings. See Groups for [Android](/docs/using/groups/android#notifications-and-disappearing-messages), [iOS](/docs/using/groups/ios#notifications-and-disappearing-messages) or [Linux](/docs/using/groups/linux).
6. Check the delivery options for [Android](/docs/using/notifications/android), [iOS](/docs/using/notifications/ios) or [Linux](/docs/using/notifications/linux). Phone push notifications have their own data flows, described in the [privacy policy](/privacy).
7. Keep your installation. Reinstalling to fix alerts can delete the conversations stored on the device.

## Expected result

With the app in the background, a test message from a contact produces an alert, and the message is there when you open the app.

## Escalation

Report the app version, platform, operating system version and any settings you changed. Hide message previews and contact names in screenshots.
