# Sokar

Sandboxing AI agents in YOLO mode using Podman and Java/GraalVM

Sokar runs each agent task inside a hardened, rootless container with default-deny outbound networking, a credential
vault that keeps real keys on the host, a per-task git checkpoint, and a desktop notification path for live allow/deny
decisions.

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
