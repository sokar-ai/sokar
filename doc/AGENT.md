# Working on Sokar

Conventions and hard-won facts for anyone — human or agent — changing this
codebase. Everything here is either visible in the code or was established by
measurement; where a rule exists because something went wrong, the failure is
named, because a rule without its reason gets discarded by the next person.

## The one architectural rule

**Nothing outside `agents/` may name an agent.** Not a class, not a string, not
a comment that turns into a `switch`. Sokar discovers agents at runtime by
scanning a directory and asking each binary to describe itself over varlink.

This is enforced, not trusted: `app/src/test/java/.../AgentIsolationTest.java`
is an ArchUnit test that fails the build. It was verified to bite by introducing
`if (registry.find("claude") …)` and watching the build fail with file and line.
If you find yourself wanting an exception, the answer is a new field in the agent
definition YAML, not a branch in Sokar.

The same rule in reverse: an agent module may depend on the published agent API -
`sokar-agent-api`, and `sokar-wire` for `Json` - and on nothing else of Sokar's.
Those two are the only artifacts Sokar puts on Maven Central, so the rule is also
what an agent in its own repository is *able* to resolve.

**Every shipped agent is in its own repository** —
[sokar-claude-code](https://github.com/fuinorg/sokar-claude-code),
[sokar-pi](https://github.com/fuinorg/sokar-pi) and
[sokar-omp](https://github.com/fuinorg/sokar-omp) — building against that published
contract with no checkout of this one. What remains in `agents/` is the contract and
the **stub**, which exists so the acceptance suite still has something to drive; a
suite that cannot run is one that quietly stops being maintained.

`SOKAR_E2E_AGENT` selects which agent `buildtools/e2e-tier1.sh` drives, defaulting to
`stub`. Everything else it needs — the tool's name, its prompt flag, its provider, the
variables it is pointed at a proxy with — is read from that agent's own `describe`
response. Three things were hardcoded before a second agent drove it: the tool was
looked for at `~/.local/bin/<name>`; the proxy variable was found by matching
`*UNIX_SOCKET`, which is one agent's spelling and not a rule; and `sokar agents | grep`
fails under `set -o pipefail` whenever *any* installed agent is unusable.

## Code

- **Java 25.** Records for data, sealed interfaces where the set of cases is
  closed and the compiler should check exhaustiveness.
- **Javadoc on every public type and method**, with `@param` and `@return` -
  brief, and only the most important facts. The `org.fuin:pom` parent enforces a
  good deal of this. A method whose Javadoc needs paragraphs is either doing too
  much or carrying an explanation that belongs in this file.
- **Comments say why, not what.** The useful ones name a constraint, a
  measurement, or a bug that already happened. `// Increment the counter` is
  noise; `// Before anything can connect: a window in which the directory is
  traversable is the whole hole` is not.
- **An inline comment is one line.** Not one sentence spread over three - one
  line. The reasoning that does not fit is a finding, and findings go in
  `.sokar.md` or here, where they can be found without reading the code.
- **A comment in `pom.xml` is one line too**, same rule and same reason. Build
  files attract essays about traps; name the trap and stop.
- **British-leaning spelling** in prose and comments: behaviour, recognise,
  serialise.
- Prefer a small named method over a comment explaining a block.

## Tests

- **Descriptive method names, not `testXxx`.** All 467 test methods read as
  sentences — `refusesADomainThatIsBothAllowedAndRefused`,
  `readsTheDomainsAnAgentNeeds`. There is no `testXxx` left; do not reintroduce it.
- **Every guard must be proven to fail.** A test that has never failed is a test
  nobody has checked. When adding a rule, deliberately violate it once and watch
  it break, then keep the negative case if it can be expressed as a test. This is
  how the ArchUnit rule, the FFM metadata check, the domain-coverage check, the
  git-gate firewall rule and the package freshness rule were all validated.
- **Test observable behaviour**, not internals: the generated ruleset, the
  packaged file list, what a container can actually reach.
- A test that needs podman belongs in `buildtools/e2e-tier1.sh`, not in surefire.
  Unit tests must run without a container runtime.
- **Never commit with a failing suite.** Run `./mvnw -B test` as its own step,
  read the result, then commit. Chaining build-and-commit in one command has
  already put two red commits in this history.

## Building

```
./mvnw clean install                          # JDK 25+
./mvnw -Pnative clean package                 # native binaries
./mvnw -Pnative,dist clean verify             # + .deb and .rpm
```

See [build.md](build.md). Three things that will bite:

- **Packaging binds to `verify`, not `package`.** native-image binds to
  `package`, and an inherited plugin runs before the module's own — so at
  `package` time the binary does not exist. `-Pnative,dist package` rebuilds
  everything and produces **no packages at all**, or silently leaves an older
  package beside a newer binary. `buildtools/check-packages.sh` fails on exactly
  that.
- **The `dist` profile is inherited by modules that are not agents.** It lives in
  `agents/pom.xml` so adding an agent needs no packaging config, so the aggregator
  and `sokar-agent-api` inherit it with no binary to package. `agent.package.skip`
  is on by default and each agent turns it off; `-pl agents/stub` hides the
  failure that a full-reactor build hits.
- **`${project.version}` inside a parent's `<dependencyManagement>` interpolates
  against the *inheriting* module**, not the parent. That is why the root POM
  uses a literal `<sokar.version>` property, and why an enforcer rule checks the
  two have not drifted.
- **FFM downcalls are not discovered by static analysis.** An unregistered one is
  a `MissingForeignRegistrationError` at runtime in the shipped binary. Run
  `./buildtools/check-ffm-metadata.sh` in CI, and `--update` after adding a
  downcall.

## Facts that were expensive to learn

- **FFM and static linking are mutually exclusive.**
  `Linker.defaultLookup()` itself `dlopen`s `libc.so.6`, so *any* FFM use fails
  in a static image. This is why `sokar` and `sokard` are dynamically linked and
  the three hooks — which make no FFM calls by design — are static musl.
  `wire/src/test/java/.../NoForeignFunctionMemoryTest.java` keeps them that way.
- **`java.net.http.HttpClient` negotiates HTTP/2 and exposes its pseudo-headers.**
  `response.headers().map()` contains `:status` beside the real ones, so relaying
  "every header that is not hop-by-hop" into an HTTP/1.1 response emits an illegal
  name and the client discards the whole reply. It surfaces as a connection error,
  not a header error: the agent said `Unable to connect to API` after 21 responses
  that were all HTTP 200. `VaultProxy.forwardable` drops anything starting with a
  colon. A relay built on an HTTP/1.1-only client cannot hit this, which is why it
  is specific to this rewrite.
- **A rootless container's agent user is a subordinate uid on the host.** It
  cannot open a `0600` socket the host user owns; the connection simply fails.
  Sokar does not pass `--userns=keep-id` (that would mean pinning the agent's uid,
  and 1000 is taken on `ubuntu:24.04` — the agent is 1001). So a socket the
  container must reach is world-writable inside a `0700` directory: the
  directory carries the access control.
- **A rootless container reaches the host at `169.254.1.2`.**
  `host.containers.internal` is a name podman writes into the container's
  `/etc/hosts`; it does not resolve on the host. Resolving it host-side returns
  null, which once silently removed a firewall rule and made every push hang.
- **`XDG_DATA_HOME` is podman's container storage.** Redirecting it in a test
  rebuilds every layer and leaves undeletable directories owned by mapped uids.
  Redirect something narrower.
- **Setting an agent's socket variable is not enough.** It selects the transport;
  without the base-URL variable the agent falls back to its own compiled-in
  endpoint. Both must be set. Residual DNS lookups for the provider are *not*
  evidence of a bypass — check the proxy's request log, which exists for this.
- **The snapshot builder names the modules it warms `~/.m2` with**, and one of them
  (`agents/claude`) moved to its own repository. Maven then refuses before compiling
  anything, so the warm-up produces *no output at all* and the only message is the
  builder's own "failed with exit code 1". Every snapshot build failed that way,
  whatever the image, until it was pointed at `agents/stub`.
- **A local build shadows a packaged one and says nothing** - hooks and agents
  alike, by design, so either can be tried without uninstalling. That is how an
  install of today's package leaves yesterday's firewall hooks running.
  `sokar doctor` names what is in use and what it hides.
- **A shell in a directory a `clean` build removed defeats every command.** It
  cannot be detected in advance: the check would be the file operation that
  fails. `SokarCli` catches it and says so in one line.
- **The git gate binds loopback, and only one of three ways of asking for it is
  right.** A rootless container cannot reach the host's loopback until pasta is
  told to map it there. Passing `--network pasta:--map-host-loopback,...`
  **replaces** podman's own pasta defaults: `e2e-tier1` then reports **open
  egress** with the ruleset still loaded. `pasta_options` in the `containers.conf`
  drop-in keeps those defaults but applies to every container the operator runs.
  What Sokar does is the same setting in a `CONTAINERS_CONF_OVERRIDE` file, for
  the `podman start` it runs itself - defaults kept, other containers untouched
  (`LoopbackMapping`). **Only `start` reads it**; setting it on `create` does
  nothing and says nothing, and the symptom is a push that hangs. Under
  slirp4netns podman ignores it in silence, so `task run` asks
  (`podman info -f {{.Host.RootlessNetworkCmd}}`), binds every interface and says
  why. **`task resume` replays the gate command it recorded**, so a task first run
  under pasta comes back bound to `127.0.0.1` even if podman has since been switched
  to slirp4netns, and the push then hangs. Deciding the bind again on resume would
  contradict the recorded-command design that makes resume reconstructible at all.
- **`dnsmasq --nftset` is load-bearing, and its absence is silent.** It is what
  makes a declared domain reachable rather than merely resolvable. A dnsmasq
  compiled without it accepts the config and opens nothing. `sokar doctor` probes
  for it; note that `no-nftset` contains `nftset`, so a substring check reports
  the opposite of the truth.
- **SELinux silently stops `nft` from reading a file, with nothing in the audit
  log.** `/usr/sbin/nft` is labelled `iptables_exec_t`, so running it transitions
  into a confined domain that cannot open the operator's runtime files; the denial
  is `dontaudit`ed, so `ausearch` reports no matches and only `setenforce 0` tells
  you. Measured on Fedora 44: `nft --file <path>` fails with "Permission denied"
  while `head` reads the same file in the same context. `NftHook` feeds the
  ruleset on **stdin** instead. Ubuntu has no such transition, so this cannot be
  reproduced on the development machine.
- **SELinux refuses a container's connection to a host process, whatever the socket
  is labelled.** podman relabels a mounted socket `container_file_t` with the
  container's MCS categories, and it is still denied: `connectto` is checked against
  the *server process* context, and any host program a person starts is
  `unconfined_t`. Measured on Fedora 44 - the request never reaches the proxy and
  `vault.log` stays empty, so it reads as an authentication failure. The socket is
  labelled at creation instead, by writing the context to
  `/proc/thread-self/attr/sockcreate` **before the socket is opened** - the kernel
  assigns the label at `socket()`, not at `bind()`, so wrapping the bind leaves it
  unlabelled while everything else looks right. `SocketContext.openUnixSocket()`
  exists so that cannot be got wrong at a call site. Clearing needs a NUL byte, since
  an empty write is no write at all. Also note `ausearch -m AVC -ts recent` reported
  no matches while `/var/log/audit/audit.log` held the denials; grep the file.
- **An agent that can only be given a URL needs a relay, not a relocated broker.** A
  host-side listener is unreachable from a rootless container (measured: refused via
  `169.254.1.2` and on the container's own loopback), and binding the broker inside the
  task's network namespace fails differently: it keeps the host's *mount* namespace, so it
  reads the host's `/etc/resolv.conf` and resolves nothing. `sokar vault relay` binds
  `127.0.0.1` in the namespace and forwards to the broker's socket, so the broker keeps the
  host's DNS, egress and credential.
- **Give a container a literal loopback address, never `localhost`.** Node resolves it to
  `::1` first, so an IPv4-only listener answers `ECONNREFUSED` while everything else looks
  correct. The container's resolver answers NXDOMAIN for undeclared names anyway, so
  depending on resolution there is a mistake in itself.
- **`podman unshare` runs as `container_runtime_t`, not `unconfined_t`.** Anything entering
  a task's namespace that way needs its own SELinux grant; a policy written only for the
  operator's own processes refuses it.
- **Hook binaries on disk are not a registration.** podman reads hook descriptors from
  the directories its `containers.conf.d` drop-ins name, in file-name order, and each
  `hooks_dir` replaces the last - so a drop-in sorting after Sokar's own switches the
  hooks off while every descriptor stays present and correct. `HookInstaller.registration`
  reports the four states apart (registered, never installed, naming binaries that are
  gone, shadowed by a later drop-in); `doctor` exits 69 for any but the first, and a task
  refuses to start.
- **Take the helper census before stopping anything.** Stopping a container fires the
  poststop hook, which reaps the helpers and deletes their pid files - so a count taken
  afterwards has nothing left to count. Measured: it reported none while stopping five.
- **A bind-mounted socket is bound to the file that existed when the container started.**
  A helper that replaces its socket a moment later leaves the container holding a deleted
  inode: every request through it goes nowhere while the same request from the host is
  answered. Helpers therefore record whether they must be up *before* the container or need
  the *running* container, and are started in that order.
- **Every helper of a given name writes the same pid file**, so starting a second one leaves
  the first named by nothing and reapable by nothing. Measured: two resumes of an already
  running task left two `shield watch` processes re-parented to init, and the later
  `task stop` reported "4 of 4 stopped" while they went on running. A running task now
  answers `already up; nothing to resume`, and a recorded helper that is still alive is not
  started twice.
- **The runtime's state is a phrase, not a word.** `Exited (143) Less than a second ago` is
  twice the width of the column it was printed in, and ran into the next one - in the very
  listing an operator reads to find the name of the task to resume.
- **No hook fires for a container that never started.** The poststop hook reaps every
  helper the state directory records a pid for, which covers a container that ran. A
  refused ruleset or an image that will not build leaves the credential proxy, gate and
  watcher running with nobody to stop them, holding their sockets until the next run
  trips over them. `TaskRunner.reapOrphans` covers that gap, guarded by the container's
  pid so a running task keeps its helpers.
- **`/run/user/<uid>` is cleared when the user's last session ends.** Task state does
  not survive a logout, and over SSH it does not survive the gap between two commands
  unless the account has lingering (`loginctl enable-linger`). A "state directory
  vanished" is far more often this than a bug.
- **A hook failure reaches the operator only if the hook writes it down.** The
  runtime reports an exit code and discards the hook's stderr, so `Hook.execute`
  logs the reason to `hooks.log` as well.
- **Terok is the reference when something is unclear.** Sibling checkouts live in
  `../../terok-ai/`: `terok`, `terok-sandbox`, `terok-executor`, `terok-shield`,
  `terok-clearance`, `terok-util`. It has already hit most of these problems.
  Read it for *what* to do, never for prose or code to copy — Sokar is a
  ground-up rewrite, and Apache-2.0 attribution is taken seriously here. Note
  also where Terok has *not* solved something: its Claude OAuth proxy is
  experimental and off by default, and its Copilot authentication is broken.

- **A provider is data; an agent is code.** An agent needs a binary because it has behaviour
  that cannot be expressed as data - a stream formatter, first-run setup, one CLI's quirks. A
  provider is an upstream, a header, a prefix and a path, so it is a YAML file found by a
  directory scan under `/usr/share/sokar/providers`. Do not give it a process.

- **The dialect's path belongs on the endpoint, never in the agent.** One provider serves
  different wire formats under different paths - OpenRouter answers the OpenAI dialect at
  `/api/v1` and Anthropic's at `/api`. An agent that hardcodes one cannot be pointed at a second
  provider without being rebuilt, which is exactly what it cost before.

- **Adding a field to a record that crosses varlink is two edits, and the compiler checks
  neither.** The server reads the new field, the client has to send it, and a missing one arrives
  as an empty string. `SetupContext.parameters()` is compared against the record's own components
  by a test for that reason.

## The rented test machines

Both acceptance legs boot a prepared Hetzner snapshot, found by label, and destroy the server in
a `finally`. `buildtools/ci/` holds the driver (`remote-tier1.py`), the API helpers
(`hetzner.py`) and the leak sweeper (`sweep.py`). **The snapshot *builder* is not in the
repository** - it lives beside it, so the image cannot currently be rebuilt by anyone else. That
is a gap, not a decision.

The Ubuntu leg runs **26.04**, not 24.04. 24.04 ships podman 4.9.3 and always will - podman is
in `universe` and a stable release does not change major versions - and Sokar now refuses podman
4, so a 24.04 machine could not run the suite at all. Mixing a newer release's podman into 24.04
was measured and is not an option either: it upgrades libc6 2.39 to 2.43 and 154 other packages,
which is a dist-upgrade wearing a 24.04 label. The cost of the move is that the published binary
is built against a newer glibc, because a native image links it dynamically and FFM rules out a
static one.

What the images contain: GraalVM 25.0.2 pinned by its published digest, the musl cross-toolchain
and a musl-built zlib, `gcc`/`glibc-devel`/`zlib-devel`, podman with `ubuntu:24.04` and
`alpine:3.20` pre-pulled, Sokar's SELinux policy loaded on the Fedora leg, and a warm `~/.m2`
from building the project once. The checkout itself is deleted - it goes stale immediately, the
dependency cache does not. 1.48 GB, about EUR 0.018 a month; a run boots one in about a minute.

Provisioning a machine that can build Sokar took nine attempts, four of which failed on the
provisioning rather than on the suite:

- **Hetzner's Fedora image ships SELinux `permissive`.** The targeted policy is installed and
  `/sys/fs/selinux` is present; only the mode is wrong. It needs the config changed,
  `/.autorelabel`, and a reboot - and since everything is installed while permissive, the
  relabel is not optional.
- **Sokar's own policy module is on no fresh machine.** Without `sokar_socket` a task container
  is denied `connectto` on its own vault socket, the denial is `dontaudit`'ed, and it presents as
  an agent that cannot authenticate with **nothing in the audit log**. Compile and load it while
  the checkout is still there, and check `semodule -l` afterwards.
- **`install -d -o build` sets ownership on the last component only**, so `/home/build/.local`
  stayed root-owned and rootless podman would not start. A single root-owned directory in a
  user's home reads as a Sokar permissions bug.
- **An ordinary user has no `sudo`**, which is correct - so nothing in a run may assume it.
  Install into the operator's own directories, which is the shape a task runs in anyway.
- **Verify by compiling, not by inspecting.** "`gcc` is present" is not "native-image works": a
  missing compiler surfaces twenty minutes into a build as *"Default native-compiler executable
  'gcc' not found"*. The check builds a real probe twice, once ordinarily and once
  `--static --libc=musl` as the hooks need. An earlier version reported a compiler missing that
  was there, because its scratch directory came from `mktemp -d` as root while `javac` ran as
  the build user - a check that fails for its own reasons is worse than no check.
- **Do not rebuild the image to test a change to the provisioning.** Provision once with
  `--keep` and iterate against the live server over SSH. Every defect above was found at the end
  of a fifteen-minute cycle and would have been found in under a minute that way.
- **`eu-central` is a network zone, not a location.** `servers.create()` takes a location;
  `fsn1`, `nbg1` and `hel1` all sit in that zone. Passing the zone fails.
- **Do not hard-code a location.** Availability is per datacentre and changes: on 2026-09-06
  `fsn1` offered *zero* server types while `nbg1` and `hel1` offered eighteen. The failure is
  `unsupported location for server type`, which reads like a wrong type or a bad token rather
  than a full datacentre. `hetzner.location_for()` asks which location in `eu-central` currently
  has the type and uses that.
- **A Containerfile here-document builds on podman 5 and not on podman 4.** podman 4.9.3,
  which Ubuntu 24.04 ships, reads every line as an instruction and fails with
  `Unknown instruction: "IF"`. Anything an agent contributes to an image has to be one
  `RUN`, continued with backslashes. It also has to be in the *definition*: `imageLayer()`
  is never called by Sokar, which builds from the `describe` response's `installAsRoot`.
- **Three repositories rent from one project, and nothing coordinates them.** The core's two
  acceptance legs plus an agent's two can ask for six machines at once; the API answers
  `resource_limit_exceeded` and the run dies after having built everything. Creating a server
  now waits and retries on that one error - and only that one, because a bad image or a full
  datacentre is not something waiting fixes. The agent repositories also run their legs
  `max-parallel: 1`, so each takes one machine at a time rather than two.
- **Name a run's servers after the run *and the leg*.** Both matrix legs share `GITHUB_RUN_ID`,
  so a cleanup keyed on it alone deletes the other leg's machine mid-suite.

## Publishing

**Two artifacts reach Maven Central: `sokar-agent-api` and `sokar-wire`.** Nothing else,
because nothing else is a contract anyone outside resolves. The packages go to
Artifactory, `sokar-dist-deb` and `sokar-dist-rpm`, together with the agents' — an agent
package declares `Depends: sokar`, so split across repositories the dependency would not
resolve from one configured source.

- **Two different properties control it, and only one is real under the release profile.**
  `maven.deploy.skip` belongs to `maven-deploy-plugin`, which never runs under
  `-Pcentral-sonatype-release`: `central-publishing-maven-plugin` is a build extension that
  injects its own goal into every module. `skipPublishing` is what it reads. Both are
  default-deny in the root and turned off in exactly those two modules. A local
  `-DaltDeploymentRepository` check exercises the *other* plugin and proves nothing about CI.
- **`jf rt upload` needs `--flat=true`.** Without it the source directory travels into the
  target and the package lands one level deep, reporting success.
- **A Debian upload without `deb.distribution`, `deb.component` and `deb.architecture` is
  stored and never indexed**, with no error anywhere. Setting them needs **Annotate**
  permission; overwriting the stable snapshot file name needs **Delete**. Both are separate
  from Deploy.
- **Build-info is not published.** It writes to `artifactory-build-info`, which the token
  cannot reach, and `disable-auto-build-publish: true` stops the action attempting it.
- **`.github/workflows/artifactory-smoke.yml`** checks all of that in twenty seconds without
  building anything. Run it after rotating the token.

**The published binary is the one the acceptance suite tested.** The Ubuntu leg fetches its
binaries back before the server is destroyed and the publish job packages those, rather than
compiling its own. Two reasons: a hosted runner has no musl cross-compiler, so the static
hooks cannot be built there at all; and until this changed, the suite exercised one build
while the packages shipped another. **Ubuntu, not Fedora** — a native image links glibc
dynamically, so one built on Fedora will not start on Ubuntu 24.04 while the reverse runs on
both. Both packages carry identical bytes, so there is one binary to get right. The leg
writes a manifest beside the binaries and the publish job reads it, because the two lists
drifted when they were written down twice.

## Security rules that are not negotiable

- **Never put a secret in a command line.** A process list is world-readable.
  Credentials go in through standard input, an environment variable on a
  process you spawned, or a `0600` file — never `argv`.
- **The real credential never enters a task container.** The agent gets a
  phantom token; the vault proxy swaps it on the way out. If you find yourself
  passing the real key in, stop and reconsider the design.
- **What must not leave the container is the credential, not the traffic.** The
  provider's own host is reachable: withholding it stopped every agent that checks
  the provider is up before starting, and measured on 2026-09-04 that check ignores
  the base URL and the socket entirely. What the deny kept in was the phantom token
  - random, task-scoped, worth nothing to the provider. The real credential never
  enters the container, and that is the property to defend.
- **Fail closed.** The nft hook refuses to let the container start if the ruleset
  will not load. Keep that property in anything new on the startup path.
- **Log tokens abbreviated, never whole** — `PhantomToken.abbreviate`. A log that
  contains a working credential is a credential store with no lock on it.

## Commits

One brief line. The reasoning behind a change is a finding, and a finding goes in
`.sokar.md` or in this file, where it can be found later without `git log`.

## Documentation

**Run the unit suite before committing.** The acceptance suite is CI's job, on `main`, on two
rented machines - one with SELinux enforcing, one with podman 4. Before a release, run it
deliberately rather than assuming a green badge covered it. A development VM is for diagnosing a
failure quickly, not for gating a commit; nothing should depend on one existing.

**A documentation-only push builds nothing.** `paths-ignore: ['**/*.md']` on push and pull
request: nothing in the build reads a markdown file, and on `main` a run rents two machines
and republishes. A push that mixes docs and code still builds - the filter is per push, not
per file - and `workflow_dispatch` ignores it, so a run can always be forced.

**A requirement that is done is deleted**, file and index row together, once whatever
is worth keeping has moved into this file. They describe work to do, not work that was
done; git history is where finished work lives.

**Requirements are not referenced from code.** No class, comment, commit message or
test may cite a requirement number. Requirements move, merge and are dropped; code
that names one goes stale silently and starts to look like a contract. A comment
should name the constraint itself, which is what makes it worth reading anyway.

**It is the agent API, not an SPI.** `sokar-agent-api` carries both halves of the
contract: the types Sokar calls to discover and drive an agent, and the types an
agent implements so Sokar can call in. "SPI" names only the second half and reads
as though the first is not there. Say **the agent API**, or **the agent contract**
where the point is that both sides share it.

**Every agent and provider names its upstream project by link, and an implemented one
carries a `README.md` in its own directory that opens with that link.** Two projects can
share a name - Pi and Oh My Pi are different codebases from different authors - and a
requirement that says only "Pi" cannot be checked by anyone. The link is the identity;
the npm package or download URL beside it is what the build actually installs.

`README.md` in the root is an index; the substance lives in `doc/` —
`getting-started-debian.md`, `getting-started-fedora.md`, `faq.md`, `why.md`,
`your-tooling.md`, `sokar-for-dummies.md`, `build.md` and this file — plus
`agents/README.md`. `README.md` is the only markdown file in the root, so a new
document goes in `doc/` and is linked from the index. `.sokar.md` is the planning
document — gitignored, and the place where phase status, decisions and findings
are recorded.

Write only what you have verified. Prefer a measured number to an adjective.
When something is not built, mark it **TODO** rather than describing it in the
present tense and hoping.
