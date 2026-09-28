# B56 — Programs Sokar Runs That Could Be Calls

**Status:** open, nice to have. Nothing waits on it. It is to be checked again later rather than
scheduled, because what Java and GraalVM can call natively moves with each release.

Sokar starts external programs where it needs something from the operating system. Each one is a
process, a command line, output parsed as text, and a program that has to be installed in the
version expected. Some could be a function call instead: through the Foreign Function & Memory API
(`java.lang.foreign`, standard since Java 22), or through GraalVM's C interface
(`org.graalvm.nativeimage.c`, which exists only in a native image). This file is what was found on
2026-09-13.

## What already is a call

FFM is used in four places, all in the dynamically linked binaries. GraalVM's C interface is used
nowhere.

| Class | Call | Why a call |
|---|---|---|
| `ProcessHardening` | `prctl(2)` | Per thread, and verified by reading the value back |
| `Exec` | `execvp(3)` | Hands a terminal to a shell without Sokar sitting in between |
| `NetlinkSocket` | `socket(2)` with `AF_NETLINK` | The JDK has no netlink |
| `KernelKeyring` | `libkeyutils` | The vault's unlock cache |

## The constraint that decides most of it

**The three OCI hooks cannot use FFM.** They are built `--static --libc=musl`, and FFM's default
lookup opens `libc.so.6` on its first downcall, which fails in a static image. `NoForeignFunctionMemoryTest`
in `hooks` and in `wire` enforces it, because a violation links and then fails at container start —
on the fail-closed path. GraalVM's C interface binds when the image is linked instead, so it is the
only candidate inside a hook; whether it works against static musl is unmeasured.

## The programs, and what was found

| Program | Started by | Could be | Found so far |
|---|---|---|---|
| `nsenter` | the three hooks, `EgressPolicy` | `setns(2)` | Entering a network namespace is per thread and allowed in a multi-threaded process. The program that has to run inside it — `nft`, `dnsmasq` — must start in that namespace, and whether a child started from Java inherits the calling thread's namespace in a native image is unmeasured. In a hook only the C interface applies. |
| `nft` | `NftHook` | `libnftables` | A C library with the same ruleset text as input. A static hook would have to link it and its dependencies statically. |
| `podman` | `Podman`, task control | podman's REST API on the user's socket | Not a native call at all: podman is Go and has no C API. The JDK already speaks HTTP over a unix socket; it would make the user's `podman.socket` a dependency. |
| `podman unshare` | `EgressPolicy` | — | Joining a rootless user namespace needs a single-threaded process, which neither a JVM nor a native image is. Stays a program. |
| `git` | the gate, `GitSubprocess` | JGit, or libgit2 through FFM | JGit is plain Java and needs no native call. Whatever replaces it, the smart-HTTP path is binary and has to stay in bytes end to end. |
| `systemd-creds` | `SystemdCredential` | — | No public library call for encrypting a credential was found. |
| `tar` | `AgentStaging` | plain Java | Gzip is in the JDK and a tar reader is small. No native call needed. |
| `dnsmasq` | the resolver | — | A long-running server, a program by nature. |
| `sh -c` | `CommandPassphrase` | — | The operator's passphrase command is a shell command by contract. |
| `newuidmap`, `newgidmap` | podman, indirectly | — | Setuid helpers. The privilege is the point, and no call replaces it. |

## Not in scope

**Shell strings the Hetzner driver sends over ssh** (`Leg`, `AgentLeg`). They run on another machine,
which no call in the driver's process can reach. The way to less shell there is to keep the logic
in the driver and send plain commands, or to make the step a `sokar` subcommand. B53 did that for
the two cases it had - the rootless check under the unit's properties (`UnitProperties`) and the
agent's description (`AgentDescription`) - and was finished on 2026-09-28; another case would be an
issue of its own ([index](README.md)).

## Acceptance

- Every external program Sokar starts appears here, either with the reason it stays a program or
  replaced by a call.
- A replacement is proven on a JVM and in the shipped native image, on both acceptance legs, before
  the program is removed.
- Nothing in `hooks` or `wire` uses FFM while they are built static, and the test still says so.

## To be checked again

- **FFM in a static image**: whether a later GraalVM still needs `libc.so.6` for the default lookup.
- **GraalVM's C interface against static musl**, from inside a hook.
- **`setns(2)` and a child process** in a native image: whether the child starts in the namespace of
  the thread that entered it.
- **podman's REST API against its CLI**: what depending on `podman.socket` costs an operator.
