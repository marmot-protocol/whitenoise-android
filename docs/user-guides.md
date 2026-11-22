# Maintaining Android user guides

Android owns the Markdown under `app/src/main/assets/user-guides`. Update the
affected guide in the same PR when a user-facing workflow or instruction changes.
List the changed guides under **Documentation** in the PR description next to
the **Visual changes** evidence. If the instructions still apply, write
**No docs impact**. Behavior changes can affect docs without changing pixels.

Each guide starts with a level-one title. Use standard Markdown headings,
lists and links. Keep stable article IDs and preserve manual steps for any
native shortcut. Screenshots belong in PR review evidence. Run
`python3 scripts/check_user_guides.py` before submitting.

The candidate APK bundles these sources directly. Before distributing it,
check the affected guides against the candidate and open an article from an
offline search. Keep the Android release tag and source SHA with the results.

The White Noise website should import the guides from the exact commit behind
the published stable Android release. Verify the tag and release state,
preserve public article URLs, and keep the previous snapshot if import fails.
An Android draft release or a change on master alone does not advance the
website documentation.
