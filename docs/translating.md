# Help translate White Noise for Android

You can start with one screen or flow. The English text is split across
[the main values files](../app/src/main/res/values/). This guide covers the app
interface. Translating messages inside a chat uses a separate language catalog.

## Pick a language and a flow

Use the Android resource qualifier for your locale. Japanese uses `values-ja`. Regional and script
variants use qualifiers such as `values-pt-rBR` and `values-b+zh+Hant`. Look for an existing locale before starting a new one.

From the repository root, run this with Python 3.10 or later:

```sh
python3 scripts/translation_worklist.py --locale ja
```

The worklist shows the priority, flow, source file, resource key, and status.
Start with the launch flows, then translate the everyday features. Most long-press
explanations can wait. An explanation that tells someone what a privacy control
exposes belongs with the control. The same applies to TalkBack labels for core actions.
The [launch priorities](translation-priorities.json) take precedence over file grouping.

## Edit the resources

For a shipped locale, edit its files under `app/src/main/res/values-<qualifier>/`.
For a new locale, put the entries you translated under
`app/src/preview/res/values-<qualifier>/`. The preview build includes that
directory. Other builds use the main resources. Use the English filenames where
practical so reviewers can find the corresponding text quickly.

- Keep the resource name, type, XML tags and format arguments. Arguments such as
  `%1$s` and `%2$d` can move to fit the sentence. Preserve Android escapes,
  deliberate line breaks and literal tokens such as `npub` and `nsec`.
- Let Android fall back for missing keys. For plurals, use the quantities your
  locale needs and always include `other`. For example, Russian uses `one`,
  `few`, and `many` for different integer counts.
- Use the screen to resolve ambiguous strings. Add an English source comment
  when future translators would need the same context. Explain the action or state. Give an example for ambiguous arguments. Leave existing `translatable="false"` entries
  in the source.
- If you use machine assistance, say which flow was machine-assisted and flag
  ambiguous wording. Have a fluent speaker review the copy before release.
  Verify what changes when someone accepts a recovery prompt or changes a privacy setting. Check destructive actions on a test profile.

| English term | Meaning to preserve |
| --- | --- |
| Sign in / sign out | Start or end access to the account on this device. Wiping device data is a separate action. |
| Private key | The secret used to control an account. Keep `nsec` and `npub` distinct. |
| Relay | A server White Noise uses to exchange events. |
| Invite | A request to join a conversation or group. |
| Leave / remove / delete | Leaving changes membership. Removing takes a chosen item or member out of its context. Deletion copy should state what is deleted and for whom. |

## Check and submit a pull request

Run `python3 scripts/translation_worklist.py` to check the resource files. You
can add `--base origin/master` to see new or changed English keys and their
affected locales. A changed source entry calls for a review of its meaning.
Name the locale and screens in your PR. List the checks you ran and flag wording that needs a fluent reviewer.
Screenshot tests render in English, so a PR that edits a shipped locale under
`app/src/main/res/` should include `Visual changes: none` in its description.

For a new locale, ask for the stable PR preview. Set the preview app's language
to **System default**, then put your target language first in Android's language
settings. Reopen the affected screens and check the translated actions and errors.
Confirm unfinished screens can fall back. Restore the device language after
testing. Preview qualification follows the [CI request policy](ci-request-policy.md).

## Promote a locale

Review the launch worklist and the localized app with a fluent speaker. Try
onboarding, recovery, invites, message composition, warnings, plurals and
TalkBack. Check narrow screens and larger system text. For an RTL language,
check mirrored navigation and mixed-direction text such as links and public
keys. Android's [pseudolocales](https://developer.android.com/guide/topics/resources/pseudolocales)
are useful for layout checks before translated text is available.

Shipped locales currently require full key coverage. Move the reviewed locale
into `app/src/main/res/` and add its tag and native language name to
[the language picker](../app/src/main/java/dev/ipf/whitenoise/android/ui/settings/LanguageOption.kt)
in the same PR. Check the release resources and picker, System default, persistence
after restart, and the branded app-name overlays for debug and staging. Release
and store listing translations are separate from the app resource files.

## Browser translation pilot

A browser editor gives volunteers a way to contribute from the web. To pilot
[Weblate](https://docs.weblate.org/en/latest/formats/android.html), start with
one English resource file and map its matching locale XML files as a component.
Use a fork or dedicated translation branch and bring changes back through
reviewed PRs. Check XML comments, positional arguments, escaped text, plurals,
and non-translatable entries in a round-trip diff. Verify that the browser path
keeps new locales in the preview source set until promotion and that Git
contributors can still submit small PRs. Use the launch worklist to pick the
first resource group.
