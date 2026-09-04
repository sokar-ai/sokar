# 0017 — Remote Access

**Status:** open

Remote access is a tunnelling problem. The daemon keeps its private socket; the
client reaches it through an existing encrypted channel. No new listener, no
certificates, no tokens.

## Acceptance

- A remote machine's tasks are usable with no configuration on that machine beyond
  existing remote access.
- The daemon binds no network interface in any configuration.
- Tunnel loss is shown as disconnection, never as an empty fleet.
- Reconnection recovers without restarting the client.

## Notes

Whether the tunnel can carry the daemon's socket directly decides the whole
transport posture, including whether [0010](0010-Notifications.md) is achievable
remotely. Establish that before building on it.

## To be checked

- **Can the tunnel carry the daemon's socket directly?** If the client can forward a
  local endpoint straight to a remote socket, the daemon binds nothing and the
  posture is settled. If it cannot, something on the far side has to bridge, and that
  brings back every question about listeners and origins this requirement was written
  to avoid.
- This single answer also decides [0010](0010-Notifications.md) and constrains
  [0018](0018-Mobile-Client.md). Answer it first.
