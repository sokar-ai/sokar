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
- **US spelling** in prose and comments: behavior, recognize, serialize, license,
  defense. Changed from British-leaning on 2026-09-07 and swept through the
  repository in one commit, identifiers included - `GitGate.initialize()` was
  `initialise()`. Two words were left alone because they are not errors in US
  usage and changing 65 of them would have been diff noise: *afterwards* and
  *towards*.
- Prefer a small named method over a comment explaining a block.

## Tests

- **Descriptive method names, not `testXxx`.** All 775 test methods read as
  sentences — `refusesADomainThatIsBothAllowedAndRefused`,
  `readsTheDomainsAnAgentNeeds`. There is no `testXxx` left; do not reintroduce it.
- **Every guard must be proven to fail.** A test that has never failed is a test
  nobody has checked. When adding a rule, deliberately violate it once and watch
  it break, then keep the negative case if it can be expressed as a test. This is
  how the ArchUnit rule, the FFM metadata check, the domain-coverage check, the
  git-gate firewall rule and the package freshness rule were all validated.
- **Watch the RIGHT thing fail.** Breaking the rule is only half of it. The agent
  name-collision check survived its own mutation: it asserted the "ignored"
  message, which is built from the losing side and stayed word-for-word identical
  while the register held the other copy. It was testing the reporter, not the
  behavior. Assert on what the system DOES - which binary the `FROM` column names,
  which container exists, what a host can reach - never on what it says about
  itself. Two sibling near-misses the same day: a grep that matched the fixture's
  own name (a task called `lockedrun`, greped for "locked"), and a test for a
  state a constructor forbids. If a mutation leaves a check green, the check is
  the thing that is broken.
- **Test observable behavior**, not internals: the generated ruleset, the
  packaged file list, what a container can actually reach.
- **A fixture must not inherit the machine's configuration.** `git init` takes
  its branch name from `init.defaultBranch`, so a test that pushed `main` into a
  fixture built without `--initial-branch` produced an upstream whose HEAD named
  a branch that was never created - and `git fetch origin`, which asks for HEAD
  when given no refspec, then failed. Green on a laptop that sets the option,
  red on CI which does not. Run the suite with `GIT_CONFIG_GLOBAL=/dev/null`
  before trusting anything that shells out to git.
- **Keep the reason in the assertion.** `assertThat(x.reason()).isEqualTo(MEASURED)`
  fails with "expected MEASURED but was FAILED" and throws away the detail the
  object is carrying. `.as("git said: %s", x.detail())` turns a reproduction
  round into a readable CI log.
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

See [build.md](doc/build.md). Three things that will bite:

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
  is the same operation under its older name and goes through the same code.
- **A token printed in full defeats the type that hides it.** `TaskToken.toString` and
  `PhantomToken.toString` both abbreviate because tokens end up in log lines by accident - and
  `gate serve` then wrote `token.value()` into `gate.log`, which the daemon streams to whatever is
  tailing it. Print the token, not its value.
- **An unmodifiable map is not a copied one.** `Map.copyOf` loses insertion order, and the egress
  report is read in the order its sources were consulted - the agent's hosts, then its provider's,
  then the project's, grouped by the set that granted them. A report whose order changes between
  runs cannot be diffed against yesterday's. Wrap a `LinkedHashMap` instead.
- **A registry that two processes write is a directory of files, not one document.** The project
  registry began as a single JSON file, which meant read-modify-write: 24 concurrent starts kept
  one entry and lost 23, measured, and both writers went through the same temporary file so the
  document moved into place could have been a mixture of the two. One file per project removes the
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
  answers yes for a daemon that died hours ago. The acceptance suite's daemon section skipped
  itself that way and reported success; it now asks `org.varlink.service.GetInfo` through
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
- **A failure is held, not swept up.** `--keep` has to be decided before a run, and the run worth
  looking at is the one that went wrong - which is known only afterwards. So a non-zero exit stops
  the container through `TaskControl` and leaves it: workspace, logs and unpushed commits intact,
  `task resume` to go back in, `task stop --purge` to discard. Stopped rather than left running,
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
  guessed at. Measured before this existed: a task stopped first and purged afterwards was
  removed silently, with its unpushed commit, reporting success.
- **A rescue that pushed nothing must not report success**, because the caller removes a
  container on the strength of that answer. In a repository with no initial commit the push
  asked for `HEAD` before it committed, printed `nothing to push`, exited zero, and the
  container was removed as rescued while the mirror never saw a ref. Commit first, and exit
  non-zero when there is genuinely nothing.
- **The guard covers `sokar task stop --purge` and nothing else.** A `podman rm` typed
  directly, or a tidy-up script, still destroys a workspace without a word: nothing Sokar
  writes can stop the runtime's own command.
- **The clearance watcher has two ways in, and only one of them broadcast.** When it starts
  its own reader, events arrive as varlink `Report` calls and subscribers see them on the way
  past. When the reader hook is already running inside the container - the normal case - the
  watcher *follows the file* that hook appends to, and that path reached the hub without ever
  reaching a subscriber. A client subscribed to a live task saw nothing at all while the log
  beside it recorded the decisions. Both ways in now call `ClearanceService.publish`.
- **A decision that lives only in the watcher is a retry loop.** The hub deduplicates in a map in
  its own process, and a resumed task starts a fresh watcher that re-reads its events file from
  the beginning - so every destination already decided arrived again within seconds and was asked
  about a second time. An agent refused once only had to keep retrying until something restarted
  the watcher. Decisions are now appended to `~/.local/state/sokar/clearance/<container>.jsonl` and
  read back on start. Under the *state* directory because everything else a task writes is under
  the runtime one, which the kernel clears at logout and `task stop --remove` deletes outright: a
  record of what an agent reached that disappears with the task is not a record. Named by the
  container, so a decision belongs to one run and is not silently in force for the next.
