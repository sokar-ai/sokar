# B06 — Remote Access

**Status:** open

**Where the other half lives.** The client's decisions are the interface's, and were taken on 2026-09-07 - recorded in sokar-frontend's `doc/decisions.md`: a unix socket forward, the interface managing `ssh` itself, and a cut stream shown as a disconnection rather than an empty machine. Nothing here blocks them, and the reconnection criteria below can point at those decisions instead of waiting on them.

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

## Measured, 2026-09-07

**The second shape exists now.** `sokar daemon connect` puts this process's standard input and
output onto the daemon's socket and interprets nothing in between, so
`ssh host sokar daemon connect` speaks varlink straight down the ssh session: no socket file on
the client, nothing to tear down, and the connection lives exactly as long as the ssh command.
Measured against a running daemon by piping one call in and reading the reply out; the ssh leg
could not be measured on this machine, which runs no sshd, so what is proven is the bridge, not
the hop.

**Bytes, not lines.** Varlink frames are NUL-separated JSON and a stream is answered over time, so
the bridge copies raw bytes and flushes on every read. A line-buffered one would hold a reply until
the next arrived, which for a fleet watch means holding it until something changes - exactly the
case an interface has to show promptly.

**One client can carry several streams, and each costs a connection.** A fleet watch and two log
tails were run at once against one daemon, each through its own bridge, while both logs were
appended to: all three delivered, none blocked another. That is the answer to the question, and it
is also the difference between the two shapes - `ssh -L` forwards one socket and a client opens as
many connections through it as it likes, while `connect` is one connection per invocation, so
three streams are three ssh sessions. Neither is wrong; the second trades sessions for having no
socket file and no lifetime to manage.

**The 64 KB chunk is not a limit, it is a rate.** A 1.5 MB log tailed as 24 replies, largest
68 KB, all 30,000 lines delivered and none lost. But the tail loop sleeps 200 ms between chunks,
so a backlog drains at roughly 320 KB/s however fast the transport is: opening a large log is
slow by construction rather than by bandwidth. That is a decision to revisit deliberately - the
sleep is there so a live tail does not spin - not a bug to fix in passing.

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

Notifications are the part this constrains that is not yet settled - F23 in the
[interface's own set](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md),
whose file is gone because a finished requirement there is deleted rather than kept:
a forwarded socket delivers events to a client that is connected, which is not the same as
reaching a person whose client is closed.

## To be checked

- **What a client should do when a stream is cut.** The daemon's streams now fail loudly rather
  than ending quietly, which is the honest half; reconnecting and resuming a watch is a decision
  nothing has had to make yet, and it is this requirement's to make because the transport decides
  what a reconnection costs.
- **Whether an interface should manage the tunnel itself.** Spawning `ssh` and owning its
  lifetime is friendlier and puts key handling and process supervision inside the client;
  requiring a tunnel that is already up keeps it out of the credential business. Not this
  requirement's to decide alone, but it constrains what "reconnection recovers" above can mean.
- **Whether a forward is verified before a flow depends on it.** `doc/authentication.md` already
  records that a port collision lies - ssh binds `[::1]` when `127.0.0.1` is taken and says nothing
  - and a login then completes against whatever is listening. Nothing checks. Raised by a security
  review on 2026-09-10; the cheaper answer it also names is a device-code flow, which removes the
  forward rather than checking it, and is only available where the provider offers one.
- **Which of the two shapes an interface should use.** Both exist and both work; the trade is
  measured above, and choosing is the interface's decision rather than this one's. What is not
  measured is either shape over a real ssh hop from a second machine - this machine runs no sshd -
  so the reconnection behavior below is still untested against a link that actually drops.
