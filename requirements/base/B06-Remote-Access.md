# B06 — Remote Access

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
transport posture, including whether notifications are achievable remotely
([F23](../frontend/F23-Notifications.md)). Establish that before building on it.

## To be checked

- **What a client should do when a stream is cut.** The daemon's streams now fail loudly rather
  than ending quietly, which is the honest half; reconnecting and resuming a watch is a decision
  nothing has had to make yet, and it is this requirement's to make because the transport decides
  what a reconnection costs.
- **Whether one client can carry a fleet watch and several log tails at once.** Each streams
  correctly on its own connection; nobody has run them together, and the 64 KB cap on a tail
  reply is a guess at the bulk problem rather than a measurement of it.

- **Can the tunnel carry the daemon's socket directly?** If the client can forward a
  local endpoint straight to a remote socket, the daemon binds nothing and the
  posture is settled. If it cannot, something on the far side has to bridge, and that
  brings back every question about listeners and origins this requirement was written
  to avoid.
- This single answer also decides [F23](../frontend/F23-Notifications.md) and constrains
  [F20](../frontend/F20-Access-From-Elsewhere.md). Answer it first.
