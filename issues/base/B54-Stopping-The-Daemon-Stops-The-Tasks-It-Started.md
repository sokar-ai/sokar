# B54 — Stopping The Daemon Stops The Tasks It Started

**Status:** open, found and measured on 2026-09-13 while answering the interface's question whether
it should offer to stop a daemon as well as start one. Related to B43 - a restarted machine leaves
tasks `Exited (143)` too, from a different cause - and to B44, which brings a stopped task back.

**What it blocks.** `sokar-frontend` F33, offering to stop a daemon from the interface. Until
this is met, such a button would stop running tasks it cannot even name - nothing tells a client which
tasks the daemon started - so the interface waits rather than put that cost behind a dialog.

## What happens, measured

On the `ubuntu26.04` test machine, as its user: a project of class `guarded`, one task started with
`Start` over the daemon's socket, the stub agent. podman's cgroup manager there is `systemd`.

**Before `systemctl --user stop sokard`**, the unit's control group held the daemon **and the whole
task**:

    sokard                     the daemon
    conmon                     the process that keeps the container alive
    pasta                      the container's network
    dnsmasq                    the task's resolver
    sokar shield read          the firewall's log reader
    sokar gate serve           the git gate
    sokar shield watch         the clearance watcher

**After it**, every one of those was gone, the container included.

**After `start`**, the daemon listed the task as `Exited (143)`, `activity: DEAD`, `helpers: 0`,
`startAction: RESUME`. Nothing on disk was lost - removing the task afterwards still discarded 29
files the agent had added - but the running task was stopped by stopping the daemon.

## Why

The unit sets no `KillMode`, so systemd's default applies: stopping a unit stops **every process in
its control group**. And a task started through the daemon is started from inside it - the daemon
runs `TaskLaunch` in its own process, which starts the helpers and podman as its children, and they
stay in the unit's control group. conmon landed there too, although podman's cgroup manager is
`systemd`; why is part of what is open below.

## Why it matters

**It is the opposite of what this repository says it wants.** `doc/daemon.md` explains why the unit
has no `RuntimeDirectory=`: *"systemd removes what it creates when the unit stops … a stopped daemon
would take running tasks' state with it."* That paragraph guarded the task's **files**, and the
**processes** went anyway.

**A crash takes the tasks too - inferred, not measured.** The unit restarts on failure, and systemd
stops a failed unit's control group before it starts it again, so a daemon that crashes would stop
every task it started on the way to recovering.

**Who started a task decides how long it lives - inferred, not measured.** A task started from the
CLI is not a child of the daemon and would not be in its control group. The same task, started from
the interface, dies with the daemon.

## What must be true

**Stopping, restarting or losing the daemon does not stop a task. A task ends when something asks
for that task to end.**

## Acceptance

- A task started through the daemon keeps running across `systemctl --user stop sokard` and `start`:
  the same container, its helpers alive, its gate reachable, a clearance question still answerable.
  Measured on a machine, for a `guarded` task and for one holding a credential.
- The same across a daemon that crashes and is restarted by systemd.
- A task started through the daemon and one started from the CLI live in the same place, so how a
  task was started does not decide how long it lives.
- A test fails if a task's process is ever found in the daemon's own control group again.

## Open questions

1. **Where a task's processes should live.** In a scope of their own per task - podman's, or a
   transient `systemd-run --user --scope` - or by changing the unit's `KillMode`. The last is one
   line and the weakest: the processes stay in the unit's control group, and systemd warns about
   what is left over at the next start.
2. **Why conmon landed in the unit's control group** although podman's cgroup manager is `systemd`.
   What podman needs in order to give a container its own scope has to be measured, not assumed.
3. **Whether anything should record that a task was started through the daemon**, once the answer to
   the first question makes that difference disappear.
