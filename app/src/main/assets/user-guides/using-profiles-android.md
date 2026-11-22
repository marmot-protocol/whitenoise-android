# Your profile

Your profile is how people recognize you in White Noise. The **Profile** screen shows the notice **Profile is public**, which means everyone on the network can see your profile information, so add only details you are comfortable publishing.

## Edit your details

1. Go to **Settings** > **Account** > **Profile**, or tap your avatar on **Settings**, then tap **Edit**.
2. Update any of these fields:
   - **Name**: your display name. Tap **Restore saved name** to bring back the last saved name.
   - **NIP-05 address**: a Nostr address in the form name@domain.
   - **Lightning address**: for example you@walletprovider.com. White Noise shows **Checking…** while it confirms the address.
   - **About**: a short bio.
3. Tap **Save**.

Expected result: **Saving profile…** appears while White Noise publishes your changes. A confirmed NIP-05 address shows **Verified Nostr address**.

## Add a photo or banner

1. Tap **Add Photo** or **Add banner**. If you already have one, tap **Change Photo** or **Change banner**.
2. Pick **Choose photos** or **Choose files** to use an image from your device, or pick **Find Image on Web**. Image links must be public HTTPS URLs.
3. To take an image off your profile, choose **Remove Photo** or **Remove banner**.

**Uploading…** stays on screen until the image is stored.

> **Privacy:** Before you tap **Find Image on Web**, know that your search goes to an image search service, and loading results contacts the sites that host them. Those services receive the request and your IP address, as the White Noise privacy policy describes. Choosing an image from your device avoids that exchange.

Profile pictures and banners are public. Removing one updates your profile, but copies may remain on the independent servers that stored or relayed it.

## Check profile status

White Noise shows **Set up your profile** until you add a name. After that it shows **Profile ready** or **Profile ready · Optional details available**. Tap **Details** for the checklist, where **Display name** is marked **Recommended** and the remaining items (**Profile picture**, **Bio**, **Banner**, **NIP-05 address**, **Lightning address**) are **Optional**.

## Share your profile

Open **Share & Connect** by tapping your profile row at the top of **Settings**:

- Let someone scan your **Profile QR code**.
- Tap your shortened public key to copy it, or tap **Copy public key**. White Noise shows **Copied**.
- Tap **Share profile**, then **Share profile URL** or **Share profile card**. The card reads *On White Noise? Message me here.*
- Tap **Save to Gallery** to keep the card image. Expected result: **Saved to gallery**.
- Tap **Scan QR Code** to scan someone else's profile code.

## Switch or add profiles

In **Settings**, tap **Add Profile** if this device has one profile, or **Switch Profile** if it has more than one. **Switch Profile** lets you pick another signed-in profile or add one. Adding a profile offers the same choices as the welcome screen: **Sign In** with a private key or Amber, or **Sign Up** for a new identity. See [Install and set up White Noise](/docs/get-started/install/android) for each step.

If creating the identity or the Amber sign-in fails, start the step again. When Amber asks for approval, approve the request so sign-in can finish.

**Sign Out** is at the bottom of **Settings** and under **Settings** > **Account** > **Profile Keys**. Signing out keeps your groups, history and drafts on this device. For keys, signing out, and what **Sign Out & Wipe** removes, see [Recover and back up your keys](/docs/using/recovery/android) and [Identity settings](/docs/settings/identity).

## Fix common problems

- If your profile fails to save, review each field and tap **Save** again. NIP-05 and Lightning addresses use the form name@example.com.
- If a Lightning address fails to resolve, correct the spelling. When White Noise reports a network problem, reconnect and save again.
- If an image upload or preparation fails, try again or choose another image.
- If a scanned code is rejected as something other than a White Noise profile, ask the person to show the code from their **Share & Connect** screen.

## Related

- [Identity settings](/docs/settings/identity)
- [Contacts on Android](/docs/using/contacts/android)
- [Your profile on iOS](/docs/using/profiles/ios)
