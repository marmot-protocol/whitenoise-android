# Appearance sample coverage

The Appearance sample renders synthetic content with the same `FontAppearanceSample`
used by the font-family and font-size dialogs. It contains no account or chat content.
It follows the current app theme, font family and effective OS × app font size.
The RTL and large-text switches affect only this sample. Reset, leaving the screen,
or replacing the account/runtime discards those local switches. The large-text simulation
doubles the inherited font-scale setting; Android nonlinear scaling means it does
not promise exactly twice the rendered glyph size.

| Setting or concern | Sample coverage | Required separate qualification |
| --- | --- | --- |
| Font family | Current app typography and selectable dialog sample | Actual long labels, missing glyphs and emoji in production routes |
| Font size | Current OS × app scale; labeled local large-text simulation | Real system size changes, rotation and constrained windows |
| Bubble colors | Production preview token resolver in Light/Dark/AMOLED | Received/sent/selected/deleted/attachment states in actual conversations |
| Action colors | Current theme/action controls; existing production color picker preview | Focus, pressed, disabled and contrast states on real actions |
| RTL | Local direction simulation, not a locale/preference change | Real RTL localization, bidi messages, icons and gesture direction |
| Motion | Reports the live system animation preference | Actual transitions, interruptions and reduced-motion behavior |
| TalkBack / Switch Access | No certification from a visual sample | Actual screen semantics, focus order, labels and navigation |
| Touch targets / system contrast | Not certified by scaling synthetic content | Measured production targets and platform accessibility modes |

Local source and authored tests are not rendered or device evidence. Record screenshot
and hosted results against the exact candidate before a future PR is called ready.
