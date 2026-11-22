# Message delivery

**Outcome:** You can trace a message from your device to its recipient and find the point where it stalled.

The status on your device tracks local send progress only. Only the recipient can confirm that the message was fetched and decrypted, and the same applies to whether they read it.

On Android, your own messages show **Sending…**, **Sent** or **Failed**, and a message that White Noise couldn't confirm was published shows **Delivery not confirmed**. **Received** appears on messages from other people: it means the message arrived and was decrypted on your device. It is not a receipt for the messages you send. **Message Details** shows a message's status, times and sender.

## Diagnose

1. Check the status on your device. A message stuck on **Sending…** usually points to a connection or relay problem, so work through [Connection](/docs/troubleshooting/connection) first.
2. A failed message offers **Discard failed message**, which removes it from the chat. Otherwise, ask the recipient whether it arrived, either in the conversation or through another trusted channel. Delivery slows down while a participant is offline or while the participants lack a relay they can all reach.
3. Before resending, find out whether the original arrived, since duplicates clutter a group and can make an agent repeat an action. Keep the exact text of the message along with its error label.
4. Confirm the recipient is still a member of the group. Once a removal takes effect, the removed member loses the ability to decrypt new messages.
5. For an agent, send a harmless roundtrip, such as asking it to reply with a fixed word. Check that the answer comes from the expected runtime and account, and that it matches the expected group and project. Give each environment a dedicated group of its own.
6. If an agent task may have run partway, check its external results before asking it to try again. Look at changed files and commits, along with any services the task touched.

## Expected result

The recipient confirms receipt, or you have identified the participant or relay that fails to connect.

## Escalation

Collect your version and platform. Add timestamps and error labels, then follow [Report a problem](/docs/troubleshooting/report-problem).

**Before sharing logs:** group diagnostic logs are opt-in and include group and message identifiers. On iOS and Android, turning on log sharing automatically uploads eligible logs to the Internet Privacy Foundation. On Android the switch is **Share group diagnostic logs**. It covers every profile on the device, and logs already stored may upload too. Ask other participants for consent before sharing logs or screenshots that reveal them.
