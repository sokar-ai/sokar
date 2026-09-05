# 0047 — Separate Repositories And Continuous Integration

**Status:** **both legs are green.** Tier 1 passes 26 of 26 on a hosted Ubuntu runner and 26 of
26 on a Fedora server with SELinux enforcing, provisioned on demand and destroyed afterwards. The
gap this document said could never be closed on a hosted runner is closed by
[0048](0048-Fedora-Test-Server-Snapshot.md). The repository split has not started.

Two changes that only make sense together: the agents move into repositories of their own,
and a push starts building and testing what is today built and tested by hand on two VMs.

Everything is verified by hand right now - VMs started when needed, binaries copied in over
ssh, suites run from a shell. That is why the rule "always run the tests on both VMs before
committing" exists, and it holds only for as long as one person remembers it. Splitting the
agents out without CI first would multiply the hand work by the number of agents.

## Acceptance

- A push to any of the repositories builds it and runs its unit suite.
- A push runs the **tier 1** acceptance suite on a real machine with podman, nftables and
  dnsmasq - not a mock, and not a stub.
- **Tier 2** runs where a credential exists, and never for a pull request from a fork.
- An agent builds in its own repository against a **published** SPI, with no checkout of this
  one, and the package it produces installs against a `sokar` binary it was never built with.
- Jars are published to Sonatype; `.deb` and `.rpm` to Artifactory.
- What is **not** covered by CI is written down rather than assumed.

---

## 1. The measurement that comes first

**Does `buildtools/e2e-tier1.sh` run on a GitHub-hosted Ubuntu runner?**

Nothing below is worth starting until this is answered, because it decides whether "CI runs
what the VMs run" is achievable at all or whether CI is reduced to unit tests. In rough order
of how likely each is to bite:

- **`/run/user/<uid>` may not exist.** Actions runs as a service account rather than a login
  session, and that directory is tied to sessions - a fact already recorded here because it
  cost time once. Task state lives there.
- rootless podman plus `nsenter` into a container's network namespace to run `nft`
- whether Ubuntu's `dnsmasq` carries the `--nftset` support the egress filter depends on
- per-user OCI hook registration under `~/.config/containers`
- unprivileged user namespaces enabled on the runner

Half a day, in this repository, changing nothing else. The outcome is either "CI can run the
real suite" or a different plan.

## 2. What CI covers, and what it does not

| | where | when | why there |
|---|---|---|---|
| build + unit tests | GitHub-hosted | every push and pull request | free, no secrets, so a fork's pull request gets it |
| **acceptance suite** | **two rented machines, in parallel** | merged to `main`, and manual dispatch | needs SELinux enforcing and podman 4, which no hosted runner has |
| tier 2 | not wired up | | needs a provider credential and spends money per run |
| **release build and publish** | GitHub-hosted | on a tag | an artifact that is published must not come from a machine rented for ten minutes |

**The rented legs are gated to `main` on purpose.** Each one rents a machine, so running them on
every push to a branch would spend money on every revision of work in progress - and a fork's
pull request cannot see the secrets in any case. What guards a pull request is the build and unit
tests, which is why that job has to be worth having on its own.

Tier 2 is gated for two independent reasons: secrets do not reach pull requests from forks, so
it could not work there; and every run spends real API credits.

### Both acceptance legs are rented, and that was measured rather than assumed

| | total | of which native-image |
|---|---|---|
| hosted runner, 2 cores | **858 s** | 634 s |
| rented `cpx42`, 8 cores | **320 s** | - |

The rented machine is 2.7 times faster *including* creating and destroying itself. Two cores is
the whole difference. It also means the build runs at full optimisation rather than `-Ob`, so a
pull request and `main` produce the same binary - closing a gap this document opened earlier.

**Neither leg is redundant.** Fedora is the only place SELinux enforcing is tested at all. Ubuntu
24.04 carries **podman 4**, and the git gate was firewalled off for every user on that release -
a defect neither development VM could see, because both run podman 5.

### Fedora and SELinux are not covered by a hosted runner, and that is stated rather than hidden

