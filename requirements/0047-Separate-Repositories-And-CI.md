# 0047 — Separate Repositories And Continuous Integration

**Status:** **both legs are green.** Tier 1 passes 26 of 26 on a hosted Ubuntu runner and 26 of
26 on a Fedora server with SELinux enforcing, provisioned on demand and destroyed afterwards. The
gap this document said could never be closed on a hosted runner is closed by
a prepared snapshot per leg. The repository split has not started.

Two changes that only make sense together: the agents move into repositories of their own,
and a push starts building and testing what is today built and tested by hand on two VMs.

Everything is verified by hand right now - VMs started when needed, binaries copied in over
ssh, suites run from a shell. That is why the rule "always run the tests on both VMs before
committing" exists, and it holds only for as long as one person remembers it. Splitting the
agents out without CI first would multiply the hand work by the number of agents.

## Acceptance

- A push to any of the repositories builds it and runs its unit suite.
- A push runs the **tier 1** acceptance suite on a real machine with real podman, nftables and
  dnsmasq - the containment is never mocked. (The *agent* inside it may be the stub below; the
  machinery around it may not.)
- **Tier 2** runs where a credential exists, and never for a pull request from a fork. It is
  switchable from the build settings, and a leg switched off says so rather than passing quietly.
- The core keeps an acceptance suite **after every agent has left**: a stub agent, built here,
  that exercises the agent API, the firewall and the clearance path without downloading a vendor CLI.
- An agent builds in its own repository against a **published** agent API, with no checkout of this
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
| tier 2 | **belongs to the agent**, not here | | it tests an agent against a provider, and the agents are leaving |
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

**None of that is earned.** The agent API references exactly three types outside itself:

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

### Making the agent API stand alone is one module move

`sokar-clearance` holds two unrelated things, and the agent API needs only the smaller one:

```
clearance/
├── ClearanceHub, ClearancePrompt, DesktopPrompt, Verdict, …   desktop prompts, needs dbus
└── varlink/  VarlinkClient, VarlinkServer, VarlinkConnection, VarlinkException
```

**`varlink/` is four classes and contains no dbus reference at all.** The D-Bus stack comes
entirely from the desktop half, which no agent touches - so today an agent adapter carries D-Bus
on its compile classpath for nothing, and `AgentIsolationTest` cannot see it because that rule is
about names rather than about what the agent API drags along.

Two edits:

1. **Delete the `sokar-core` dependency from `agents/api/pom.xml`.** Declared and unused; free.
2. **Move `clearance/…/varlink/` into `sokar-wire`.** That is the natural home: `wire` is already
   the transport-and-encoding module, it has no dependencies of its own, and `Json` - the other
   thing the agent API needs - is there. Three consumers move with it: `agents/api`, `app`, and
   `clearance` itself. A package move and import updates, checked by the compiler, not a redesign.

What an out-of-repository agent then resolves:

```
before   sokar-agent-api, sokar-core, sokar-clearance, sokar-wire,
         dbus-java-core, dbus-java-transport-native-unixsocket, slf4j-api, snakeyaml
after    sokar-agent-api, sokar-wire, snakeyaml
```

**Do it before the split, not after.** Today it is one package move inside one reactor with a
compiler checking it. Once two agents live in their own repositories pinned to a released
contract,
the same move is a breaking change to a published artifact plus a coordinated release across
three repositories. It is also right independently of the split.

### Done, and the one thing the analysis missed

**The closure is now three artifacts**, measured with `dependency:tree` rather than predicted:

```
org.fuin.sokar:sokar-agent-api
+- org.yaml:snakeyaml
+- org.fuin.sokar:sokar-wire
\- org.jspecify:jspecify
```

`sokar-core`, `sokar-clearance`, both `dbus-java` artifacts and `slf4j-api` are gone.

**What the analysis got wrong: `VarlinkServer` reaches into `sokar-core`.** The audit above
established that `varlink/` contains no D-Bus reference, which is true, and concluded the move
was a package rename. It is not: `VarlinkServer` calls `SocketContext.openUnixSocket()` from
`org.fuin.sokar.core.hardening`, so moving `varlink` into `wire` alone would have replaced a
dependency on `sokar-clearance` with one on `sokar-core` - undoing the edit it was paired with.

