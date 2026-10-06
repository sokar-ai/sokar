# Working on Sokar

## Before a change is handed over

- **`mvn test` is unit tests only**: whatever needs podman or a machine is in the acceptance suite, which surefire
  never sees, so it runs against a real machine before a push is proposed:

      ./mvnw -q -s settings.xml -N exec:java@deploy -Ddeploy.options="--vm <user>@<host> --key <key> --account"
      ./mvnw -B -s settings.xml -pl acceptance/suite -am verify -Dsurefire.skip=true \
          -Dsokar.acceptance.host=<host> -Dsokar.acceptance.user=<user> 2>&1 | tee suite.log

- **`-am` builds the acceptance kit from this checkout**; without it the suite takes whatever kit was last installed,
  and a new step reads as undefined.
- **`-Dcucumber.filter.tags='not @slow'` is enough for a change to what the CLI prints**; the restart scenarios reboot
  the machine and run only where that is agreed.
- **The first terminal scenario of a run can fail where the same step then passes**, because the first pty races the
  login banner: run it again before believing it.

## The one architectural rule

- **Nothing outside `agents/` names an agent** - no class, string or comment that turns into a `switch`; Sokar finds
  agents at runtime, and `AgentIsolationTest` fails the build otherwise. A difference between agents is a new field
  in the agent definition, never a branch in Sokar.
- **An agent builds only against `sokar-agent-api` and `sokar-wire`**, with `sokar-acceptance-kit` in test scope and
  `sokar-bom` imported - the four artifacts this repository publishes to Central.
- **Every shipped agent has its own repository** (`sokar-claude-code`, `sokar-pi`, `sokar-omp`); `agents/` holds the
  contract and the **stub**, which the acceptance suite drives, so every scenario that starts a task names
  `--agent stub` and reads what it asserts about it from `sokar agents --verbose`.
- **Say "the agent API"**, never "SPI": `sokar-agent-api` carries both what Sokar calls and what an agent implements.
- **Every agent and provider names its upstream project by link**, and an implemented one has a `README.md` in its
  directory opening with that link.

## Installing on a machine to test

- **`exec:java@deploy` builds the packages CI publishes and installs them on a machine that stays**, with lingering
  and the daemon restarted; `--skip-build` installs what is in `target/`, and the interface is not installed by it.
- **`--account` installs into one account only** (`~/.local/bin`, `~/.local/share/sokar`, its own
  `sokard.service`) and stops unless the login PATH's binary, the daemon and the hooks are all that account's.
- **A non-login shell still runs the package's `sokar`**, since `~/.local/bin` is on PATH only after a login shell:
  use `bash -lc` or the full path.

## Areas, and who works in which

The command line and the daemon's logic are modules by area, `app-<area>`, each in the package `org.fuin.sokar.app`.

| Area | Module | Daemon methods | Contract part | File locations |
|---|---|---|---|---|
| shared ground: context, paths, records | `app-base`, `app-model` | - | `10-common` | `SokarPaths` |
| agents and providers | `app-agents` | `AgentMethods` | `40-agent` | `AgentPaths` |
| the vault, credentials, grants | `app-vault` | `VaultMethods` | `30-vault` | `VaultPaths` |
| messages and their conversation | `app-messaging` | `MessagingMethods` | `45-messaging` | `MessagingPaths` |
| egress, the shield, clearance | `app-egress` | `EgressMethods` | `25-egress` | `EgressPaths` |
| tasks | `app-task` | `TaskMethods` | `15-task` | `TaskPaths` |
| projects, following, backups, clearing | `app-project` | `ProjectMethods` | `20-project` | `ProjectPaths` |
| the gate and review | `app-gate` | `GateMethods` | `35-gate` | `GatePaths` |
| the command line, `doctor`, `setup` | `app` | `MachineMethods` | `50-machine` | - |

- **The modules' dependencies are the boundaries**: `base` depends on no area, `model` on `base`, `agents` on `base`;
  `vault`, `messaging` and `egress` on `base` and `model` and not on each other (`egress` also on `agents`); `task`
  on all of those; `project` on `task` and `messaging`; `gate` on `messaging`.
- **Each area registers its daemon methods in its `<Area>Methods` and describes them in its own contract file** under
  `daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1/`; `InterfaceDescriptionTest` holds each to its part.
- **File locations are per area** (`context.paths().messaging().mailbox(...)`); `SokarPaths` keeps the roots.
- **The tests stay in `app`**, which shares the package with every area.
- **`vault clear` belongs to the `vault` command group but lives in the clearing area's module**, added where the
  command line is assembled, because it reaches transports and tasks, which the vault's area does not depend on;
  `ClearedItem` and `MachineDeployKey` sit in the common part of the contract, since project and vault both answer them.
- **An agent changing an area it does not own says so in the channel first**, and a change to `app-base` or
  `app-model` is always said there first.

## Code

