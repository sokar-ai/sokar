# B54 — Stopping The Daemon Stops The Tasks It Started

**Status:** decided on 2026-09-18, and not built. The cause is measured, the fix is measured in
isolation, and what is left is doing it in the daemon. Found on 2026-09-13 while answering the
interface's question whether it should offer to stop a daemon as well as start one. Related to B43 - a restarted machine leaves
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
stay in the unit's control group.

## Measured, 2026-09-18: where the parts actually are, and what kills them

On `ubuntu26.04` as `claude`, podman 5 with cgroup manager `systemd`, one `guarded` project, the stub
agent, the task started through the daemon's socket rather than from the CLI.

**The workload is already in a scope of its own; its monitor is not.**

    sokard.service/                       pasta, conmon, dnsmasq,
                                          sokar shield read, sokar gate serve, sokar shield watch
    user.slice/libpod-<id>.scope/container /run/podman-init -- sleep infinity, and the agent

So podman **did** give the container its own transient scope. What stayed in the daemon's control
group is conmon - the process that keeps the container alive - together with the network, the
resolver and the three helpers.

**Why conmon is there, and it is not what the 2026-09-13 note assumed.** The daemon's environment
carries both `XDG_RUNTIME_DIR` and `DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1001/bus`, so
podman could reach the user's systemd and did: the `libpod-<id>.scope` exists and is active. podman
places the **container** in that scope and leaves **conmon in the control group of whoever invoked
it**. The daemon invoked it, so conmon is the daemon's.

**A scope of its own did not save the task.** `systemctl --user stop sokard`, measured:

| | before | after |
|---|---|---|
| container | `Up About a minute` | `Exited (143)` |
| conmon | alive | gone |
| the workload inside `libpod-<id>.scope` | alive | gone |
| pasta, dnsmasq, the three helpers | alive | gone |
| `libpod-<id>.scope` | active | inactive |

The payload died although it was not in the unit's control group, because its **monitor** was: kill
conmon and the container goes with it, and podman's cleanup then takes the scope down. **The thing to
move out of the daemon is the monitor and the helpers, not the payload.**

**The fix, measured in isolation.** The same task started inside a transient scope -
`systemd-run --user --scope --unit=sokar-scope-t2 --collect sokar task start …` - puts conmon, the
gate, the resolver and the helpers in `app.slice/sokar-scope-t2.scope`. Stopping the daemon then
leaves everything running:

    sokard: inactive
    containers up: sokar-b54-t2 Up 9 seconds
    conmon: alive   gate: alive   dnsmasq: alive

That is the shape to build: **one transient scope per task, created by whoever starts it**, so that a
task's lifetime is decided by the task and not by its parent. It also answers the third question this
requirement used to carry - once both paths put a task in a scope of its own, nothing needs to record
which path started it, because the difference is gone.

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
- **A task's scope ends when the task ends.** No scope, and no helper, outlives the task it belongs
  to - otherwise stopping the daemon stops nothing and removing a task leaves processes nobody
  names.

## To be checked

- **Whether removing a task reaps helpers that live in its scope.** Measured once, in the unusual
  arrangement above - a CLI-started task inside a hand-made scope - `sokar task remove` reported
  `removed` and left conmon, `sokar shield read` and dnsmasq running in the scope, conmon reparented
  to init. The scope then had to be stopped by hand. Whether that is an artifact of the hand-made
  scope or a defect in removal has to be measured again once the daemon creates the scope itself,
  because a task that leaves its helpers behind is the failure this requirement's own contract calls
  out: *a container that is up with no helpers is not the same thing as a healthy task*, and the
  mirror image - helpers with no container - is no better.
