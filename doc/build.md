# Structure

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

### CPU

Every image is built for x86-64 v1, the baseline every x86-64 CPU has: `-march=x86-64` in the root
POM's native build arguments. native-image's default is the build host's generation - v3 on any
recent machine - and such a binary refuses to start on an older CPU or on a virtual machine that
hides AVX2, which an operator learns only by running it.

Each image carries the list of CPU features it checks for at startup. `buildtools/cpu-check` reads
that list in `package`, right after the images are built, and fails unless it is exactly
`[CX8, CMOV, FXSR, MMX, SSE, SSE2]` - in either direction, since a list that changed means the
target changed. A native profile turns it on and names its images in `sokar.cpu.images`; a new
image is added there.

### FFM metadata

Panama downcalls are not discovered by native-image's static analysis. An unregistered one is not a
build error, it is a `MissingForeignRegistrationError` at runtime in the shipped binary, so the
registrations are generated from a test run rather than maintained by hand:

```
./mvnw -Pnative,ffm-check -Dagent=true -pl buildtools/ffm-check,core,shield,vault -am verify                            # verify; fails on drift
./mvnw -Pnative,ffm-check -Dagent=true -pl buildtools/ffm-check,core,shield,vault -am verify -Dsokar.ffm.update=true  # add, then commit the result
```

Run it in CI. After adding or changing a downcall, run it with `--update` and commit
`reachability-metadata.json` alongside the code.

### Packages

```
export JAVA_HOME=/path/to/graalvm-25
./mvnw -Pnative,dist clean verify
```

Produces a `.deb` and an `.rpm` for Sokar itself, in `dist-deb/target` and
`dist-rpm/target`, plus one of each per agent under `agents/*/target`.

**`verify`, not `package`.** Both packagers are bound to `verify`, because
native-image binds to `package` and a plugin inherited from a parent runs before
the module's own — so at `package` time the binary does not exist yet. Running
`-Pnative,dist package` therefore rebuilds every binary and produces no packages
at all, or worse, leaves an older package in place beside a newer binary.

The `sokar` package installs:

| Path | Contents |
|---|---|
| `/usr/bin/sokar`, `/usr/bin/sokard` | the two dynamically linked binaries |
| `/usr/libexec/sokar/hooks/` | the three static hook binaries |

It registers nothing. Podman reads OCI hook descriptors per user, so each
operator runs `sokar setup` once; that writes the descriptors into
`~/.config/containers` and points podman at them. Nothing in the package touches
a system-wide podman configuration.

Agents are not part of it. They ship as separate `sokar-agent-*` packages that
depend on `sokar` and install into `/usr/libexec/sokar/agents`, which `sokar`
scans at runtime — see [the agent guide](../agents/README.md).

A snapshot build produces `0.1.0~SNAPSHOT`, with a tilde, because both dpkg and
rpm sort `~` below everything; left as `-SNAPSHOT` it would sort *above* the
release and neither apt nor dnf would upgrade from it.

To check the built packages, after `./mvnw install` and a `-Pdist verify`:

```
./mvnw -pl buildtools/package-check exec:java@check
```

It compares the deb and the rpm against each other — they declare their contents
in two different plugin syntaxes in two different modules, so they can drift —
and then installs both, plus an agent package, in clean Ubuntu and Fedora
containers to confirm the dependencies resolve and `sokar setup` finds the
packaged hooks. It also fails if a package is older than the binary it carries,
which is the `package`-instead-of-`verify` mistake above.