`SocketContext` moved too, to `org.fuin.sokar.wire`. That is the right home independently: it
labels a unix socket so a container may connect to it, which is transport, and it imports
nothing but the JDK, so `wire` keeps its zero-dependency guarantee. Its other three callers
(`supervisor`, `vault`, `app`) already resolved it transitively or now declare `sokar-wire`.

**And a second one: `VarlinkInteropTest` used core's process runner** to drive `varlinkctl`.
`core` depends on `wire`, so carrying that test across would have been a cycle. It now runs the
process itself - about twenty lines - which is the correct shape for a test in a module whose
whole point is having no dependencies.

**The lesson.** An import audit that reads the *package* misses what a *class* calls. Both
findings were the compiler's, in seconds, inside one reactor - which is the argument for doing
this before the split rather than after, made concrete.

**The `test-jar` dependency is unused and should go first.** Neither agent's tests import
anything from `org.fuin.sokar.core`. Deleting it removes a cross-repository test-jar dependency,
which is the worst thing on that list.

**The test-jar must not reach Sonatype, and nine modules use it.** It is produced by `core` and
consumed across the reactor, so it cannot simply be dropped. The clean form is a module of its
own - shared test fixtures as an ordinary jar with `maven.deploy.skip`, and `core` stops
producing a test-jar.

**Done, and the fixture turned out to be one class.** `sokar-testing` now holds
`FakeCommandRunner`, in `org.fuin.sokar.testing` rather than in `core`'s package - a jar owning
a package another artifact also owns is the kind of thing that reads as a mistake later. `core`
no longer produces a test-jar at all, and nothing in the reactor asks for one.

Not nine modules but six, and one of those was carrying a dependency it never used: `clearance`
declared the test-jar and imports nothing from it. `core`'s own tests do not use the fixture
either, so `sokar-testing` can depend on `core` without a cycle - which is what makes it an
ordinary jar rather than a second test-jar.

**Also done: `<name>` and `flatten-maven-plugin`.** The audit said the *root* POM lacks the
`<name>` Central requires. True, but not sufficient: **`<name>` is not an inherited element**, so
a root-only fix leaves every deployed artifact without one. Eighteen modules now declare theirs,
and the flattened `sokar-agent-api` POM was checked rather than assumed - it carries name,
description, url, licences, developers and scm, has no `<parent>`, and lists its three
dependencies at resolved versions.

**And the url that flattening exposed is fixed.** Maven appends each child's artifactId to an
inherited `url` and `scm`, so `sokar-agent-api` advertised
`github.com/fuinorg/sokar/sokar-agents/sokar-agent-api/` - a path that does not exist, in every
published POM. Maven 3.6.1 added attributes that turn the appending off, and the root now
carries all four.

**They are not all in the same place, which is the part that wastes an hour.**
`child.scm.url.inherit.append.path`, `child.scm.connection.inherit.append.path` and
`child.scm.developerConnection.inherit.append.path` are fields of `Scm`, so they go on `<scm>`.
`child.project.url.inherit.append.path` is a field of `Model`, so it goes on **`<project>`** -
putting it on `<url>`, where it reads naturally, is silently ignored: the scm three take effect
and the project url keeps appending, which looks like the feature half-working. `javap` on
`maven-model`'s `Model.class` settles it in one command. Every module's flattened POM now says
`https://github.com/fuinorg/sokar/`.

## 4. Publishing

**Jars to Sonatype.** Only what an out-of-repository agent actually needs. Two prerequisites:

- **`flatten-maven-plugin`**, `oss` mode, in the main build rather than in a release profile, so
  what lands in `~/.m2` is what gets deployed. The reference is `jtenman/pom.xml` in a sibling
  checkout, which also explains why: flattening writes resolved versions inline, so the parent
  and aggregator POMs need not be published at all.
- **The root POM has no `<name>`**, which Sonatype requires. `description`, `scm` and `url` are
  there; `licenses` and `developers` come from the `org.fuin:pom` parent.

