# Mark read icon

Mark read uses the Material Symbols Outlined `drafts` open envelope, so it no
longer shares the Select checkmark (#3566). Mark unread keeps the closed
envelope with its unread dot (`ic_mark_unread`). The pair is used by the chat
row action sheet, the selection bar, chat swipe cues and the notification
Mark read action.

- [Upstream SVG](https://github.com/google/material-design-icons/blob/6d7ca43bd6e6668531a00fcaca06d921b63dd716/symbols/web/drafts/materialsymbolsoutlined/drafts_24px.svg)
- Local resource: `app/src/main/res/drawable/ic_mark_read.xml`
- Google Material Symbols, [Apache License 2.0](material-symbols-LICENSE.txt)

The SVG path is unchanged. Its Android vector wrapper shifts the upstream
`0 -960` view box into the 960-unit viewport and uses the existing icon size
and theme tint, with no new runtime dependency.
