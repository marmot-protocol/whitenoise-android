# Back up and recover your account

Your private key is how you sign in to this profile again, and only you hold it. White Noise has no way to recover it for you, so back it up before you sign out, wipe an account, or change phones.

## What a key backup restores

A private key backup restores your identity: your profile and the ability to sign in as yourself. Message history and group encryption (MLS) state live on the device where they were created and stay out of the backup. If you move to a new phone with only your key, your identity moves with you and your conversations remain on the old device. To rejoin groups from a new device, ask members to invite you again.

## Encrypted backup import

White Noise exports identity backups as NIP-49 `ncryptsec1` strings protected by your passphrase. The current sign-in screens require a decrypted private key, shown as `nsec` in these guides. Before you delete an identity or its app data, keep the existing profile signed in on its device and keep the `ncryptsec1` backup and its passphrase available. Ask the runtime or app maintainers for a trusted method to decrypt NIP-49 locally, and confirm it produces your `nsec` before removing anything. Keep the key and passphrase on devices you trust; avoid online converters and web uploads.

## Back up your private key

1. Go to **Settings** > **Account** > **Profile Keys**.
2. Under **Export**, tap **Export Encrypted Private Key**.
3. Enter a **Password** and repeat it in **Confirm password**. Choose a long, unique password; the **Strength** meter rates it from **Too short** to **Strong passphrase**. You need this password to open the file.
4. Tap **Export** to save the backup to a file you choose, or tap **View backup** to see it first.
5. Finish the save in the file location you picked.

Expected result: an encrypted backup file in the location you chose. Your backup exists only once that location has saved it, so check it’s there. Keep the password somewhere separate from the backup, because you need both to restore.

**View backup** opens **Your encrypted backup** with **Copy** and **Export**. The preview hides after 30 seconds or when you leave the screen. A copied backup is cleared from the clipboard after about a minute.

### Copy or export the raw private key

Anyone who has your private key controls your profile, and it can’t be recovered or reset. Use the encrypted backup, or keep the raw key only in a trusted password manager.

- **Show private key** reveals the key on this screen. It doesn’t copy or share it.
- **Copy Private Key** copies it to the clipboard, marked as sensitive. White Noise clears it after about 60 seconds, even if you leave the screen. The clear is skipped if you copy something else in the meantime. Android may run it a little late, so paste the key right away and don’t rely on the timer.
- **Export Private Key** opens **Keep Your Private Key Safe**. Choose **Share** to send the plain-text key to an app you pick, or **Export Private Key** to save it as a plain-text file with no password. Sharing exposes the key to the app you choose.

You can use the same private key to sign in to other Nostr apps as the same identity, with the same public key. Give it only to apps you trust to hold it, or keep it in a signer app like Amber, which signs for other apps without handing them the key.

If Profile Keys shows **Signed in with Amber**, Amber holds this profile’s private key. Manage backups in Amber.

### If an export stops

- **Export expired**: return to Profile Keys and start again. No key data was written.
- **Couldn’t Save File**: choose another location and try again.
- **Couldn’t open sharing**: choose another app or save the key to a file instead.

## Sign out

Back up your private key first if you haven’t. On **Settings**, or on Profile Keys under **Account session**, tap **Sign Out**. Sign-out keeps this account’s local data on the device, including groups, message history and drafts, along with the saved sign-in. Notifications for this account stop and temporary data is cleared while you’re signed out. Sign back in from the account picker to return.

The sign-out sheet has a **Remove invitation keys from relays** switch, which starts on. While it is on, signing out removes your published invitation keys from relays, so nobody can invite you to new groups until you sign in again. Turn it off to leave them published.

A notice that some invitation keys may still be on relays means sign-out finished while relay cleanup is incomplete.

## Sign out and wipe

> **Warning:** **Sign Out & Wipe** permanently deletes this account’s messages and group encryption data, removes its sign-in key from this device, and removes its published invitation keys from relays. You can sign in again later with the same key. Past groups, messages, and media are gone from this device for good, and rejoining groups requires new invitations.

1. Back up your private key first, and confirm the backup is saved.
2. Under **Account session**, tap **Sign Out & Wipe** and review **What gets destroyed**.
3. Tap **Sign Out & Wipe** to continue.
4. Type WIPE in **Confirmation**, then tap **Wipe**.

Expected result: progress moves from leaving groups and removing invitation keys through to wiping local data, ending at **Account wiped from this device**.

If the outcome shows **Local data wipe incomplete** or **Couldn’t wipe account**, read the outcome list and write down what it reports before you tap **Close**. Use a recovery action only if the app explicitly offers one. Then contact support with those details. Leave reinstalling, clearing app storage, or starting the wipe again until support advises it, since repeating destructive steps can make the remaining state harder to sort out.

## Related

- [Privacy and security settings](/docs/using/privacy/android)