**Packages to Artifactory, with the JFrog CLI** - reversing an earlier decision. The planning
document chose `artifactory-maven-plugin` over the CLI so that `mvn deploy` would record
build-info. That reasoning holds for Maven artifacts and not for these: `dist-deb` and
`dist-rpm` are `pom`-packaged modules that drop a file in `target/`, and a Debian repository
needs a PUT to a pool path carrying `deb.distribution`, `deb.component` and `deb.architecture`
as **matrix parameters**. The Maven plugin deploys into a Maven layout and has no notion of
them, so the package would be stored and never indexed. `jf rt upload --target-props` sets them,
and `jf rt build-publish` still records build-info - which was the actual reason the plugin was
preferred, and it is not lost.

**Build-info is not published, for now.** `jf rt build-publish` writes into a separate internal
repository, `artifactory-build-info`, and a token scoped to the two distribution repositories has
no permission there - measured, as a 403 after both uploads had already succeeded. Build-info was
the stated reason for preferring the CLI over the Maven plugin, so this is a loss rather than a
tidy-up: it can be restored by granting `github-build` deploy permission on that repository, and
until then the traceability from an artifact back to its build is only what the file name and the
repository's own timestamps carry.

**`--flat=true` is not optional.** Without it `jf rt upload` carries the *source* directory into
the target, so `dist-deb/target/*.deb` uploaded to `pool/main/s/sokar/` lands at
`pool/main/s/sokar/dist-deb/target/...`. It uploads successfully and reports success. Found in
the agent repository, where the same mistake put the package under `.../sokar-agent-claude/target/`.

**`jf rt build-collect-env` is deliberately not used.** It publishes environment variables into
build-info, and its default exclusion pattern - `*password*;*psw*;*secret*;*key*;*token*` -
does not match `OSS_SONATYPE_GPG_PASSPHRASE`, which is in scope in that job. The git commit from
`build-add-git` is the traceability that was wanted; the environment is not. Two dedicated repositories, because Debian
and RPM repositories are separate typed repositories and one key cannot serve both -
`sokar-dist-deb` and `sokar-dist-rpm` already exist as modules. Debian needs matrix parameters
(`deb.distribution`, `deb.component`, `deb.architecture`) or Artifactory stores the file and
never indexes it, silently. Packages are signed. The deploy token is scoped and lives in CI.

### Built, 2026-09-06

The release machinery is already in `org.fuin:pom:2.0.2` - a `central-sonatype-release` profile
carrying `maven-gpg-plugin` and `central-publishing-maven-plugin` against a
`central-sonatype` server id. Sokar inherits it, so what was missing was the three things around
it, taken from `cqrs-4-java`, which publishes the same way:

- **A `settings.xml` in the repository.** CI passes `-s settings.xml`, so a developer's own
  `~/.m2/settings.xml` is never what makes a build work. It holds the `central-sonatype` server
  reading `OSS_SONATYPE_USERNAME` / `OSS_SONATYPE_TOKEN` from the environment, the
  `gpg.passphrase` the profile expects, and the snapshot repository. Nothing secret is in it.
- **An allow-list, not a deny-list.** `maven.deploy.skip` is **true in the root**, and exactly
  two modules turn it off: `sokar-agent-api`, which an agent in its own repository compiles
  against, and `sokar-wire`, which its POM names. Verified rather than assumed by deploying to a
  `file://` repository: seventeen modules printed *Skipping artifact deployment* and two
  artifacts landed.
- **A `deploy` job** on a hosted runner, on pushes to `main`, `needs: [build, tier1]`. Publishing
  after the acceptance suite costs nothing that is not already being spent, and a snapshot that
  fails tier 1 is worse than no snapshot.

**What the dry run caught: no sources and no javadoc.** The fuin parent declares
`maven-source-plugin` and `maven-javadoc-plugin` in `<pluginManagement>` only, so a module that
does not name them ships a bare jar - which Central accepts as a snapshot and **rejects as a
release**. That would have surfaced at the first real release rather than now. Both published
modules name them, and a deploy now produces jar, sources, javadoc and a parent-less flattened
POM. `maven-gpg-plugin` was confirmed to reach all four: run with the profile and no key, it
fails with *no default secret key* after reporting *signing 4 files*.

