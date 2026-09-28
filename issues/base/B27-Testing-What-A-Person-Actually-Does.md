# B27 — Testing What A Person Actually Does

**Status:** in progress - the suite is built and runs everywhere; what tier 1 still checks in shell
is what is left. It changes how everything else is verified. Raised on 2026-09-09 by the
operator, after a day of being handed test instructions: *"I wonder why I should do all these user
tests on my own."*

That is the right complaint. What follows is why it happened, which is not "there is no acceptance
suite".

## There is a suite, and it cannot reach any of this

`buildtools/e2e-tier1.sh` was 1177 lines of bash (1357 on 2026-09-28) and runs on two rented machines in parallel on
every merge to main. It covers the failures that actually happen: the agent CLI never reaching the
image, the firewall applied late or not at all, a credential in the wrong variable, an agent
reaching for a host its definition never declared.

**What it cannot do is allocate a terminal.** Measured: no `ssh -t`, no `script`, nothing that
gives the remote command a pty. So every behaviour gated on `tty.isTerminal()` is invisible to it:

- the offer to start a stopped task on `attach`, and that a script is **not** offered it
- colour on work that exists nowhere else, and no escapes when the output is a pipe
- `vault put` reading a credential without echoing it
- the passphrase prompt, and the login container's interactive session

Those are exactly the behaviours that have been landing on a person to check by hand, and it is not
a coincidence: they are the ones a suite built on `ssh <command>` structurally cannot see.

**Second gap, smaller but felt daily.** The suite reports as a script's standard output. There is
no per-case overview, nothing a merge request shows, and a failure is found by reading a log.

## What must be true

**What a person does at a terminal is tested by the build, on a real machine, and reported case by
case — so that a release is judged by something other than somebody trying it.**

## Acceptance

- Scenarios are written given/when/then and run from Maven, so `mvn verify` covers them and CI
  reports them as tests rather than as a script's output.
- **A scenario can drive a real terminal**: the remote command sees a pty, and a scenario can send
  input, wait for a prompt to appear, and assert on what came back.
- **The same scenarios can assert the absence of a terminal**, because half of these behaviours are
  "does not ask when nobody is there". A suite that only ever allocates a pty tests one side of
  every one of them.
- The same feature files run against the local VM and against the rented machines, with the target
  chosen rather than duplicated.
- A scenario that fails names what it did and what it saw, without anybody opening a log.
- Nothing in a scenario, a report, or a CI log carries a credential.
- A failure is reproducible on the developer's own VM with one command.

## Decided 2026-09-09

- **Java, Cucumber, run by Maven.** Not a new script language beside the one already there.
- **A real terminal, over SSH.** Simulating an end user is the point; a command whose output is a
  pipe is a different program.
- **Both targets:** the local VM during development, the rented machines in CI.

## Built, and measured

- **The suite:** `acceptance/suite`, Cucumber run by Maven, driving a machine through the kit
  (`sokar-acceptance-kit`, published so the agent repositories use the same steps). A scenario gets
  a real terminal - sshj, `allocatePTY` then `startShell`, in the kit's `Terminal` - or asserts the
  absence of one, and reports as a test case.
- **Both targets, one property:** `-Dsokar.acceptance.host` points it at the local VM or at a rented
  machine; the Hetzner leg runs it after tier 1 on each of its two machines.
- **Nothing runs nowhere any more.** The eleven `@slow` scenarios were excluded from CI and ran
  nowhere until `57f730a` (2026-09-12) made the leg run every scenario - and fixed the three faults
  that had hidden behind the exclusion, recorded in `Leg`'s comment. Measured 2026-09-28: 92
  scenarios on the ubuntu26.04 VM and on both Hetzner legs, 0 skipped, the `@slow` ones among them.
- **It runs twice on one machine.** Measured 2026-09-28: the VM suite green, 92 of 92, finishing at
  05:32Z and again at 06:21Z on the same account. Only the package was reinstalled between the two;
  nothing the first run left behind was cleaned up.

## Decided 2026-09-28, by the operator

- **Tier 1 is ported whole, in stages.** Its checks move into Cucumber scenarios one group at a time
  - the firewall, the credential proxy, the gate, the declared and refused domains, and so on - and
  each part of `e2e-tier1.sh` is deleted in the same change that lands its scenarios. So there is
  never a check in both places, the report converges on one, and each stage ships on its own. The
  script is gone when the last group is.
- **Every command a person types has at least one scenario**, even where a unit test already covers
  its logic. A unit test proves the logic; only a scenario proves what somebody sees at a terminal,
  on a machine - which is where today's defects were found, not in the logic. The suite grows
  slower for it, and that is the accepted cost.
- **The local VM is assumed, not provisioned.** It exists; `exec:java@deploy` installs the build and
  the suite runs against it. Provisioning from nothing is what the Hetzner legs do, on every push.

## What is left

1. **An inventory**: every command, and whether a scenario covers it. The decision above makes the
   gaps the list of work, and nobody has counted them.
2. **Tier 1, group by group**, each group's scenarios landing with that part of the script deleted.
