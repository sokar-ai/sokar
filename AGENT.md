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
- **A local build shadows a packaged one and says nothing** - hooks and agents
  alike, by design, so either can be tried without uninstalling. That is how an
  install of today's package leaves yesterday's firewall hooks running.
  `sokar doctor` names what is in use and what it hides.
- **A shell in a directory a `clean` build removed defeats every command.** It
  cannot be detected in advance: the check would be the file operation that
  fails. `SokarCli` catches it and says so in one line.
- **The git gate binds every interface, and that is not yet fixable.** A loopback
  bind is unreachable from a rootless container (measured: connection refused).
  pasta's `--map-host-loopback` makes `127.0.0.1` reachable at `169.254.1.2` and
  works - but passing it as `--network pasta:<options>` replaces podman's own
  pasta defaults, and `e2e-tier1` then reports **open egress** with the ruleset
  still loaded. The per-task token is what keeps the gate shut. The next thing to
  try is `pasta_options` in the `containers.conf` drop-in `sokar setup` already
  writes, which adds to podman's defaults instead of replacing them.
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
- **Name a run's servers after the run *and the leg*.** Both matrix legs share `GITHUB_RUN_ID`,
  so a cleanup keyed on it alone deletes the other leg's machine mid-suite.

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
failure quickly, not for gating a commit; nothing should depend on one existing. See
[0047](requirements/0047-Separate-Repositories-And-CI.md).

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

`README.md` is an index; the substance lives in `getting-started.md`, `why.md`,
`your-tooling.md`, `build.md` and `agents/README.md`. `.sokar.md` is the planning
document — gitignored, and the place where phase status, decisions and findings
are recorded.

Write only what you have verified. Prefer a measured number to an adjective.
When something is not built, mark it **TODO** rather than describing it in the
present tense and hoping.
