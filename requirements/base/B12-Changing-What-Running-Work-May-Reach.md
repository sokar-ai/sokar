# B12 — Changing What Running Work May Reach

**Status:** built. Widening, narrowing, and turning enforcement off or back on while a task runs.

It was wrongly retired on 2026-09-08 and restored the same day: every criterion had been checked
except the fourth, and the fourth was the one nothing answered. Worth keeping as a note about how
retiring works - a requirement is met when *every* criterion is, and the one that is easiest to
skim past is the one no method implements.

The retirement was a reading error worth recording: every criterion was checked except the fourth,
and the fourth is the one no method answers. `Start` takes `clearance`, so it is choosable when a
task is created; nothing sets it afterwards. `WidenTask` and `NarrowTask` are explicitly not it -
they grant and withdraw names, and that the ruleset stays on is their whole point.

Widening and narrowing both, and the question narrowing turned on is answered
below. A task says what enforces its egress, and a running task can be given a name to reach
without being stopped - from the CLI and over the socket, with the scope stated rather than
assumed.

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

## Decided, 2026-09-07

### One method, and it must be told how far the change goes

**A new method for the running task, taking the scope as a required value**: this run, or this run
and the project file. `SetEgress` stays exactly as it is, for the case where no task is running.

Two separate methods were rejected for one reason: somebody who means both would make two calls,
and the second can fail after the first succeeded. That leaves the run widened and the file not,
which is precisely the state this requirement says nobody may be left guessing about. One call can
report what it changed because one call did all of it.

The scope is required rather than defaulted, because the acceptance says neither is the silent
default - and a `?bool` that defaults to run-only is a silent default wearing a parameter's
clothes.

### An offline project is refused, exactly as the editor refuses it

`REFUSED_BY_CLASS`, the same outcome `SetEgress` already answers with. The class is the project's
promise that its tasks reach nothing; a run that can step around it at will makes the promise
worth nothing, and the way out already exists and is visible - raise the class in the file and
start a task. An interface can hide the action entirely for such a task rather than offering
something that will be refused.

### The clearance watcher puts the address in the firewall, as it already does

The name is recorded as approved for this run and the resolver is extended; the first connection
is still dropped, the watcher sees it, looks the address up to the name in the resolver's log -
which it already does today, to show an operator something they can recognize - finds it approved
and allows it without asking anybody. From then on the host is open.

The cost is one dropped packet and a retry, which is exactly what every clearance decision costs
today. The alternative - Sokar resolving the name itself and writing the answers straight into the
set - was rejected because Sokar's answer and the container's can differ: a CDN, GeoDNS or plain
round-robin leaves an address open that the container never receives while the one it does receive
stays blocked. That failure reads as "the grant did not work" and is miserable to find.

**What this needs first**: the generated resolver configuration has to put its `server=` lines in a
`servers-file`, because that is the only part dnsmasq re-reads on `SIGHUP`. The `nftset=` lines
stay in the main file - a servers-file may contain nothing else - which is exactly why the firewall
half goes through the watcher rather than through dnsmasq.

## Built, 2026-09-07

**The resolver is told, not restarted.** The generated configuration now keeps its `server=` lines
in a `servers-file`, which is the only part dnsmasq re-reads on `SIGHUP`. Measured against real
dnsmasq with the configuration Sokar actually generates: a name answered `NXDOMAIN`, a line was
appended and the process signalled, and the same process then answered with real addresses. No
restart, and no window in which the container resolves nothing.

**The firewall is not told at all.** A `servers-file` may hold nothing but `server=` lines, so the
`nftset=` mapping cannot be added later - which is why the granted name goes into a file the
clearance watcher reads instead. The first connection is still dropped; the watcher looks the
address up to the name in the resolver's log, finds it granted, and allows it without asking
anybody. The audit record says `source: granted`, because "allowed without being asked" and
"allowed by somebody" are different events.

**One method, and it must be told how far the change goes.** `WidenTask(task, domains, scope,
dryRun)` over the socket, `sokar shield egress --task <name> --add-domain <host>` on the command
line, `--also-project` for the other scope. A call that does not say arrives as `ScopeRequired`
rather than as a default.