- **A restored allow has to be put back into the firewall, not only remembered.** A resumed task
  gets a fresh namespace and a ruleset the hook rebuilds from the project, so an address cleared
  last time is no longer in it. Remembering the answer alone leaves the hub saying allow while the
  packets are still dropped and nothing will ever ask again - worse than either honest state.
- **`nft add element` answers `File exists` for an address already in the set.** A watcher
  restarted against a container that kept running re-applies every decision it recorded, so this
  is the normal case rather than an edge one. `EgressPolicy.allow` treats it as the outcome asked
  for; anything else is still a failure.
- **A notification that is closed rather than replaced destroys the only evidence it existed.**
  An expired clearance prompt left the destination blocked forever and took the question off the
  screen, so an operator who had been away could not tell it from one that was never raised. The
  timeout now sends a second `Notify` carrying the first notification's id, without actions and
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
  nothing saying so: measured, a daemon-started task had no gate and no clearance watcher while
  reporting that it had started. `SokarBinary.path()` is the one answer; it prefers the running
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
- **Prior art in this space has usually hit the problem first.** Where something is
  unclear, look at how others solved it — and read it for *what* to do, never for
  prose or code to copy: Sokar is a ground-up rewrite and Apache-2.0 attribution is
  taken seriously here. Note where they have *not* solved something either; a gap in
  somebody else's implementation is as informative as a solution, and cheaper to find
  than to rediscover. Which projects, where their checkouts are, and what each has
  already answered is a working note rather than product documentation: `.AGENT.md`
  in the repository root, gitignored like every dotfile here.

- **A provider is data; an agent is code.** An agent needs a binary because it has behavior
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

- **Ctrl-C left a task running without its gate, its broker or its watcher.** A `finally` covers
  every way an attached `task run` can end except the one that happens: a signal reaches the whole
  foreground process group, so the helpers — plain children, not detached — die with the CLI while
  the container, its ruleset and its resolver keep running. Measured on the test machine: a
  container up eleven minutes with the agent still working inside, nothing to push through and no
  way to reach the provider. `Teardown` arms a shutdown hook so the cleanup runs on a signal too,
  and it takes a lock rather than a flag — the runtime waits for hook threads and not for the main
  thread, so a hook that saw "somebody else has it" and returned would let the process exit with
  the container half stopped. **Measured, not assumed:** the native image runs shutdown hooks on
  SIGINT, SIGTERM and SIGHUP, with no `--install-exit-handlers` in the build arguments.

- **A signalled run is kept, not removed.** The teardown starts at exit code 130, so it takes
  `cleanUp`'s failure branch: the container is stopped and held, because a run somebody
  interrupted may hold commits that never reached the gate.

- **Not every `sokar-` container is a task.** `vault login` runs an agent's own login in a
  throwaway container, which carries the prefix so a cleanup can find it — and it turned up in
  `sokar task list`, where every column describes something it does not have. `ContainerName`
  splits `isSokar` (Sokar made it) from `isTask` (it has a workspace, a gate, a ruleset and a
  clearance). The prefix alone cannot make that split: `login` is a legal project name, so
  `sokar-login-shell-25471` is a real task, and what separates them is that a login carries only a
  timestamp where a task carries a task name and a run id.

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

## The interface contract

`daemon/src/main/resources/varlink/org.fuin.sokar.Tasks1.varlink` is the API the frontend is
built against, in a separate repository
([sokar-frontend](https://github.com/fuinorg/sokar-frontend)). It is the contract, not a
description of one: the daemon serves it verbatim through
`org.varlink.service.GetInterfaceDescription`, and `InterfaceDescriptionTest` fails the build
when a method is registered without appearing in it, appears in it without being registered,
reads a parameter it does not describe, or throws an error it does not name.

- **The trailing `1` is the compatibility promise**, and it is varlink's own convention -
  `org.fuin.sokar.Clearance1` already followed it. Within one number the interface only grows:
  new methods, new `?` parameters, new reply fields. Nothing that exists may be removed, renamed,
  retyped or given a new meaning. A change that cannot be made that way becomes `Tasks2`, served
  *beside* `Tasks1` for at least one release, because a fleet is not upgraded at once.
- **Adding an enum value is explicitly not breaking**, so the contract requires clients to
  tolerate unknown ones. `Outcome` will gain entries.
- **Parameter names must be varlink identifiers.** Hyphens are not: `credential-type` and
  `token-hours` had to become `credentialType` and `tokenHours` before the description could be
  written truthfully, which is the sort of thing an IDL catches and an ad-hoc map does not.
- **Derive nothing on the client side that the daemon can send.** A clearance prompt now carries
  its own `key` because a client rebuilding it from the other fields is one separator away from
  answering a prompt that does not exist, while the task stays blocked.

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

**Everything in the repository is written in English** - code, comments, requirements,
documentation, commit messages - whatever language the conversation that produced it happened in.
The spelling rule above is the detail; this is the rule. A repository that switches language
halfway strands every reader who does not share the author's first one, and the people most likely
to read a security tool's reasoning are not all German speakers.

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

**Every agent repository carries the same three-job build.** Build and unit tests on a pinned
`ubuntu-24.04` runner - the oldest glibc a native image must run against, and still the right
place to compile even though Sokar no longer runs there, since 24.04 will never have podman 5.
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