- **Java 25: records for data, sealed interfaces where the set of cases is closed.**
- **US spelling in prose and identifiers**; *afterwards* and *towards* stay as they are.
- **A comment in `pom.xml` is one line too.**
- **A secret reaches a container as `--env NAME`**, the value copied from Sokar's own environment; podman drops a name
  it cannot resolve instead of failing, so the names and the values come from one object
  (`ContainerSpec.toArguments()` and `environment()`), tested at both places.
- **A token is logged abbreviated** (`PhantomToken.abbreviate`, `TaskToken.toString`), never by its value.
- **The real credential never enters a task container**: the agent holds a phantom token that the broker swaps on the
  way out, and the provider's own host stays reachable, since the token is worthless there.
- **Fail closed on the start path**: the nft hook refuses to start a container whose ruleset does not load.

### Nullness

- **NullAway runs in every main compile as an error, scoped by `@NullMarked`**; test code is not checked.
- **A package without `@NullMarked` is skipped in silence**, so `NullMarkedPackagesTest` fails on one with main code.
- **NullAway trusts unannotated libraries**, so a planted defect that proves the check runs dereferences something
  declared `@Nullable` here (`Json.parse`), never a JDK method.
- **Only what is compiled is checked**: judge with `clean`, not after an incremental `-pl` build.
- **The compiler's lists append** (`combine.children="append"`): every module with commands declares picocli's
  processor, and `AreaModulesTest` fails when one does not.
- **An optional picocli option with no default is `@Nullable`**, and `OptionNullnessTest` walks the command tree.
- **`.mvn/jvm.config` opens javac's internals for Error Prone on JDK 25**; a bare `mvn` from elsewhere misses it.
- **No suppressions**: an invariant the checker cannot see is a `requireNonNull` whose message says it.

## Tests

- **A test that needs podman belongs in the acceptance suite**; unit tests run without a container runtime.
- **A scenario about a packaged file cannot pass on a CI leg**, which builds on the machine and has no package
  installed; what a package holds is checked by `sokar-package-check`.
- **A suite opens one ssh connection per run and a channel per scenario**; a connection per scenario is refused by CI.
- **JUnit is one set**: `org.junit:junit-bom` is imported before the fuin BOM, at the version Cucumber is built
  against.
- **`doc/reach.md` is held by `ReachDocumentTest`**, which reads its numbers off the code and refuses a row that does
  not say control or accident-catcher; a change that widens what a task can reach, hold, spend or produce changes
  that page in the same commit.
- **Review ranking is `ReviewRanking` in the gate, and git prints the patch in its order (`-O`)**; a dangerous file
  is never filed as generated or reformatting, and a rename is ranked by where it arrives.

## Building

    ./mvnw -s settings.xml clean install                 # JDK 25+
    ./mvnw -s settings.xml -Pnative clean package        # native binaries
    ./mvnw -s settings.xml -Pnative,dist clean verify    # + .deb and .rpm

- **Packaging binds to `verify`**, after native-image binds to `package`: `-Pnative,dist package` produces no package,
  or an older one beside a newer binary, which `./mvnw -N exec:java@package-check` catches.
- **`agent.package.skip` is on by default and each agent turns it off**, since `dist` lives in `agents/pom.xml` and
  the aggregator and `sokar-agent-api` inherit it.
- **The root POM names its version as a literal `<sokar.version>`**, because `${project.version}` in a parent's
  `<dependencyManagement>` resolves against the inheriting module; an enforcer rule checks the two match.
- **An FFM downcall is not found by static analysis**: `./mvnw -s settings.xml -Pnative,ffm-check -Dagent=true
  -pl core,shield,vault -am verify` fails on one the tests made that is not registered, and
  `-Dsokar.ffm.update=true` records it; the changed `reachability-metadata.json` goes into the commit that adds or
  changes the downcall.
- **Two files are shell, since they run where there is no Java yet**:
  - `selinux/install-selinux-policy.sh` loads the shipped SELinux module, run once by an administrator;
  - `dist-setup/sokar-setup.sh` prepares a machine that has nothing installed, run as root.
- **The musl toolchain the hooks link against comes from the build tools**:
  `./mvnw -q -s settings.xml -N exec:exec@machines -Dmachines.args=musl`.
- **A new native image goes into its module's `sokar.cpu.images`**, or `sokar-cpu-check` never sees it.
- **FFM and static linking exclude each other** (`Linker.defaultLookup()` loads `libc.so.6`), so `sokar` and `sokard`
  link dynamically and the hooks, with no FFM, statically against musl; `NoForeignFunctionMemoryTest` holds them so.
- **A CI job compiles with the pinned GraalVM**, through `.github/actions/pinned-jdk`, never `setup-graalvm` with
  `'25'`.
- **`sokar-release` is a set of commands**, called through `exec-maven-plugin`; a pin beside the CLI is a
  `sokar.release.pin.<name>.*` property moved with `--pin <name>`, and `--min-age 3d` takes a release only once aged.
- **A change that needs a new `sokar-buildtools` is two pushes in order**: `sokar-buildtools` first, this one after
  its publish, at `sokar.buildtools.version`; the agent repositories resolve this one's artifacts after its publish.

## How the parts behave

### Containers and podman

