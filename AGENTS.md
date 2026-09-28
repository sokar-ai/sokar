# Working on Sokar

Conventions and facts for anyone — human or agent — changing this
codebase. Everything here is either visible in the code or established by
measurement; every rule carries its reason, stated so that it stays true,
because a rule without its reason gets discarded by the next person.

## Before you propose a push

**`mvn test` is not "the tests".** It runs surefire, which is unit tests only - a test that needs
podman or a machine belongs in the acceptance suite, which surefire never sees. So "1131 tests
green" can be true while the product is broken in ways CI will find fifteen minutes and two rented
machines later.

**So the acceptance suite runs against a real machine before a push is suggested, not after.**

```
SOKAR_VM=user@host SOKAR_VM_KEY=<key> ./mvnw -q -pl buildtools/hetzner exec:java@deploy   # install what you are about to push
./mvnw -pl acceptance/suite verify -Dsokar.acceptance.host=<ip> 2>&1 | tee suite.log
```

About that second line:

- **Do not pipe it into `grep`.** The pipeline buffers and you see nothing at all until it ends,
  which is indistinguishable from a hang. `tee` shows each scenario as it happens.
- **It is slower here than in CI, not faster.** Measured: half an hour locally
  against six minutes on a rented `cpx42`, because the scenarios that build images and wait up to
  480s for a prompt dominate, and a VM on a desktop is slower at both. What it buys is not speed -
  it is an answer without spending a CI round and two rented machines, and one you can watch and
  interrupt. Run the fast half first with `-Dcucumber.filter.tags='not @slow'` when the change only
  touches what the CLI prints.
- **`sokar.acceptance.user` and `sokar.acceptance.key` default to the test identity.** Pointing the
  suite at a machine is one property; the rest is already right.

What the suite covers that nothing else does: the CLI's own words at a real terminal, with a pty -
`isTerminal()` is false everywhere else, so a message a person sees is one no unit test reads.

**A machine-specific note may exist in `.AGENTS.md`**, beside this file and not committed. It holds
what is true of one machine rather than of the project - which VM is there, which account, where a
key is. Read it if it exists; do not put anything in it that the next person on a different machine
would need.

## The one architectural rule

**Nothing outside `agents/` may name an agent.** Not a class, not a string, not
a comment that turns into a `switch`. Sokar discovers agents at runtime by
scanning a directory and asking each binary to describe itself over varlink.

This is enforced, not trusted: `app/src/test/java/.../AgentIsolationTest.java`
is an ArchUnit test that fails the build. It bites: introducing
`if (registry.find("claude") …)` fails the build with file and line.
If you find yourself wanting an exception, the answer is a new field in the agent
definition YAML, not a branch in Sokar.

The same rule in reverse: an agent module may depend on the published agent API -
`sokar-agent-api`, and `sokar-wire` for `Json` - and on nothing else of Sokar's in compile
scope. In test scope it may add `sokar-acceptance-kit`, to drive a machine from its own
scenarios, and it imports `sokar-bom` so that it names no Sokar version and gets JUnit as one
set. Those four are the only artifacts Sokar puts on Maven Central, so the rule is also what an
agent in its own repository is *able* to resolve.

