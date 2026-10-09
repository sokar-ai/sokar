# B136 — An Approve Bound To What Was Reviewed

**Status:** open.

**What must be true.** What `sokar gate approve` forwards is the commit the person read, on the command line as in
the interface and the plugin. A push the agent makes between `sokar gate review` and `sokar gate approve` is never
forwarded unseen.

## Why

A task keeps running while its work is read. The incoming ref holds the agent's latest push, so a second push after
the review replaces what was read. Three ways to approve already bind to the reviewed commit and refuse a moved one as
`MovedSinceReview`: `sokar gate approve --commit <full id>`, `sokar approve` in a checkout (it lands the commit it
showed), and the daemon's `Approve` with `commit`, which the interface and the plugin always send. A plain
`sokar gate approve -p <project> <task>`, the form the documentation shows first, takes whatever the ref holds at that
moment. `--signed` binds only when the key is enrolled. `reach.md` calls approval a control; on this path it holds only
for the commit nobody read.

## The shape

- `sokar gate review` ends with the commit it showed in full and the approve line that names it:
  `sokar gate approve -p <project> <task> --commit <full id>`.
- `sokar gate approve` without `--commit`, where a person can be asked, shows the commit that waits now and asks
  before forwarding it; where nobody can be asked, it is refused with the commit that waits, so a script names it.
- `doc/commands.md`, `doc/how-it-works.md` and `doc/reach.md` show the bound form first.

## Acceptance

- Seen to fail first: a push after `gate review` and before a plain `gate approve` is forwarded today; afterwards it
  is shown and asked about, or refused without a terminal.
- `gate review`'s last lines name the full commit and the bound approve line.
- `--commit` with the commit that waits forwards it, as now; with another, `MovedSinceReview` as now.

## To be checked

- Whether a stopped task is reason enough to approve without asking, since nothing can move its ref any more.

**Guideline points, 2026-10-09:** the operator's review of security guidelines counts this as its item F20,
answering AISVS 9.2.8, OWASP AI Agent Cheat Sheet §1/§4.

