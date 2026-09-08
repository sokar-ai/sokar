# B05 — Health And Diagnostics

**Status:** built. `sokar doctor` probes every external dependency by name and each failure
carries one next action, enforced by the type rather than by habit. What is left is the question
it inherited: whether a machine that works less well than it should may be refused, rather than
only reported.

Several dependencies fail silently rather than loudly: a missing capability in a
resolver, a policy that blocks a mount, a stale copy of a binary shadowing the
installed one. Each produces a symptom far from its cause.

## Acceptance

- Every external dependency is probed and reported by name.
- A capability that is present but compiled out is detected, not assumed from a
  version number.
- Shadowed binaries are named, with what is hiding what.
- Each failure carries the single next action.
- The same answer is available to something that is not a terminal.

## Built, 2026-09-07

**Eight dependencies, each by name**: podman, the hook registration, podman's rootless network
backend, dnsmasq's nftset support, `nft`, `git`, `nsenter` and the SELinux policy - plus the
keyring, which decides whether a passphrase can be cached at all. Four of them were not probed
before, and each fails somewhere far from itself: without `nft` a container comes up with no
ruleset, without `git` the gate has no mirror to serve, without `nsenter` a clearance decision
cannot be applied to a running task, and without libkeyutils every vault command asks again.

**A failure that names no next action cannot be constructed.** A probe is a record with a state,
and its constructor refuses anything but `OK` without an action:

```
dnsmasq nftset      MISSING - this dnsmasq cannot open the firewall for declared domains
                    -> install a dnsmasq built with nftset support (Fedora and Debian both ship one)
```

That is the criterion turned into a type. The line somebody forgets is the line an operator is
reading at their worst moment, so forgetting it fails the build instead.

**Four states, not two.** `UNKNOWN` is its own answer: every dependency here is one whose absence
is invisible until a task behaves strangely, so a probe that guesses well is indistinguishable
from one that works. `DEGRADED` is the other - it is what a machine using slirp4netns rather than
pasta gets, with the consequence spelled out (the git gate binds every interface and is reachable
from this machine's network) and the choice named. Only `MISSING` fails the command.

**Shadowed binaries name both halves.** They used to say only which copy is unused, which sends an
operator looking for the one that is used - and it was found on a real machine as yesterday's
build in the data directory, silently winning:

```
not used /usr/libexec/sokar/agents/sokar-agent-<name>
         hidden by /home/<user>/.local/share/sokar/agents/sokar-agent-<name>
```

The probes go through the context's command runner rather than building their own, so a machine
state can be faked in a test. That is how the slirp4netns case is covered without a machine that
has it.

## Notes

Where a probe cannot answer, it must say so rather than assume the good case.

## To be checked

- **Whether an operator may refuse a working machine that is weaker than it should be.** Half
  answered: the doctor now reports such a machine as `DEGRADED`, names what it costs, and exits
  zero, because the machine does run tasks. What is not answered is whether `task run` should be
  able to refuse - a project or a machine saying "not without pasta" - and that is a policy
  decision rather than a probe. Nothing refuses today; the gate binds every interface, says so,
  and leaves the per-task token as what keeps it shut.

## Built, 2026-09-08: the diagnosis is on the wire

**`Doctor` answers the probes and a `ready` flag**, using the same rule the CLI exits non-zero on,
so a machine cannot be called ready by one and unready by the other. Each probe carries the single
next action, and a probe that fails and names none cannot be constructed - so an interface renders
that field without checking whether it is there. It runs external programs, so it is a read that
takes a moment and is not something to poll.

What follows is why it was worth doing, and why the other half of the same request was refused.

**The diagnosis existed and only the CLI could see it.** `sokar doctor` probes nine dependencies with
four states and one next action each - a failure that names no next action cannot even be
constructed - and none of it is on the daemon's contract. So the question it answers, *can this
machine do the thing I am about to ask of it*, cannot be asked from anywhere else.

That matters because every one of these failures surfaces far from its cause. Without `nft` a
container comes up with no ruleset; without `git` the gate has no mirror; without `nsenter` a
clearance decision cannot reach a running task. **Today a person learns all of this by starting
work and watching it behave strangely.**

Nothing new has to be decided: the probes, the states and the next actions are written. What is
missing is a read on the wire.

### The other half is withdrawn, and it could not have worked anyway

The interface also asked to *offer the fix and run it* - installing packages, writing under `/etc`.
That is root on the node, and exactly the power this product is built around not having. The
operator ruled it out.

There is a second reason it was never reachable: **a machine that is not ready usually has no
daemon to ask.** A readiness check delivered over the daemon's own socket can only answer for a
machine whose daemon is already up, so the case most worth fixing is structurally out of reach of
this transport. What is wanted is the diagnosis, and the diagnosis exists.

