# Invitations

**Outcome:** Both people see the conversation and can exchange messages.

**Prerequisites:** White Noise installed for both people. You also need the other person's public key or profile link; their QR code works too.

Your public key is safe to share. Keep your private key secret, including in conversations about troubleshooting.

## Diagnose

1. Confirm the identity. Compare the `npub` you entered with the one shown in the other person's profile, using a channel you trust. A single changed character points to a different account.
2. Ask the recipient to open White Noise and stay connected for a few minutes. An account has to publish the key material others need before anyone can invite it.
3. Check relays. Everyone in a conversation needs a shared way to exchange messages. If either of you uses a custom relay list, make sure you can both reach at least one relay in common.
4. Check both people’s invitation lists before starting another conversation. Use a retry action if White Noise offers one. On Android, an invitation you received shows **Accept** and **Decline**, or **Invitation pending**. If it says **Couldn't check invitation**, tap **Retry**. Starting again can create a duplicate conversation.
5. For an agent, add the agent's public key in New Message. Send a short test message from the account you authorized during setup. If the agent stays silent, confirm the connector is running and that your account is on its allowed senders list.
6. In groups of 150 or more members, Android warns that additional invitations might not be delivered reliably. Check that the new member sees the group before relying on it.
7. If you were removed from a group, ask an admin to add you again. Messages sent while you were out stay out of your reach.

## Expected result

The conversation appears for both people and a test message arrives.

## Escalation

Record the app version and platform. Add the time of the invitation and any error label. Redact contacts' keys and names, then follow [Report a problem](/docs/troubleshooting/report-problem).
