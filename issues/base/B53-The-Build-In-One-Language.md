# B53 — The Build In One Language

**Status:** open, written 2026-09-13 at the operator's instruction, **first priority**.

**The rule is `sokar-project` PJ06** - that the Java repositories build, check, update and test in
Java and Maven, and that anything left in another language says why. This file is the tooling that
carries it out here and in the agent repositories: the shared tool, and Sokar's own scripts.

**Where the other half lives.** Replacing the scripts in `sokar-claude-code`, `sokar-pi` and
`sokar-omp` is each of those repositories' own work, handed over on 2026-09-13 and cut into four
issues per repository. Three of the four are **blocked by this file** - the build-time tools
and the update pipeline moving onto the shared tool, and the acceptance stage moving onto kit
scenarios: `sokar-claude-code` CC12, CC13, CC14; `sokar-pi` PI10, PI11, PI12; `sokar-omp` OM10,
OM11, OM12. The fourth, the pin check in a unit test - `PinAgreementTest`, in all three - is
not blocked and starts first.

## What is there, measured on 2026-09-13

Tracked files only, `mvnw` left out because the Maven wrapper is standard: **24 files, about 6,300
lines**. About 3,700 lines are Python and about 2,600 are shell.

**In Sokar:**

| File | Lines | What it does | Called from |
|---|---|---|---|
| `buildtools/e2e-tier1.sh` | 1181 | the tier-1 end-to-end test, run on a rented machine | `Leg` |
| `buildtools/check-packages.sh` | 375 | compares the `.deb` and `.rpm` with each other and with a real install | `build.yml`, `Leg` |
| `buildtools/deploy-vm.sh` | 113 | builds here and installs onto a test machine | by hand |
| `buildtools/check-ffm-metadata.sh` | 91 | reruns the tests under the native-image tracing agent and fails on drift in the FFM registrations | `core/pom.xml` |
| `buildtools/install-musl.sh` | 64 | installs the musl toolchain, pinned by digest | `pom.xml`, `Main`, `Snapshots` |
| `selinux/install-selinux-policy.sh` | 33 | compiles and loads the SELinux module | the deb and rpm, at install |
| `buildtools/compare-bills.py` | 137 | compares bills of materials | **nothing** - no tracked file names it |

**In the three agent repositories**, mostly the same tools three times:

| File | Lines | Copies | What it does |
|---|---|---|---|
| `check-changelog.py` | 308 | **byte-identical** in all three | fails a change to code that does not touch `CHANGELOG.md`; **removed on 2026-09-13**, see B55 |
| `compare-bills.py` | 190 | **byte-identical** in all three | decides whether an update may publish without a person, from the bill's dependencies and licences |
| `update.py` | ~240 | diverged | moves the pinned agent CLI to a new version |
| `upstream-version.py` | ~170 | diverged | asks the vendor or registry whether a newer version exists |
| `check-pin.py` | ~165 | diverged | checks that everything naming the pinned version agrees |
| `acceptance.sh` | ~310 | diverged slightly | the last stage, against the published packages on a rented machine |
| `add-fetched-cli.py`, `merge-tree-bill.py` | 60, 52 | two repositories, one | put what the image fetches into the bill |
| `build-pi-tree.sh` | 139 | `sokar-pi` | builds Pi and its Node runtime inside a pinned container |
| `broker-check.sh` | 119 | `sokar-omp` | proves the brokered path end to end with a deliberately fake key |

## Why it matters beyond preference

- **Copies drift.** Of the five Python tools in all three agent repositories, three have already
  diverged. A fix in one copy is a fix in one copy.
- **None of it is tested.** Each agent repository already records that its Python tools have no
  test harness and that nothing in CI could run one. The same code in Java gets JUnit with the
  build it is part of.
- **A second toolchain on every build machine.** `python3` is required by every agent workflow,
  and it is a runtime the build does not pin.
- **Review in two languages.** A reviewer of a Java build has to read the Python that decides what
  gets published.

## Sokar's half

**The changelog check left this requirement on 2026-09-13.** logchange was adopted through the fuinorg
parent POM, the old check is removed from the agent repositories, and requiring an entry is B55. The
shared tool below does not carry it.

1. **One shared tool, published the way `sokar-machines` and `sokar-acceptance-kit` are**, as a
   snapshot on Central that the agent repositories already know how to resolve. It covers the bill
   comparison, adding to and merging bills (through CycloneDX's Java
   library), the upstream version lookup and the version move. **What differs between agents is
   configuration or a strategy, never a copy.**
   It also carries the check of a pinned digest against what the vendor publishes, **callable on
   every push**: in `sokar-claude-code` and `sokar-omp` that check runs on every push today, because it
   is what catches a hand-made version bump that forgot the digest - a build that tests and packages
   green and fails only at an image build - and it needs the network, so it cannot be a unit test.
