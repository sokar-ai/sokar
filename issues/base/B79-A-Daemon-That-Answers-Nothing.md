# B79 — A Daemon That Answers Nothing

**Status:** open. Seen once, not reproduced.

## What happened

In a full acceptance run on the ubuntu26.04 VM, a daemon of a scenario's own started, answered
`GetInfo`, and then answered no call for four minutes. Four `CanStart` calls, one of them for an agent
that is not installed and touches no project, each ran out a 60-second timeout. The daemon logged
nothing in that time and used 6 seconds of CPU. The scenario ("the daemon says whether work can
start") passes on its own, and passed in a run that repeated the two features before it.

What was different in the failing run, measured afterwards from the unit's journal:

- the scenario before it starts a daemon and kills it with SIGKILL one second after it starts;
- about fifteen fixture projects were followed at that point, because a run removes its projects at
  the end.

## What it might be - inferred, not measured

- The daemon starts four background virtual threads at once - following projects, measuring
  upstreams, moving mail, watching mail directories - and the VM has two CPUs, so two carrier threads.
  A background pass that pins both carriers, in a native call, would leave no carrier for a call on
  the socket until it finishes. Nothing here has shown a pinned carrier yet.
- Something left by the killed daemon that the next one waits on. Nothing of that kind has been
  found: the only file locks are the vault's, and the kernel releases those.

## What must be true

**A call on the daemon's socket is answered, or refused, within a bound - whatever the daemon's own
background work is doing.**

## Seen in the acceptance suite, 2026-09-29

`task-unattended.feature`'s scenario "the daemon says what an agent was refused, and runs one with a
prompt" asks a daemon of its own for `Agents`, then `Start`, each bounded at 600 seconds. Twice that day
neither was answered: on the fedora VM against build 201, and on the ubuntu VM against build 208. Both
machines had the stub and agents installed machine-wide. The same scenario passed on the ubuntu VM against
builds 205, 207 and 208 (a rerun), so it is intermittent. On the fedora VM, the daemon had started a
second `sokar-agent-stub serve` on the socket the first one held. Not yet reproduced on demand.

## To be checked

- A thread dump of a daemon in that state: the native image needs `--enable-monitoring=threaddump`
  for `SIGQUIT` to print one.
- Whether the first follow pass after a start, with many projects followed, keeps calls waiting.
