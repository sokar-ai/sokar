# B40 — The Domain That May Bind The Socket

**Status:** soon.

**What must be true.** Only Sokar may bind a socket carrying Sokar's label, and a task that connects
to one reaches Sokar rather than something else the operator happens to be running.

## Why

It is small. A security review read `selinux/sokar_socket.te` and objected to `unconfined_t` holding
`create bind listen`. The objection as written overstates it; what survives examination is real and
is what this file is for.

## What the policy says today

`sokar_socket_t` is placed on the socket object by the server, which sets its socket creation
context before `bind()`. Three domains appear:

- `unconfined_t` — binds the sockets and connects to them as a host-side client.
- `container_t` — the task, which may only connect.
- `container_runtime_t` — the relay inside the task's network namespace, which may only connect.

**The container half is already right, and was measured.** Neither container domain may create a
socket of this type, so a compromised container cannot stand up a listener wearing the label and
collect another task's token. That is the property the labelling was bought for and nothing here
weakens it.

## What is actually left

`unconfined_t` is the operator's own login domain, not Sokar's. Every process the operator runs is
in it, so any of them may bind a socket carrying `sokar_socket_t` — and a task connecting to the
credential socket cannot tell which of them answered.

**The honest bound on this is that such a process has already lost.** Anything running as the
operator can read the vault file, the runtime directory and the socket's own parent directory,
which is `0700` and is what actually keeps other users out. So this is not a privilege boundary
being crossed; it is a second lock on a door whose first lock is the uid.

**What makes it worth doing anyway is that the second lock is cheap and specific.** A domain
transition on `/usr/bin/sokar` would mean the label can be bound by that binary and by nothing
else, so an operator-owned process that is not Sokar cannot impersonate the broker even though it
could read everything else. The distance between "already lost" and "lost quietly" is the whole of
what this buys.

## Acceptance

- A process that is not Sokar, running as the operator, cannot bind a socket labelled
  `sokar_socket_t` — demonstrated by trying it, not by reading the policy. Seen to fail: on an
  enforcing machine with the policy loaded, a test process outside Sokar's domain binds such a
  socket.
- Sokar itself binds and connects as it does today, on a machine with the policy loaded and
  SELinux enforcing. Seen to fail: `gate serve`, `vault serve` or `vault relay` is denied a bind
  or a connect there.
- The container half is unchanged: both container domains may connect and neither may create. Seen
  to fail: `container_t` or `container_runtime_t` can create a socket of the type, or cannot connect
  to one.
- A machine without the policy behaves as it does now, and says which of the two it is. Seen to
  fail: a machine without the policy refuses what it allows today, or does not say the policy is
  absent.
- The policy states which domain each rule is for and why, so a later reader can tell a decision
  from an accident. Seen to fail: a rule in the policy without that statement.

## To be checked

- **Whether a domain transition is achievable for a binary an operator may also run from a build
  tree.** A transition keyed to `/usr/bin/sokar` covers the packaged binary; a developer running
  `app/target/sokar` is unlabelled and would be refused, which is a worse outcome than today if it
  is not handled deliberately.
- **What the daemon and the helpers transition to.** `gate serve`, `vault serve` and `vault relay`
  are the processes that actually bind, and whether they are the same domain as the CLI or one of
  their own decides how narrow the rule can be.
- **Whether the same argument applies to the sockets' directory mode rather than to SELinux.** If
  the answer is that a `0700` directory plus the uid is the real control, then this is hardening on
  a machine that has SELinux and nothing at all on the machines that do not, and that asymmetry
  should be stated rather than discovered.
- **Whether AppArmor has an equivalent worth writing.** The supported set is two distributions and
  only one of them is SELinux; a guarantee that exists on one is a guarantee with a footnote.
