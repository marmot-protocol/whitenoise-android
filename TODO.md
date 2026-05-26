# TODO

- [x] The message input field at the bottom of the chat screen needs to not have a background and appear to float at the bottom. we should be able to see the chat content scroll behind it.
- [x] The emoji/reply panel should not be opened from the three dots icon - it should be opened by long-pressing on the actual message bubble itself.
- [x] The New Chat button at the bottom right of the screen should just be a circle + button
- [x] The title of the group chat should be the name of the group, or without that, the name of the other user
- [x] we should remove the + from the top right of the chat list screen
- [x] the new chat bottom sheet should show an icon to open the QR code scanner.
- [x] we aren't fetching and displaying user's display names or avatars, we need to everywhere we show user data.
- [ ] Replace Android's temporary profile presentation memo with a Marmot-side batch profile summary or warm directory-cache API so chat lists can resolve names/avatars in one fast binding call.
- [ ] Improve group management around the newer bindings: make add/remove member flows explicit, expose admin promote/demote/self-demote status clearly, show MLS/admin state without requiring a manual diagnostics button, and refresh members/state after each mutation.