**Both surfaces answer with what happened, including the half-state.** Widening the run and
failing to write the project file answers `NO_PROJECT_FILE` with the run genuinely widened - not a
failure, because reporting one would leave somebody believing nothing had changed while the task
can now reach the host.

**Neither surface pretends the refused attempt is retried.** Both say the host is reachable from
the agent's next attempt. The packet that was dropped is gone.

## Decided and built, 2026-09-08

**Narrowing takes the name and the addresses, and lets running connections finish.** The open
question was what a withdrawal means for what is already open, and the three possible answers were
not equally honest:

- *the name only* leaves a container that already resolved it connecting by address, so calling
  that "narrowed" is a half-truth;
- *the name and its addresses* stops every new connection, which is what somebody means;
- *and the established flows too* is the only thing that stops a transfer in progress, and it
  reaches an agent as a network fault it cannot tell from a broken link.

The middle one was chosen. The ruleset accepts `ct state established,related` without consulting
the set again - measured, not assumed - so **`NarrowTask` says what it did rather than claiming the
host is unreachable**, and the contract says so where a client will read it. If a transfer has to
stop, stopping the task is what does that.

**The addresses come from a record written when each grant was applied**, in
`granted-addresses` beside the granted names. Re-resolving the name at withdrawal time was
rejected here for the reason widening already had: a CDN, GeoDNS or round-robin answers Sokar and
the container differently, and the addresses that differ are exactly the ones a withdrawal would
leave open. Recording it needed the clearance hub to pass the resolved name alongside the address
it lets through - it had both and was discarding one.

**A grant covers subdomains; a withdrawal does not.** `example.test` answers for
`api.example.test` when deciding what to let through, but taking the parent back must not remove an
address let through under a name somebody granted separately and still means. Proved by mutation -
and the test that proves it failed first on correct code, which is how it emerged that a subdomain
cannot be granted separately *after* its parent at all, because the covering rule makes that a
no-op.

## Built, 2026-09-08: `sokar task clearance` and `SetClearance`

`prompt`, `allow`, `deny` or `off`, on a task that is already up. The state used to be chosen when
a task started and never again, so somebody watching a task ask about the same host for the
twentieth time had to stop it and start again to make it stop.

**Deliberately not `WidenTask` with a special value.** Those grant and withdraw names, and that the
firewall stays loaded is what they mean. Folding "stop asking about anything" into them would make
one method mean two unrelated things, and the quiet one would be the dangerous one.

**What it cannot undo, and says so.** Turning enforcement off does not recall a connection that was
already refused - the packet was dropped and nothing retries it - and turning it back on does not
recall anything waved through while it was off. The ruleset is loaded throughout, so this never
opens a destination by itself; what changes is whether a blocked connection produces a question.

**The part that would have been missed.** A resume rebuilds a task's helpers from `resume.json`,
so turning enforcement off has to take the watcher out of that record too. Otherwise the mode
somebody just turned off comes back at the next resume - silently, and only after a restart. There
is a test for it, and the mutation that leaves the record alone fails.

**One thing that could only be got right by knowing the project.** A container is
`sokar-<project>-<task>-<run>` and a project name may contain hyphens, so the task name is only
answerable once the project is known - which is read from the sidecar rather than guessed. The
first version split on the first hyphen and would have named `code` for a task called
`code-review`, putting a prompt in front of somebody about something that does not exist.

## To be checked

- ~~How enforcement is turned off on a task that is already running~~ **Built, above.**
  What was left open with it: turning it back *on* mid-run is a different promise, because the
  connections waved through while it was off are not recalled by switching it back. That is stated
  rather than solved, because there is nothing to solve - the packets are gone. `Start` takes `clearance`
  and nothing changes it afterwards, so the state a task is in was chosen before anybody saw what
  it would do. What is not obvious is whether it should be reachable in both directions - turning
  it back *on* mid-run is a different promise, because the connections that were waved through
  while it was off are not recalled by switching it back.

  It is deliberately not `WidenTask` with a special value. Those two grant and withdraw names, and
  that the firewall stays loaded is what they mean; folding "stop asking about anything" into them
  would make one method mean two unrelated things, and the quiet one would be the dangerous one.

