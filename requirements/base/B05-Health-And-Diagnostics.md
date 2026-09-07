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
