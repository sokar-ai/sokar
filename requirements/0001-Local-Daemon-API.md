# 0001 — Local Daemon API

**Status:** started. The socket, the protocol and the first call exist; the rest of the calls do
not. `sokard` was a 17-line skeleton before this.

Built and measured on Fedora 44: `sokard` binds
`$XDG_RUNTIME_DIR/sokar/sokard.sock` as `srwx------`, answers
`org.fuin.sokar.Tasks.List` with the real tasks on the machine, and answers
`org.varlink.service.GetInfo` so anything that speaks the protocol can introspect it without Sokar
writing the checker. **Another account on the same machine is refused by the kernel** - measured
with a second user, not argued: `PermissionError: [Errno 13] Permission denied` on `connect`.
Root can still connect, because root bypasses file modes; that is a property of the operating
system rather than something a check here could change.

**Varlink, because the agent contract already speaks it.** Sokar drives every agent over varlink,
so the client, the server and its streaming replies were already built and tested here. A second
protocol for the same kind of local IPC would have been two things to get right instead of one.

**One question, one implementation.** `TaskInventory` answers "what tasks are there" and both
`sokar task list` and the daemon's `List` call it. The acceptance criterion below asks for the CLI
and the interface to reach identical behaviour; two implementations of the same question are how
they come to disagree about what is running, in front of an operator deciding what to stop.

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

## Still to build

- `Stop` and `Resume`, which is what [0009](0009-Task-Lifecycle-Control.md) waits on. `List` is
  a pure read; both of those change the machine, and their logic still lives inside the picocli
  command classes rather than beside `TaskInventory` where both callers could reach it.
- The streaming calls - task state, clearance prompts, log tails. The server supports `more`
  replies already and the agent contract uses them, so this is work rather than a question.
- **Killing the daemon while a task runs is not yet demonstrated.** It is structurally true - the
  daemon starts nothing and owns nothing, tasks belong to the container runtime and their helpers
  to themselves - but the check that would prove it killed the wrong process, so it is claimed by
  argument and not by measurement.

## To be checked

- Does the chosen protocol stream well enough for a live log tail and a fleet of
  task states at once, or does it need a second channel for bulk output?
- What happens to a call that is in flight when the daemon restarts? A client that
  silently shows stale state is worse than one that shows an error.