- **A rootless container's agent user is a subordinate uid** (1001 on `ubuntu:24.04`), so a socket the container must
  reach is world-writable inside a `0700` directory.
- **A rootless container reaches the host at `169.254.1.2`**; `host.containers.internal` exists only inside it.
- **`XDG_DATA_HOME` is podman's storage**: a test redirects something narrower.
- **Give a container a literal loopback address, never `localhost`**, which Node resolves to `::1` first.
- **The gate binds loopback, mapped in by a `CONTAINERS_CONF_OVERRIDE` file that only `podman start` reads**
  (`LoopbackMapping`); `--network pasta:...` would replace pasta's defaults and leave an undeclared address open, and
  under slirp4netns `task start` binds every interface and says so.
- **A bind-mounted socket is bound to the inode that existed at start**, so helpers declare whether they must be up
  before the container or need it running, and start in that order.
- **`podman diff` lists what was installed in a container**, counted before removal; `/etc` and `/var` always show.
- **`podman cp <task>:/workspace` works on a container that cannot start.**
- **`podman cp container:/dir target` copies the directory into the target**, so a copy of its contents names `dir/.`.
- **`{{.Labels}}` prints Go's `map[a:b c:d]`**: read one label with `{{index .Labels "org.fuin.sokar.project"}}`.
- **The runtime's state is a phrase** (`Exited (143) Less than a second ago`), and `Age.compact` renders the instant.
- **A Containerfile here-document needs podman 5**: what an agent adds to an image is one `RUN`, continued with
  backslashes, in its definition's `installAsRoot`.
- **podman has no "rebuild from here"**: an `ARG` at the seam between the packages layer and the agent's layers,
  given a value podman has not seen, invalidates the cache from that line on, which is what `--rebuild AGENT` is.

### Firewall, names and SELinux

- **An nftables set holds addresses and a project names hosts**, so a grant and a withdrawal are two operations.
- **`nft add element` answers `File exists` for an address already there**, which `EgressPolicy.allow` treats as done.
- **The resolver re-reads its servers file on `SIGHUP`**, which is how a live widening reaches it.
- **`dnsmasq --nftset` is what makes a declared name reachable**; `doctor` probes for it, and `no-nftset` contains
  `nftset`.
- **SELinux runs `nft` confined, unable to read the operator's files, with the denial unaudited**, so `NftHook` feeds
  the ruleset on stdin.
- **SELinux checks `connectto` against the serving process**, so a socket a container reaches is labeled at
  `socket()` through `/proc/thread-self/attr/sockcreate` (`SocketContext.openUnixSocket()`); clearing it writes a
  NUL byte.
- **`ausearch -m AVC -ts recent` can miss what `/var/log/audit/audit.log` holds**: grep the file.
- **`podman unshare` runs as `container_runtime_t`**, which needs its own SELinux grant.
- **Hook descriptors count only where podman reads them**: each `hooks_dir` replaces the last, so a later drop-in
  switches Sokar's off; `HookInstaller.registration` tells the states apart, `doctor` exits 69, and a task refuses.
- **A package upgrade does not re-run `sokar setup`**: `Registration.STALE` compares contents, and a task start
  repairs `MISSING` and `STALE` and asks podman again.
- **A local build shadows a packaged one** - hooks and agents alike - and `doctor` names what is in use.

### Credentials and the vault

- **`java.net.http.HttpClient` exposes HTTP/2 pseudo-headers**, so `VaultProxy.forwardable` drops every header
  starting with a colon.
- **An agent needs its base-URL variable as well as its socket variable**, or it uses its compiled-in endpoint; DNS
  lookups for the provider are not a bypass, the proxy's request log is the evidence.
- **An agent that takes only a URL gets `sokar vault relay`** on `127.0.0.1` in the task's namespace, forwarding to
  the broker, which keeps the host's DNS, egress and credential.
- **A provider is data, a YAML file under `/usr/share/sokar/providers`; an agent is code.** The dialect's path
  belongs to the endpoint, never to the agent.
- **A passphrase is verified before it is cached**, and `vault init` asks twice.
- **`vault lock` does not reach a running task**, whose broker holds its credential until it stops, and says so.
- **A sign-in is stored whole** - refresh token (hidden as `grant/<name>`), its end, token endpoint, client id - or
  it dies within hours.
- **The broker renews a minute before the end, inside one vault update under its lock**; from a task, a
  `grant_type=refresh_token` request is refused and an answer carrying a token field is withheld.
- **An imported sign-in is never renewed**, since renewing the copy ends the developer's own session.
- **Without a terminal there is no passphrase prompt**: `sokar setup` leaves the vault, says so and exits 0.
- **An agent's permission prompts are switched off by Sokar** through `AgentDefinition.sandboxedCommand()` on both
  start paths; a consent dialog a flag causes is answered by a settings file the agent declares, and the API-key
  billing dialog is refused, never answered.
- **An agent's first-run files are placed by `TaskLaunch.placeAgentFiles`**, with an empty token when there is no
  credential.
- **A task's route tokens are kept in the vault with the agent's, under `task/<container>/route/`**, and put back
  when the task starts again, so a restart does not change the tokens the container holds.
