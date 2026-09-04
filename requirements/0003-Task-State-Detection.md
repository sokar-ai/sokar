# 0003 — Task State Detection

**Status:** open

Three states carry almost all the value: working, idle, and waiting. The third is
the one that matters — an agent blocked on a prompt nobody saw is indistinguishable
from a slow one, and that is how an unattended run wastes an afternoon.

## Acceptance

- Waiting is detected from the task's own signals, not inferred from a timeout.
- Idle is distinguished from finished.
- The state has a timestamp, so "idle for 40 minutes" is answerable.
- A task whose container died is reported as dead rather than idle.

## Notes

Depends on [0001](0001-Local-Daemon-API.md). The states must be cheap enough to
stream continuously for every task at once.

## To be checked

- **Can "waiting for a person" actually be detected per agent?** The requirement
  assumes every agent exposes a signal for it. If some do not, the honest options are
  a per-agent capability flag or narrowing this requirement to the states that can be
  observed from outside the agent. Settle this before anything depends on it.
- Is a task that is waiting on a decision distinguishable from one waiting on its own
  prompt? They need different answers from the person.
