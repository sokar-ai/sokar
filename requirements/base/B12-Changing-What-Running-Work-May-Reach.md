# B12 — Changing What Running Work May Reach

**Status:** open

`SetEgress` edits what a project's *next* task may reach. There is nothing that changes what the
task in front of somebody is allowed to reach right now, and a container's ruleset and resolver
are built when it starts.

So the only way to widen a running task today is to stop it and start it again. That is expensive
in the way that matters most: the run has a workspace, a gate token, commits that have not reached
the gate, and an agent halfway through something. Restarting to add one host throws all of it away
to change a line in a file.

The gap shows up as a question with no good answer. An agent an hour into a task asks for a host
nobody declared — a package mirror, a documentation site, an API the work turned out to need. The
clearance prompt answers it once, for that connection. The next connection asks again. There is no
way to say *"this whole run may reach that"*, and no way to say it without starting over.

**The path already exists.** A clearance decision is applied to a running container, and
[B05](B05-Health-And-Diagnostics.md) records the dependency that makes it possible: *"without
`nsenter` a clearance decision cannot be applied to a running task"*. So reaching into a live
container to change what it may talk to is something Sokar already does, once, per connection.
What is missing is the same reach used deliberately rather than one host at a time.

There is a second, much smaller thing in the same area. `Start` takes `clearance` — `prompt`,
`allow`, `deny` or `off` — and **`Task` does not carry it back**. A task running with enforcement
off is the most consequential state in the product and it is invisible: nothing an interface lists
can mark it, because nothing tells the interface it is true.

## Acceptance

- What a **running** task may reach can be widened and narrowed, and takes effect without the task
  being stopped, restarted or rebuilt.
- Whether a change reached the running task, the project's stored configuration, or both, is part
  of the answer. **The two are different things** and an interface must never have to guess which
  it just did.
- A change meant only for the run in front of somebody does not outlive it, and one meant to
  persist says so. Neither is the silent default.
- Turning enforcement off is reachable while a task runs, is its own state rather than the widest
  setting, and **is readable back from the task** so that it can be marked wherever that work is
  listed.
- A change the task's security class does not permit comes back as an outcome naming the reason,
  in the way `SetEgress` already refuses one, rather than as an exception.
- What a change would open and close can be asked for **before** it is made, as `SetEgress` allows
  with `dryRun`. This is the most consequential edit in the product and it is more consequential
  against a task that is running than against a file.
- The answer says what it opened and closed as **hosts**, not as set names, and in the order the
  sources granted them — the same rule `SetEgress` already follows, for the same reason.

## Notes

Asked for by the interface. [F17 Network Exposure
Control](https://github.com/fuinorg/sokar-frontend/blob/main/requirements/F17-Network-Exposure-Control.md)
has six acceptance criteria; three are built on `Prompts` and `Decide`, and the other three are
this file:

- *"The exposure level of running work can be changed from where that work is listed, without
  restarting it."*
- *"The available levels are named by what they permit, and the current level is always visible on
  the work itself."* — visible is answered by `Task.securityClass`; changeable is not answered at
  all.
- *"Turning enforcement off entirely is possible, distinct from every other level, and visibly
  marked wherever that work appears."* — the marking half needs `clearance` on `Task`.

The `Task` field is worth doing whether or not the rest is: it is a reply field, which under the
compatibility promise costs nothing and needs no `Tasks2`, and it turns an invisible state into a
visible one. The same argument as [B11](B11-What-A-Task-Says-About-Itself.md), which was taken.

**This is not a request to weaken the gate.** Widening what one run may reach is a decision
somebody makes, and it should be as legible and as refusable as every other one — refused by class
where the class refuses it, previewed before it happens, and recorded. What it removes is the
choice between restarting an hour of work and answering the same question forty times.

## Measured, 2026-09-07

Three of the questions below are answered by reading what already runs, and one of the answers
contradicts something this file assumes.

**"The path already exists" holds for an address, not for a name.** A clearance decision adds an
address to the live nftables set, which works because the container had already got that far: it
had an address to dial. A host *nobody declared* has no address, because the resolver refuses it -
`address=/#/` makes everything NXDOMAIN and only a declared name gets a `server=` line. So widening
a running task by name is two changes, not one: the firewall set, and the resolver.

**The resolver cannot be told without being restarted.** It runs as
`dnsmasq --keep-in-foreground --conf-file=<state>/dnsmasq.conf` inside the container's network
namespace, started by the supervisor hook, with its pid in `dnsmasq.pid`. `SIGHUP` clears its cache
and re-reads hosts files - it does **not** re-read the configuration file, which is where
`server=/<name>/<resolver>` lives. Restarting it is mechanically possible; what it costs is a
window in which the container's lookups fail, and any in-flight one is lost.

**A live change does not survive `Resume`.** The prestart hook loads `<state>/ruleset.nft` on every
`podman start`, and the supervisor hook re-reads the resolver's conf file the same way. Both are
written once, when the task is created. So a change made only in the live namespace is gone the
moment a task is resumed - which makes "run-only" and "persisted" genuinely different things
rather than a nicety, and means a run-only change has to say that it is one.

**An already-refused connection is not recovered.** The packet was dropped; adding an element
afterwards affects the next attempt and nothing else. That is already how a cleared destination
behaves, and the answer for an interface is the same: it can say a host is now reachable, never
that the thing that failed will now succeed.

**The helpers do not disagree.** The clearance watcher edits the same live set and keeps its own
record of what was decided; a host widened behind its back simply produces no drop events, so it
raises no prompt. The gate has nothing to do with egress. The one asymmetry is that the watcher's
own record does not know about such a host, which costs nothing: that record exists to avoid asking
twice.

## To be checked

- **Is a live change one action or two?** Changing the running task and changing the project file
  are different intentions — *"this run needs it"* against *"this project needs it"* — and an
  interface has to offer them as different things. Whether that is one method with a flag or two
  methods decides what the interface can honestly say it did.
- **May an `offline` project be widened at all while running?** The standing editor already
  refuses with `REFUSED_BY_CLASS`. Whether that refusal is the same one here, or whether a running
  task is stricter still, decides whether the interface offers the action at all for such a task.
- **May a live widening restart the container's resolver?** Only visible after measuring: adding a
  name means restarting dnsmasq inside the running namespace, which is a short window where the
  container resolves nothing and any lookup in flight fails. The alternative is to allow live
  widening by address only - no restart, and useless for the case that motivated this, since the
  host somebody wants to add is a name.
