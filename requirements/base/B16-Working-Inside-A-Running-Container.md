# B16 — Working Inside A Running Container

**Status:** open. What carries a session is decided; what a session IS between attachments is not

A person can start work that is meant to be driven by hand — `Start` takes `mode: SHELL`, and the
interface offers it — and then cannot get inside it. **Interactive work can be created and not
entered**, which is the more awkward half of the two.

Today the only way in is `ssh` to the machine and a container command there. That works, and it
means the interface's answer to *"let me try something in this container"* is *"leave the
interface"*. An interface people have to leave to do the interactive part of the job is one they
stop coming back to.

## What is missing

Nothing attaches to a running task. `Start` has only the path that runs and returns; there is no
method that opens a shell in a container that already exists, and nothing that carries what a
person types into it.

The gap shows up in three places at once:

- **F12 Interactive Session Attach** is entirely this, and is not started because nothing behind
  it exists.
- **F21 Continuity And Updates** asks that *"where the environment allows work **and sessions** to
  outlive the window, they do"*. Work does. A session cannot outlive something that cannot be
  attached to in the first place, so half of that criterion waits here.
- **`Start(mode: SHELL)` and `mode: AGENT`** are both offered by the interface and neither can be
  returned to. A mode that says *"a person is driving this"* is a promise about a person who has
  no way in.

## Acceptance

- **A shell inside a running container is reachable from the interface**, over the same socket
  everything else uses, without a terminal on the machine and without the person opening an ssh
  session of their own.
- **What is typed reaches it and what it prints comes back, while it runs.** Not a command that is
  submitted and answered: a session, with a prompt at the far end.
- **Leaving does not end it.** The work carries on exactly as it did, and the same session can be
  entered again. Detaching is not stopping, and nothing about leaving is destructive.
- **Coming back says what is true now**, and is explicit about what it can and cannot show of what
  happened while nobody was attached. Silently showing a stale screen, or replaying an hour from
  the beginning as though it were live, are both worse than saying which it is.
- **More than one session at once**, against different tasks, each identifiable by the task it
  belongs to.
- **A session that cannot be opened answers with a named outcome rather than an exception**, in
  the way `SetEgress` and `WidenTask` already refuse — a task that is not running, a container
  that has no shell, a class that forbids it. An interface can then say which, rather than
  reporting that something broke.
- **Nothing about being inside weakens what the container is held to.** The same egress ruleset,
  the same clearance watcher, the same gate. A shell is a person working under the rules, not a
  way around them.
- **Closing the interface does not end a session**, on the same terms as work: what a person left
  running is still running when they come back.

## Notes

Asked for by the interface. The frontend already offers `SHELL` and `AGENT` when work is started,
because [B11](B11-What-A-Task-Says-About-Itself.md) settled the modes and `Start` takes them — so
the product already tells people that driving work by hand is a thing it does.

**The return path is the requirement, not the attaching.** An interface that cannot be got back to
reliably is one people stop leaving; that is why *leaving does not end it* and *coming back says
what is true* are criteria of their own rather than details of the first one.

**This is not asking for a terminal emulator to be part of the contract.** What the interface needs
is a way to carry bytes in both directions to a process in the container, and a way to be told what
it may claim about what it is showing. How that is drawn is this end's problem.

## Decided, 2026-09-08

### varlink cannot carry it, and that is settled

Measured against the implementation and true of the protocol: **one call in, many replies out.**
`more` streams replies *from* the service; there is no message a client can send that a service
would attach to a call already in flight. `send()` writes once and everything after it is
`receive()`. So attaching is not a method in the sense the other methods are, and no amount of
naming makes it one.

Sending a keystroke per call was considered and rejected: each call is independent, so the protocol
guarantees no ordering between two of them, a session id would have to be invented to tie them
together, and every keypress costs a round trip.

### The session is carried by ssh, and the command it runs is Sokar's

**The client opens a second ssh channel with a pty and runs `sokar task attach <task>` on the
node.** Not `podman exec`, and not a daemon method.

This is what a person already does by hand, and it is the reason it works: **ssh is the byte pipe
varlink is not.** A pty over ssh is a correct terminal without anything being rebuilt - window
size, `SIGWINCH`, signals, escape sequences and `TERM` all arrive right. Piping the same bytes
through the daemon would mean reimplementing pty allocation and resize, and getting that subtly
wrong is exactly the failure this requirement is about: colours work, and then `Ctrl-C` ends the
wrong thing.

**Running Sokar's own verb rather than the runtime's is what keeps the rest.** The client does not
learn which container runtime is underneath, which [B08](B08-McSokar-Apple-Containers.md) needs;
Sokar can refuse by class before it execs anything; and it can record that somebody was inside. A
client that ran `podman exec` itself would have none of those and would hard-code the runtime.

**No privilege is added by this.** Whoever forwards the daemon socket over ssh already has an
account on the node and can already run anything the operator can - so a second channel grants
nothing new. That holds because the operator's key is a normal one; a key restricted with
`restrict,permitopen=` could forward and not execute, and this design deliberately does not
support that shape. It is written down here rather than discovered by somebody hardening a key and
losing the terminal.

**Containment is untouched either way.** The egress ruleset, the clearance watcher and the gate are
in the kernel and in the OCI hooks. They hold for every process in the container however it got
there, so a shell is a person working under the rules and not a way around them.

## What is still open, and it is the hard half

**Leaving does not end it - and ssh does not give that.** `sokar task attach` over a channel that
closes takes the process with it. Persisting a session across detachments needs something that
lives between them, and that decision is untouched by the transport:

- a multiplexer inside the container, which means installing one into every image;
- a process the supervisor owns beside the other helpers, which is the shape everything else here
  uses;
- or attaching to the container's own primary process, which is one session and not several.

**What re-entering may claim** follows from that answer and not from this one. If nothing keeps a
scrollback, coming back shows a prompt and nothing behind it and must say so.

**Whether a class may forbid it.** An `offline` project's tasks reach nothing; whether a person may
still open a shell in one is a policy question, and the answer belongs in `task attach` where it
can be refused before anything is exec'd.

**What it costs to leave one open.** Six attachments and a closed window leaves six of whatever the
session turns out to be. Worth bounding before it is built.