**Both published jars declare `Automatic-Module-Name`** - `org.fuin.sokar.wire` and
`org.fuin.sokar.agent.api`. Neither had one, and neither carried OSGi headers either: the fuin
parent manages `maven-bundle-plugin` but does not bind it, so nothing was writing a manifest
beyond `Created-By`. Left alone, a modular consumer gets a name derived from the *file name* -
`sokar.wire`, `sokar.agent.api` - which matches no package and moves if the artifactId ever
does. Stating it costs one manifest entry and fixes it before anyone can depend on the derived
one.

### The published binary is the one the suite tested

A first attempt published from a hosted runner, building everything there. It failed, and the
failure was worth having: the runner has no musl cross-compiler, so the three statically linked
hooks could not be built at all. `install-musl.sh` would have fixed it in a line and left two
worse things in place.

**The binary that shipped was never the binary that was tested.** Tier 1 built on Hetzner and
exercised that; the publish built its own from the same source and shipped that instead. Same
sources, different artifacts, and nothing checked that the second behaved like the first.

**And it cost fifteen minutes** of a two-core runner, most of it native-image, for a build that
had already been done twice on eight-core machines minutes earlier.

So the ubuntu acceptance leg now brings its binaries home - `remote-tier1.py --fetch`, streamed
back through the existing ssh as a tar, **after** the suite passes and before the server is
destroyed - and the publish job downloads them and only packages. No native-image on a hosted
runner, no musl toolchain, and what an operator installs is what tier 1 ran.

**Only the ubuntu leg, and that is not arbitrary.** Both packages carry identical bytes, so
there is exactly one binary to get right, and a native image links glibc dynamically: built on
Fedora 44 it will not start on Ubuntu 24.04, while the reverse runs on both. The older baseline
is the one to ship. Fedora stays the leg that proves SELinux, not the leg that compiles.

Two things this changed elsewhere. The remote build was `-pl app,hooks,agents/claude` and never
built `daemon` - which both packages install, so it had been coming from the hosted build alone.
And an agent repository building on `ubuntu-latest` has the same glibc exposure with no warning
when the label moves, so `sokar-claude-code` now pins `ubuntu-24.04`.

**This reverses a decision recorded above**, that publishing must not come from a machine rented
for ten minutes and destroyed. The trade is deliberate: that machine's verdict already decides
whether the release is good, so trusting its output is a smaller step than it first appears -
and shipping an untested artifact to avoid it was the larger risk.

**Still needed before this runs green:** four repository secrets -
`OSS_SONATYPE_USERNAME`, `OSS_SONATYPE_TOKEN`, `OSS_SONATYPE_GPG_PRIVATE_KEY`,
`OSS_SONATYPE_GPG_PASSPHRASE`. A missing one fails the job rather than publishing an unsigned
artifact, which is the behaviour to want.

### The first run published everything, 2026-09-06

The allow-list above did not hold. Run 92160991865 uploaded all nineteen modules to
central-snapshots, root aggregator and RPM package included.

`maven.deploy.skip` is read by `maven-deploy-plugin`, and under `-Pcentral-sonatype-release`
that plugin never runs. `central-publishing-maven-plugin` is declared with
`<extensions>true</extensions>`, and its `DeployLifecycleParticipant` rewrites every module's
model at session start: where it finds no pre-existing publish binding it injects its own
`publish` goal on the `deploy` phase - the log says *Installing Central Publishing features*
and then names 19 modules. `maven-deploy-plugin` appears nowhere in that log, so the property
that was supposed to be the allow-list was never consulted. Its own switch is `skipPublishing`,
checked per artifact in `PublishMojo.processSnapshot`, alongside `excludeArtifacts` - a list of
artifactIds. The root POM now sets `skipPublishing` true and the same two modules set it false,
and both properties carry a one-line comment naming which plugin reads which.