- **The minting-grant guard in `VaultProxy` matches `refresh_token` and `client_credentials` alike**: it refuses the
  container asking, not the broker's own purchase, made on the host side of the socket; a test fails if the pattern
  narrows back to `refresh_token`.
- **`TokenPurchase` is synchronized, which is the single flight**, and its failure messages never echo what the
  authorization server sent back, which may carry the secret.

### Tasks and their helpers

- **A task's helpers are `sokar` re-invoked**, so their path comes from `SokarBinary.path()`, never
  `ProcessHandle.current()`.
- **Take the helper census before stopping**: the poststop hook reaps the helpers and their pid files.
- **A running task answers "already up"** on resume, and a recorded helper still alive is not started twice.
- **No hook fires for a container that never started**: `TaskRunner.reapOrphans` stops its helpers.
- **A hook writes its failure to `hooks.log`**, since the runtime discards its stderr.
- **`/run/user/<uid>` is cleared when the last session ends**, so a task survives a logout only with lingering, and
  comes back after a reboot only from its saved record, through `task start --restarted`.
- **Project and class are podman labels as well as in `sidecar.json`**, and the container name is never parsed for
  them.
- **A task is the container whose id Sokar recorded**; every act goes to that id, never to a name.
- **The workspace stays inside the container**: a host directory would run the agent's `.git` hooks and config on
  the next host-side git command, so nothing reads a workspace from the host.
- **A stopping task writes what it holds unpushed into its state directory**, read back by a later removal; a task
  with no note is refused, not guessed at, and `task status` asks without destroying anything.
- **`readable: false` is not "holds nothing"**, and `WorkHeld` runs git in the container, so it is never asked per row.
- **A rescue that pushed nothing exits non-zero**; it commits first.
- **A non-zero exit or a signal keeps the container, stopped**, even with `--rm`; `Teardown`'s shutdown hook takes a
  lock so Ctrl-C cannot leave a task running without its helpers.
- **A shell's last exit status is not a verdict on the task.**
- **`sokar panic` stops every task and removes nothing.**
- **`task start` creates or resumes, and does not attach**; `task attach` is the separate step.
- **`tmux new-session -A -s sokar` is the session**, its scrollback pinned in `/etc/sokar/tmux.conf`; `sokar` is not
  in a task image, which carries `curl`, `ca-certificates`, `git`, `openssh-client` and `tmux`.
- **The workspace is checked out on an unborn HEAD**, never on an empty directory, with the branch asked of the
  mirror, and `remote.sokar.push = HEAD:$SOKAR_TASK_REF` makes a bare `git push` reach the gate.
- **`ContainerName.isSokar` and `isTask` differ**: a login container carries only a timestamp after the prefix, and
  one left over is swept by the next `vault login`.
- **Where an agent's session id is - an event field in a headless run, session files in a driven one - is declared by
  the agent package beside `supports_resume` and `resume_flag`**; the id is read from what the host already owns
  (`task.log`, the config directory Sokar writes) and kept in the task's durable state, never in `/run/user`.

### Messages

- **The host owns the mailbox's layout**: it makes every directory when it makes the task, and nothing that reads
  or carries a message - the filter, a transport - creates one; finding one missing, it refuses or defers.
- **Every hop of a message is a rename** between directories whose names are the states, and nothing between the
  outbox and the transport rewrites a message's bytes, because the signature is over them.
- **Which transport queue a message goes to is the host's decision after resolving `metadata.to` against the peer
  table, never the filter's**, and a transport runs on the host, never in a task and never in the sluice's process.

### The daemon and its contract

- **`sokard` speaks varlink on an owner-only socket and listens nowhere else**; remote access is an ssh forward or
  `sokar daemon connect`.
- **A socket file outlives its process**: a daemon is asked (`org.varlink.service.GetInfo`), never found by its file,
  and a second daemon on a live socket refuses and names the holder.
- **`sokar daemon connect` copies bytes and flushes on every read**; one invocation is one connection.
- **A stream that closes without a final reply was cut short**, never finished.
- **varlink carries no session**, so an interactive session is ssh with a pty, running Sokar's own verb.
- **One question, one implementation** (`TaskInventory`, `TaskControl`, `TaskLaunch`, `GateSupport`), which the CLI
  and the daemon only render.
- **A field added to a record that crosses varlink is two edits**, and `SetupContext.parameters()` is compared with the
  record by a test.
- **`Tasks1`'s `1` is the compatibility promise**: within it the interface only grows, a breaking change is `Tasks2`
  served beside it, and an added enum value is not breaking.
- **A parameter name is a varlink identifier** (`credentialType`, never `credential-type`).
- **A client derives nothing the daemon can send**: a clearance prompt carries its own `key`.
- **A reply gains a value only where the reader's sentence claims an act** (`Lock`'s `wasCached`).
- **A listing never reaches the network**: a fetch is its own method.
- **A tail sends 64 KB chunks 200 ms apart**, so a backlog drains at about 320 KB/s.
- **A registry two processes write is a directory of files**, one per entry.
- **The daemon hands out paths and takes back only those**, reporting a moved file as absent.
- **A refusal is a named value, never a sentence to parse**: a follow carries its outcome, the commit it refused
  beside the one in force, the signer's fingerprint and `needsAPerson`, and `Following()`, `Projects().following`
  and `doctor` render the same values rather than deciding again.
