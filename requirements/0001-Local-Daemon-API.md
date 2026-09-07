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

- ~~`Stop` and `Resume`~~ - built, and they finished task lifecycle control, which is retired.
  Their logic moved out of the picocli command classes into `TaskControl`, which both the CLI and
  the daemon call; the commands now render what it returns and decide nothing. Measured over the
  socket against a real container, refusals included.
- ~~Clearance prompts as a call.~~ - built, and the round trip is measured.
- The rest of the surface: starting a task, the gate's review commands, the vault, the agent
  inventory. Each is a call the interface will need and none of them exists yet.
- ~~Killing the daemon while a task runs is not demonstrated.~~ - demonstrated: `SIGKILL` to
  `sokard` left the task up with all four of its helpers, still listed by the CLI.

## Streaming, built 2026-09-07

`Watch` streams the fleet's state and `Tail` streams one of a task's logs, both as varlink `more`
replies on the connection the call arrived on. Asked *without* `more`, each answers once instead
of refusing - one method serves a client that can stream and one that cannot, and the data is not
put behind a capability.

Measured against a real task on Fedora 44, which is where the flaw was: comparing whole answers
made `Watch` fire every second, because the runtime's state is a phrase carrying an age and
`Up 3 seconds` becomes `Up 4 seconds` on its own. A fleet view would have redrawn continuously
because a clock moved. The comparison now ignores the age and keeps the exit code - a task that
was stopped and one that died are different things - and the same task then produced one reply on
connect, nothing through eight seconds of ticking, and exactly one more when it actually stopped.
No unit test would have found this: a fake answers whatever it is told, and it was told a
constant.

`Tail` reads by position rather than watching the file, because a log is appended to by another
process entirely. A file that shrank was rotated and is read from the start, a partial last line
is left for the next read, and one reply carries at most 64 KB so a large log cannot become one
enormous message. A log name is checked against the files that are there rather than resolved as
a path: `../../etc/passwd` is a log name until something refuses it.

Both end when the client goes away, which is the only disconnect signal there is - sending to a
closed connection throws, and nothing else tells a server that a reader left.

## Clearance prompts, built 2026-09-07

Every running task already serves its own prompts on its own socket - the watcher's
`org.fuin.sokar.Clearance1`. `Prompts` is the one socket an interface talks to instead of
discovering and connecting to each of them: it subscribes to every running task, tags each event
with the task it came from, and picks up tasks that start after the client connected. `Decide`
carries an answer back to the watcher that asked.

The event carries the deduplication key, not just the destination. A client answering has to name
the same thing the hub deduplicates on, and deriving that key from three separate fields at the
far end is one rule in two places, waiting to drift.

**Subscribing had been delivering nothing, and only a live task showed it.** The watcher has two
ways in: when it starts its own reader, events arrive as `Report` calls and are broadcast on the
way past; when the reader hook is already running inside the container - which is the normal case
- it *follows the file* that hook appends to and decided from there, reaching the hub without ever
reaching a subscriber. So the service whose whole point is that these events are interesting to
more than one thing had one consumer. `ClearanceService.publish` now feeds subscribers from the
follow path too.

Measured on Fedora 44, against a real task with a real blocked connection:

```
prompt streamed → Decide {allow:true} → {"ok": true}
watcher log:  decided deny 9.9.9.9:443 │ allowed 9.9.9.9
container:    the connection then succeeded
```

That is the whole loop: a dropped packet became a question, the answer crossed two processes, the
live nftables set changed, and the task got through. The acceptance suite still passes, so the
watcher's own behaviour is unchanged for a task nobody is watching.

## To be checked

- Does the chosen protocol stream well enough for a live log tail and a fleet of
  task states at once, or does it need a second channel for bulk output? **Half
  answered**: each streams correctly on its own connection, and the 64 KB cap on a tail
  reply is a guess at the bulk problem rather than a measurement of it. Nobody has yet
  run a fleet watch and several tails through one client at once.
- What happens to a call that is in flight when the daemon restarts? A client that
  silently shows stale state is worse than one that shows an error.
