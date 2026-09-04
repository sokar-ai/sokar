# 0005 — Review And Approve Work

**Status:** open

A task's output waits for review before it goes anywhere. The interface shows what
is waiting, how old it is, what it changes, and offers exactly two answers.

## Acceptance

- Pending work is listed oldest first, because the oldest is the one being forgotten.
- A full diff and a commit list are viewable without leaving the screen.
- Approve forwards it; reject discards it; both say what happened.
- Approving asks for the destination only when the project has not named one.
- Work waiting longer than a configurable age is marked.

## Notes

The review queue turning into a rubber stamp is the failure mode. Age marking exists
to counter that.