- **An agent's end is asked only of a log quiet for ten seconds, and once per size**: asking at every pass would
  start every agent for every running task twice a second.
- **A long-lived background pass runs on a platform thread of its own (`BackgroundPass`), and every `VarlinkServer`
  serves each connection on a platform thread**, so work pinned in a native call can never take the carriers a call
  on the socket needs; `BackgroundPassTest` holds the first.
- **`sokard` is built with `--enable-monitoring=threaddump`**, so `SIGQUIT` dumps its threads to the journal without
  stopping it; keep that flag.

### Clearance

- **Both ways into the clearance watcher broadcast** through `ClearanceService.publish`.
- **Decisions are appended to `~/.local/state/sokar/clearance/<container>.jsonl` and read back on start**, and a
  restored allow is put back into the firewall.
- **Every decision is broadcast with its `verdict`**, and an expired prompt is replaced by a second `Notify` with the
  first one's id, never closed.

### What the CLI says

- **A diagnostic names the next action**: `Probe` refuses any state but `OK` without one, and `UNKNOWN` is a state.
- **A doctor probe asks the machine through the context** (`context.runner()`, `context.podman()`), never a process
  builder of its own, so a machine state is faked in a unit test; `DoctorCommand.ready` is the one rule the exit code
  and the daemon's `ready` share.
- **Absence and failure are never rendered alike**: a lookup that could not answer is its own outcome, never "nothing
  there" (`KernelKeyring.forget` answers `UNKNOWN` beside `CLEARED` and `NOTHING_CACHED`); a read path that asks
  anyway may fold them, a path that claims an act may not.
- **A refusal never names a way out that is unavailable in the state it refuses in.**
- **Colour goes through picocli's `Ansi.AUTO`**, painting only work that exists nowhere else and a clearance of
  `off`.
- **An unhandled failure is one line and a path**: `CliErrors.failures(paths)` writes the trace to
  `$XDG_STATE_HOME/sokar/failures.log`, owner-only, with the command's name and never its arguments.
- **A shell in a directory a `clean` removed is caught by `SokarCli`** in one line.
- **`task list` shows what `TaskInventory` computes**, and `TaskInventory.isLog` is the one allow-list of what a log
  is.
- **A name's shape is no evidence that it names something**: a task exists as a container or by its logs.
- **Counts cross the boundary and the sentence stays where it is read** (`Held.phrase()`); a note of an unknown
  version is refused.
- **`project.yml` is edited as text**, never round-tripped through a YAML model (`EgressEdit`).
- **A process wrapper streams a long command's lines as they arrive**, so a killed one leaves them.
- **A verb that ends something answers for everything the machine lists**, followed or not.

## The rented test machines

- **A CI leg boots a prepared snapshot through `sokar-machines`** (`./mvnw -s settings.xml -N exec:exec@machines
  -Dmachines.args='...'`: `leg`, `sweep`, `snapshot --os <os> --repo <sokar checkout>`), whose builder lives in
  `sokar-buildtools` beside the pins and compiles Sokar on the machine before taking the image.
- **The Ubuntu leg runs 26.04**, since 24.04 ships podman 4 and Sokar refuses it.
- **A snapshot restores only onto a disk at least its size**, so it is taken at 40 GB.
- **`podman images` as root shows nothing on a snapshot**: rootless podman keeps a store per user and the pre-pulled
  images belong to `build`, so ask as `build`.
- **Hetzner's Fedora image ships SELinux permissive**: enforcing needs the config, `/.autorelabel` and a reboot, and
  Sokar's policy module is loaded and checked with `semodule -l`.
- **`install -d -o build` owns only the last component**, so a root-owned parent stops rootless podman.
- **Provisioning is verified by compiling a probe, ordinarily and `--static --libc=musl`**, and changed against a
  live server, never by rebuilding the image.
- **A location is asked of the API per server type, within `eu-central`**, and a create retries only on
  `resource_limit_exceeded`; a run's servers are named after the run and the leg.

## Publishing

- **Four artifacts reach Central: `sokar-agent-api`, `sokar-wire`, `sokar-acceptance-kit` and `sokar-bom`**; the
  packages and the agents' go to Artifactory, `sokar-dist-deb` and `sokar-dist-rpm`.
- **Under `-Pcentral-sonatype-release` only `skipPublishing` counts**, default-deny in the root and off in those four.
- **`jf rt upload` needs `--flat=true`**, and a Debian upload needs `deb.distribution`, `deb.component` and
  `deb.architecture` (Annotate permission), or it is stored and never indexed; build-info is not published.
- **`.github/workflows/artifactory-smoke.yml` checks the upload path without building**; run it after rotating the
  token.
