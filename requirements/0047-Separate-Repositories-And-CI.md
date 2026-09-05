# 0047 — Separate Repositories And Continuous Integration

**Status:** open, and blocked on one measurement before any of it is worth starting.

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

| | where | when |
|---|---|---|
| build + unit tests | `ubuntu-latest` | every push and pull request |
| tier 1 | `ubuntu-latest` | every push and pull request |
| tier 2 | `ubuntu-latest`, credential from a repository secret | `main` and manual dispatch only |
| **Fedora, SELinux enforcing** | **nowhere** | see below |

Tier 2 is gated for two independent reasons: secrets do not reach pull requests from forks, so
it could not work there; and every run spends real API credits.

### Fedora and SELinux are not covered, and that is stated rather than hidden

**A hosted runner cannot run SELinux in enforcing mode.** The runners are Ubuntu with AppArmor,
SELinux cannot be turned on from inside a container, and nested virtualisation is not
dependably available.

That costs more here than it would elsewhere: enforcing mode is where four real defects were
found, none of which reproduced on Ubuntu - `nft` unable to read a file, a socket labelled
after creation instead of before, a missing `connectto`, and `podman unshare` running as
`container_runtime_t`.

**Fedora with SELinux enforcing is therefore tested locally only, before a release, and the
README says so.** A green badge does not mean that platform passed.

**TODO, not scheduled:** spawn a dedicated AWS VM from the workflow for this leg, most likely
provisioned with Terraform, so it runs on demand rather than on somebody's laptop.

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

## To be checked

- The spike in section 1, which gates everything.
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
