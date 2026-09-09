# B26 — What This Machine Has Been Doing

**Status:** open. Asked for on 2026-09-09, after establishing that every host-side log Sokar keeps
belongs to one task, lives on tmpfs, and is gone when that task or that session is.

## What exists, so that this is not built over something already there

Each task's state directory holds seven files, and they **are** host-side — the gate, the broker,
the relay, the resolver and the clearance watcher all run on the node, not in the container:

| file | written by |
|---|---|
| `gate.log` | the git gate serving that task |
| `vault.log` | the credential broker |
| `relay.log` | the port relay |
| `dnsmasq.log` | the resolver |
| `clearance.log` | clearance decisions |
| `workspace.log` | the clone |
| `events.jsonl` | blocked connections, from the NFLOG reader |

`Logs(task)` and `Tail(task, log)` put those on the wire already, and `Logs` deliberately asks
rather than assumes, because which files exist depends on what the task started.

**Three things are missing, and only the third is the reason for this file.**

- **The CLI cannot read any of them.** `sokar task` has nine subcommands and none opens a log. The
  contract is ahead of the terminal here, which is backwards for a tool whose first audience is at
  the machine.
- **Nothing is machine-wide.** Only `failures.log` and the clearance records live outside a task,
  and the first records crashes.
- **None of it survives.** A task's state directory is under `$XDG_RUNTIME_DIR`, cleared when the
  user's last session ends. So a machine's history is deleted by a reboot — measured twice on
  2026-09-09, once as tasks listing no project and once as a task that could never be resumed.

## Why aggregating the seven files is the wrong answer

Seven files, seven formats: dnsmasq writes its own, each helper writes its own. Merging them by
time means parsing all seven, and **a parser that silently mis-orders entries is worse than no
view** — it produces a plausible story of what happened, which is the failure this project keeps
writing down. It would also be a read per file per task on every refresh, which is the listing
cost `since` and `behindMeasured` both exist to avoid.

The per-task logs stay. They are the detail. What is missing is the spine.

## What must be true

**A machine can say what it has done — which tasks ran, what reached the gate, what was refused
and what failed — for longer than the tasks themselves existed, and without reading anything a
container wrote.**

## Acceptance

- Events are recorded for at least: a task started, stopped, or removed; an image built; hook
  registration repaired; a push arriving at the gate, approved or rejected; a clearance decision;
  a credential brokered for a task; a command failing unexpectedly.
- **The record survives a reboot and the removal of the task it refers to.** That is the property;
  a journal in the runtime directory would be this requirement's own failure.
- Each event says when, what happened, and which task or project it concerns — enough to be read
  in order without joining it against anything else.
- **No argument, credential, prompt, or agent output is ever written to it.** Names and outcomes
  only. The same rule `failures.log` follows, for the same reason: a log is where a credential
  must not end up.
- It is readable at the terminal and over the socket, and both show the same events in the same
  order.
- Following it live never requires polling a file from a client.
- It cannot grow without bound on a machine nobody administers.
- Writing an event never fails a command. A journal that cannot be written loses an entry; it does
  not stop a task.

## Notes

**This is a new subsystem, not a view.** Every moment worth recording needs a call added at the
place it happens, and each one is a decision about what is worth saying. That is the cost, and it
is the reason to argue the event list before writing any of it.

**A node is an OS user with a `sokard`,** so this is per user rather than per machine, and two
users on one host have two journals. That is correct and should be said on screen, or somebody
will read one and believe it covers the box.

**The interface will want it, but the interface is not the reason.** An operator asking "what
happened here last night" has nowhere to look today, and that is true with no interface at all.

## To be checked

- **Concurrent appends.** The daemon, the CLI and several helpers run as the same user and would
  write to one file. On Linux an `O_APPEND` write below `PIPE_BUF` is atomic, which would make one
  line per event safe without a lock — **this must be measured, not assumed**, and the line length
  bounded on purpose if it is what the design rests on.
- **What retention means when nobody administers the machine.** Size cap, age cap, or rotation;
  and whether losing the oldest events silently is acceptable, given the whole point is history.
- **Whether `Events` streams or is polled.** `Tail` already streams, so the shape exists — but a
  journal that a client follows from a cursor is a different contract from one that tails a file,
  and the difference shows up the first time two clients read it at once.
- **Whether the per-task logs should be reachable from an event.** Naming the file an event came
  from would let somebody go from the spine to the detail, and would also couple the journal to a
  directory that no longer exists after a reboot.
- **Whether `sokar logs <task>` should exist regardless.** It closes the CLI-versus-contract gap
  with methods that already exist, is small, and is not this requirement — but it will be asked for
  by the same person on the same day.
