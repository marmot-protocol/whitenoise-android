# Install and set up White Noise

White Noise for Android runs on Android 11 or later. After you install it, you can create a new profile or sign in with a Nostr identity you already have.

## Get the app

1. On Android, we recommend installing White Noise from [Zapstore](https://zapstore.dev/apps/dev.ipf.whitenoise.android).
2. On the White Noise listing, choose **Download** and follow Zapstore’s installation instructions. If Zapstore has no build for your phone, use another download option.
3. If you'd rather use Google Play or download from GitHub, see [Download](/download) for those options.
4. Open White Noise to reach the welcome screen.

**Expected result:** the welcome screen shows **Sign Up** and **Sign In**. If the app reports a connection problem, connect to Wi-Fi or mobile data and tap **Retry**.

## Create a new profile

**Before you sign up, know that your profile is public.** Everyone on the network can see the details you publish, including your photo, so consider using a nickname in place of your real name.

1. Tap **Sign Up** on the welcome screen to open the **Sign up** form.
2. Check the **Name** people will see. White Noise fills in a generated name. Edit it, or tap **Suggest name** for another. If you clear the field, White Noise publishes a generated name.
3. If you like, add **About** text, or tap **Add Photo** and choose **Choose photos** or **Choose files**. **Find Image on Web** sends your search and IP address to DuckDuckGo and to the image hosts, so choose an image from your device to avoid that. Use **Change Photo** or **Remove Photo** to adjust it.
4. Tap **Sign Up**, then wait while White Noise shows **Creating Identity**, **Uploading…** and **Publishing profile…**.

If identity creation stops, check your connection and tap **Sign Up** again. If your identity was created but the photo upload or profile publishing stopped partway, tap **Retry**, which keeps the same identity. You can also choose **Continue without these changes**, which keeps the identity; edit your profile later from [Profiles](/docs/using/profiles/android).

## Sign in with an existing identity

For an encrypted backup, first follow [Encrypted backup import](/docs/settings/identity#encrypted-backup-import).

A Nostr identity has two keys: your public key (npub) is the address you share with people, and your private key (nsec) signs you in. Keep your nsec private, because anyone who has it can act as you.

1. Tap **Sign In** on the welcome screen to open the private key form.
2. Paste or type your nsec into **Enter private key**. To scan it instead, tap **Scan QR Code** and allow camera access.
3. Tap **Sign in** and wait while it shows **Signing in…**. White Noise then finishes setting up your account. When sign-in succeeds, it clears the key field and the clipboard.

**Sign in with Amber** appears only when the Amber signer app is installed. Tap it, approve the identity request in your signer (step 1 of 2), then sign the identity proof (step 2 of 2). If Amber reports that sign-in was cancelled, start again from the welcome screen.

If you have saved profiles on this device, the welcome screen offers **Continue as** with a profile name, or **Choose profile**.

Sign-in messages and what to do:

- **That's a public key. Enter your secret key (nsec) to sign in.** Replace the npub with your nsec.
- **That doesn't look like a valid nsec or npub key.** Check for typos or missing characters.
- **Couldn't sign in with that key. Check it and try again.** This also appears for an encrypted key that starts with `ncryptsec1`, which this screen cannot import.
- **Account setup didn't finish. Tap sign in again to resume it.** or **Account setup is still finishing. Tap sign in again to complete it.** Tap **Sign in** again.
- **Unexpected account state, so sign-in stopped. Nothing was changed.** See [Access problems](/docs/troubleshooting/access).

With no connection, White Noise says **No internet connection** and asks you to reconnect and retry.

## Finish account setup

If **Sign in** stays open after you sign in, White Noise is checking your account in stages. The progress bar shows the current stage, and the page shows one decision at a time. Checks cover your profile, follow list, read and write relays, inbox relays, device use and messaging keys. You see **Connecting your account…**, the current stage (**Stage X of Y**) and how many stages are complete. Some checks take longer. Tap **Details** to see each check under **Setup checks**, with a status such as **Waiting**, **Checking**, **Ready**, **Needs attention**, **Try again**, **Waiting for signer** or **Skipped**. **Other options** lists more actions.

If a relay decision appears, tap **Review suggested changes** or **Edit relays**, then inspect the proposed read and write lists. Recommendations keep existing relay entries, while a custom list removes entries you leave out. Changes can affect your other Nostr apps, so review the lists before choosing **Save**. Choose **Discard changes** if you don’t want to publish them.

A profile decision (**Add a profile?**) offers **Add profile** or **Continue without a profile**. Adding a profile updates your profile in other Nostr apps.

**Chats on this device** (also shown as **One phone per account**) comes next. Chats don’t sync between phones, so use this account on one phone at a time. If White Noise finds messaging keys from another app or device, this device gets its own separate chats, chats in the other app stay there, and a new chat with you may arrive in either app. If it can’t check, it says so and gives the same warning. Read the warning, then tap the button confirming that you understand to continue.

If a relay can’t be reached or a signer is needed, choose **Try another discovery relay** or **Reconnect signer**. If setup stops, tap **Resume checks**. To stop, choose **Cancel setup**.

When every check is ready, White Noise shows **Opening your chats…**; tap **Open Chats** if it waits. To leave early, choose **Finish later**: your progress is saved, and you choose this account again to finish. Anything already published stays on relays.

## Choose whether to share diagnostics

After setup finishes, **Help Improve White Noise** appears over your chats with two switches, both off until you turn them on:

- **Share usage**: feature use and app performance.
- **Share diagnostic logs**: technical data about messaging.

There is no confirm button. Closing the sheet saves your choice, and closing it without choosing saves both as off. You can change this later in **Settings** > **Privacy & Security** > **Diagnostics & Improvements**; see [Privacy](/docs/using/privacy/android#diagnostics).

## Recover an unfinished setup

White Noise offers two recovery options, and neither one restores earlier conversation history.

- **Recover** appears on the **Recover this account?** prompt when an earlier sign-in stopped partway. Recovering clears the unfinished setup and tries signing in again. Invitation keys that were already published may stay on relays. **Cancel** closes the prompt and changes nothing.
- **Recover setup** appears under **Recover sign-in setup?** on a saved profile whose setup needs repair. It signs that profile out and clears the setup that didn’t finish, so you must sign in again yourself with the profile's private key. Anything already published to relays stays there, and nothing new is published. White Noise then says **Setup cleared. Sign in again with this profile’s private key.**

If sign-in stops because of an unexpected account state, White Noise says nothing was changed. See [Access problems](/docs/troubleshooting/access) for more help.