**A hosted runner cannot run SELinux in enforcing mode.** The runners are Ubuntu with AppArmor,
SELinux cannot be turned on from inside a container, and nested virtualisation is not
dependably available.

That costs more here than it would elsewhere: enforcing mode is where four real defects were
found, none of which reproduced on Ubuntu - `nft` unable to read a file, a socket labelled
after creation instead of before, a missing `connectto`, and `podman unshare` running as
`container_runtime_t`.

**Fedora with SELinux enforcing is therefore tested locally only, before a release, and the
README says so.** A green badge does not mean that platform passed.

### How the Fedora leg gets covered: an on-demand box, driven over SSH

Not a self-hosted runner, and not a nested VM. A GitHub-hosted job acts as **controller**: it
creates a Hetzner Cloud server, runs the suite on it over SSH, and destroys it.

```
GitHub-hosted job (ubuntu-latest)
  → hcloud: create server from a prepared snapshot
  → ssh: run buildtools/e2e-tier1.sh, stream the output back
  → hcloud: delete the server            ALWAYS, even on failure
```

**Why not a self-hosted runner.** There is no agent to install, register, patch or trust, no
long-lived machine holding a token, and nothing accumulates between runs - podman images, a
warm `~/.m2`, stale `/run/user/<uid>/sokar` state. Every one of those has already produced a
confusing failure in this project, and an ephemeral machine cannot have them. The cost is that
the Actions UI shows one long SSH step instead of named ones, which the suite's own output
makes tolerable.

**Why not nested QEMU.** Nesting is what forces bare metal - on AWS, nested virtualisation
exists only on `.metal` instances. It buys a matrix of many operating systems from one host,
and this needs exactly one. An ordinary virtualised VM gives real SELinux: measured on the
development VM, `systemd-detect-virt` says `kvm` and `getenforce` says `Enforcing`. SELinux is
a guest-kernel property; virtualisation is irrelevant to it. What a hosted runner lacks is not
hardware but **the choice of OS image**.

**Sizing**, from measurement rather than estimate:

| | value | why |
|---|---|---|
| vCPU | **8** | native-image is the entire cost - 17m26s for seven binaries on GitHub's 2 cores; the same `sokar` binary takes 3m43s there and ~35s on a 16-core machine |
| RAM | **16 GB** | GraalVM takes ~80% of RAM for a build and used 6.29 GB for one binary. The development VM's 3 GB is too small to build at all |
| disk | **80 GB** is ample | GraalVM 733 MB, musl toolchain 243 MB extracted, build output 231 MB, `~/.m2` 1-2 GB, podman storage already 687 MB after a few runs with task images at ~500 MB each |

A Hetzner **CPX42** (8 vCPU, 16 GB, 320 GB) at **€0.1335/hour** fits with room to spare.

**Cost, which is what makes this worth doing at all:**

| | |
|---|---|
| one run, 15 minutes | **€0.033** |
| 20 runs per month | €0.67 |
| 100 runs per month | €3.34 |
| **left running by mistake** | **€97/month** |

So the whole design rests on one thing: **the server is always destroyed.** The delete step runs
on failure and on cancellation, and a scheduled sweep removes anything tagged older than an hour
- a workflow that dies before its cleanup is the realistic way this becomes expensive, not a
decision anyone makes.

**Driven with the Hetzner Cloud Python API**, which is small enough to keep in one script:

```python
from hcloud import Client
from hcloud.images import Image
from hcloud.server_types import ServerType

client = Client(token=..., application_name="sokar-ci")
response = client.servers.create(
    name="sokar-ci-<run id>",
    server_type=ServerType(name="cpx42"),
    image=Image(name="<the prepared snapshot>"),
)
...
response.server.delete()      # BoundServer.delete(), and it must always run
```

**A prepared snapshot, not a stock image.** An 8-core box builds in four or five minutes, but a
stock Fedora needs podman, GraalVM, the musl toolchain and a warm `~/.m2` first - ten minutes of
provisioning to save twelve of building. A snapshot with all of it costs about €0.25/month and
brings boot-to-ready to roughly a minute. Refreshing it then becomes a periodic chore of the
same kind as [0044](0044-Automated-Agent-Updates.md).