`skipPublishing` is the right lever rather than `excludeArtifacts` for the same reason
`maven.deploy.skip` was: a list of what not to publish has to be edited whenever a module is
added, and a module added and forgotten gets published. It is also safe at the end of the
reactor - the mojo runs in every module either way, so the last one still performs the upload.

**Why the local verification missed it.** It deployed with a plain
`mvn deploy -DaltDeploymentRepository=file://...`, without the release profile. That is
`maven-deploy-plugin`, which does honour `maven.deploy.skip` - so seventeen modules printed
*Skipping artifact deployment* and the check passed while exercising a code path CI does not
take. A verification that does not name the profile the real deploy uses verifies a different
build.

The fix was proven on the CI path: `mvn clean deploy -Pcentral-sonatype-release -s settings.xml
-Dgpg.skip=true`, with no credentials. Before, twenty artifacts were staged into
`target/central-deferred`; after, eighteen modules print *Skipping Central Snapshot Publishing
for artifact* and only `sokar-wire` and `sokar-agent-api` are staged. Both runs end at HTTP 401
from central-snapshots, which is as far as this goes without a Sonatype account - the upload
itself is unverified.

**Agents pin a released agent API, never a snapshot.** This is the one point worth arguing about,
because it is the failure this organisation has already hit: downstream CI resolving a snapshot
that had vanished upstream while a local `~/.m2` still held a copy. Agents now carry their own
version line, so depending on a released API is the shape the design already wants.

## 5. The order

1. ~~**Spike** - tier 1 on a hosted runner, in this repository, nothing else moved.~~ **Done.**
2. ~~**CI for this repository**, whatever the spike allows.~~ **Done** - both legs rented, in
   parallel, gated to `main`; hosted runners keep the build and will carry the release.
3. ~~**Shrink the published surface** - drop the unused `sokar-core` dependency, move `varlink`
   into `sokar-wire`, delete the unused test-jar dependency, extract the shared fixtures, add
   `<name>`, add `flatten-maven-plugin`.~~ **Done.** 467 tests green; the agent API resolves three
   artifacts.
4. **Publish the agent API and its closure** at a real version.
5. ~~**Write the stub agent** and move tier 1 onto it.~~ **Done.** This has to happen *before* an agent
   leaves, not after: the moment `agents/claude` is a different repository, the core's own
   acceptance suite has nothing to run, and a suite that cannot run is a suite that quietly
   stops being maintained. It is also the switch tier 2 needs - with the stub as the default,
   turning every real provider off leaves a suite that still proves something.
