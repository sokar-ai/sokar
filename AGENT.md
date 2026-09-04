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

The same rule in reverse: an agent module may depend on `sokar-agent-api` and
nothing else of Sokar's.

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

- **Descriptive method names, not `testXxx`.** 296 of 320 test methods read as
  sentences — `refusesADomainThatIsBothAllowedAndRefused`,
  `readsTheDomainsAnAgentNeeds`. The `testXxx` minority is drift; do not add to
  it.
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
- **`dnsmasq --nftset` is load-bearing, and its absence is silent.** It is what
  makes a declared domain reachable rather than merely resolvable. A dnsmasq
  compiled without it accepts the config and opens nothing. `sokar doctor` probes
  for it; note that `no-nftset` contains `nftset`, so a substring check reports
  the opposite of the truth.
- **Terok is the reference when something is unclear.** Sibling checkouts live in
  `../terok-ai/`: `terok`, `terok-sandbox`, `terok-executor`, `terok-shield`,
  `terok-clearance`, `terok-util`. It has already hit most of these problems.
  Read it for *what* to do, never for prose or code to copy — Sokar is a
  ground-up rewrite, and Apache-2.0 attribution is taken seriously here. Note
  also where Terok has *not* solved something: its Claude OAuth proxy is
  experimental and off by default, and its Copilot authentication is broken.

## Security rules that are not negotiable

- **Never put a secret in a command line.** A process list is world-readable.
  Credentials go in through standard input, an environment variable on a
  process you spawned, or a `0600` file — never `argv`.
- **The real credential never enters a task container.** The agent gets a
  phantom token; the vault proxy swaps it on the way out. If you find yourself
  passing the real key in, stop and reconsider the design.
- **A proxy without a firewall rule is not containment.** Whenever a phantom
  token is issued, the provider's own host is withheld from the ruleset, or an
  agent with a compiled-in URL bypasses the proxy and leaks the token upstream.
- **Fail closed.** The nft hook refuses to let the container start if the ruleset
  will not load. Keep that property in anything new on the startup path.
- **Log tokens abbreviated, never whole** — `PhantomToken.abbreviate`. A log that
  contains a working credential is a credential store with no lock on it.

## Documentation

`README.md` is an index; the substance lives in `getting-started.md`, `why.md`,
`your-tooling.md`, `build.md` and `agents/README.md`. `.sokar.md` is the planning
document — gitignored, and the place where phase status, decisions and findings
are recorded.

Write only what you have verified. Prefer a measured number to an adjective.
When something is not built, mark it **TODO** rather than describing it in the
present tense and hoping.
