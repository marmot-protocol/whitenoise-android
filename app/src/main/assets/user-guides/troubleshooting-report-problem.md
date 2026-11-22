# Report a problem

A good report describes what happened and where, then compares it with what you expected. It shares as little conversation data as possible.

**Privacy first.** Screenshots and logs can expose your contacts' names and keys. Copied messages expose their words as well. Crop or redact other people's details, and ask them before sharing anything they wrote. Group diagnostic logs are a separate opt-in choice and include group and message identifiers. On iOS and Android, turning on log sharing uploads eligible logs to IPF automatically.

## Where to report

On Android, open **Settings > Help & support > Chat with support**, which appears while a profile is active. On iOS, open **Settings > Chat with support**. Either opens help inside White Noise. For a reproducible bug, choose the affected project:

- [Android issues](https://github.com/marmot-protocol/whitenoise-android/issues)
- [iOS issues](https://github.com/marmot-protocol/whitenoise-ios/issues)
- [Linux issues](https://github.com/marmot-protocol/whitenoise-linux/issues)
- [Agent connector issues](https://github.com/marmot-protocol/mdk/issues)
- [Website issues](https://github.com/marmot-protocol/whitenoise-web/issues)

## Android bug-report screen

Open **Settings > Help & support > Help > Report a bug**. First, White Noise shows a review screen explaining that nothing is attached: messages, media, contacts, profile details, keys and logs stay out of the form. Tap **Open GitHub** to open the public issue form, then review anything you add. If no browser is available, return to Help or try again after installing one.

## Before you report

For Linux log controls and their privacy limits, see [Linux diagnostics and local logs](/docs/settings/linux/diagnostics).

1. Note your platform and device. Add your White Noise version too; [Compatibility](/docs/compatibility#find-your-version) explains where it appears.
2. Write down the steps that led to the problem and when it happened. Copy the exact error label as well, if one appeared.
3. Keep two kinds of status apart. A message marked as sent on your device has a local status. It does not show that the recipient read it, so confirm receipt or reading by asking the recipient through another channel. On Android, **Message Details** shows **Received** only for messages that arrived on your device, not as a delivery receipt for messages you sent.
4. Check your connection with [Connection](/docs/troubleshooting/connection). A relay or VPN can block or delay traffic. Network providers can do the same, and White Noise relies on all of them for connectivity.
5. For missing notifications, confirm the operating system allows White Noise to notify you and to run in the background. Treat a notification as a convenience; delivery is confirmed by the recipient.

## Keep local data intact

Clearing storage or resetting the app can remove the local state your conversations depend on, and reinstalling has the same effect. Your identity key alone does not restore MLS conversation history. Report the problem before trying any of these steps.

## Agent problems

For a connected agent, include the runtime name (for example Hermes or Claude Code) and what the agent replied. Add connector errors with private content removed. Connector guides live in the MDK repository.

## Expected result

Maintainers can reproduce or narrow the problem while your private conversations stay private.