6. ~~**Split one agent** - Claude Code, alone - and prove it builds and packages against the
   published agent API with no checkout of this repository.~~ **Done**, and removed from this
   reactor. It builds, packages, publishes and runs its own acceptance suite in
   [sokar-claude-code](https://github.com/fuinorg/sokar-claude-code).
7. **Split Pi.** Second on purpose: it ships a 70 MB npm tree built by `npm ci` inside a pinned
   container, so its CI needs podman and a much longer build. One hard problem at a time.
8. **Then [0044](0044-Automated-Agent-Updates.md)**, which gets easier: one repository per agent
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
| ~~**SELinux enforcing**~~ | ~~hosted runners are Ubuntu/AppArmor~~ | **covered** by an on-demand Fedora server booted from a prepared snapshot |
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

Run `92061482762`, both legs on rented machines, in parallel:

| leg | podman | security module | result |
|---|---|---|---|
| Ubuntu 24.04 | **4.9.3** | AppArmor | **26 of 26** |
| Fedora 44 | **5.8.4** | SELinux, enforcing, policy loaded | **26 of 26** |

The podman column is the point. The two development VMs both ran podman 5, so the split
the legs exist to cover was, until this run, asserted rather than observed. It is now in a
build log: the leg that reproduces what Ubuntu 24.04 LTS users actually have is the one on
podman 4, and it is green on a binary that no longer assumes pasta's address.

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

## What the split does to the tests

**Tier 2 goes with the agent.** It asks whether *this agent* can authenticate against *this
provider* - which is the agent's question, not the core's. Wiring it into this repository now
would mean moving it almost immediately, so it stays unwired here and becomes part of whatever
each agent repository runs.

**Tier 1 has a harder problem, and it is not yet answered.** It needs an agent binary and a real
vendor CLI to run at all:

```
AGENT="$ROOT/agents/claude/target/sokar-agent-claude"
PASS  the installed CLI is the pinned version (2.1.236 (Claude Code))
```

Once `agents/` leaves, the core has nothing to test with. Three ways out, and they are not
exclusive:

| | what it buys | what it loses |
|---|---|---|
| a **stub agent** kept in the core | fast, no vendor download, tests exactly what the core owns, no dependency on any agent's release | never notices a real CLI reaching for an undeclared host - which is how the Datadog intake was found |
| the core's CI **pulls a published agent package** | tests the real thing, and proves an agent installs against a core it was not built with | the core's tests then break when an agent's release breaks, which is the coupling the split exists to remove |
| the core keeps **one agent** as a test fixture | simplest | the split is then not a split |

**Decided: a stub agent lives in the core**, and a published agent is used on a release. The stub
is the piece to write before anything moves, because without it the core is untestable the moment
`agents/` leaves.

### The stub is better than a vendor CLI for most of what tier 1 checks

Not merely a cheaper substitute. Today the domain-coverage check depends on **Claude Code
happening to resolve a Datadog intake** - a real finding, but an accident of one vendor's
telemetry in one version, and it will change under us. A stub can reach for a declared host and an
undeclared one *on purpose*, which turns that check from an observation into an assertion.

It also removes a 320 MB download from every run, and it tests exactly what the core owns: the
agent API, the image build, the firewall, the phantom token, the gate.

**What it must not become** is a stub that passes because it asks nothing of the core. It has to
install a binary, resolve names, present a credential and push through the gate - otherwise the
suite goes green while proving less than it used to, which is worse than deleting it.

**What the stub cannot cover**, and so belongs in an agent's own repository: that a real CLI is
installed at the pinned version, honours a socket or a base URL, and reaches only the hosts its
definition declares.

### Written, 2026-09-06

`agents/stub`, on Sokar's version rather than its own, with `agent.package.skip` on - nobody
installs it, the suite copies the binary. Its tool is a shell script the definition writes into
the image: it looks up `example.com`, which it declares and is granted, looks up `example.net`,
which it declares as **refused**, and redeems its task token through the proxy socket. Both names
are IANA-reserved, so neither can move, expire or start redirecting.

That pair is the point. The domain-coverage check currently depends on Claude Code happening to
resolve a Datadog intake; with the stub, the refusal is declared on one side and asked for on the
other, so a firewall that quietly stopped working fails the run instead of going unnoticed.

**Three things this cost, none of them predicted:**

- **`Agent.imageLayer()` is never called by Sokar.** The first version put the script there, as an
  override, which is the natural-looking place. Nothing runs it: the image is built from the
  *describe* response's `installAsRoot`, and there is no protocol method for a layer. The override
  compiled, unit-tested green, and would have produced an image with no tool in it. Anything an
  agent contributes to an image has to be in the definition.
- **A here-document is what makes that readable**, and podman had to be asked whether it accepts
  one. `installAsRoot` lines are joined with `\n` into the Containerfile, so a quoted `<<'STUB'`
  spanning list items needs no escaping at all - but Dockerfile here-documents are a BuildKit
  feature and podman's own parser is not BuildKit. Measured: podman 5.8.4 builds it, the file
  lands, and `--version` answers. The definition is Maven-filtered, so the script uses `$VAR`
  rather than `${VAR}` throughout - filtering replaces `${...}`.
- **The first test passed while proving nothing.** It asserted the script *mentioned* each
  declared domain; pointing the lookup at a third name left it green, because the old name was
  still in an `echo` beside it. It now parses the `getent hosts` lines and requires the set of
  names actually looked up to equal the set declared - in both directions. Both were bitten:
  point a lookup elsewhere and it fails, declare a domain nothing asks for and it fails.

Seven tests, 474 in the suite. The negative control is worth keeping: run the script in a plain
`ubuntu:24.04` and *both* names resolve, so a green run inside a task is the containment
working rather than the script pretending.

### Tier 1 drives it, and stopped naming an agent

`SOKAR_E2E_AGENT` selects the agent, defaulting to `stub`; `SOKAR_E2E_AGENT=claude` points the
same checks at the real one. Everything the suite used to hardcode is now read from the agent's
own `describe` response - the tool's name, its prompt flag, its default provider, and the
variables it is pointed at a proxy with. Both agents pass every check.

**Three things were hardcoded that nobody had noticed**, because with one agent they were
indistinguishable from facts:

- **The tool was looked for at `~/.local/bin/<name>`.** Where an agent installs its tool is the
  agent's business, and the two already disagree - one uses `~/.local/bin`, the other
  `/usr/local/bin`. It is `command -v` now.
- **The proxy variable was found by matching `*UNIX_SOCKET`**, which is Claude Code's spelling
  rather than a rule. An agent naming its socket anything else had its *base URL* tested as a
  socket path, which fails as "not a socket in the container" and reads like a broken mount.
  The definition states both variables; they are read from it.
- **`sokar agents | grep` fails under `set -o pipefail` whenever `sokar agents` exits non-zero**,
  which is what one unusable agent installed anywhere on the machine produces - a stale build,
  say, speaking an older protocol. Discovery then failed for a reason that had nothing to do
  with the agent under test. The listing is captured before it is searched.

None of these were reachable with a single agent in the tree. They are the cost of the split
showing up as soon as something else drove the same code.

### And then `agents/claude` was deleted

What it took beyond removing the module, none of it obvious from the module itself:

- **`check-packages.sh` had no agent package to check.** It installs one and asserts its
  dependency on `sokar` resolves - a property of Sokar's packaging, not of any agent. The stub
  now packages itself for that purpose. It is never published: the publish step uploads
  `dist-deb` and `dist-rpm` and nothing else.
- **`e2e-tier2.sh` was deleted rather than moved.** It asked whether *Claude Code* could
  authenticate, which is that agent's question; the agent repository answers it now, against
  the published packages rather than a build tree.
- **The remote build and the publish job both named the module.** The acceptance leg built it
  and the deploy job fetched its binary to package.
- **`getting-started.md` told you to copy the agent's `.deb` out of `agents/claude/target`**,
  which no longer exists. A build here now produces Sokar and no agent, and the document says
  so and links to where the agent comes from.

455 tests, down from 474: the 19 that moved are Claude Code's own.

**And the stub's own image layer had to be rewritten, on podman 4's account.** It wrote its tool
with a Containerfile here-document, which podman 5.8.4 builds and podman 4.9.3 - what Ubuntu
24.04 ships - does not: podman 4 reads every line as an instruction and fails with
`Unknown instruction: "IF"` on the script's first `if`. It was tested on one podman and called
verified.

The layer is now a single `RUN` with `printf`, continued across lines, and the script avoids
`if` entirely so each line survives as one argument. A unit test asserts the build line contains
no `<<`, and the other tests **render** the script by running that line rather than parsing it -
so they check the file an image actually receives, and cannot be fooled by the form changing
again.

That is the second defect this leg has found that neither development VM could, both of them
about podman 4 against podman 5.

## Tier 2, and turning it off

Tier 2 belongs to the agent repositories, but wherever it runs the shape is the same: a real
credential, the cheapest model, one trivial prompt, and the checks that the credential never
enters the container or the logs.

**It has to be switchable without editing a workflow.** A provider outage, an expired card or a
rate limit should be a setting, not a commit.

- a repository **variable** - not a secret - lists which providers to exercise, for example
  `TIER2_PROVIDERS=anthropic,openrouter`. Empty or absent means the job does not run.
- each provider's credential is a **secret**, named for the provider, and a provider named in the
  variable whose secret is missing is an error rather than a silent skip - "switched off" and
  "misconfigured" must not look the same.
- the matrix is built from that variable, so adding a provider is a settings change.

Cost is not the reason for the switch: one prompt against the cheapest model measured **0.0023
USD**. The reason is that a red build should mean the code is wrong.

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
