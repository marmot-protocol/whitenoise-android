# Account access

**Outcome:** You can use your White Noise identity again, and you understand exactly what your private key restores.

**Prerequisites:** The device that holds the account, or a secure backup of your private key.

**Before you act:** White Noise cannot reset or recover your private key, so keep your backup secure. Your key restores your identity only. Message history and group encryption state live on the device that holds them, and reinstalling the app or clearing its data deletes the conversations stored on that device.

For an Android or iOS encrypted identity backup, first follow [Encrypted backup import](/docs/settings/identity#encrypted-backup-import). For Linux vault access and backup limits, use [Back up and recover your account](/docs/using/recovery/linux) before changing or removing any files.

## Diagnose

1. Start on the device that already has the account. If the app opens but misbehaves, restart it and work through [Connection](/docs/troubleshooting/connection) before you change anything else.
2. Keep the existing installation. Conversation data lives on the device, so treat reinstalling or clearing storage as a last resort.
3. Moving to a new device? Import your private key there to keep the same identity, and expect to start fresh. Earlier conversations and their encryption state stay on the old device. Use the profile on one device; White Noise does not synchronize conversation history between installations.
4. If your device or key may have been stolen, treat the identity as compromised and warn your contacts through another trusted channel. Messages already stored on the stolen device remain there, because encryption cannot erase them.
5. Share only your public key (`npub`). Keep your private key out of chats and support requests, and keep it out of agent prompts as well.

## Expected result

The account opens with the same public key. On a new installation, contacts may need to invite you to conversations again.

## Escalation

If the account still fails to load, note your app version and platform. Copy the exact error label too, then follow [Report a problem](/docs/troubleshooting/report-problem). Before sharing screenshots, remove any keys and contact names. Remove message content as well.
