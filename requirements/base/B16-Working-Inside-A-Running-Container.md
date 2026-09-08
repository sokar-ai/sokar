# B16 — Working Inside A Running Container

**Status:** open

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

## To be checked

- **Whether the protocol can carry it at all.** varlink's `more` streams replies from the service;
  there is no matching way for a client to keep sending into a call that is already open. So this
  may not be one method — it may be a second channel the daemon hands out, a socket path of its
  own, or something else entirely. **Settle this before anything else**, because it decides
  whether the interface draws a terminal or hands somebody a line to paste.
- **What a session is, when nobody is attached.** A process that keeps running and can be
  re-entered needs somewhere to live between attachments. Whether that is a multiplexer inside the
  container, a process the supervisor owns, or the container's own primary process is a decision
  with different costs for what *coming back* can honestly show.
- **What re-entering may claim.** If nothing keeps a scrollback, coming back shows a prompt and
  nothing behind it, and the interface has to say so. If something does keep one, how much, and
  what happens when it is longer than that. Either answer is workable; not knowing which is not.
- **Whether a class may forbid it.** An `offline` project's tasks reach nothing; whether a person
  may still open a shell inside one is a policy question, and if the answer is no it wants a named
  outcome rather than a surprise.
- **What it costs to leave one open.** A person who attaches to six tasks and closes the window
  leaves six of whatever this turns out to be. Whether that is bounded, and by what, is worth
  deciding before it is built rather than after somebody finds out.
