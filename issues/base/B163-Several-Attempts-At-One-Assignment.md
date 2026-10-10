# B163 — Several Attempts At One Assignment

**Status:** open.

**What must be true.** `sokar task start --attempts N -P "…"` starts N tasks on the same assignment, from the same commit,
each in its own container; the gate shows their results side by side, one is approved and the others are discarded.

## Why

An agent given the same assignment twice comes back with different work, and the better of several is often worth more
than one carefully watched run. Sokar already has what that needs - cheap tasks kept apart, and a review before
anything reaches the upstream; what is missing is the shared start and the comparison. Comparable tools offer it only
as a hosted service, not on the person's own machine.

## The shape

- The N tasks share a name with a number each (`<name>-1` … `<name>-N`) and a group the gate knows them by.
- `sokar gate review <group>` shows, per attempt: the diff's size, the files touched, whether its build passed where the
  project names one, and the agent's own summary; `gate diff` between two attempts.
- Approving one discards the others' waiting pushes and offers to remove their tasks (B159), default no.
- Size: medium - the start, the gate view; the interface's view is `sokar-frontend`'s part.

## Acceptance

- Seen to fail first, then green: `--attempts 3` starts three tasks on one commit; the gate lists them as one group;
  approving one leaves nothing of the others waiting.
- `doc/commands.md` says it.

## To be checked

- Whether N attempts may share one credential's rate limit, and how a limit reached by one is said.
- The highest N a machine is allowed, by default.
