# F23 — Notifications

**Status:** open

Unattended means unattended. A decision waiting inside a window nobody has open is the same as no
decision at all.

Moved here from the central index, where it was 0010.

## Acceptance

- A waiting decision raises a notification within seconds.
- Finishing raises one, distinguishing success from failure.
- Acting on the notification opens the work it came from.
- Notifications can be turned off per project.

## Notes

Deliverable on the desktop. The daemon's `Prompts` call carries a waiting decision to whatever is
listening, so what is missing is the raising, not the knowing.

## To be checked

- **This may be unachievable from elsewhere.** Delivery to a client that is closed needs a channel
  that survives the client being closed, and a tunnel-only transport has none. If
  [F20](F20-Access-From-Elsewhere.md) confirms that, this should be cut to local-only rather than
  left to fail quietly on a phone.