- **The bill a package installs is its own** (`dist-deb`'s `makeBom`, its subject `sokar-dist-deb`).
- **The published binary is the one the Ubuntu leg tested**, fetched back with a manifest, since a hosted runner has
  no musl and a native image built on Ubuntu runs on Fedora too.
- **Every agent repository carries the three-job build**: build and unit tests on `ubuntu-24.04`, publish with an
  index check, and acceptance on rented `ubuntu` and `fedora` from the package repository (`agents/README.md`).

## This repository's files

- **`README.md` is an index; the substance is in `doc/`** (index `doc/index.md`) and `agents/README.md`; the root
  holds only `README.md`, `AGENTS.md`, `CLAUDE.md` and the generated `CHANGELOG.md`.
- **Issues are in `issues/`**, by set: `B` the product (`issues/base/`) and `P` providers (`issues/providers/`); an
  `A` number is `sokar-project`'s and an `F` number `sokar-frontend`'s.
- **A changelog entry is one YAML file under `changelog/unreleased/`** with a one-sentence `title` and a `type`;
  `CHANGELOG.md` is generated by `./mvnw -N logchange:generate`, and `logchange:lint` runs in `validate`.
- **A push of only documents - `.md` files, `mkdocs.yml`, `doc/` and `issues/` - builds nothing**, and the Shared
  rules workflow checks it; `workflow_dispatch` forces a run.
- **`mkdocs.yml` is the order of `doc/`**, which the documentation site takes; `mkdocs build --strict` checks the
  pages before a push.
- **Every page of `doc/` is in `mkdocs.yml`'s navigation exactly once, and a link out of `doc/` is absolute**, to the
  repository on the forge, since a relative one cannot work on the site; `check-doc-site` holds both, in the root's `validate`.
- **One subject has one page**, so somebody adding a setting knows where it goes; an example that can be executed is
  executed by a test, as `DocumentedProjectFileTest` parses the project file page's YAML with its reader.
- **The tests tagged `documents` run alone with `./mvnw -B -s settings.xml -Pdocuments test`**;
  `DocumentTestsTaggedTest` fails when a test reads a document without the tag.
- **Skills used:** `graal`, `java-code-review`, `test-quality`, `security-audit`, `concurrency-review`, `clean-code`,
  `solid-principles`.

## Shared across the Sokar repositories

> **BEGIN Shared Area** · sha256 `ab50c3787a547eab` · changed 2026-10-06T09:00Z

Identical in every repository `project.yml` names. The markers carry the SHA-256 of the lines between
them (the first 16 hex digits) and the UTC time that text last changed; change it in the channel
first, never in one copy.

### Agents and the rules they follow

- **When several agents run in parallel, they may communicate through a shared channel.** How it
  works depends on the local setup and is defined in the optional `.AGENTS.md` or fed into every
  agent's context when it starts.
- **Every repository has exactly one responsible agent, and one agent may be responsible for
  several.** Which one is defined in the optional `.AGENTS.md` or fed into every agent's context when
  it starts.
- **A rule that holds for more than one repository is stated generally and shared** - here, or in the
  shared block of `.AGENTS.md` if it concerns the local setup. A rule is one or two sentences and
  says only what matters.
- **A rule belongs in `.AGENTS.md` only if it concerns local settings that cannot be shared through
  `AGENTS.md`.**
- **An agent writes only in its own repositories and asks the responsible agent for anything from
  another**, in the channel. The coordinating agent may also read the other repositories, but never
  writes in them.
- **Where a channel exists, an agent stays reachable on it while any work is open**, and says there at
  once when it waits on the operator.
- **A contract between repositories - an interface, a file format, a path another repository links
  to - changes only after agreement in the channel or by an operator decision**, and in both
  repositories.
- **An ambiguous answer is asked about once, plainly, instead of guessed at.** When an answer
  changes, everything built on the old one is looked for.

### Commits and pushes

- **While working, commit in logical steps, so a mistake can be rolled back.** A task is pushed only
  when it is finished, squashed into one commit as its last step; several finished tasks may go out
  in one push, one commit each.
- **An edit replaces an exact block, never everything between two landmarks; only the files you
  edited are formatted.** Before a commit, `git status` is read and only your own files are added,
  never with `git add -A`.
- **Unless the operator says otherwise, the operator pushes, and agents commit and stop.** An
  instruction to push covers exactly what it names.
- **A change that needs another repository's change names that dependency when it is handed over**,
  and is pushed only after the change it needs has built and published.
- **A change is ready only when every step its build workflow runs - not only Maven - has passed
  locally.**
- **Amend, squash or rebase only commits that are not pushed**, checked with
  `git ls-remote origin refs/heads/main`, never with a cached `origin/main`. A pushed commit is
  repaired by a new commit on the remote's tip, never by a force push.
- **A commit message is one brief line saying in words what changed**, never by a requirement number;
  the reasoning goes into an issue or a decision.
- **A change to what ships or builds gets its changelog entry in the same commit**, under
  `[Unreleased]` and the heading of its kind. A generated changelog is changed only through its
  sources, never by hand.

### Issues

- **Every open task is an issue in `issues/`, named `<prefix><nn>-Short-Title.md`** with one of its
  repository's prefixes; a repository may own several, for subgroups. An issue is one task, says
  what must be true and how it is judged done, and names a dependency on another repository's issue
  by repository and number.
- **A new issue follows this skeleton**, and its index row is added in the same commit; *Why* and
  *The shape* may follow *What must be true* in a larger issue:

      # <PREFIX><nn> — <Short Title>

      **Status:** now | soon | later; blocked by <repository> <number>, if so.

      **What must be true.** One or two sentences, from the point of view of whoever uses it.

      ## Acceptance

      - How it is judged done: what is measured, and what must be seen to fail.

      ## To be checked

      - Open questions, if any; deleted once answered.
- **`issues/README.md` is the index, grouped into Now, Soon and Later**, each a table of number,
  status, blocked by, what it covers and open questions, ordered by what to do next; it changes in
  the same commit as the issue. An open task is never a TODO in code or a note in a commit message.
- **An issue's unanswered questions sit under *To be checked*, and the index counts them.** An
  answered question is deleted once its answer is in the criteria, the design or a decision.
- **A finished issue is deleted, with its row and every mention of its number.** What outlives it
  moves first: to `doc/` if a user needs it, to `doc/decisions.md` if it is a decision, to
  `AGENTS.md` if it is a rule, to `.AGENTS.md` if it is true only on one machine.
- **`doc/decisions.md` opens with an index** - subject, one line of what holds, link - and a row is
  written with its decision. An accepted risk states the exposure, why it stays, and what would
  change the answer.
- **A requirement not yet placed waits in `sokar-project`** until it is clear which repository builds
  it, then moves there as an issue.
- **Link to a requirement by its number and the index, never to its file**, which is deleted when the
  requirement is finished. A repository that can test this, does.
- **Code, comments, test names and anything that ships never cite an issue number**; they state the
  constraint itself, since the issue is deleted once it is finished.

### Testing and machines

- **Work is tested on local VMs before it is handed over, on rented machines reachable from here only
  when the operator says so, and in the GitHub build on every push.** Which machines a setup has is
  defined in the optional `.AGENTS.md` or fed into every agent's context; machines are rented only
  through the shared tooling, and nothing else names a provider.
- **How agents exchange files for a test on a local VM is defined in `.AGENTS.md`.** Where it says
  nothing, a test uses only the agent's own artifacts.
- **A local VM is restarted only when agreed in the channel or asked of the operator.** A rented
  machine reachable from here is restarted only by the operator.
- **A rented machine's name says which agent and which run made it.** Every run deletes exactly what
  it created, after a failure too, and never sweeps by age or touches a machine it cannot attribute.
- **A script that changes a machine refuses to start over leftovers, removes only what it created -
  on interrupt too - and exits non-zero naming anything it left.**
- **Before a run on a shared machine, check what it does to that machine and say so.**
- **Before a long run, say how long it will take**, from a measured time, or say that it is a guess.
- **A test that waits on an agent, a model or any paid service fails fast on a loop**: it stops as
  soon as the same failure repeats, instead of waiting out its time, and names what repeated. A run
  seen looping is reported at once, so the operator can cancel it.
- **A defect is fixed only after a unit or integration test reproduces it.** The test fails first,
  then the fix makes it pass.
- **A test is trusted only once it has been seen to fail**: break the code on purpose, watch the
  test go red, and restore it. A break that does not compile proves nothing.
- **A guard asserts what its reference set is, not only that it has entries**: an empty set, one
  that cannot change, and one nobody reads all pass as green as one that works.
- **A test asserts the observable effect, never what the system reports about itself or its
  internals.** A fixture states what the real system produces, never what the code assumes, and is
  never edited so that a feature has something to show.
- **A test depends on nothing outside itself**: not on the machine's configuration (a suite calling
  git runs with `GIT_CONFIG_GLOBAL=/dev/null`), not on wall-clock time, and not on a pinned version
  written into it - it reads the version from the build.