**Every shipped agent is in its own repository** —
[sokar-claude-code](https://github.com/sokar-ai/sokar-claude-code),
[sokar-pi](https://github.com/sokar-ai/sokar-pi) and
[sokar-omp](https://github.com/sokar-ai/sokar-omp) — building against that published
contract with no checkout of this one. What is in `agents/` is the contract and
the **stub**, which exists so the acceptance suite has something to drive; a
suite that cannot run is one that quietly stops being maintained.

**Testing by hand rather than by leg.** A leg runs the suite once on a machine that is then
deleted, which is the wrong shape for sitting in front of an interface.
`./mvnw -pl buildtools/hetzner exec:java@deploy`, with `SOKAR_VM=user@host` and `SOKAR_VM_KEY`,
builds the same packages CI would publish and installs them on a machine that stays: it enables
lingering so tasks survive a logout, restarts the daemon, and prints the socket an interface
connects to. `-Ddeploy.options=--skip-build` installs what is already in `target/`. The interface is not installed by it - that runs natively where
the person is and forwards the socket over ssh.

The scenarios that start a task drive the **stub** and name it, with `--agent stub`: a machine a
person develops on carries real agents too, and a scenario that left the choice to Sokar would be
refused as ambiguous there. What they assert about it - the names it is refused, the host its
broker reaches - is read from `sokar agents --verbose` rather than written down twice.

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
- **US spelling** in prose and comments: behavior, recognize, serialize, license,
  defense. Identifiers included - `GitGate.initialize()`, not `initialise()`. Two
  words stay as they are because they are not errors in US usage and changing
  their 65 uses would be diff noise: *afterwards* and *towards*.
- Prefer a small named method over a comment explaining a block.

### Nullness is checked when it compiles

**NullAway runs in every main compile, as an error.** Error Prone with only NullAway enabled, in the
root POM's `default-compile` execution, scoped by `OnlyNullMarked`. Test code is not checked: a test
passes `null` on purpose to watch a refusal. The reason is measured: of 210 findings, four were
defects a person would meet - `sokar doctor` threw instead of reporting an unreadable record,
`shield egress` and `talk pass` looked up a project called `null` (`talk pass` takes the task's
own, as `shield egress --task` does), and `vault serve` would write its token to no file at all. Each has a test, watched to fail against the unfixed code.

- **A package without `@NullMarked` is skipped in silence.** Measured: a dereference of a `@Nullable`
  result, planted in the acceptance kit, failed the build with the kit marked and compiled green
  without its `package-info.java`. `NullMarkedPackagesTest` fails on any package with main code that
  is not marked, so a new package carries it from its first commit.
- **NullAway trusts what this repository does not annotate.** `System.getenv(...).length()` passes: a
  JDK method is assumed non-null. So a planted defect that proves the check runs has to dereference
  something declared `@Nullable` here, such as `Json.parse` - a plant that uses `getenv` proves
  nothing.
- **Only what is compiled is checked.** An incremental build leaves an up-to-date module alone, and
  NullAway with it: `-pl daemon -am` reports nothing for findings in `app`. Judge with `clean`.
- **The compiler's lists append.** `app` declares picocli's processor, which writes the native image's
  metadata; a `default-compile` execution in the root that replaced `annotationProcessorPaths` would
  drop it with every unit test green and fail only in the shipped binary. Both lists carry
  `combine.children="append"`, checked in `help:effective-pom`.
- **picocli sets its fields, but only the ones it is given.** They are excluded from NullAway's
  initialization check by annotation, and an optional option with no default is declared
  `@Nullable`, so every use of it is checked. `OptionNullnessTest` walks the whole command tree and
  fails on one that is not.
- **`.mvn/jvm.config` is what lets Error Prone run on JDK 25**: without it javac's internals are
  closed and the compile dies before checking a file. `./mvnw` reads it; a bare `mvn` from another
  directory does not.
- **No suppressions.** Where a value cannot be `null` for a reason the checker cannot see, a
  `requireNonNull` says the reason in its message - five of them, each one an invariant rather than a
  hope.

## Tests

- **Descriptive method names, not `testXxx`.** All 775 test methods read as
  sentences — `refusesADomainThatIsBothAllowedAndRefused`,
  `readsTheDomainsAnAgentNeeds`. There is no `testXxx`; do not introduce one.
- **A family of guards needs one that has fired, or their green means nothing.** Guards comparing
  bills of materials show it: one that compares an empty set, one that compares five Java libraries
  that cannot change, one whose bill is shipped and compared by no job at all, and one that compares
  140 components and has stopped a release are **all green in the same way.** The working one is
  the instrument - it is what makes the others visible, and a project holding only the others has
  no reason to look. So when several checks of one kind all pass, ask which of them has ever failed;
  if the answer is none, that is the finding.
- **Every guard must be proven to fail.** A test that has never failed is a test
  nobody has checked. When adding a rule, deliberately violate it once and watch
  it break, then keep the negative case if it can be expressed as a test. The
  ArchUnit rule, the FFM metadata check, the domain-coverage check, the git-gate
  firewall rule and the package freshness rule are all validated this way.
- **Watch the RIGHT thing fail.** Breaking the rule is only half of it. An agent
  name-collision check that asserts the "ignored" message survives its own
  mutation: the message is built from the losing side and stays word-for-word
  identical while the register holds the other copy. That tests the reporter, not
  the behavior. Assert on what the system DOES - which binary the `FROM` column names,
  which container exists, what a host can reach - never on what it says about
  itself. The same trap in two other shapes: a grep that matches the fixture's
  own name (a task called `lockedrun`, greped for "locked"), and a test for a
  state a constructor forbids. If a mutation leaves a check green, the check is
  the thing that is broken.
- **Test observable behavior**, not internals: the generated ruleset, the
  packaged file list, what a container can actually reach.
- **A fixture must not inherit the machine's configuration.** `git init` takes
  its branch name from `init.defaultBranch`, so a test that pushes `main` into a
  fixture built without `--initial-branch` produces an upstream whose HEAD names
  a branch that was never created - and `git fetch origin`, which asks for HEAD
  when given no refspec, then fails. Green on a laptop that sets the option,
  red on CI which does not. Run the suite with `GIT_CONFIG_GLOBAL=/dev/null`
  before trusting anything that shells out to git.
- **Keep the reason in the assertion.** `assertThat(x.reason()).isEqualTo(MEASURED)`
  fails with "expected MEASURED but was FAILED" and throws away the detail the
  object is carrying. `.as("git said: %s", x.detail())` turns a reproduction
  round into a readable CI log.
- A test that needs podman belongs in the acceptance suite, not in surefire.
  Unit tests must run without a container runtime.
- **Never commit with a failing suite.** Run `./mvnw -B test` as its own step,
  read the result, then commit. Chaining build-and-commit in one command commits
  without anyone reading the result, and puts red commits in the history.

## Building

```
./mvnw clean install                          # JDK 25+
./mvnw -Pnative clean package                 # native binaries
./mvnw -Pnative,dist clean verify             # + .deb and .rpm
```

See [build.md](doc/build.md). Three things that will bite:

- **Packaging binds to `verify`, not `package`.** native-image binds to
  `package`, and an inherited plugin runs before the module's own — so at
  `package` time the binary does not exist. `-Pnative,dist package` rebuilds
  everything and produces **no packages at all**, or silently leaves an older
  package beside a newer binary. `buildtools/package-check` fails on exactly
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
  a `MissingForeignRegistrationError` at runtime in the shipped binary. CI runs
  `./mvnw -Pnative,ffm-check -Dagent=true -pl buildtools/ffm-check,core,shield,vault -am verify`, which fails on a downcall
  the tests made that is not registered; add `-Dsokar.ffm.update=true` after
  adding a downcall, and commit the result.
- **Every native image is x86-64 v1.** `-march=x86-64` is in the root POM's
  native build arguments, and `buildtools/cpu-check` fails the `package` of any
  image whose own "required by the image" list is not exactly
  `[CX8, CMOV, FXSR, MMX, SSE, SSE2]`. A new image goes into its module's
  `sokar.cpu.images`, or the check never sees it.

### What stays in another language, and why

The build is Java and Maven; a file in another language is listed here with the reason it cannot
be. The first two run on the operator's machine, which has Sokar's native binaries and no Java; the
third prepares a build machine before Maven can use it; the fourth is a test on its way into Java:

- `selinux/install-selinux-policy.sh` - the `.deb` and `.rpm` ship it, and an administrator runs it
  once with `sudo` to compile and load the SELinux module; `sokar doctor` names the command. The
  packages run nothing when they install. It runs where there is no Java, and loading a policy
  module is shell by nature.
- `dist-setup/sokar-setup.sh` - published beside the packages and run as root on a machine that has
  nothing yet: no package, no daemon, no Java. A Java version would need the runtime it is there to
  install.
- `buildtools/install-musl.sh` - prepares a machine before Maven can link the hooks: two pinned,
  digest-checked downloads, and zlib built from source with `make` against the musl compiler it
  unpacked. The build only reads `musl.home`; a Maven plugin would be the same downloads and the same
  `make` with more lines around them.

- The acceptance suite is where the end-to-end checks are: a real image, a real container, the
  firewall, the broker, the gate and the daemon, driven over ssh as a person or a script would. A
  leg installs the build into the build user's own directories - binaries, hooks, the daemon,
  providers, egress sets and the agent it made - and runs every scenario against that.

Nothing else in the repository is a script: the package check, the VM deploy, the FFM metadata
check and the bill comparison are Java.
The `sh -c` commands the Java sends - into a container from `Podman`, `TaskControl` and
`TaskWorkspace`, over ssh from the kit's `Machine` and the legs - are not scripts: there a shell is
the interface, and what they decide is decided in Java before they are sent.

**The shared release tooling is `sokar-release`**, published to Central like `sokar-machines`, and
what the agent repositories' update jobs and builds call: comparing, adding to and merging bills,
the upstream version lookup, the version move, the pin check. Two choices shape it.
**Commands, not a Maven plugin**: the update job is not a lifecycle phase - it runs on a schedule
and opens a pull request - and what does run in a build is called through `exec-maven-plugin` just
as well, with no descriptor or plugin harness.
**A separate artifact, not part of `sokar-machines`**: that one brings an SSH stack, sshj and
BouncyCastle, and folding the release tools in would put an SSH client on the classpath of every
package build and tie the two versions together. An agent's difference is configuration or a
strategy in it, never a copy.

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
  not a header error: the agent reports `Unable to connect to API` while every response
  is HTTP 200. `VaultProxy.forwardable` drops anything starting with a
  colon. A relay built on an HTTP/1.1-only client cannot hit this.
- **A secret goes in through standard input or an environment, never an argument.**
  `/proc/<pid>/cmdline` is world-readable and `/proc` is mounted without `hidepid` on both
  supported distributions, measured; `/proc/<pid>/environ` is owner-only. `vault put` reads the
  value from stdin for that reason, and every variable a container gets is named on podman's
  command line without its value: `--env NAME` makes podman copy it from its own environment,
  which Sokar sets. Measured against podman 5.7.0, including a value carrying spaces, a colon and
  trailing `==` - the gate's `Authorization: Basic ...` header - which arrives byte for byte.
- **A name podman cannot resolve is dropped, not passed as empty.** So the arguments naming the
  variables and the map carrying their values must come from the same object, or a container comes
  up silently missing one - and an agent missing its endpoint variable quietly uses its own
  compiled-in one instead of failing. `ContainerSpec.toArguments()` and
  `ContainerSpec.environment()` are that pair; `Command.withEnvironment` carries it to the
  process.
- **Verify a passphrase before caching it.** A wrong one accepted now fails at the next command,
  where it reads as a corrupt store rather than as a typo. `vault unlock` opens the vault first and
  caches nothing when it cannot. Nothing to verify against on a first run, which is also the run
  that sets the passphrase.
- **Locking is a verb, and it does not reach a running task.** `sokar vault lock` drops the cached
  passphrase; a task that is already up read its credential when its proxy started and holds it in
  that process's memory until the task stops. The command says so when any task is running, because
  an operator who believes otherwise has locked nothing they think they locked. `unlock --forget`
  is the same operation under another name and goes through the same code.
- **A token printed in full defeats the type that hides it.** `TaskToken.toString` and
  `PhantomToken.toString` both abbreviate because tokens end up in log lines by accident - and
  `token.value()` written into `gate.log` reaches whatever is tailing it, since the daemon streams
  that log. Print the token, not its value.
- **An unmodifiable map is not a copied one.** `Map.copyOf` loses insertion order, and the egress
  report is read in the order its sources were consulted - the agent's hosts, then its provider's,
  then the project's, grouped by the set that granted them. A report whose order changes between
  runs cannot be diffed against yesterday's. Wrap a `LinkedHashMap` instead.
- **A registry that two processes write is a directory of files, not one document.** A registry
  kept as a single JSON file means read-modify-write: 24 concurrent starts keep one entry and lose
  23, measured, and writers sharing one temporary file can move a mixture of two documents into
  place. One file per project removes the
  class of problem - different projects never touch the same file, the same project writes the same
  bytes - rather than guarding it with a lock.
- **A remote client has no filesystem, so the daemon hands out paths rather than taking them on
  faith.** Every gate method and `Start` take a `project.yml` path, which is fine for a CLI typed
  on the machine that holds it and impossible over a forwarded socket. `Projects` answers what
  exists - from the gate mirrors, the tasks that exist, and a registry each task start writes - so
  a client passes back a path this machine gave it. A recorded file that has moved is reported as
  absent rather than as a path nothing can read: a call made with it would fail in a way that looks
  like a fault in the daemon.
- **A unix socket file outlives the process that made it.** Nothing unlinks it on SIGTERM, and
  the next server unlinks it before binding - so a check written as "is the socket file there?"
  answers yes for a daemon that died hours ago, and a test guarded by it skips itself and reports
  success. The acceptance suite's daemon section asks `org.varlink.service.GetInfo` through
  `sokar daemon connect` and believes the answer, not the file.
- **A stdio bridge to the daemon must copy bytes, not lines.** `sokar daemon connect` exists so
  that `ssh host sokar daemon connect` speaks varlink down the ssh session with no socket file on
  the client. Frames are NUL-separated JSON and a stream is answered over time, so it flushes on
  every read: a line-buffered bridge holds a reply until the next one arrives, which for a fleet
  watch means holding it until something changes.
- **`-L` and `connect` differ in what a second stream costs.** A forwarded socket carries as many
  connections as a client opens; `connect` is one connection per invocation, so three streams are
  three ssh sessions. Measured with a fleet watch and two log tails at once - all three delivered,
  none blocked another.
- **A tail's 64 KB chunk is a rate, not a limit.** A 1.5 MB log arrives complete, in 24 replies,
  but the loop sleeps 200 ms between chunks so a backlog drains at roughly 320 KB/s whatever the
  transport can do. The sleep is there so a live tail does not spin; changing it is a deliberate
  decision, not a tidy-up.
- **A failure is held, not swept up.** The container is kept by default, and `--rm` has to be
  decided before a run - but the run worth looking at is the one that went wrong, which is known
  only afterwards. So a non-zero exit stops the container through `TaskControl` and leaves it even
  when `--rm` was given: workspace, logs and unpushed commits intact, `task start` to go back in,
  `task remove` to discard. Stopped rather than left running,
  because a task nobody is watching that still holds a firewall, a gate and a credential proxy is
  not kept, it is abandoned.
- **`sokar panic` stops everything and removes nothing.** The reason for reaching for it is that
  something is going wrong and nobody yet knows what, which is exactly when destroying the evidence
  is worst. It takes no container names on purpose: somebody in that position is not going to list
  what is running first. It goes through the same `TaskControl.stop` a deliberate stop uses, so the
  record of what a task held that never reached the gate is written for every task it stops.
- **`podman diff` is what answers "what did the agent install in there".** Added paths only: a
  container that ran at all reports `/etc` and `/var` as changed, and a number that is never zero
  means nothing. Counted before removal, because afterwards there is nothing left to ask - it is
  the one part of a task with nowhere to arrive, unlike the workspace, which has the gate.
- **A diagnostic that names no next action is not a diagnostic.** `sokar doctor` reports each
  dependency as a `Probe`, and the record's constructor refuses any state but `OK` without one -
  the line somebody forgets is the line an operator is reading at their worst moment. `UNKNOWN` is
  a state of its own for the same reason: every dependency here is invisible until a task behaves
  strangely, so a probe that guesses well cannot be told from one that works.
- **`project.yml` is edited as text, never round-tripped through a YAML model.** It is the one
  file here a person writes by hand and a colleague reads in a diff, and a load-and-dump throws
  away every comment, the key order and the quoting they chose. `EgressEdit` replaces the two keys
  where they stand and leaves any line it does not understand alone; the result is parsed by
  `ProjectReader` before it is written, so a surgical edit that produced something the reader
  refuses fails at the edit rather than at the next task.
- **A rootless container's agent user is a subordinate uid on the host.** It
  cannot open a `0600` socket the host user owns; the connection simply fails.
  Sokar does not pass `--userns=keep-id` (that would mean pinning the agent's uid,
  and 1000 is taken on `ubuntu:24.04` — the agent is 1001). So a socket the
  container must reach is world-writable inside a `0700` directory: the
  directory carries the access control.
- **A rootless container reaches the host at `169.254.1.2`.**
  `host.containers.internal` is a name podman writes into the container's
  `/etc/hosts`; it does not resolve on the host. Resolving it host-side returns
  null, and a firewall rule built from that answer is silently left out - every push then hangs.
- **`XDG_DATA_HOME` is podman's container storage.** Redirecting it in a test
  rebuilds every layer and leaves undeletable directories owned by mapped uids.
  Redirect something narrower.
- **Setting an agent's socket variable is not enough.** It selects the transport;
  without the base-URL variable the agent falls back to its own compiled-in
  endpoint. Both must be set. Residual DNS lookups for the provider are *not*
  evidence of a bypass — check the proxy's request log, which exists for this.
- **The snapshot builder names the modules it warms `~/.m2` with**, so each one must
  exist in this repository: `agents/stub` does, `agents/claude` is in its own repository.
  Naming a missing one makes Maven refuse before compiling anything, so the warm-up
  produces *no output at all* and the only message is the builder's own "failed with
  exit code 1" - for every snapshot build, whatever the image.
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
  **replaces** podman's own pasta defaults: an undeclared address is then
  **open** with the ruleset still loaded. `pasta_options` in the `containers.conf`
  drop-in keeps those defaults but applies to every container the operator runs.
  What Sokar does is the same setting in a `CONTAINERS_CONF_OVERRIDE` file, for
  the `podman start` it runs itself - defaults kept, other containers untouched
  (`LoopbackMapping`). **Only `start` reads it**; setting it on `create` does
  nothing and says nothing, and the symptom is a push that hangs. Under
  slirp4netns podman ignores it in silence, so `task start` asks
  (`podman info -f {{.Host.RootlessNetworkCmd}}`), binds every interface and says
  why. **Bringing a task back replays the gate command it recorded**, so a task first run
  under pasta comes back bound to `127.0.0.1` even if podman has been switched
  to slirp4netns in between, and the push then hangs. Deciding the bind again on resume would
  contradict the recorded-command design that makes resume reconstructible at all.
- **`dnsmasq --nftset` is load-bearing, and its absence is silent.** It is what
  makes a declared domain reachable rather than merely resolvable. A dnsmasq
  compiled without it accepts the config and opens nothing. `sokar doctor` probes
  for it; note that `no-nftset` contains `nftset`, so a substring check reports
  the opposite of the truth.
- **SELinux silently stops `nft` from reading a file, with nothing in the audit
  log.** `/usr/sbin/nft` is labeled `iptables_exec_t`, so running it transitions
  into a confined domain that cannot open the operator's runtime files; the denial
  is `dontaudit`ed, so `ausearch` reports no matches and only `setenforce 0` tells
  you. Measured on Fedora 44: `nft --file <path>` fails with "Permission denied"
  while `head` reads the same file in the same context. `NftHook` feeds the
  ruleset on **stdin** instead. Ubuntu has no such transition, so this cannot be
  reproduced on the development machine.
- **SELinux refuses a container's connection to a host process, whatever the socket
  is labeled.** podman relabels a mounted socket `container_file_t` with the
  container's MCS categories, and it is still denied: `connectto` is checked against
  the *server process* context, and any host program a person starts is
  `unconfined_t`. Measured on Fedora 44 - the request never reaches the proxy and
  `vault.log` stays empty, so it reads as an authentication failure. The socket is
  labeled at creation instead, by writing the context to
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
- **A task's workspace is inside its container, and it stays there on purpose.** Mounting a
  host directory at `/workspace` would let an operator open the work with ordinary tools -
  and it puts agent-controlled content on the host: `.git/hooks` runs on the next host-side
  git command, and `.git/config` can point `core.pager`, `core.fsmonitor` or an alias at
  anything, so `git status` in that directory is enough to run whatever the agent wrote.
  Another project in this space takes that option and calls the directory
  `workspace-dangerous`; its own warning is the argument against it. **The same rule forbids reading a workspace from the host to
  check it**, which is why the check below is a note rather than a look.
- **Nothing can ask a stopped container what it holds.** `podman exec` needs a running one,
  and removing a task is exactly when nobody is running it. The census is therefore written
  into the state directory on the way down, while it is still knowable, and read back by
  whoever removes the task later; it cannot go stale, because a stopped container's
  filesystem does not change. A stopped task with no note at all is refused rather than
  guessed at. Without it, a task stopped first and removed afterwards is removed silently,
  with its unpushed commit, reporting success.
- **A rescue that pushed nothing must not report success**, because the caller removes a
  container on the strength of that answer. In a repository with no initial commit, a push that
  asks for `HEAD` before committing prints `nothing to push` and exits zero, and the container is
  removed as rescued while the mirror never sees a ref. Commit first, and exit
  non-zero when there is genuinely nothing.
- **The guard covers `sokar task remove` and nothing else.** A `podman rm` typed
  directly, or a tidy-up script, still destroys a workspace without a word: nothing Sokar
  writes can stop the runtime's own command.
- **The clearance watcher has two ways in, and both must broadcast.** When it starts
  its own reader, events arrive as varlink `Report` calls and subscribers see them on the way
  past. When the reader hook is already running inside the container - the normal case - the
  watcher *follows the file* that hook appends to. A path that reaches the hub without reaching a
  subscriber leaves a client subscribed to a live task seeing nothing at all while the log beside
  it records the decisions. Both ways in call `ClearanceService.publish`.
- **A decision that lives only in the watcher is a retry loop.** The hub deduplicates in a map in
  its own process, and a resumed task starts a fresh watcher that re-reads its events file from
  the beginning - so every destination already decided arrives again within seconds and is asked
  about a second time, and an agent refused once only has to keep retrying until something
  restarts the watcher. Decisions are appended to `~/.local/state/sokar/clearance/<container>.jsonl` and
  read back on start. Under the *state* directory because everything else a task writes is under
  the runtime one, which the kernel clears at logout and `task stop --remove` deletes outright: a
  record of what an agent reached that disappears with the task is not a record. Named by the
  container, so a decision belongs to one run and is not silently in force for the next.
- **A restored allow has to be put back into the firewall, not only remembered.** A resumed task
  gets a fresh namespace and a ruleset the hook rebuilds from the project, so an address cleared
  in an earlier run is not in it. Remembering the answer alone leaves the hub saying allow while the
  packets are still dropped and nothing will ever ask again - worse than either honest state.
- **`nft add element` answers `File exists` for an address already in the set.** A watcher
  restarted against a container that kept running re-applies every decision it recorded, so this
  is the normal case rather than an edge one. `EgressPolicy.allow` treats it as the outcome asked
  for; anything else is still a failure.
- **A notification that is closed rather than replaced destroys the only evidence it existed.**
  Closing an expired clearance prompt leaves the destination blocked forever and takes the question
  off the screen, so an operator who has been away cannot tell it from one that was never raised. The
  timeout sends a second `Notify` carrying the first notification's id, without actions and
  with urgency dropped from critical to normal - critical notifications never expire on their own,
  which is also why the millisecond timeout passed to the first one does nothing.
- **A question on a stream has to carry its own answer.** A subscriber that saw a prompt and never
  saw a verdict cannot tell one that ran out from one still waiting, because the only other
  evidence is an event that stops arriving. Every decision - allow, deny, or nobody answered - is
  broadcast as the same event with a `verdict`, which is also what feeds the record, so an answer
  given from a client is written down like one given at the machine.
- **`sokard` speaks varlink on an owner-only socket, and that is the whole access story.**
  No listener on any interface, so remote access is a tunnelling problem rather than an
  authentication one, and another account on the same machine is refused by the kernel -
  measured with a second user: `PermissionError` on `connect`. Root still gets in, because
  root bypasses file modes; that is the operating system's, not something a check could change.
- **One question, one implementation, or the CLI and the daemon will disagree.** `TaskInventory`
  answers what tasks exist, `TaskControl` decides what stopping and resuming do, `TaskLaunch`
  starts one, `GateSupport` resolves a project's mirror. Both callers render what those return
  and decide nothing themselves. The refusals are why it matters: one that existed in the CLI
  and not over the socket would be a task removed, remotely, with work that existed nowhere else.
- **A varlink stream that ends without a final reply was cut short, not finished.** A finished
  stream arrives as a reply without `continues`; a socket that simply closes did not finish.
  Returning normally there is indistinguishable from success, and a client would go on showing
  what it last saw - stale state for a fleet view, or a clearance prompt that has expired.
- **A task's helpers are `sokar` re-invoked, so never ask `ProcessHandle.current()` for the
  path.** The gate, the credential broker, the relay and the clearance watcher are all the CLI
  with different arguments. Inside `sokard` - or anything else that is not `sokar` - that
  question answers with the wrong binary, and the container comes up with helpers missing and
  nothing saying so: measured, a daemon-started task has no gate and no clearance watcher while
  reporting that it started. `SokarBinary.path()` is the one answer; it prefers the running
  process when that is itself `sokar`, so a local build still shadows a packaged one.
- **Take the helper census before stopping anything.** Stopping a container fires the
  poststop hook, which reaps the helpers and deletes their pid files - so a count taken
  afterwards has nothing left to count. Measured: it reported none while stopping five.
- **A bind-mounted socket is bound to the file that existed when the container started.**
  A helper that replaces its socket a moment later leaves the container holding a deleted
  inode: every request through it goes nowhere while the same request from the host is
  answered. Helpers therefore record whether they must be up *before* the container or need
  the *running* container, and are started in that order.
- **Every helper of a given name writes the same pid file**, so starting a second one leaves
  the first named by nothing and reapable by nothing. Measured without a guard: two resumes of
  a running task leave two `shield watch` processes re-parented to init, and `task stop`
  reports "4 of 4 stopped" while they go on running. A running task therefore answers
  `already up; nothing to resume`, and a recorded helper that is still alive is not started
  twice.
- **The runtime's state is a phrase, not a word.** `Exited (143) Less than a second ago` is
  twice the width of a listing column and runs into the next one - in the very listing an
  operator reads to find the name of the task to resume.
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
- **Prior art in this space has usually hit the problem first.** Where something is
  unclear, look at how others solved it - and read it for *what* to do, never for
  prose or code to copy: Sokar is a ground-up rewrite and Apache-2.0 attribution is
  taken seriously here. Note where they have *not* solved something either; a gap in
  somebody else's implementation is as informative as a solution, and cheaper to find
  than to rediscover. Which projects, where their checkouts are, and what each has
  already answered is a working note rather than product documentation: `.AGENTS.md`
  in the repository root, gitignored like every dotfile here.

- **A provider is data; an agent is code.** An agent needs a binary because it has behavior
  that cannot be expressed as data - a stream formatter, first-run setup, one CLI's quirks. A
  provider is an upstream, a header, a prefix and a path, so it is a YAML file found by a
  directory scan under `/usr/share/sokar/providers`. Do not give it a process.

- **The dialect's path belongs on the endpoint, never in the agent.** One provider serves
  different wire formats under different paths - OpenRouter answers the OpenAI dialect at
  `/api/v1` and Anthropic's at `/api`. An agent that hardcodes one cannot be pointed at a second
  provider without being rebuilt.

- **Adding a field to a record that crosses varlink is two edits, and the compiler checks
  neither.** The server reads the new field, the client has to send it, and a missing one arrives
  as an empty string. `SetupContext.parameters()` is compared against the record's own components
  by a test for that reason.

- **A session in a container costs one word in a layer Sokar already writes.** `tmux new-session -A
  -s sokar` is attach-or-create in one call, so returning and starting for the first time are the
  same operation and the one that matters is exercised every time rather than once. It fixes the
  boundary at the container rather than at the window: a session ends when the task does, and
  closing a window ends nothing.

- **What a session may claim on returning is a number somebody chose, or it is nothing.** The
  scrollback is pinned in `/etc/sokar/tmux.conf` in the image and read explicitly, rather than left
  as tmux's default - a figure nobody chose is a figure nobody can state, and a base image or a
  dotfile could otherwise change it underneath the one process that has to say it.

- **`sokar` is not installed in a task image**, so a session opened with `podman exec` is a terminal
  in the container and hands nobody the node. The image carries `curl`, `ca-certificates`, `git`,
  `openssh-client` and `tmux`, and nothing else.

- **Ctrl-C must not leave a task running without its gate, its broker or its watcher.** A `finally`
  covers every way an attached `task start` can end except a signal: a signal reaches the whole
  foreground process group, so the helpers - plain children, not detached - die with the CLI while
  the container, its ruleset and its resolver keep running. Measured without a shutdown hook: a
  container up eleven minutes with the agent still working inside, nothing to push through and no
  way to reach the provider. `Teardown` arms a shutdown hook so the cleanup runs on a signal too,
  and it takes a lock rather than a flag - the runtime waits for hook threads and not for the main
  thread, so a hook that saw "somebody else has it" and returned would let the process exit with
  the container half stopped. **Measured, not assumed:** the native image runs shutdown hooks on
  SIGINT, SIGTERM and SIGHUP, with no `--install-exit-handlers` in the build arguments.

- **A signalled run is kept, not removed.** The teardown starts at exit code 130, so it takes
  `cleanUp`'s failure branch: the container is stopped and held, because a run somebody
  interrupted may hold commits that never reached the gate.

- **Not every `sokar-` container is a task.** `vault login` runs an agent's own login in a
  throwaway container, which carries the prefix so a cleanup can find it - and has none of what a
  `sokar task list` column describes. `ContainerName` splits `isSokar` (Sokar made it) from
  `isTask` (it has a workspace, a gate, a ruleset and a clearance). The prefix alone cannot make
  that split: `login` is a legal project name, so `sokar-login-shell-25471` is a real task, and what
  separates them is that a login carries only a timestamp where a task carries a task name and a
  run id.

- **An agent's permission prompts are turned off by Sokar, not by an option.** The manifest
  declares only *how* - `sandboxed: arguments:`, because the flag is the agent's own and guessing it
  would be wrong for every other agent. Whether is never asked: inside a task the answer is always
  yes, since an agent stopping to ask whether it may run a command is asking about a restriction
  the container already imposes, and in an unattended run nobody is there to answer. Both start
  paths go through `AgentDefinition.sandboxedCommand()` so the attached and unattended runs cannot
  drift - an attached run that asks for approval is, unattended, a hang.

- **Fetching is not checking out.** A workspace script that fills `.git` and stops leaves the task
  in a directory holding nothing but `.git`, and an agent asked to change a project cannot see one
  file of it. The checkout is guarded on an **unborn HEAD**, not on an empty directory: the same
  script runs again on resume, and a workspace holding the agent's commits or its uncommitted edits
  must survive that. Which branch is asked of the mirror rather than assumed - and
  `git remote set-head -a` is not enough on its own: a mirror made by `git init --bare` has HEAD on
  `refs/heads/main` whatever was pushed into it, so for a project on `master` it fails outright and
  only the candidate list finds the branch. Measured: that is how an empty gate mirror is created.

- **A shell exits with its last command's status, which is not a verdict on the task.** Taken as
  one, a typo at the prompt makes leaving a session print "it failed, so nothing was removed" and
  keep the container. The attached path separates the two: the code still reaches the caller the
  way ssh reports a remote command's status, while the cleanup is told the session ended normally.
  Only a signal - which never reaches that line - counts as unfinished.

- **`sokar setup` is not re-run by a package upgrade, so an installed file can be stale.** The
  descriptors and the `containers.conf.d` drop-in are written once by `setup`; the package replaces
  only the binaries. A check that asks only whether the files are *there* reports a descriptor from
  an older release as `ACTIVE` while podman goes on running what it says. `Registration.STALE`
  compares contents, `outdated()` names the files, and `task start` refuses on it like any other
  non-`ACTIVE` state - a container with no firewall looks entirely normal, so this must stop rather
  than warn. Note the asymmetry: a hook that is *added* is caught by presence alone, because the
  missing file has a new name; one whose contents changed is caught only by comparing.

- **Bringing a task back does not attach.** It starts the container and its helpers and returns,
  so its output must not promise "go back in". Both that output and the kept-task message name
  `task attach` as the separate step. There is no separate verb for bringing a task back:
  `task start` creates or resumes, deciding from the task's state.

- **A fact about a container belongs on the container.** Project and security class are written
  into `sidecar.json` under `$XDG_RUNTIME_DIR`, which the system destroys when the user's last
  session ends, and as podman **labels** as well: with the sidecar alone, every task surviving a
  reboot lists both as `-`, and bringing one back fails obscurely because the fail-closed nft hook
  cannot read the sidecar its annotation still points at. Labels rather than annotations because
  only labels come back from `podman ps`: an annotation would cost one `inspect` per row of a list
  an interface redraws. Each label is read on its own, by name (see `{{.Labels}}` below), and the
  container name is never parsed for them: splitting it on its hyphens cannot tell a project name,
  which may contain hyphens, from the task name after it.

- **A dialog caused by a flag cannot be answered by that flag.** `--dangerously-skip-permissions`
  makes the CLI open with a bypass-mode warning defaulting to "No, exit"; it is consent, not a
  permission prompt. Answered by a settings file the agent declares, through the `ContainerSetup`
  seam, which exists for exactly this. **The API-key dialog beside it is refused rather than
  solved**: it fires on a collision between two payment models on one account, and suppressing it
  would answer "bill it that way" for somebody.

- **"Run this command first" is a defect when the command is always the same.** `sokar setup`
  writes the hook descriptors and the podman drop-in, and being told to run it is a thing people
  forget - which is how a machine ends up with an installation nobody completed. A task start
  repairs the two states that are only ever "write the files", `MISSING` and `STALE`, and says so.
  It still cannot be the package's job: podman reads descriptors per user, so an install script
  running as root does not know whose configuration to write, and a system-wide `hooks_dir` would
  point every user's podman at Sokar - the same `SHADOWED` offence Sokar refuses to tolerate in
  other people's drop-ins. **The repair is verified, not assumed:** what podman will do is asked
  again afterwards, because writing descriptors cannot rule out missing binaries or a drop-in that
  sorts later. Those two still refuse - one is a broken installation, the other is somebody else's
  file.

- **The same destruction is guarded on both paths.** `/workspace` lives in the container's own
  writable layer, so removing the container destroys it. `task remove` asks `unhandedWork` first
  and refuses with `HOLDS_WORK`, offering `--rescue`; the end of an attached run asks too, or
  walking out of a shell would discard what a removal refuses to touch. It is only answerable while
  the container runs - which it still is at that moment, because the shell is an `exec` beside
  `sleep infinity`. Keeping only when there is actually something held is what stops this refilling
  `task list` with dead containers.

- **A destructive command used as a query is a missing query.** Whether a workspace holds changes
  nobody has pushed is not left to running a removal and reading the refusal: `task status` asks
  the same `unhandedWork` without touching anything. Note what it cannot do: the workspace is
  inside the container, so once a task is stopped the only source is the note `task stop` wrote on
  the way out - and a task stopped by a reboot or a kill has neither. It says "cannot be read while
  the task is stopped" rather than "nothing", because nothing would read as nothing to lose.

- **`task list` shows what `TaskInventory` already computes.** `TaskInventory` computes sixteen
  fields and the daemon hands an interface all of them; a CLI rendering five shows a third of what
  the machine already knows.

- **The age is rendered, not thrown away.** `ContainerSummary.since()` is an instant precisely so
  "how long has it been like this" is answerable, and the runtime's phrase is twice its column's
  width, so `Age.compact` renders the instant instead - and refuses a future one, because a
  negative age in a column is a bug somebody has to explain rather than information.

- **A login container is never reused**, because its name carries the millisecond it was made. So
  one still on the machine is litter, and `vault login` sweeps them before it does anything
  expensive - which covers what a teardown structurally cannot, a kill or a power cut. The login
  *image* is the expensive part and is deliberately kept.

- **Colour goes through picocli's `Ansi.AUTO`, never escapes written by hand.** It paints only on
  a terminal and honours `NO_COLOR`, so a pipe, a log and a test fixture stay clean - and this
  output is read by scripts as well as by people. Two things are painted: work that exists nowhere
  else, and a clearance of `off`.

- **A bare `git push` in a task reaches the gate.** Checking the workspace out from a
  remote-tracking branch makes it track `sokar/main`, so on its own `git push` with no arguments
  lands on `refs/heads/main` in the mirror: nothing appears in `gate pending`, and the push says it
  worked. The clone sets `remote.sokar.push = HEAD:$SOKAR_TASK_REF`, so the obvious command goes
  where the work is meant to go. **Both settings are measured, not assumed:** with one remote git
  2.53 falls back to it and `remote.pushDefault` is redundant - it earns its place only when an
  agent adds a second remote, where without it a bare push fails with "no destination configured"
  and with it still reaches the gate.

- **Counts cross the boundary; the sentence stays where it is read.** The note a stopping task
  writes is versioned JSON with the two counts, and `Held.phrase()` words them for a terminal. A
  rendered phrase - "2 commits and 3 changed files" - on the wire means English, a fixed plural
  rule and an unsplittable string, and a client can neither show one number nor sort by it. **A
  note in the rendered-phrase form is parsed back rather than discarded:** it is our own wording in
  one of three shapes, and answering "nobody looked" about a task that was actually measured would
  destroy the work this exists to protect. An unknown *version*, though, is refused - the same rule
  as the sidecar, because a misread answer here decides whether work is destroyed.

- **`readable: false` is not "holds nothing".** Holding nothing is readable with two zeros; not
  readable means nobody could look. `WorkHeld` is asked per task and never on a listing, because it
  runs git inside the container - a call per row of a list a client redraws.

- **A task cannot be resumed across a restart, and podman explains that badly.** The state
  directory is under `$XDG_RUNTIME_DIR`, which the system clears when the user's last session ends
  - and the container bind-mounts the broker socket out of it, so `podman start` fails with crun's
  "cannot stat .../vault.sock". No helper can be started that brings the socket back, because the
  directory recording which helpers there were went with it. `resume` detects the missing
  directory before asking podman, and says what happened plus how to get the workspace out:
  **`podman cp <task>:/workspace` works on a container that cannot start**, measured.

- **A stack trace is not a message, and it is not nothing either.** Every refusal here is one line
  beginning "sokar:", and an unhandled exception puts sixteen frames of picocli on the terminal.
  `CliErrors.failures(paths)` writes them to `$XDG_STATE_HOME/sokar/failures.log` - **kept rather
  than offered behind a flag**, because the failure nobody can reproduce is exactly the one worth
  having a trace for. The terminal gets the sentence and the path; `doctor` names the file once it
  exists. **The command's name goes in it, never its arguments:** `--upstream
  https://user:token@host` is a credential, and a log is where that must not end up. Owner-only,
  because a message can quote anything. The name logged is the command that failed, not the root
  command, which would call every failure "sokar" and name nothing; a test checks it.

- **What a listing offers and what a reader accepts are one rule.** Two separate `.log` checks, in
  `Logs` and in `Tail`, could drift, and a `.log` filter is wrong for both the same way:
  `events.jsonl` (what the firewall blocked) and `reader.err` are logs whose names do not say so.
  One rule, `TaskInventory.isLog`, is used by the listing, the wire and the CLI. **It stays an
  allow-list:** the same directory holds `vault.token`, the live phantom token, beside the sockets
  and the ruleset - "everything that is not a secret" has to be right forever, including about
  files a later release adds, while "these names" fails closed.

- **The shape of a name is not evidence that it names something.** `task logs sokar-does-not`
  passes the `isTask` check and finds no state directory, and answering "either nothing wrote one,
  or the machine has restarted since it ran" would be a sentence about a task that exists, said
  about one that never did. The rule is **exists as a container OR has logs**, not existence alone:
  a container somebody removed by hand leaves its state directory, and those logs are worth reading
  precisely then.

- **`{{.Labels}}` is Go's map formatting, not `k=v,k=v`.** podman renders `map[a:b c:d]` - space
  separated, colon separated. A label read that assumes the comma form, with a test fixture that
  assumes it too, makes **the parser and its test agree with each other and with nothing else**:
  every task on a real machine lists no project after a reboot, which is the exact failure the
  labels exist to fix. The format asks for one value at a time -
  `{{index .Labels "org.fuin.sokar.project"}}` - which answers the value or empty, so there is no
  shape to get wrong and no other label on the container can affect it. **A fixture written from
  the same assumption as the code proves the assumption, not the behaviour.**

- **JUnit's versions are one set or they are three guesses.** Picking `junit-platform-suite` by
  asking Central what is newest puts platform 1.14 beside the Jupiter 5.12 the fuin BOM pins. That
  works only by declaration order, and moving the artifact one level deeper breaks it with
  `NoClassDefFoundError` and failsafe's "versions of JUnit jars not properly aligned". The build
  imports `org.junit:junit-bom` **before** the fuin BOM, at the version **Cucumber is built
  against**: aligning down to 5.12 is coherent and makes the acceptance suite discover no tests at
  all.

- **A suite that opens a connection per scenario is a suite that gets refused.** Around eighty
  ssh connect/disconnect cycles in two minutes, and CI answers "Connection refused" to two of
  them. One connection for the run, a channel per scenario: measured at 80 connections to 2, and
  15.7s to 8.5s. It does not show locally, because a hop to a VM on the same host is fast and
  forgiving in a way a rented server is not.
## What the finished requirements measured

- **An environment is not an argument list.** Every variable a container gets is named on podman's
  command line without its value, and podman copies the value from Sokar's own environment: an
  argument list is world-readable and an environment is not. The pair that must not drift - the
  names in the arguments, the values in the environment - is guarded by tests at *both* places that
  build them, because **podman drops a name it cannot resolve rather than failing**. A typo there
  is a container quietly missing a credential, not an error.

- **A grant and a withdrawal are not mirror images.** An nftables set holds **addresses**, and a
  project declares **names**. So widening by name resolves to addresses and adds them; narrowing by
  the same name cannot simply remove them, because the name may now resolve elsewhere and the
  addresses may be shared. Two operations, not one with a sign.

- **The resolver cannot be told anything without being restarted** - which is why a live widening
  goes through the servers file it re-reads on `SIGHUP` and not through its configuration.

- **Nothing applied to a running task survives a restart unless it was written down.** Turning
  enforcement off therefore also removes the watcher from what a later start would restore;
  otherwise the setting comes back silently, and only after a restart.

- **A vault entry that looks like a placeholder is questioned rather than stored.** A value goes in
  through standard input and comes back only as a name, a kind and a length.

- **A backup nobody recorded is not a backup you can list.** `gate backup <file>` writes a bundle
  wherever an operator names it, so it records each one - a backup is listable only because it is
  recorded. And a restore refuses with `HOLDS_WORK` naming the refs, because unreviewed pushes
  exist only in the mirror and overwriting one destroys the only copy.

- **A listing must not reach the network.** A triggered fetch is its own method rather than a flag
  on a read, or the queue costs what a listing must not.

- **varlink cannot carry a session at all.** It is one call in and many replies out, with no way
  for a client to keep sending into an open call. So an interactive session is carried by ssh with
  a pty, running Sokar's own verb rather than the runtime's - which adds no privilege, because
  whoever can forward the daemon socket can already run commands there. The session is a terminal
  **in the container** rather than on the node, because `sokar` is not installed in a task image.

- **A clearance answer must survive a restart.** It is never asked twice for a task - including
  across a restart, which is where a repeated question comes from - and is written to a record
  that outlives the task. A question nobody answered is replaced on screen by one saying so, and
  reaches a client as a verdict rather than as silence.

- **A defect recorded from one environment is a measurement, not a fact about the product.** A
  report of `Console.readPassword()` throwing in the native image, with a stack trace and three
  reproductions, does not reproduce with the same binary at a pty, over `ssh -tt`, or through the
  acceptance kit, with no code change in between. What makes such a report checkable is the exact
  command, the terminal it ran under, and **whether the process was sandboxed**. Ask for those
  three before believing a defect only one machine has seen.

  Two facts about it hold. **A minimal native image built on GraalVM 25.3.4 reads a passphrase
  correctly, and the product pins 25.0.2** - if that fault exists it lives in that gap, and it is
  the one hypothesis worth keeping. And should it appear, driving `tcgetattr`/`tcsetattr` through
  FFM reads a line with echo off in both runtimes, proven at a pty; it is the same mechanism
  `KernelKeyring` already uses, so it is in idiom rather than a new one.

- **An agent's first-run dialogs are that agent's business, and answering them inside a task is not
  a choice.** First-run consent is handled in the agent repositories. What stays here is the seam:
  `TaskLaunch.placeAgentFiles` places the files an agent declares - the agent decides what is in
  them, Sokar writes the bytes, and nothing in Sokar branches on an agent's name. A task with **no
  credential** gets an *empty token* rather than no files at all, because two of Claude Code's
  three files have nothing to do with a credential: returning early walks a task started without
  one into every dialog. Measured: `prepared 2 file(s)`, and the CLI reached its prompt with no
  dialog in between.

  **A stub that cannot ask questions cannot notice an agent that does.** A suite whose only agent
  has no first-run dialogs stays green past an agent that has them. Which dialogs each agent shows,
  and what answers them, is recorded in that agent's own repository.

## The agent repositories consume what this one publishes

**Push this repository first, and wait for it to publish.** The three agent repositories resolve
`sokar-machines` and `sokar-acceptance-kit` as snapshots from Central, and Sokar's own `main` build
is what puts them there. Pushing both within a few minutes races: an agent's leg that starts before
the publish it needs resolves the previous snapshot and fails with the usage text of tooling that
predates the command asked of it.

`settings.xml` already sets `updatePolicy` to `always`, so nothing is stale that the repository
has. What cannot be fixed in a workflow is an artifact that does not exist yet.

## The rented test machines

Both acceptance legs boot a prepared Hetzner snapshot, found by label, and destroy the server in
a `finally`. It is Java, in `buildtools/hetzner`:
`org.fuin.sokar.machines.Main leg` runs a leg, `... sweep` deletes what a run left behind, and
`... snapshot --os <os> --repo .` builds the image a leg boots. There is no Python under
`buildtools/ci/`: one set of helpers serves this repository and the three agents, because copies
of the same helpers drift.

**The builder lives in the repository, so anyone can rebuild an image.** A snapshot only restores
onto a disk at least as big as the one it came from: an image taken on a 320 GB machine to hold
1.6 GB of content makes every leg rent the one type big enough, at 0.1114 EUR/h, and an image taken
at 40 GB leaves the choice open. **The builder compiles Sokar on the machine before taking the
image**, which is what makes the list below true rather than aspirational: booting an image and
running a hello-world native image proves neither a JDK nor musl, because hello-world links nothing
statically and needs no JDK on the machine at all.

The Ubuntu leg runs **26.04**, not 24.04. 24.04 ships podman 4.9.3 and always will - podman is
in `universe` and a stable release does not change major versions - and Sokar refuses podman
4, so a 24.04 machine cannot run the suite at all. Mixing a newer release's podman into 24.04
is not an option either: measured, it upgrades libc6 2.39 to 2.43 and 154 other packages,
which is a dist-upgrade wearing a 24.04 label. The cost of the move is that the published binary
is built against a newer glibc, because a native image links it dynamically and FFM rules out a
static one.

What the images contain: GraalVM 25.0.2 pinned by its published digest, the musl cross-toolchain
and a musl-built zlib, `gcc`/`glibc-devel`/`zlib-devel`, podman with `ubuntu:24.04` and
`alpine:3.20` pre-pulled, Sokar's SELinux policy loaded on the Fedora leg, and a warm `~/.m2`
from building the project once. The checkout itself is deleted - it goes stale immediately, the
dependency cache does not. 1.48 GB, about EUR 0.018 a month; a run boots one in about a minute.

Provisioning a machine that can build Sokar has to get each of these right, and each fails on the
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
  stays root-owned and rootless podman will not start. A single root-owned directory in a
  user's home reads as a Sokar permissions bug.
- **An ordinary user has no `sudo`**, which is correct - so nothing in a run may assume it.
  Install into the operator's own directories, which is the shape a task runs in anyway.
- **Verify by compiling, not by inspecting.** "`gcc` is present" is not "native-image works": a
  missing compiler surfaces twenty minutes into a build as *"Default native-compiler executable
  'gcc' not found"*. The check builds a real probe twice, once ordinarily and once
  `--static --libc=musl` as the hooks need. A scratch directory from `mktemp -d` as root while
  `javac` runs as the build user makes the check report a compiler missing that is there - a
  check that fails for its own reasons is worse than no check.
- **Do not rebuild the image to test a change to the provisioning.** Provision once with
  `--keep` and iterate against the live server over SSH. A rebuild is a fifteen-minute cycle;
  against the live server, a defect like those above shows in under a minute.
- **`eu-central` is a network zone, not a location.** `servers.create()` takes a location;
  `fsn1`, `nbg1` and `hel1` all sit in that zone. Passing the zone fails.
- **Do not hard-code a location.** Availability is per datacentre and changes: measured, `fsn1`
  offered *zero* server types while `nbg1` and `hel1` offered eighteen. The failure is
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
  waits and retries on that one error - and only that one, because a bad image or a full
  datacentre is not something waiting fixes. The agent repositories also run their legs
  `max-parallel: 1`, so each takes one machine at a time rather than two.
- **Name a run's servers after the run *and the leg*.** Both matrix legs share `GITHUB_RUN_ID`,
  so a cleanup keyed on it alone deletes the other leg's machine mid-suite.

## Publishing

**Four artifacts reach Maven Central: `sokar-agent-api`, `sokar-wire`, `sokar-acceptance-kit`
and `sokar-bom`.** Nothing else, because nothing else is a contract anyone outside resolves: the
first two are what an agent compiles against, the kit is what its acceptance scenarios drive a
machine with, and the BOM is the one place a repository building against Sokar reads a version
from, so that no agent repository imports a JUnit set that only coexists by declaration order. The
packages go to
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

**The bill of materials a package installs is the package's own.** `dist-deb`'s `makeBom`, from its
own dependencies - app, sokard, hooks and what they link, 19 components - so its subject is
`sokar-dist-deb`, not `sokar`; the plugin has no name override. The root's aggregate over the whole
reactor is not it: that names the acceptance kit's cucumber, junit and sshj and the release tooling,
42 components that do not ship, and changes with whichever modules the last build included.
`buildtools/package-check` asserts what it must and must not name. **`makeBom` skips itself
under `-o`**, so build the packages online.

**The published binary is the one the acceptance suite tested.** The Ubuntu leg fetches its
binaries back before the server is destroyed and the publish job packages those, rather than
compiling its own. Two reasons: a hosted runner has no musl cross-compiler, so the static
hooks cannot be built there at all; and a binary compiled for the packages is not the build
the suite exercised. **Ubuntu, not Fedora** — a native image links glibc
dynamically, so one built on Fedora will not start on Ubuntu 24.04 while the reverse runs on
both. Both packages carry identical bytes, so there is one binary to get right. The leg
writes a manifest beside the binaries and the publish job reads it, because two lists
written down twice drift.

**So no acceptance leg has a package installed, and a scenario about a packaged file cannot pass on
one.** A leg builds on the machine and stages into `~/.local/share/sokar`; `/usr/share/sokar` is not
there. `exec:java@deploy` does install the `.deb`, so such a scenario is green on the VM and red on both
legs - measured: a bill check passed 102/0 on the VM and failed 10 of 10 in CI.
What the package holds is checked on the package, in `buildtools/package-check`.

## The interface contract

`daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1.varlink` is the API the frontend is
built against, in a separate repository
([sokar-frontend](https://github.com/sokar-ai/sokar-frontend)). It is the contract, not a
description of one: the daemon serves it verbatim through
`org.varlink.service.GetInterfaceDescription`, and `InterfaceDescriptionTest` fails the build
when a method is registered without appearing in it, appears in it without being registered,
reads a parameter it does not describe, or throws an error it does not name.

- **The trailing `1` is the compatibility promise**, and it is varlink's own convention -
  `org.fuin.sokar.Clearance1` follows it too. Within one number the interface only grows:
  new methods, new `?` parameters, new reply fields. Nothing that exists may be removed, renamed,
  retyped or given a new meaning. A change that cannot be made that way becomes `Tasks2`, served
  *beside* `Tasks1` for at least one release, because a fleet is not upgraded at once.
- **Adding an enum value is explicitly not breaking**, so the contract requires clients to
  tolerate unknown ones. `Outcome` will gain entries.
- **Parameter names must be varlink identifiers.** Hyphens are not, so the parameters are
  `credentialType` and `tokenHours`, not `credential-type` and `token-hours` - a description that
  uses the hyphenated names cannot be written at all, which is the sort of thing an IDL catches and an ad-hoc map does not.
- **Derive nothing on the client side that the daemon can send.** A clearance prompt carries
  its own `key` because a client rebuilding it from the other fields is one separator away from
  answering a prompt that does not exist, while the task stays blocked.
- **A client needs to be told which of two things happened when its sentence claims an act, and
  does not when its sentence describes a state.** That is what decides whether a reply gains a
  value.
  `Lock` answers `wasCached` because *"the store is shut"* claims an act and is false for a store
  that was already shut; `SetClearance` answers `UNCHANGED` because *"nothing was restarted"*
  deserves its own sentence. `UnlockWithShare` needs nothing of the kind: it returns `UNLOCKED`
  for a store that was opened and for one whose bound was replaced, the state afterwards is the
  same, and the interface says only *"open until <time>"*. **A value nobody renders is the same
  defect as a state nobody can reach**, so the question is asked of the reader's sentence rather
  than of the daemon's branch.

## The skills this repository expects you to have

These are the house's, not a preference. **Every repository that builds a native image or is
written in Java uses the first two**, which is all of them except the interface; **the interface
uses the third**, and nobody is without one.

- **GraalVM** — <https://github.com/oracle/skills/tree/main/graal>, Oracle's own.
- **Java** — <https://github.com/decebals/claude-code-java>.
- **Dart and Flutter** — <https://github.com/flutter/agent-plugins>, the Flutter team's own.

**This repository uses seven of them, by slug:**

    graal  java-code-review  test-quality  security-audit  concurrency-review
    clean-code  solid-principles

The Java set upstream is eighteen slugs, among them `spring-boot-patterns`, `jpa-patterns` and
`java-migration`, which describe nothing here; *the Java set* alone does not say which are meant.

Neither Java skill applies to the interface, and that exempts it from *those two*, not from having
any: the vendor ships its own set the same way Oracle does.

**Where they come from here: <https://fuinorg.jfrog.io/artifactory/agent-skills/>**, one package
per skill, republished rather than pulled from GitHub by each machine. The reason is the one every
other dependency in this product has: a tag upstream is a name its owner may repoint, and a machine
that fetches at install time gets whatever it points at that day. What is in that repository is
what was reviewed. Each package carries its upstream LICENSE and records the exact commit it was
built from.

### How to get them

**The repository is readable without credentials and without any tool**, because a skill is a
directory holding `SKILL.md` and installing one is unpacking an archive. Commands rather than a
verb: there is no instruction every agent understands, so this section gives you something to run.

    BASE=https://fuinorg.jfrog.io/artifactory/agent-skills
    curl -fsSL $BASE/.skills/skills.json             # every slug with its latest version
    curl -fsSL $BASE/.skills/<slug>/versions.json    # the versions of one skill
    curl -fsSL -o /tmp/s.zip $BASE/<slug>/<version>/<slug>-<version>.zip
    unzip -q -d <your skills directory>/<slug> /tmp/s.zip

**Where `<your skills directory>` is depends on the harness, and only you know yours.** Claude Code
reads `~/.claude/skills/<slug>/` and a project's `.claude/skills/<slug>/`; another harness has its
own place, and a skill put where nothing reads it fails silently. Verify by asking the harness what
it loaded, not by looking at the directory.

**Check what you downloaded.** `curl -fsSL $BASE/../api/storage/agent-skills/<path>` states the
artifact's SHA-256; an interrupted transfer otherwise installs a truncated skill, which reads as a
short one rather than as an error.

**If your harness cannot install a skill, read it.** Unpack it outside the repository - a scratch
directory - and read its `SKILL.md`. Measured here: a module written by a session that could not
write its skills directory sent a CI token wherever a property pointed. An agent that cannot install
and is not told this concludes it has no skills.

**If the JFrog CLI happens to be installed**, `jf agent skills install <slug> --repo agent-skills`
does the same with resolution and an install record. Do not install it for this — the four commands
above are the whole requirement.

**The version is `YYYY.MMDD.P`** — the upstream commit's date, then the packaging revision, so a
newer upstream always sorts higher and a repackaging of the same upstream never reuses a number.
`2026.911.3` is the eleventh of September, packaged the third time. (Not `2026.09.11`: SemVer
forbids a leading zero in a numeric identifier.)

There is a second repository, <https://fuinorg.jfrog.io/artifactory/agent-packages/>, which holds
no skills. It exists for Agent Packages, which can carry prompts, hooks, instructions and MCP
declarations as well — this product's own agent configuration, when it ships. The skills are not
published there as well: the same 44 skills in two places is two copies of one truth, and the
unused copy is the one that quietly goes stale.

That also makes them the same kind of thing as the packages this build publishes, which is the
point - an agent's knowledge is a dependency, and a dependency nobody versions is one nobody can
roll back.

**Why it is a rule rather than a suggestion.** Most of what costs this project a day is neither
a design mistake nor a bug: it is a property of the toolchain that somebody has to rediscover.
FFM and static linking being mutually exclusive, a reachability-metadata file deciding whether a
resource exists in the image at all, a passphrase read that works in a JVM and not in a native
image - each of those is written down further up this file *because* it is expensive to learn.
A skill that carries the same knowledge in advance is the cheaper end of the same lesson.

**What it does not change.** A skill is knowledge, not authority: where it and a measurement from
this repository disagree, the measurement wins and the disagreement is worth writing down. The
facts above this section were all measured here, on the versions this build pins.

## Shared across the Sokar repositories

The same text in every repository `project.yml` names. Change it in the channel first, not in
one copy.

- **The operator pushes. Agents commit and stop.** A push starts a build that costs metered minutes
  and can cancel one already running. Say what is ready and let the operator decide when.
- **A rewrite is cheap only while the commits are yours alone. Ask the remote first.**
  *The operator pushes. Agents commit and stop* - and stop includes stop amending, stop squashing,
  stop rebasing. A commit stops being yours the moment it is pushed, and nothing tells you when that
  happened except asking:

      git ls-remote origin refs/heads/main            the tip, and it cannot be stale
      git merge-base --is-ancestor <commit> <tip>     whether the commit is already in it

  `origin/main` and `@{u}` are caches and answer a question about your last fetch. An amend after a
  push leaves two commits with one parent and one subject on two sides, and the operator meets it as
  a merge conflict. **The repair is never a force push** - reset onto the remote's commit and
  re-apply as a new one, because the side that pushes is the side whose history is real. That same
  reset is also the only safe way to squash, which is why the cure and the correct method are one
  operation.
- **Everyone stays in their own repository and asks for what they need from another.** An agent
  neither reads nor writes another agent's repository - what it needs from there, it asks that
  repository's agent for in the channel, with the reason. The one exception is the coordinating
  agent, who may **read** the other repositories. Reading does not replace asking: a file shows what
  is the case, and only the agent who wrote it knows why. **Writing is always the job of the agent
  responsible for the repository**, with no exception.
- **The channel is append-only.** An entry begins with `## <UTC timestamp> — <agent>`. Headings
  inside an entry are free; scan for entries by the timestamp, never by `##` alone. Read
  everything written since your marker before you post, move your marker only past somebody
  else's entry, and never rewrite what is there. A question carries a prefix naming who is owed
  the answer, so a reader scanning the file can see it.
- **When quoting a document that has headings, indent it four spaces rather than fencing it.**
  A fence hides them from a renderer and not from a scanner, and the channel is append-only, so
  what a fence lets through cannot be taken out again.
- **Re-read the channel immediately before appending to it.** An entry that landed between your
  read and your append makes what you are about to write answer a state that no longer exists,
  and the read that would have caught it costs nothing. The marker says what to compare against.
- **Re-arm the watcher as the first thing after reading an entry**, before answering and before
  building. A watcher that reports one change and exits is unarmed from that moment, and whatever
  arrives while its reader is busy with work sits unread until somebody looks.
- **Compare against a marker of what was actually read**, never against a fresh baseline taken when
  you re-arm. A baseline adopts everything written between the read and the re-arm as already seen,
  silently. Keep the last heading you read and compare against that.
- **The file's order is the truth and the headings are a label.** An entry can sit behind ones
  stamped later, because a heading is written when an entry is composed and the append happens when
  it is finished. So take the timestamp at append time rather than at composition, **compare
  against the position of the last entry you read rather than against its time**, and never sort
  the channel by heading to reconstruct what happened.
- **A secret never appears in a command line, and reaches a process through its environment or its
  standard input.** Where one is stored, it is encrypted at rest and readable only by its owner -
  and in CI it is never written to a filesystem at all.
- **Every file fetched from Artifactory follows redirects** - `curl -L`, `jf rt curl -L`. A file
  large enough is answered with a `302` to its cloud storage, and a fetch without `-L` gets an
  empty body: the check passes for months and fails the day the file grows. Where "large enough"
  lies is not known; a Debian index is past it. The `/api/` endpoints answer directly. Let `curl`
  drop the credentials on that cross-host redirect - the storage URL is signed - and never pass
  `--location-trusted`.
- **The test machines are shared, and so is everything a run resolves from** - `~/.m2`,
  `~/.sokar/handover/` and what a VM has installed. Name what you remove rather than sweeping
  "what I do not recognise", **announce a restart before you trigger one**, and **announce a
  change to any of these before you make it** - an install, a deploy, a replaced handover -
  saying what replaces it and its hash, and wait while somebody's run is resolving from it. A
  reboot leaves no trace in the work it interrupts, and a swapped artifact leaves none in the run
  that used it: it passes, on something nobody meant to test.
- **Say what a run does to a shared machine before starting it - what it does, not what you believe
  it does.** Check first. A confident wrong answer costs somebody else an afternoon.
- **Link to a requirement by its number and to the index, never to its file.** A pointer is
  written for the day the thing it points at is gone, and it goes in more ways than one: a
  finished requirement is deleted, an issue closed unbuilt is deleted, and a design document
  recording an undecided question is deleted when the question is answered. A link to a file
  breaks on all three; a link to the index breaks on none. **Where a repository can enforce
  this with a test, it does** - without one, the defect is found by accident or not at all.
- **From "both are valid" it does not follow that both should exist.** Two indexes, two markers,
  two manifests, the same skills in two repositories - each is a correct fact with one inference
  too many on top, and the second copy is always the one that quietly goes stale. When a thing is
  right in two forms, publish one and say why.
- **Measure before you claim.** "It works" means it was run. "It is not the cause" means the
  counter-test was run too. A finding without a measurement is a guess wearing a fact's clothes.
- **"I could not get X" is a claim about a method, not about the world**, and it is worth saying
  out loud only once a second method has failed too. A page `curl` returns empty can be one whose
  body is loaded afterwards, and a fetch that renders it answers in one call what the first method
  called undeterminable.
- **Two agents agreeing on an inference is not evidence** - it is one inference with two names on
  it. Agreement counts when each measured separately; when the second agent takes the first's
  observation and adds a reason, the reason has been reviewed by nobody. **Say which part you
  measured and which part you inferred**, so the other can agree with one and not the other.
- **An issue is one task.** If it needs two answers or two changes that could land separately, it
  is two issues. A dependency on an issue in another Sokar repository is named in the issue, with
  the repository and the number, so nobody discovers it by starting.
- **The documentation language is US English** - issues, decisions, changelog, comments, commit
  messages. The channel too.
- **Do not refer to feature numbers in commit messages.** Just state what the feature is. A commit
  says *"Start work in a chosen repository"*, not *"B67"* - the number means nothing to somebody
  reading the history without the index beside it, and the index outlives the requirement by being
  deleted when it is finished.
- **Dot files and directories are not checked in.** `.gitignore` ignores `.*` and names only the
  exceptions a build needs. Anything true of one machine goes in `.AGENTS.md`, which that rule
  ignores by itself.
- **A Java repository builds in one language.** Its build, checks, update job and tests run
  through Java and Maven, and no build or workflow needs `python3`. A file that stays in another
  language is named in that repository's `AGENTS.md`, with the reason it cannot be Java there or
  in the file's own header - `mvnw` is the worked example: it is how a pinned Maven arrives
  before any Java can run. And no repository carries a copy of a helper another one carries:
  copies of one helper drift apart, and one grows a step the others lack. The drift is the
  argument, not the tidiness.
- **Java code is null-checked when it compiles.** Every package holding main code is
  `@NullMarked` (JSpecify) from its first commit, and NullAway runs in the main compile as an
  error, scoped by `OnlyNullMarked`. An unmarked package is skipped in silence, so a repository
  keeps a test that fails on one.
- **Documentation and rules say what is true now.** A README, `build.md`, everything under
  `doc/` and `AGENTS.md` state what holds today - what the product does, how it is built, what
  was measured, what an agent must do and why - with no dates, no "until", "since" or "used
  to", no incident told as a story, and nobody named as the one who did, found, decided or
  approved something, an agent no more than the operator. Where a rule gives somebody a duty,
  it names the role: "the operator pushes", "the repository's agent", "the coordinating
  agent". A rule keeps its reason, stated so that it stays true. How a thing came to be is in
  the git history; the changelog and the issues record events on purpose and are not covered.
  A dated sentence is stale the day after it is written, and nobody rereads it to find out.

## Security rules that are not negotiable

- **Never put a secret in a command line.** A process list is world-readable.
  Credentials go in through standard input, an environment variable on a
  process you spawned, or a `0600` file — never `argv`.
- **The real credential never enters a task container.** The agent gets a
  phantom token; the vault proxy swaps it on the way out. If you find yourself
  passing the real key in, stop and reconsider the design.
- **What must not leave the container is the credential, not the traffic.** The
  provider's own host is reachable: withholding it stops every agent that checks
  the provider is up before starting, and that check ignores the base URL and the
  socket entirely - measured. What a deny would keep in is the phantom token -
  random, task-scoped, worth nothing to the provider. The real credential never
  enters the container, and that is the property to defend.
- **Fail closed.** The nft hook refuses to let the container start if the ruleset
  will not load. Keep that property in anything new on the startup path.
- **Log tokens abbreviated, never whole** — `PhantomToken.abbreviate`. A log that
  contains a working credential is a credential store with no lock on it.

## Timestamps come from the clock, never from memory

**Any date or time written anywhere - a channel entry, a requirement, a comment, a snapshot
description, a note that says when something was measured - is read from the system first.**

    date -u +"%Y-%m-%dT%H:%MZ"

A model has no clock and no reliable sense of elapsed time, so a timestamp written from memory is a
guess that looks like a fact. It can be hours off, and a file whose timestamps were composed rather
than read can have its entries in the true order and every date wrong.

**The same rule for durations and ages.** "About twenty minutes ago" and "three weeks behind" are
observations with a date attached, not properties - compute them from two timestamps that were both
read, and say when they were taken. Something recorded as a property is read later as one that
still holds.

**Never correct a wrong timestamp with a second guess.** Read the clock and use what it says. A
correction that is also estimated is the same fault twice, and the second one is more convincing.

## Commits

One brief line. The reasoning behind a change is a finding, and a finding goes in
`.sokar.md` or in this file, where it can be found later without `git log`.

**The changelog is part of the change**, written by hand in the same commit: an entry for anything
that changes what ships or builds, none for issues or notes. Nothing enforces it; requiring an
entry is open work in B55 ([index](issues/base/README.md)).

In this repository an entry is **one YAML file under `changelog/unreleased/`**, with at least a
`title` - one sentence - and a `type`: `added`, `changed`, `deprecated`, `removed`, `fixed`,
`security`, `dependency_update` or `other`. `CHANGELOG.md` is generated from those files by
`./mvnw -N logchange:generate` and is **never edited by hand**, because the next generate overwrites
it. `logchange:lint` runs in `validate`, so a malformed entry fails the build.

## Documentation

**Run the unit suite before committing.** The acceptance suite is CI's job, on `main`, on two
rented machines - one with SELinux enforcing, one with podman 4. Before a release, run it
deliberately rather than assuming a green badge covered it. A development VM is for diagnosing a
failure quickly, not for gating a commit; nothing should depend on one existing.

**A documentation-only push builds nothing.** `paths-ignore: ['**/*.md']` on push and pull
request: nothing in the build reads a markdown file, and on `main` a run rents two machines
and republishes. A push that mixes docs and code still builds - the filter is per push, not
per file - and `workflow_dispatch` ignores it, so a run can always be forced.

**Everything in the repository is written in English** - code, comments, requirements,
documentation, commit messages - whatever language the conversation that produced it happened in.
The spelling rule above is the detail; this is the rule. A repository that switches language
halfway strands every reader who does not share the author's first one, and the people most likely
to read a security tool's reasoning are not all German speakers.

**A requirement that is done is deleted**, file and index row together, once whatever
is worth keeping has moved into this file. They describe work to do, not work that was
done; git history is where finished work lives.

**An index linked by URL from another repository is a published path, and moving it is announced
in the channel first.** A cross-repository dependency is written as the repository and the number -
`Sokar B54` - and needs no link; but an index does get linked across repositories where a reader has
to arrive somewhere, and `issues/README.md` here links `sokar-frontend`'s index that way. Such a
link breaks silently: no test at either end can see it, because neither repository holds both ends.
So a rename, a move or a split of an index is announced with the old path and the new one **before**
the commit that does it.

**An answered question is deleted the same way**, out of the requirement's own *To be checked*
section, once what its answer decided has moved into the acceptance criteria, into the design, or
into this file. A question kept with its answer beside it reads as open work to everyone who
scans the section, and becomes a second account of a decision that is already written elsewhere.

**Requirements are not referenced from code.** No class, comment, commit message or
test may cite a requirement number. Requirements move, merge and are dropped; code
that names one goes stale silently and starts to look like a contract. A comment
should name the constraint itself, which is what makes it worth reading anyway.

**It is the agent API, not an SPI.** `sokar-agent-api` carries both halves of the
contract: the types Sokar calls to discover and drive an agent, and the types an
agent implements so Sokar can call in. "SPI" names only the second half and reads
as though the first is not there. Say **the agent API**, or **the agent contract**
where the point is that both sides share it.

**Every agent repository carries the same three-job build.** Build and unit tests on a pinned
`ubuntu-24.04` runner - the oldest glibc a native image must run against, and still the right
place to compile even though Sokar does not run there, since 24.04 will never have podman 5.
Then publish to Artifactory with a check that the package is *indexed* and not merely stored. Then
an **acceptance matrix on rented Hetzner machines**, `ubuntu` and `fedora`, installing from the
package repository rather than from a build tree. The third job is the one that gets left out and
the only one that proves what an operator installs; `agents/README.md` states it in full, and
`sokar-pi` and `sokar-omp` are the worked examples.

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
