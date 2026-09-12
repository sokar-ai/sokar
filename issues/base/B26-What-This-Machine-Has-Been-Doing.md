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
users on one host have two journals. Confirmed with the interface, whose machine list is keyed the
same way, so the two map one to one. **If this ever becomes host-wide it must be said loudly** - it
would be the single place where the two sides' meanings of "machine" diverge.

**The interface will want it, but the interface is not the reason.** An operator asking "what
happened here last night" has nowhere to look today, and that is true with no interface at all.

## Decided 2026-09-09

- **Retention: both caps, whichever bites first.** A size cap alone lets one loud day push out a
  month of history on a busy machine; an age cap alone bounds nothing on one. **The journal records
  its own trimming**, because history that shortens silently is history nobody can trust — "there
  is nothing from before the 3rd" and "nothing happened before the 3rd" must not look alike.
- **Reading: `Events(after: ?cursor)`, every event carrying its own cursor.** One method for
  three jobs: no cursor is history, the last cursor is the resume, and `more` follows. The
  interface argued it better than this file first did — **a machine reached over ssh is a forward
  that drops**, and every drop is a hole in a pure stream that comes back looking calm. The
  failure a journal must not have is the one where nothing happened for ten minutes and nothing
  says whether that is true.
- **An event names the task, never a log file.** The journal outlives the state directory, so a
  stored path is a promise it cannot keep. A client wanting detail asks `Logs(task)`, which already
  answers what still exists - so the journey from spine to detail arrives at "this task has no logs
  any more" rather than at a dead path. Two conditions on that field, both from the interface's own
  scars: **it is absent for an event that belongs to the machine rather than a task**, and absence
  must not render as an unknown task; and where a kind is cheap - gate, broker, clearance - it is
  carried beside the name, so the spine can say which log without naming a file.
- **`sokar task logs` is built now, separately.** It needs nothing from this requirement.

## To be checked

- **Concurrent appends.** The daemon, the CLI and several helpers run as the same user and would
  write to one file. On Linux an `O_APPEND` write below `PIPE_BUF` is atomic, which would make one
  line per event safe without a lock — **this must be measured, not assumed**, and the line length
  bounded on purpose if it is what the design rests on.
- **Whether an event can be tied to its task without widening what an event holds.** A security
  review on 2026-09-10 asked for correlation and proposed carrying destination address, port and
  process to get it. The first half is this requirement's gap; the second is refused here - the
  journal carries names and outcomes, and an address in a durable record is the thing a task's own
  logs are on tmpfs to avoid. What is open is whether a task id alone is enough to answer *what has
  this machine been doing* without the rest coming with it.
- **What the two caps actually are.** Decided that there are two; the numbers are not chosen, and
  they should come from what a busy machine really writes rather than from a round figure.