**When it runs:** `main` and manual dispatch, not every push. Ubuntu stays on free hosted
runners, which already pass 22 of 24 checks. A few runs a week puts this under a euro a month
for the one gap that cannot be closed any other way.

**Two things that will bite on any machine that is not a GitHub runner:**

- **`loginctl enable-linger` for the user the suite runs as.** A process started by a service is
  not a login session, so `/run/user/<uid>` may not exist - and that is where task state lives.
  It happened to exist on GitHub's runner; that is luck, not a guarantee.
- **`subuid`/`subgid` entries**, or rootless podman does not start at all.

## 3. What has to change before an agent can leave

Four things, all measured rather than assumed.

**The cut itself is already clean.** Nothing in the core depends on an agent artifact, and
`dist-deb` and `dist-rpm` reference no agent path - the agents package themselves. That part of
the design held.

**But the published surface is four artifacts, not one.** `agents/README.md` says "The only
dependency is `sokar-agent-api`". It is not:

```
sokar-agent-api → sokar-core, sokar-clearance (+ snakeyaml, dbus-java, slf4j)
sokar-wire
sokar-core  test-jar, test scope
```

**None of that is earned.** The SPI references exactly three types outside itself:

```
org.fuin.sokar.wire.Json
org.fuin.sokar.clearance.varlink.VarlinkClient
org.fuin.sokar.clearance.varlink.VarlinkServer
```

| artifact | why it is in the closure | used? |
|---|---|---|
| `sokar-wire` | `Json` | yes - five classes, and no dependencies of its own |
| `sokar-clearance` | `VarlinkClient`, `VarlinkServer` | yes, four of its thirteen classes |
| `sokar-core` | declared `compile` in `agents/api/pom.xml` | **no** - not referenced in main or test |
| `dbus-java-core`, `dbus-java-transport-native-unixsocket`, `slf4j-api` | transitive through `sokar-clearance` | **no** |

### Making the SPI stand alone is one module move

`sokar-clearance` holds two unrelated things, and the SPI needs only the smaller one:

```
clearance/
├── ClearanceHub, ClearancePrompt, DesktopPrompt, Verdict, …   desktop prompts, needs dbus
└── varlink/  VarlinkClient, VarlinkServer, VarlinkConnection, VarlinkException
```

**`varlink/` is four classes and contains no dbus reference at all.** The D-Bus stack comes
entirely from the desktop half, which no agent touches - so today an agent adapter carries D-Bus
on its compile classpath for nothing, and `AgentIsolationTest` cannot see it because that rule is
about names rather than about what the SPI drags along.

Two edits:

1. **Delete the `sokar-core` dependency from `agents/api/pom.xml`.** Declared and unused; free.
2. **Move `clearance/…/varlink/` into `sokar-wire`.** That is the natural home: `wire` is already
   the transport-and-encoding module, it has no dependencies of its own, and `Json` - the other
   thing the SPI needs - is there. Three consumers move with it: `agents/api`, `app`, and
   `clearance` itself. A package move and import updates, checked by the compiler, not a redesign.

What an out-of-repository agent then resolves:

```
before   sokar-agent-api, sokar-core, sokar-clearance, sokar-wire,
         dbus-java-core, dbus-java-transport-native-unixsocket, slf4j-api, snakeyaml
after    sokar-agent-api, sokar-wire, snakeyaml
```

**Do it before the split, not after.** Today it is one package move inside one reactor with a
compiler checking it. Once two agents live in their own repositories pinned to a released SPI,
the same move is a breaking change to a published artifact plus a coordinated release across
three repositories. It is also right independently of the split.

**The `test-jar` dependency is unused and should go first.** Neither agent's tests import
anything from `org.fuin.sokar.core`. Deleting it removes a cross-repository test-jar dependency,
which is the worst thing on that list.

**The test-jar must not reach Sonatype, and nine modules use it.** It is produced by `core` and
consumed across the reactor, so it cannot simply be dropped. The clean form is a module of its
own - shared test fixtures as an ordinary jar with `maven.deploy.skip`, and `core` stops
producing a test-jar. Nine POMs change one dependency each; mechanical, but not nothing.

