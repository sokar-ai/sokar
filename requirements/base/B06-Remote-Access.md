# B06 — Remote Access

**Status:** open

Remote access is a tunnelling problem. The daemon keeps its private socket; the
client reaches it through an existing encrypted channel. No new listener, no
certificates, no tokens.

## What was measured

**The tunnel carries the socket directly.** This requirement's central question is answered, so
the posture is settled and nothing has to bridge. Against a daemon on another machine:

```
ssh -L /tmp/sokard-remote.sock:/run/user/1001/sokar/sokard.sock user@host -N
```

A client then opens the forwarded path instead of `$XDG_RUNTIME_DIR/sokar/sokard.sock` and
nothing else changes — same calls, same replies, same code. Streaming survives it: watching the
task list through the forward, a task stopped on the far machine was reported 0.4 s later. SSH
creates the local endpoint owner-only, so the forwarded socket is no more exposed than the
original.

The consequence, written down because it is a trade and not a free win: reaching a Sokar needs
a shell on its machine. There is no browser and no other client, and the frontend's
corresponding requirement dropped its browser criterion because of this.

## Acceptance

- A remote machine's tasks are usable with no configuration on that machine beyond
  existing remote access.
- The daemon binds no network interface in any configuration.
- Tunnel loss is shown as disconnection, never as an empty fleet.
- Reconnection recovers without restarting the client.

## Notes

**How long a clearance prompt should wait is this requirement's question now.** The watcher gives
an operator 60 seconds, which was never measured against anybody. What the silence costs is
smaller than it was - an expired question is recorded, replaced on screen by a notice saying the
destination stays blocked, and can still be answered afterwards through `Decide` - but the answer
has to reach somebody, and who can be reached is decided here rather than by the prompt.

Notifications are the part this constrains that is not yet settled
([F23](https://github.com/fuinorg/sokar-frontend/blob/main/requirements/F23-Notifications.md)):
a forwarded socket delivers events to a client that is connected, which is not the same as
reaching a person whose client is closed.

## To be checked

- **What a client should do when a stream is cut.** The daemon's streams now fail loudly rather
  than ending quietly, which is the honest half; reconnecting and resuming a watch is a decision
  nothing has had to make yet, and it is this requirement's to make because the transport decides
  what a reconnection costs.
- **Whether one client can carry a fleet watch and several log tails at once.** Each streams
  correctly on its own connection; nobody has run them together, and the 64 KB cap on a tail
  reply is a guess at the bulk problem rather than a measurement of it.

- **Whether an interface should manage the tunnel itself.** Spawning `ssh` and owning its
  lifetime is friendlier and puts key handling and process supervision inside the client;
  requiring a tunnel that is already up keeps it out of the credential business. Not this
  requirement's to decide alone, but it constrains what "reconnection recovers" above can mean.
- **Whether `ssh host sokar daemon connect` is the better shape than `-L`.** Piping stdin and
  stdout to the socket removes the local socket file and the forwarding lifetime entirely, at
  the cost of a subcommand that does not exist yet. Measured only in the `-L` form so far.
