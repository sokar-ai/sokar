# B166 — Line Comments Sent Back To The Agent

**Status:** open.

**What must be true.** A person reviewing waiting work can comment on lines of its diff, in `sokar gate review` and in
the interface; a reject carries those comments into the task, through the message path, for the agent to work on.

## Why

`gate reject` gives one reason today, and a person who wants three things changed writes them out of context. Comments
on lines close the loop from review to rework without going around the gate. Comparable tools do it.

## The shape

- Comments kept with the waiting push on the host, by file and line of the diff.
- A reject delivers them to the task as one message, each with its file, its line and the lines around it; the agent is
  woken as for any message (B120).
- The parts outside the CLI: the interface's review view (`sokar-frontend`) and the IDE plugin's (`sokar-intellij`),
  each through the daemon's same method.
- Size: small to medium.

## Acceptance

- Seen to fail first, then green: two comments on a waiting push and a reject arrive in the task as one message naming
  both lines.
- `doc/commands.md` says it.

## To be checked

- Whether comments survive a new push of the same task, matched to its lines.
