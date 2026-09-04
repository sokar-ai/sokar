# 0042 — SELinux Host Socket Access

**Status:** open

On a distribution with SELinux in enforcing mode, a task container cannot connect to
the vault proxy's unix socket, and the credential swap - the mechanism the whole
design rests on - never happens.

Measured on Fedora 44, 2026-09-04:

```
avc: denied { connectto }
  scontext = system_u:system_r:container_t:s0:c215,c829     the task container
  tcontext = unconfined_u:unconfined_r:unconfined_t         the vault proxy
  tclass   = unix_stream_socket
```

The socket **file** is labelled correctly: podman relabels it `container_file_t` with
the container's own MCS categories, and the container can see it. What is refused is
connecting to a *process* running as `unconfined_t`, which is what any host-side
program a person starts is. No mount option or relabel changes this, because the
check is process-to-process rather than file-based.

The failure reaches the operator as an agent that cannot authenticate, with nothing
in `vault.log` - the request never arrives. With `setenforce 0` the same container
gets the provider's answer and the log records it.

## Acceptance

- A task container can redeem its phantom token on a machine with SELinux enforcing,
  without the operator turning SELinux off.
- Whatever makes that possible is scoped to Sokar's own socket. Letting every
  container reach every unconfined process would buy the fix by removing the
  protection.
- The machine is checked before a task runs, so an operator learns this from a
  message that names it rather than from an authentication error.
- The acceptance suite runs green on an enforcing machine, so the guarantee is
  measured where it is weakest rather than only where it is easiest.

## Notes

Three ways out, none free:

1. **`--security-opt label=disable` on the task container.** One line, works
   everywhere, and switches off SELinux confinement for exactly the container that is
   running someone else's agent. Sokar's guarantees rest on namespaces, dropped
   capabilities and the packet filter, so this removes defence in depth rather than
   the defence - but it removes it from the place with the most to gain from it.
2. **A policy module shipped with the package**, allowing `container_t` to connect to
   `unconfined_t` unix sockets. Needs root at install time. Broader than it looks: it
   grants this to *every* container on the machine, not only Sokar's.
3. **A domain of Sokar's own** for the proxy, with a rule allowing only
   `container_t -> sokar_t`. The narrow answer, and the most work: a policy module to
   write, a file transition for the binary, and a second thing to keep correct as the
   product changes.

Nothing here is decided. (1) is what makes a test run today; (3) is what a security
product should probably ship.

**A fourth way exists: do not cross the boundary.** The denial is about reaching a
host process from a container. A loopback TCP listener reached at the host address
the container already has needs no policy on any distribution - at the cost of a port
another user on the machine can see listening, where a socket in a `0700` directory
is invisible to them. Further out, a container-to-container transport would not cross
the host boundary at all and the question would not arise.

**This has been solved before, and the shape of the answer is known.** A custom type
applied to the *socket object* rather than to a file, granted to containers for
`connectto` and nothing else, so a container cannot create a listener wearing the
label and impersonate the proxy to a sibling task. The type is applied by setting the
socket creation context before `bind()`, which on Linux is a write to
`/proc/thread-self/attr/sockcreate` on the binding thread - no foreign-function call,
so it does not disturb the static hook binaries. Installing the module is one
`semodule -i` needing root once per machine.

Sokar's version of this is smaller than it might be: every socket is bound by a host
process the operator started, so the module needs rules for that domain and for
`container_t`, and nothing for the container runtime.

## Measured, 2026-09-04

The mechanism was verified on Fedora 44 with SELinux enforcing, before any of it was
built into the product:

| Question | Answer |
|---|---|
| Can a Java process set the socket creation context? | Yes - a plain write to `/proc/thread-self/attr/sockcreate`, no foreign-function call |
| Does `bind()` still work afterwards? | Yes |
| What does an unknown type do? | Fails with `EINVAL` - usable as an install check needing neither root nor a container |
| How is the context cleared afterwards? | Write a NUL byte; writing an empty string performs no write at all and silently leaves it set |
| Does a container then connect? | Yes, under enforcing |
| Does an unlabelled socket still get denied? | Yes, with the denial naming it |

The comparison was controlled: the same image, the same `--cap-drop=ALL`, the same
`:z` mount and the same directory, two sockets differing only in creation context. The
labelled one answered the container; the plain one was denied and logged.

The module needed two rules rather than three, as expected: every socket is bound by a
host process the operator started, so nothing has to be granted to the container
runtime.

Note the file label and the socket label are separate things. The container runtime
relabels the socket's *inode* to `container_file_t`, which governs opening it; the
`connectto` check is against the socket object's own context, which is what this sets.
Both have to be right, and only the second one is Sokar's job.

## To be checked

- Whether the same denial appears on other enforcing distributions, or whether the
  Fedora policy is the strict case. It decides whether this is one distribution's
  problem or the general one.
- ~~Whether `doctor` can detect it without starting a container.~~ **Answered:**
  setting the context fails with `EINVAL` when the type is not in the loaded policy, so
  the check is a write to a `/proc` file - no root, no container, no side effect.
- Whether the gate's git endpoint has the same problem. It is reached over TCP rather
  than a unix socket, so probably not, but it was not measured.
