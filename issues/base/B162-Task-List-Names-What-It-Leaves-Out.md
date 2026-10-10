# B162 — Task List Names What It Leaves Out

**Status:** in progress: `task list` names them; the daemon's list and `task status` do not yet.

**What must be true.** `sokar task list` never says "No tasks." while containers named like tasks are there: it names
the ones it does not list, and why.

## Why

A task is listed only when its container's live id is the one Sokar recorded when it made it (`container.id` in the
task's state); a container named like a task with no such id is not one, and nothing of Sokar's acts on it. That rule
came on 2026-10-04, and nothing records an id for a task made before it. Such a task, stopped, with its `task.json`
and `resume.json` still there, was left out without a word: the list said "No tasks.", which read as "nothing to lose",
and a container prune removed the work in five of them unseen.

## Built so far

- `task list` names every container named like a task that it does not list, says that Sokar holds no container id for
  it and that removing it removes what is in it; with nothing listed it says "No tasks Sokar acts on." instead of
  "No tasks." (`TaskLifecycleCommandsTest`, seen red first).

## Not yet

- The daemon's list for the interface says the same, as data.
- `task status <name>` on such a container says why it is not a task, instead of "there is no task called".

## Decided

- **No adopting** (2026-10-10): a container without the id Sokar recorded when it made it is never taken on as a task,
  not even once by a command a person runs; the 2026-10-04 rule stays closed. `task list`, `task status` and the
  daemon's list say what such a container is and that removing it removes what is in it. Saving its workspace is the
  person's own `podman cp`.
