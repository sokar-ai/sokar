# B164 — Rollback Points Inside A Task

**Status:** open.

**What must be true.** After each step of the agent, the task's workspace is kept as a state on the host; going back to
one restores its files and, where the agent can name its session, its conversation from that point.

## Why

An unattended run that took a wrong turn early is today either thrown away whole or mended by hand. The gate sees only
what the agent pushes, not the steps in between. Comparable tools let a person go back a step, files and conversation
together, and that is much asked for.

## The shape

- A state per step: a commit on a ref of the host's own in the task's workspace, never pushed and never seen by the gate
  as work; what makes a step is the agent's turn ending, as the activity reading of B120 already knows it.
- `sokar task rewind TASK [--to <n>]` lists the states, and restores one after a question with what is lost (B159,
  destructive, default no).
- The conversation goes back where the agent names a session to resume from (B46); otherwise only the files, and it
  says so.
- Size: medium; the resumption of a conversation is built (B46).

## Acceptance

- Seen to fail first, then green: three steps of a stub agent make three states; rewinding to the first restores its
  files exactly and leaves the later states listed.
- `doc/commands.md` says it, and what is not restored.

## To be checked

- How many states are kept, and what they cost on disk for a large workspace.
