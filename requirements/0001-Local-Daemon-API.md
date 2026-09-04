# 0001 — Local Daemon API

**Status:** open

The client never runs `sokar` as a subprocess and never parses its output. `sokard`
exposes the domain over a unix socket with owner-only permissions, never a network
interface. The socket is the only entry point, so remote access is a tunnelling
problem rather than an authentication problem.

## Acceptance

- The socket is `0600` and refuses a second user on the same machine.
- Every operation the interface offers exists as a call, including the streaming
  ones: task state, clearance prompts, log tails.
- Killing the client leaves running tasks untouched; the daemon is not their parent.
- The CLI and the client reach identical behaviour through the same calls, so a
  feature cannot exist in one and not the other.

## Notes

This is a prerequisite for every other requirement here. Streaming matters as much
as the calls: a client that polls will lag a prompt that expires.

## To be checked

- Does the chosen protocol stream well enough for a live log tail and a fleet of
  task states at once, or does it need a second channel for bulk output?
- What happens to a call that is in flight when the daemon restarts? A client that
  silently shows stale state is worse than one that shows an error.