## 4. Publishing

**Jars to Sonatype.** Only what an out-of-repository agent actually needs. Two prerequisites:

- **`flatten-maven-plugin`**, `oss` mode, in the main build rather than in a release profile, so
  what lands in `~/.m2` is what gets deployed. The reference is `jtenman/pom.xml` in a sibling
  checkout, which also explains why: flattening writes resolved versions inline, so the parent
  and aggregator POMs need not be published at all.
- **The root POM has no `<name>`**, which Sonatype requires. `description`, `scm` and `url` are
  there; `licenses` and `developers` come from the `org.fuin:pom` parent.

**Packages to Artifactory**, with the approach already worked out in the planning document:
`artifactory-maven-plugin` rather than the JFrog CLI, so `mvn deploy` records build-info and a
binary can be traced to the build that produced it. Two dedicated repositories, because Debian
and RPM repositories are separate typed repositories and one key cannot serve both -
`sokar-dist-deb` and `sokar-dist-rpm` already exist as modules. Debian needs matrix parameters
(`deb.distribution`, `deb.component`, `deb.architecture`) or Artifactory stores the file and
never indexes it, silently. Packages are signed. The deploy token is scoped and lives in CI.

**Agents pin a released SPI, never a snapshot.** This is the one point worth arguing about,
because it is the failure this organisation has already hit: downstream CI resolving a snapshot
that had vanished upstream while a local `~/.m2` still held a copy. Agents now carry their own
version line, so depending on a released API is the shape the design already wants.

## 5. The order

1. **Spike** - tier 1 on a hosted runner, in this repository, nothing else moved.
2. **CI for this repository**, whatever the spike allows.
3. **Shrink the published surface** - drop the unused `sokar-core` dependency, move `varlink`
   into `sokar-wire`, delete the unused test-jar dependency, extract the shared fixtures, add
   `<name>`, add `flatten-maven-plugin`.
4. **Publish the SPI and its closure** at a real version.
5. **Split one agent** - Claude Code, alone - and prove it builds and packages against the
   published SPI with no checkout of this repository.
6. **Split Pi.** Second on purpose: it ships a 70 MB npm tree built by `npm ci` inside a pinned
   container, so its CI needs podman and a much longer build. One hard problem at a time.
7. **Then [0044](0044-Automated-Agent-Updates.md)**, which gets easier: one repository per agent
   is a natural unit for an update workflow.

## What CI proves, and what only a VM can

Measured on a hosted runner, 2026-09-05, rather than predicted. **Every unknown in section 1
came back positive** and tier 1 ran: 22 of its 24 checks passed on a machine that had never
seen this project.

### What a hosted runner covers

- **The image is built correctly** - the agent contributes a layer, the CLI is installed, the
  installed version *matches the pin*, and it runs as an unprivileged user.
- **The container is hardened** - `NoNewPrivs` set, every capability dropped, the nft hook fires
  at create time, and egress to an undeclared address is denied.
- **The credential never enters the container** - a phantom token is injected, the proxy socket
  is mounted, a request through it reaches the real provider and comes back rejected, and the
  container holds no credential.
- **Failures clean up** - a task that cannot build its image leaves no helper processes and no
  pid files claiming live ones.

That is the containment story, and it is most of what matters.

### What it does not cover

| gap | why | fixable in CI? |
|---|---|---|
| ~~**SELinux enforcing**~~ | ~~hosted runners are Ubuntu/AppArmor~~ | **covered** by an on-demand Fedora server - [0048](0048-Fedora-Test-Server-Snapshot.md) |
| **the git gate** | the push hung on the runner and the cause is not yet known | unknown |
| **package installation** | CI copies binaries into `~/.local/bin`; no `.deb` or `.rpm` is built, and nothing runs `dpkg -i` or `rpm -i` against a `sokar` it was not built with | yes, not wired up |
| **tier 2** | needs a real credential and spends provider credits per run | yes, with a secret, on `main` and dispatch only |
| **glibc coupling** | `sokar` and `sokard` are dynamically linked, so building on Ubuntu 24.04 sets a floor nothing checks | yes, by testing an older base |