- **Before a commit, the full suite runs as its own step and its result is read.** A commit is gated
  on the exit code, never on grepped output, and never chained onto the test run.
- **A change handed over says which test levels actually ran** - unit tests, local VM, rented
  machine - never which ought to have. A change to documents or issues only needs the tests tagged
  `documents`, which run alone; every test that reads a document carries that tag, and a test fails
  when one does not.
- **A repository with a workflow runs `sokar-release`'s `check-shared`, `check-citations` and, where
  there is a documentation chapter, `check-doc-site`, with the tests tagged `documents`, on every
  push and pull request**, on a GitHub runner; the build skips a change to documents only.
- **A test result names every skipped test**, never just a count.
- **A failing check prints what it asked and what it got, never a guessed cause**, and its failure
  path has been made to happen once and read.

### Claims and writing

- **Measure before you claim, and say what was measured and what inferred.** Agreement between agents
  counts only where each measured, and "I could not get X" only once a second method failed too.
- **A command that should have changed something is checked by observing the change** - connect,
  read the file, ask the daemon - never by its exit code alone.
- **Every date, time or age written is read from the system (`date -u`) first, never from memory.**
  An age is computed from two timestamps that were both read.
- **When a thing is right in two forms, keep one.** The second copy is the one that goes stale.
- **Documentation and rules state what is true now**: no dates, no history, no stories, and nobody
  named, only roles. The shared blocks' markers are the one exception.
