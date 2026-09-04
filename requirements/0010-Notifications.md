# 0010 — Notifications

**Status:** open

Unattended means unattended. A decision waiting inside a window nobody has open is
the same as no decision at all.

## Acceptance

- A waiting decision raises a notification within seconds.
- Finishing raises one, distinguishing success from failure.
- Acting on the notification opens the task it came from.
- Notifications can be turned off per project.

## Notes

Deliverable on the desktop. On a remote client this depends entirely on the transport
in [0017](0017-Remote-Access.md), which may make it impossible; decide that
deliberately rather than discovering it.

## To be checked

- **This may be unachievable remotely.** Delivery to a client that is closed needs a
  channel that survives the client being closed, and a tunnel-only transport has
  none. If [0017](0017-Remote-Access.md) confirms that, this requirement should be
  cut to local-only rather than left to fail quietly on a phone.
