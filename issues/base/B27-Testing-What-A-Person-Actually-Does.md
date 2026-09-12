# B27 — Testing What A Person Actually Does

**Status:** open, and it changes how everything else is verified. Raised on 2026-09-09 by the
operator, after a day of being handed test instructions: *"I wonder why I should do all these user
tests on my own."*

That is the right complaint. What follows is why it happened, which is not "there is no acceptance
suite".

## There is a suite, and it cannot reach any of this

`buildtools/e2e-tier1.sh` is 1177 lines of bash and runs on two rented machines in parallel on
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

## The eleven scenarios that run nowhere

**Three feature files are tagged `@slow`, and nothing in this project runs them.** CI excludes them
by name - `Leg.java:190` passes `-Dcucumber.filter.tags=not @slow`, with the reason that each needs
a task image and a leg has already built one - and no other job, schedule or script runs them
instead. Every green build reports *"Tests run: 91, Skipped: 11"*, which reads as eleven tests
somebody chose to skip rather than eleven that have never run anywhere.

What is in them is not the cheap end:

| File | Scenarios | What it covers |
|---|---|---|
| `task-restart.feature` | 4 | A task that outlived the machine: what it says it belongs to, what starting it again reports, what its logs say about the reboot, and that what it held reads as unreadable rather than as nothing. |
| `task-session.feature` | 3 | Work carrying on while nobody is attached, a process started before a disconnect still running after it, and the cleanup afterwards. |
| `task-inside.feature` | 4 | The workspace holding the project, the prompt naming the task, a bare `push` reaching the gate rather than the mirror, and leaving a shell with work in it keeping the task. |

**This stopped being theoretical on 2026-09-12.** The launch path was changed that day to run a
task's agent inside the task's own session, precisely so that work carries on while nobody is
attached and a re-attachment finds it - which is `task-session.feature`, scenario for scenario.
It shipped on unit tests. The scenarios that describe the behaviour existed, were correct, and ran
nowhere; the thing that actually verified it was a person at a terminal reporting that his agent
had vanished.

**The exclusion was reasonable and its consequence was not recorded.** A leg is six minutes and an
image build is minutes each; refusing to pay that on every push is right. What is missing is the
*somewhere else* - and until there is one, the honest reading of a green build is "the fast
scenarios pass".

Three answers are possible and none has been chosen:

- **A nightly or weekly leg that runs only `@slow`**, on one machine rather than two. It pays the
  image build once and nobody waits for it.
- **The local VM**, where the image is already built and a run costs only the scenarios. That
  makes them part of what runs before a push rather than part of CI - which is where
  [AGENTS.md](../../AGENTS.md) already sends a person, and it would have caught this one.
- **Retire the tag and pay the minutes**, if it turns out to be less than it looks now that a leg
  caches images between scenarios.

Whichever it is, **a scenario that runs nowhere should be visible as that** rather than as a skip:
a build that says "11 skipped" beside 91 passes invites nobody to ask which eleven.

## To be checked

- **Which SSH client.** Two facts narrow it: the key in use is **ed25519**, and **BouncyCastle is
  already a managed dependency** of this build, so ed25519 support costs nothing new either way.
  - **SSHJ** — smallest API for expect-style work: `allocatePTY(term, cols, rows, modes)` then
    `startShell()`. Recommended unless the next point decides otherwise.
  - **Apache MINA SSHD** — more control over pty modes, and it is also a server, which this does
    not need. Worth it only if asserting on echo behaviour needs the modes set explicitly.
  - **`ssh -tt` as a subprocess** — no dependency at all, and the pty is allocated where it
    matters, on the far side. Costs the ability to set window size and pty modes, and turns every
    assertion into subprocess parsing.
- **What happens to the 1177 lines.** They work and they cover things worth keeping. Porting them
  wholesale is weeks; keeping both means two places to add a case. A third answer - port only what
  needs a terminal and leave the rest - splits the report in two, which is half of what this
  requirement is asking for.
- **Where the line sits.** A scenario against a real machine costs minutes and can be flaky; a unit
  test costs milliseconds and cannot see a pty. Deciding what belongs in each is the difference
  between a suite people trust and one they re-run until it passes.
- **What a scenario cleans up.** These leave containers, images, mirrors and a vault behind. The
  suite has to be able to run twice on the same machine, which the manual testing has repeatedly
  shown is where the interesting failures are.
- **Where the `@slow` scenarios run**, per the section above. Nightly leg, local VM before a push,
  or retire the tag - and whichever it is, a build has to stop reporting "never ran anywhere" as
  "skipped".
- **Whether the local VM is provisioned by the suite or assumed.** Assuming it is faster and makes
  "works on my machine" a real hazard; provisioning it is slower and is the thing being tested.