- **Everything written is US English** - documentation, issues, comments, commit messages, the
  channel. Replies to the operator are in the language the operator writes in.
- **A finding worth keeping is committed** - in an issue, the documentation or a decision - never
  left only in a conversation or the channel.
- **A finding taken from a third-party source is written as the finding, never naming the source**,
  in anything committed.

### Security and tools

- **A secret never appears in a command line, a log line, a file name or an answer.** It reaches a
  process through its environment or standard input, is stored only encrypted and readable by its
  owner, never touches a filesystem in CI, and is never promised to be wiped from memory in Java or
  Dart - only kept in fewer copies for less time.
- **Every download follows redirects (`curl -L`), since any server may answer with one, and is
  checked against the digest its source names before it is used.** Credentials are never passed on
  to another host (`--location-trusted` is never used).
- **Input from outside - upstream metadata, the environment, a file or an answer from another
  program - is validated before it reaches a file, a command or a decision.**
- **A binary run with more rights than its caller is taken by path and refused when it or its
  directory could have been placed or changed by anybody else.**
- **`pkill -f` and `pgrep -f` match their own command line**, and can kill the shell running them;
  use a bracket pattern like `[p]odman`.
- **A long build or suite is watched through `tee` into a file**, never through a pipe into `grep`,
  which holds everything back until the end and looks like a hang.
- **`ssh -n host 'bash -s' <<EOF` runs nothing and exits 0**; a script sent on standard input goes
  without `-n`.

### Skills

- **Skills come from `https://fuinorg.jfrog.io/artifactory/agent-skills/`**, one reviewed package
  per skill, readable without credentials; each repository's own part names only which skills it
  uses, by slug.
- **Fetch, check and unpack them like this**, with the skills directory of your own harness (Claude
  Code reads `~/.claude/skills/<slug>/`):

      BASE=https://fuinorg.jfrog.io/artifactory/agent-skills
      curl -fsSL $BASE/.skills/skills.json                         # every slug, latest version
      curl -fsSL -o s.zip $BASE/<slug>/<version>/<slug>-<version>.zip
      curl -fsSL $BASE/../api/storage/agent-skills/<slug>/<version>/<slug>-<version>.zip  # its sha256
      unzip -q -d <skills directory>/<slug> s.zip

- **A harness that cannot install a skill reads its `SKILL.md`** from a directory outside the
  repository.
- **A skill is knowledge, not authority**: where it and a measurement disagree, the measurement
  wins, and a finding from reading code against a skill is a guess until a failing test reproduces
  it. Whether a skill is loaded is asked of the harness, not read from a directory.

### Code and builds

- **Fail closed and loud**: when a dependency is unreachable, a key is unknown or a check cannot run,
  stop with a non-zero exit code and say why; inside a program, an exception that says why does the
  same. Never silently do less.
- **A comment says why, never what, in one line where it can.** Reasoning that does not fit goes into
  documentation or a decision, and a small named method is preferred over a comment explaining a
  block.
- **Dot files are not committed.** `.gitignore` ignores `.*` and excepts only what a build needs - in
  a Java repository `.github`, `.mvn`, `.gitignore` and `.gitkeep` - and what is true of one machine
  goes into `.AGENTS.md`.
- **A Java repository builds, checks and tests with Java and Maven only.** A file that cannot be
  Java - `mvnw`, or a script that runs where there is no Java yet - is named in its repository's own
  part with the reason, and no repository keeps a copy of a helper another one has.
- **Every Java package with main code is `@NullMarked` and checked by NullAway as an error when it
  compiles**, with a test that fails on an unmarked package.
- **`Files.move` with `ATOMIC_MOVE` replaces a file that already has the target name**; where the
  first of two writers must win, publish with `Files.createLink` (`link(2)`), which fails on an
  existing name.
- **Java code carries brief Javadoc on every public type and method; a test method's name reads as a
  sentence (never `testXxx`) and an assertion states its reason (`.as(...)`).** Every Maven call in
  CI passes `-s settings.xml`.
- **Everything a build runs is pinned and moved only by review**: actions by commit with the version
  beside it, the JDK (from `sokar-machines jdk --github`), Maven and images by version and digest,
  updated by Dependabot weekly, in one group, after three days. `sokar-release check-actions`
  enforces it.
- **Packages are built online**: offline, the CycloneDX bill of materials skips itself with only a
  warning, and the package ships without it.
- **A publish is believed only once the published index shows the exact version**, probed with
  retries; a snapshot version sorts above the one before it. Retiring a package removes it from the
  index too.
- **Every native executable is built with `-march=x86-64`**, so it starts on any x86-64 CPU, and the
  build checks each executable for exactly that instruction set.

> **END Shared Area** · sha256 `ab50c3787a547eab`
