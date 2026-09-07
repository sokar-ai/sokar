# F22 — Task State Visibility

**Status:** open

Three states carry almost all the value: working, idle, and waiting. The third is the one that
matters — work blocked on a question nobody saw is indistinguishable from work that is merely
slow, and that is how an unattended run wastes an afternoon.

Moved here from the central index, where it was 0003: what it asks for is what a person sees, and
the daemon already streams the underlying state.

## Acceptance

- Waiting is detected from the work's own signals, not inferred from a timeout.
- Idle is distinguished from finished.
- The state carries a timestamp, so "idle for 40 minutes" is answerable.
- Work whose container died is shown as dead rather than idle.
- The state arrives without a manual refresh, for every piece of work at once.

## Notes

The daemon's `Watch` call already streams this and reports only when something an operator would
want redrawn has changed - deliberately not when a clock moves.

## To be checked

- **Can "waiting for a person" actually be detected per agent?** This assumes every agent exposes
  a signal for it. If some do not, the honest options are a per-agent capability flag or narrowing
  this to the states observable from outside the agent. Settle it before anything depends on it.
- Is work waiting on a decision distinguishable from work waiting on its own prompt? They need
  different answers from the person.
