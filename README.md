# Sokar

Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.

Sokar runs each agent task inside a hardened, rootless container with default-deny outbound networking, a credential
vault that keeps real keys on the host, a per-task git checkpoint, and a desktop notification path for live allow/deny
decisions.

Sokar is inspired by [Terok AI](https://github.com/terok-ai/terok) — not a fork and not a port, but it owes that
project a great deal: the architecture, and a lot of hard-won knowledge about how podman, nftables and D-Bus actually
behave. Big kudos to its developers. If you are more at home in Python, use it. It's a cool project!

## Why build this if Terok is so cool?

- **It's Java** — Sorry, I'm a Java developer and Python is not my world. Fixes and enhancements are simply easier for
  me here.
- **No Python runtime** — One signed native binary per host role. The package is files; nothing has to be installed on
  the host before Sokar will run.
- **APT/DNF packaging** — Installed with `apt` or `dnf`, under whatever policy your machines already have.
- **Easy to add an agent** — An agent is its own binary and its own package, discovered at runtime. Nothing in Sokar
  names it or depends on it, and the build fails if anything starts to. Adding one — including a proprietary agent that
  will never be upstreamed — means adding a directory, not patching Sokar.
- **Pinned, verifiable installs** — An agent CLI is fetched from a pinned URL and checked against a SHA-256 before it
  is allowed to run. No `curl | bash` in the middle of a tool whose job is containment.
- **Fast startup** — 1–2 ms cold start, against roughly 10 ms for the equivalent Python. Small numbers, but the OCI
  hooks fire twice per container start, so it sits on a path you notice.

## Structure

It is built from a Maven multi-module Java 25 project and ships as signed native binaries, with no language runtime
on the host:

- **`sokar`** — the CLI, and the only command an operator types: it starts and stops task containers, edits
  configuration, and unlocks the credential vault.
- **`sokard`** — an optional daemon that serves the same domain over a unix socket for the desktop client; nothing in
  the CLI path needs it running.
- **supervisor hook** — starts the per-container supervisor (vault broker, SSH signer, git gate, clearance hub) as the
  container is created, and reaps it once the container is gone.
- **nft hook** — loads the pre-generated nftables ruleset inside the container's network namespace before the workload
  gets to run; if it fails, the container does not start, so there is no window without egress control.
- **NFLOG reader hook** — starts the reader that turns the kernel's packet-drop records into Sokar's network audit
  trail.

The three hooks are statically linked against musl and make no native calls, so they run under whatever the OCI runtime
hands them at container-create time.


## Installing additional tooling in a box

First, an ambiguity worth clearing up, because two different things get called
"the agent":

- **`sokar-agent-claude`** is the `.deb`/`.rpm`, and it installs **on the host**,
  into `/usr/libexec/sokar/agents/`. It is Sokar's *adapter*: it knows how to talk
  to Claude Code — which flags it takes, where it writes its credentials, how to
  read its output. It is about 6 MB and contains exactly one file.
- **`claude`** is the CLI itself, and it goes **inside the task image**, because
  that is where it has to run. It is about 320 MB.

The package does not contain the CLI. It carries the *instructions* for installing
one: a pinned URL and a SHA-256. Sokar asks the installed adapter for those over
varlink and folds them into the generated `Containerfile`. That keeps the package
small and lets podman's layer cache download the CLI once per pinned version
rather than once per build.

So a task image is built in three layers, in this order:

1. **base** — the distro image the project names, plus an unprivileged `agent`
   user, a `/workspace`, and `curl` + CA certificates;
2. **agent** — the pinned, digest-verified download the installed agent adapter
   asked for;
3. **project** — your own lines.

Note that layer 2 needs network access **at image build time**, to the vendor's
download host. The egress firewall governs the running task container, not the
build, so an air-gapped machine needs a mirror for that URL.

Your tooling goes in the third layer. Either inline in `project.yml`:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends ripgrep jq \
        && rm -rf /var/lib/apt/lists/*
```

or in a file beside it, for anything longer than a few lines:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet_file: "tooling.dockerinclude"
```

The two are mutually exclusive and Sokar says so rather than silently preferring
one.

**Your lines run as root, before the image drops to the `agent` user**, because
installing packages is what they are almost always for. They also run *after* the
agent layer, so they can rely on the agent CLI already being present.

`sokar task run --dry-run` shows what would be built without building it, and the
generated `Containerfile` is left in `$XDG_DATA_HOME/sokar/build/<project>/` — it
is meant to be read.

Nothing you add here escapes the rest of the model: the container still starts
with no capabilities and `NoNewPrivs`, and the egress firewall still applies. If
your tooling needs to reach a host the project does not allow, the connection is
blocked and you are prompted — installing something does not widen the network.

## Building

```
./mvnw clean install
```

Requires JDK 25 or later. Byte Buddy is pinned to 1.17.8 via `org.fuin:bom`,
because earlier versions reject class-file major version 69.

### Native binaries

```
export JAVA_HOME=/path/to/graalvm-25
./mvnw -Pnative clean package
```

Produces `app/target/sokar`, `daemon/target/sokard` and the three hook binaries
in `hooks/target/`.

The hooks are linked statically against musl, which needs a cross-toolchain:

```
./buildtools/install-musl.sh
```

It installs to `~/.local/opt/x86_64-linux-musl-native`; override the location
with `-Dmusl.home=...`. Nothing needs to be added to `PATH`.

`sokar` and `sokard` are dynamically linked, and deliberately so: they use the
Foreign Function & Memory API, and `Linker.defaultLookup()` dlopens `libc.so.6`,
which a static image cannot do.

### FFM metadata

Panama downcalls are not discovered by native-image's static analysis. An unregistered one is not a
build error, it is a `MissingForeignRegistrationError` at runtime in the shipped binary, so the
registrations are generated from a test run rather than maintained by hand:

```
./buildtools/check-ffm-metadata.sh            # verify; non-zero exit on drift
./buildtools/check-ffm-metadata.sh --update   # rewrite, then commit the result
```

Run it in CI. After adding or changing a downcall, run it with `--update` and commit
`reachability-metadata.json` alongside the code.
