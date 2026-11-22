# Relays

Relays let your profile publish information, receive chat invitations and deliver messages. Each profile has its own relay settings. Other people's apps read your published relay lists to find where to reach you, so if those lists are missing, people may be unable to invite you.

## Relay roles

| Role | Android label | iOS label | Purpose |
|---|---|---|---|
| Posting | Where I post (Profile) | Account Relays | Publishes your profile and connection information. Edits are published as your NIP-65 relay list. |
| Receiving | Where I receive (Inbox) | Inbox Relays | Where people send you chat and group invitations. Edits are published as your inbox relay list. |

The Android and iOS relay controls below require wss:// URLs. For Linux relay controls, see [Linux settings](/docs/settings/linux).

## Android

Open **Settings > Account > Relays**. **Relay lists** shows whether your lists are published, and **Profile relays** lists each relay this profile uses. Tap a relay to open **Relay**, which shows its address and its **Use For** roles.

### Add a relay

1. Tap **Add relay**.
2. Enter a unique URL that starts with wss://.
3. Under **Use For**, choose at least one role, then save.

### Remove a relay

Open the relay and tap **Remove relay**, then confirm. This profile stops using the relay, and existing chats keep their relays. If it is the only relay for a role, **Remove relay** is unavailable and the screen asks you to add another relay for that role first.

### Relay lists

The status shows Published, Missing or Status unavailable. **Refresh status** checks the status again. **Publish missing lists** publishes the missing lists so people can find where to reach you. If publishing fails, your relay settings are kept.

A relay marked **Needs attention** was imported with an address that is not a secure wss:// address, or that cannot be used. Its role assignments were kept during import. Add a secure replacement or remove the imported relay.

An imported .onion relay is marked **Needs Tor**. White Noise does not connect through Tor, so it skips that relay. Editing the lists here republishes them without it.

### Restore defaults

> **Warning:** **Restore Default Relays** replaces this profile's relay list and role assignments with the defaults, and your custom relays are removed.

### Support chat

**Chat with support** (under **Settings > Help & support**) needs a published receiving relay on the profile. If the support screen says **Profile relays need attention**, tap **Open Relays**.

## iOS

The image shows an earlier local iOS Simulator build, not an App Store binary. See [iPhone capture details](/docs/compatibility#iphone).

Open **Settings > Relays**. An empty list shows No relays published.

1. In **Account Relays** or **Inbox Relays**, tap **Add Relay**.
2. Enter a relay URL that starts with wss://, then tap **Add**.

To remove a relay, swipe it left and delete it, or tap **Edit** and use the delete controls. Each list must keep at least one relay, so White Noise shows **Keep at least one relay.** if you try to remove the last one.

**Published Relay Lists** has expandable **NIP-65** and **Inbox** rows with the relays published in each list and a count. The footer says **All relay lists are published.**, or names the missing lists and asks you to add a relay to publish them. If the screen fails to load, tap **Retry**.

## Invitation keys

Invitation keys, also called key packages, are published to relays so others can add you to groups. See [Identity and keys](/docs/settings/identity) and Groups for [Android](/docs/using/groups/android), [iOS](/docs/using/groups/ios) or [Linux](/docs/using/groups/linux).
