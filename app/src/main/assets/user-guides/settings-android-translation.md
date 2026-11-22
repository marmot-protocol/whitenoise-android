# Translate messages

Translation is handled by **Offline Translator**, a separate app that runs translation on your device. White Noise passes message text to that app, and you manage language packs inside it.

> **Privacy:** Offline Translator can write message text and translations to Android system logs. White Noise can't guarantee the security of text handled by another app.

## Set up Offline Translator

The current reviewed White Noise Android build only works with Offline Translator signed by its original developer, versions 0.8.3, 0.8.4 and 0.8.5. White Noise does not automatically accept newer versions. Each new version must be checked for compatibility before White Noise supports it.

1. Install [Offline Translator 0.8.5](https://github.com/DavidVentura/offline-translator/releases/tag/v0.8.5) from the publisher's GitHub release. If it isn't installed, **Settings > Voice & language > Translate** shows **Install from Zapstore** when Zapstore offers a supported version, and otherwise **Install from GitHub**. At the moment Zapstore lists an older version, so the app opens GitHub.
2. Open Offline Translator and install the language packs you need. Install a pack for each language you want to translate from and into.
3. Return to White Noise. Touch and hold a text message, then tap **Translate**.

The first time you translate, the app shows a privacy notice. Tap **Accept** to continue. The engine detects the message language and translates into the chat's target language. If the chat has no usable target, it uses your device language. Messages longer than 16,384 characters can't be translated.

When the translation is ready, a button beside the message switches between the translation and the original. It shows a progress ring while the app is **Translating on device…**. See [Translate a message](/docs/using/messages/android#translate-a-message) for the message controls.

### Translate before you send

Add the **Translate** shortcut to the composer, or turn on **Translate** there, to translate your own text. The first use shows the same privacy notice. The composer shows **Translation appears here** and **Will send**, and sends the translated text, not your original. Send is blocked while the translation is missing, pending or out of date. The target for outgoing text is saved for each chat. Translation is off while you dictate. If the app can't detect the language, it asks you to type a bit more.

### Troubleshooting

- **Install Offline Translator to translate on this device:** Install it from the link above.
- **This engine is unverified or unsupported. No text was sent:** Install a supported version from the link above.
- **Choose a source language and retry:** The app couldn't detect the language. Choose it in the engine, then try again.
- **Install the source language pack in the engine:** Open Offline Translator, install the pack for that language, then try again.
- **This language needs a newer Offline Translator:** Update Offline Translator to a supported version, then try again.
- **Translation failed. Check language packs in the engine, then retry:** Open Offline Translator and check that the packs you need are installed.
- **Translation unavailable. Tap Translate to try again:** Tap **Translate** again.
- **No app could open Offline Translator or its download page:** Install a web browser, then try again.
- **The saved target language is not available:** Choose another **Target language**.

## Global settings

Open **Settings > Voice & language > Translate**. The screen recommends Offline Translator, which translates on your device, and repeats the privacy warning above.

| Control | Effect |
|---|---|
| Automatically translate messages | Turns automatic translation on or off for every chat that uses the global setting. It starts off. |
| Target language | The language messages are translated into. It starts as English. Choosing a language from the list leaves its language pack uninstalled until you add it in Offline Translator. |
| Open engine | Opens Offline Translator, where you install and manage language packs. If it isn't installed, this control shows **Install from Zapstore** or **Install from GitHub** instead. |

Message **Translate** always works, whatever this setting says. Automatic translation runs only where the chat's effective setting is on.

## One chat

Open a chat's details and tap **Automatically translate messages** to set it for that chat. The choice is saved separately for each profile. Changes apply as soon as you make them, and **Close** dismisses the dialog.

- **Use global setting** follows **Settings > Voice & language > Translate**. **On** or **Off** overrides it for this chat only.
- **Use global target language** starts checked. Clear it to pick a different **Target language** for this chat.
- **Open engine** opens Offline Translator, or shows an install action if it isn't installed.

Changing the mode doesn't change the target language. These settings store your choices only. Message text and translations are kept out of them.

[Android settings](/docs/settings/android)
