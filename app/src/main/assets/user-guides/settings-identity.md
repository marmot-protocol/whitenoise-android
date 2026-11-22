# Identity, keys and signing out

Your profile is identified by a key pair. Share your **public key** so people can find and connect with you. Anyone who has your **private key** can use your profile, and White Noise can't recover it for you.

The procedures below cover Android and iOS. On Linux, use [Back up and recover your account](/docs/using/recovery/linux) for vault passwords, identity keys and backup limits, and [Your profile](/docs/using/profiles/linux) for account removal. Do not use the phone backup procedure to recover a Linux vault.

> **Important:** Your private key restores your identity only. After a wipe, past groups, messages and media are gone from that device, even if you sign in again with the same key, and you need new invitations to rejoin groups. See Recovery for [Android](/docs/using/recovery/android) or [iOS](/docs/using/recovery/ios).

## Encrypted backup import

White Noise exports identity backups as NIP-49 `ncryptsec1` strings protected by your passphrase. The current sign-in screens require a decrypted private key, shown as `nsec` in these guides. Before you delete an identity or its app data, keep the existing profile signed in on its device and keep the `ncryptsec1` backup and its passphrase available. Ask the runtime or app maintainers for a trusted method to decrypt NIP-49 locally, and confirm it produces your `nsec` before removing anything. Keep the key and passphrase on devices you trust; avoid online converters and web uploads.

## Invitation keys

Invitation keys, also called key packages, are published to your relays so others can add you to groups. You need at least one published to receive new group invitations. On both platforms, **Developer Tools > Key Packages** has **Publish New Key Package**. On iOS, turn on **Developer Tools** first. On Android, it is under **Settings** > **Help & support** > **Developer Tools** > **Key Packages**; **Republish** publishes the current key package again without replacing it, and **Delete key package** publishes a deletion event and clears that package from the local cache. See Groups for [Android](/docs/using/groups/android) or [iOS](/docs/using/groups/ios).

## Android: profile keys

Open **Settings** > **Account** > **Profile Keys**. The header shows whether the profile uses **Local signing** or an **External signer**. It also shows whether the account session is running, along with the connection status.

- **Public key**: **Copy public key** copies your public key.
- **Private key** (local signing only): **Show private key** reveals the key without copying it, and **Copy Private Key** copies it. White Noise clears that copy from the clipboard after about 60 seconds unless you copy something else first, and Android may run the clear late.
- If you signed in with Amber, Amber holds the private key, so manage backups in Amber.

### Export the private key

1. Under **Export** on the **Profile Keys** screen, tap **Export Encrypted Private Key**.
2. Enter a long, unique password twice. The app shows its strength, and you need this password to open the encrypted file.
3. Tap **Export** to save a file, or **View backup** to copy or export it. The preview hides after 30 seconds or when you leave the screen.

**Export Private Key** opens **Keep Your Private Key Safe**, where you share or save the raw key as an unprotected plain-text file. It exposes the key to whichever app you choose. An encrypted backup or a trusted password manager is safer. If the export expires, return to Profile Keys and start again; no key data was written. See [Back up and recover your account](/docs/using/recovery/android).

### Sign out

**Sign Out** is at the bottom of **Settings** and under **Account session** in Profile Keys.


- **Stays on this device:** Your groups and message history are kept, and so are your drafts. The profile stays available on this device, so you can sign back in from the account picker.
- **What changes:** Notifications for this account stop and temporary app data is cleared.
- **Remove invitation keys from relays**: On by default. Tries to remove them so you don't receive new group invitations while you are signed out. Turn it off to leave them published.

If the app says some invitation keys may still be on relays, you are signed out but those keys may still be published.

### Sign out and wipe

> **Warning:** This permanently deletes this account's messages and group encryption data and removes its sign-in key from this device. Its published invitation keys are removed from relays as well. You can sign in again with the same key, but past groups, messages and media are gone from this device. Back up your private key first.

1. Under **Account session** in **Profile Keys**, tap **Sign Out & Wipe** once your private key is safely backed up.
2. Type WIPE in the confirmation field, then tap **Wipe**.
3. The app leaves your groups and removes your invitation keys. It then wipes local data and reports how many groups were left and how many keys were removed.

If the result is **Local data wipe incomplete**, some data may remain on the device.

## iOS: profile keys

Open **Settings > Profile Keys**. **Public Key** shows your npub only, with a copy button. Under **Private Key**, **Show private key** and **Copy Private Key** each ask for confirmation, then reveal or copy the plaintext key. Doing so permanently marks the key as handled insecurely.

Under **Back Up Your Private Key**:

- **Back Up With a Password** opens the **Encrypted Private Key** sheet. Enter a **Password** and **Confirm Password**, then tap **Export** to share an ncryptsec1 backup. White Noise never stores the password.
- **Back Up Without a Password** asks **Export Without a Password?**. Tap **Export Key** to share the plaintext key and permanently mark it as handled insecurely.

## iOS: sign out and delete profile

Both actions are at the bottom of the main Settings screen and apply to the active profile.

- **Sign Out** opens a sheet. With **Wipe Local Data** off, this profile and its local data stay on this device, and its **Sign In** button on the welcome screen's **Ready to sign in** list still requires its private key. **Sign In with Another Account** uses a different key. With **Wipe Local Data** on, this profile's chats, media and secret key are erased from this device and you leave your groups; type the profile name, then tap **Sign Out and Delete Data**.
- **Delete Profile** is the iOS counterpart of Android's Sign Out & Wipe. It signs you out and permanently removes this profile's chats, media, drafts, secret key and local settings from this device. You leave your groups, and your other profiles are unaffected. Type the profile name exactly to confirm. If it fails, the screen says Couldn't wipe profile.

Leaving chats can take up to a minute, so keep White Noise open until it finishes. If **Cleanup Details** appears, some cleanup didn't finish and the screen lists what was removed and what may remain.

> **Warning:** After deleting a profile or wiping its local data, signing back in with the same key keeps your identity, but doesn't restore past groups, messages or media. You will need new group invitations. Encrypted messages already delivered to other members, and copies kept by independent relays, stay where they are.

> **Warning:** Back up the private key of every profile and confirm a working local recovery method for each encrypted backup before erasing all app data.

**Erase App Data** in Privacy & Security removes every profile and all local data from the iPhone. See [iOS settings](/docs/settings/ios).