**SELinux is the expensive one.** Four real defects were found there and **none reproduced on
Ubuntu**: `nft` unable to read a file, a socket labelled after creation instead of before, a
missing `connectto`, and `podman unshare` running as `container_runtime_t`. A green badge says
nothing about that platform.

### A gap introduced on purpose

Native-image is built with `-Ob` on every branch except `main`, because it roughly halves a
build that otherwise takes seventeen of a twenty-two minute run on two cores. So **a pull
request tests a less optimised binary than the one that ships**, and only `main` builds fully.
For behaviour that is a sound trade; it does mean a green PR is a slightly weaker statement than
a green `main`, and that is worth knowing rather than discovering.

### The division, stated plainly

CI answers *is the containment intact and does the plumbing work*, on every push. The VMs answer
*does it hold under SELinux, does it install as a package, does the gate work* - and stay a
pre-release gate rather than a formality. This reduces the manual work; it does not remove it.

## What the two legs proved, 2026-09-05

| | |
|---|---|
| hosted Ubuntu runner | **26 of 26** |
| on-demand Fedora, SELinux enforcing | **26 of 26** |

**And they were not redundant.** Bringing CI up found two defects that had nothing to do with CI:

- **The git gate was firewalled off for every user on podman 4**, which is what Ubuntu 24.04 LTS
  ships. Sokar assumed a container reaches its host at pasta's `169.254.1.2`; podman 4 answers
  with the host's own LAN address, so the rule opened an address nothing dialled and every push
  hung until it timed out. Found on a hosted runner and on neither development VM - both run
  podman 5.
- **The musl toolchain was an 89 MB unverified download** from a single small host, with no
  checksum, and it links the binaries that enforce the firewall. Now pinned by digest, with the
  mirror interchangeable because the digest is what is trusted.

### What replaces "always run the tests on both VMs before committing"

That rule was written when the VMs were the only way to run the suite at all. It cannot survive
them being switched off, and it was already the wrong shape: a build triggered by a push does not
protect a commit that has already been made.

**The rule is now:**

- **Before committing**, run the unit suite. It is seconds, it catches most things, and it is the
  only check that happens before history is written.
- **The acceptance suite is CI's job**, on `main`, on two machines that are more faithful than the
  VMs were - one with SELinux enforcing and Sokar's policy loaded, one with podman 4.
- **Before a release**, run it deliberately: `workflow_dispatch`, or `remote-tier1.py` by hand.
- **Keep a development VM for debugging, not for gating.** When a leg fails, a VM is where it gets
  diagnosed in seconds rather than in ten-minute cycles. That is a convenience, and nothing should
  depend on it existing.

**What this loses, stated plainly.** A commit can now reach `main` without the acceptance suite
having run on it - the suite runs *after* the merge, not before. That is a real weakening compared
to the old rule when the old rule was followed, and it is the price of the rule being followed at
all. If it starts to bite, the answer is a pull request gate rather than a habit: run the rented
legs on a pull request from this repository, which costs about two cents per revision.

## To be checked

- ~~The spike in section 1.~~ **Answered:** it runs, and the acceptance suite now runs on rented
  machines rather than hosted ones because they are 2.7 times faster.
- **What happens to the isolation rules.** `AgentIsolationTest` gets stronger for the core once
  `agents/` is gone - nothing left to exempt - but the rule that an agent must not depend on
  another agent loses its subject. It has to move into each agent repository or into a shared
  parent POM.
- Whether every agent repository should share a parent POM, and whether that parent is published
  or vendored.
- Whether a release is cut per repository or in lockstep, and what `AgentProtocol.VERSION` means
  once the two sides ship on different schedules.

## Notes

The workflow shape to copy is
`jenkins-update-center-generator-maven-plugin/.github/workflows/maven.yml` in a sibling checkout.
It uses an ordinary JDK; this needs GraalVM, which `setup-java` can install.
