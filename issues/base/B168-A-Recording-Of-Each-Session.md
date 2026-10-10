# B168 — A Recording Of Each Session

**Status:** open.

**What must be true.** What a task's terminal showed is kept on the host as a recording, can be played back, and is
linked from the task's journal.

## Why

After an unattended run, the journal says what was reached and refused, but not what the agent did and said on its
screen. A recording makes that traceable, and it fits "nothing leaves unseen". None of the comparable tools surveyed
keeps one.

## The shape

- The output of the task's tmux session, with its timing, written on the host outside the task's reach; the task
  cannot change or delete it.
- `sokar task replay TASK` plays it back in a terminal; it stays after the task is removed until the person removes it.
- The task's journal entry (B154) names the recording and its checksum.
- Size: small; tmux already carries the output.

## Acceptance

- Seen to fail first, then green: what a stub agent printed plays back in order after the task is removed.
- `doc/commands.md` says it, and what it may hold (anything the agent printed, secrets included where it printed them).

## To be checked

- How long recordings are kept by default, and their size limit.