2. **Sokar's own scripts:**
   - `check-ffm-metadata.sh` - candidate: the tracing-agent mode and metadata copy of the native
     Maven plugin, if it reproduces what the script checks;
   - `install-musl.sh` - candidate: a download plugin with digest verification and unpacking;
   - `deploy-vm.sh` - a command in `sokar-machines`, beside `leg`;
   - `check-packages.sh` - the content comparison as a Java test; the real install stays on a
     machine;
   - `e2e-tier1.sh` - folded into the Java acceptance suite scenario by scenario, which B27 is
     already doing;
   - `compare-bills.py` - deleted, once it is confirmed that nothing outside the tracked files runs
     it;
   - **logic built as shell inside `Leg` and `AgentLeg`** - the command still runs over ssh, but
     what it decides moves into the driver. First case: the leg's check of rootless podman under the
     daemon unit's own properties before anything has run podman. Today one shell line reads the
     `[Service]` properties with `sed | grep | grep | sed`, tests for a pause process with a shell
     `if`, and runs `systemd-run … podman unshare true`. In Java: `cat` the unit and parse its
     properties in a method with a unit test (Service section only, comments skipped, `ExecStart`,
     `Type`, `Restart` and `RestartSec` left out); `cat` the pause pid file, validate it as a number
     and send `kill -0 <pid>`; send one `systemd-run` whose arguments are quoted by `AgentLeg.quote`.
     Re-proven by the leg with `NoNewPrivileges=yes` put back into the unit, which has to fail at
     that step with `newuidmap: write to uid_map failed`, and by one leg that passes.
3. **Acceptance-kit steps for what `acceptance.sh` and `broker-check.sh` check**, so that an agent
   repository's last stage is scenarios rather than a script. B52 is the first of those steps.

## What stays, and why

- `mvnw` - the Maven wrapper is the standard.
- `install-selinux-policy.sh` - it runs on the operator's machine while a package installs, and that
  is shell by nature.
- `dist-setup/sokar-setup.sh` - it is not part of the build. It ships beside the packages and runs
  as root on a machine that has nothing yet: no package, no daemon, no Java. A Java version would
  need the runtime it is there to install. Added on 2026-09-18, after the inventory above.
- `build-pi-tree.sh` - it orchestrates podman and npm in a pinned container; a Java version would
  be the same calls with more lines. Revisit if the rest is done.
- The `sh -c` commands Sokar's Java builds in `Podman`, `TaskControl`, `TaskWorkspace`,
  `CommandPassphrase` and the kit's `Machine` - they run inside a container or over ssh, where a
  shell is the interface. They are not scripts and are not in scope.
- Inline `run:` steps in the workflows shrink to single Maven or tool invocations, but a workflow
  step is YAML running a command, not a second language.

## What must be true

**The shared tool exists and the agent repositories use it, and each of Sokar's own scripts is
either replaced or listed above under *What stays* with its reason.** Whether that satisfies the
rule is measured by PJ06, not here.

## Acceptance

- The shared tool is one implementation. An agent's difference is configuration or a strategy, and
  each agent's case is covered by a test.
- The pinned-digest check runs on every push wherever it runs today, not only in the update job.
- **Every replaced check is proven against the failure it exists for**: that failure is reproduced
  and the new check fails on it. A rewrite that passes everything proves nothing.

## Decided with the agent repositories, 2026-09-13

Both were put to the agent that would live with the result, and both answers came with the
measurement behind them.

- **Commands, not a Maven plugin.** The update job is not a lifecycle phase - it runs on a schedule,
  writes files and opens a pull request - and the changelog check takes its base and head from the
  workflow event rather than from the build. The two tools that do run inside a build today are
  already called through `exec-maven-plugin`, which calls a Java command class just as well. One
  command shape serves both callers; a plugin would add a descriptor and a plugin test harness for no
  case a command cannot cover.
- **A separate artifact, not inside `sokar-machines`.** Measured on an agent repository's tool
  classpath: `sokar-machines` brings an SSH stack - `sshj` and BouncyCastle. The build-time tools run
  in every package build, offline ones included, and folding them in would put an SSH client on the
  classpath of a changelog check and tie the release tooling's version to the machine tooling's.

**A correction to what the pin check becomes**, measured from the scripts: only part of it is a unit
test. Where it compares the pinned digest with what the vendor publishes it needs the network, and
that half stays where it runs today - on every push - and moves into the shared tool, not only into
the update pipeline. In `sokar-pi` it also checks the Node runtime the built
tree reports, which is a check after the tree is built, in the same Maven run. **Built the same day:** the offline half is a unit test in all
three repositories, counter-tested by switching each check off in turn, and `sokar-pi` no longer has
the script at all.

## Open questions

1. **The order.** The two byte-identical tools first is the obvious start, because they need no
   strategy and prove the publishing path.
2. **Whether `e2e-tier1.sh` belongs here or to B27**, which is already moving what a person does
   into the Java suite.
