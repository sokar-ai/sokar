# B59 — A Kernel Of Its Own For A Task

**Status:** later; an experiment before it is a requirement.

**What must be true.** Whether a task can run under a runtime that gives it its own kernel is measured rather than
assumed, and a task never starts that way without its ruleset failing closed.

## Why

Nice to have; nothing waits on it, and nothing in the default path changes until the first two questions below
have measured answers.

A task is a rootless container: every capability dropped, `no-new-privileges` set, an unprivileged
account inside. What it shares with the machine is the **kernel**. That is stated plainly in
`doc/how-it-works.md`, and it is the one boundary Sokar cannot make stronger by dropping
anything further: a kernel bug is one boundary away from the host.

A container can also be started with a runtime that puts it in a virtual machine of its own -
`podman run --runtime krun`, backed by libkrun and KVM. The image stays an OCI image, the workload
stays a container to everything above it, and underneath it gets its own kernel, with the
hypervisor as a second wall. **Whether Sokar's containment survives that move is unmeasured, and
this issue exists to measure it rather than to assume either answer.**

## What was measured, 2026-09-16

On the machine this was written on:

- `/dev/kvm` exists, and podman's runtime is `crun` 1.21.
- The distribution packages a KVM-backed OCI runtime (`crun-krun`, `libkrun`, `libkrunfw`), so an
  experiment needs no build from source. Other runtimes of the same kind are packaged as well.
- Both libvirt test machines were powered off, so nothing was measured there. They are themselves
  virtual machines, and so are the rented ones: **nested virtualisation is a precondition on every
  machine Sokar is currently tested on**, and it is unknown whether it is available there.

## The shape: what the experiment has to answer

The first two decide whether the rest is worth doing.

1. **Where does the ruleset go?** The `nft` hook runs at `createRuntime` and loads a default-deny
   ruleset with `nsenter --target <pid> --net` - into the workload's network namespace on this
   machine. Under a kernel of its own there is no host pid for the workload and no host namespace
   holding its network stack. Either the filtering point moves to the host side of the virtual
   interface, or the ruleset moves inside the guest and something in there has to load it before the
   agent runs - and fail closed if it cannot.
2. **Can the resolver still lie?** `NXDOMAIN` for every undeclared name, and the resolver filling
   the nftables set for the declared ones, both depend on dnsmasq running in the same namespace as
   the workload. Measure what that becomes, including whether the guest resolves over TCP only.
3. **Do the hooks still run, and what can they still do?** Three of Sokar's four hooks reach inside
   the workload's namespaces. A hook that runs on the host while the workload runs under another
   kernel is a different instrument, whatever the runtime reports.
4. **Do the sockets still arrive?** The vault proxy, the ssh-agent and the clearance socket are unix
   sockets bind-mounted into the container, and the credential swap depends on them. Measure whether
   they reach the workload, and through what.
5. **What replaces `task attach`?** That runtime does not support `podman exec`, which is exactly how
   a terminal is handed over today (`exec -it … tmux new-session -A`).
6. **What does it cost?** Start time per task, memory per task, whether the image has to change, and
   whether the acceptance suite passes unchanged against a task started this way.

## Acceptance

- Every question above has an answer measured on a machine with KVM, written down with the command
  that produced it - including the ones whose answer is "this cannot work as it stands". Seen to fail: a question
  above with no measured answer and command beside it.
- **The default does not move.** A task keeps its rootless container with the nftables ruleset until
  questions 1 and 2 have an answer that fails closed, and adoption is then **opt-in per project**. Seen to fail: a
  task in a project that has not opted in starts under the KVM-backed runtime.
- `sokar doctor` says whether this machine can do it at all, rather than a task failing to start
  with a runtime error. Seen to fail: `sokar doctor` on a machine without `/dev/kvm` or the runtime reports it
  able.
- The `offline` class is the first candidate: it declares no egress, so question 1 is smallest there.
- No acceptance scenario is weakened to make a task start this way, and a task that cannot get its
  ruleset does not start - under either runtime. Seen to fail: a task whose ruleset load is made to fail starts,
  under either runtime.

## To be checked

- **Nested virtualisation** on the libvirt test machines and on rented ones. Without it, this cannot
  be tested where everything else is tested, and that alone may end the experiment.
- **Whether the guest is the better home for the ruleset anyway.** A ruleset the workload cannot
  reach around, loaded by something the workload cannot stop, is the property that matters; which
  kernel holds it is not.
- **What a second wall is worth here.** Rootless, no capabilities and `no-new-privileges` already
  remove the usual ladder; the gain is against kernel bugs, and the cost is the four mechanisms
  above. That trade is the decision, and it belongs to the operator.
- **This is the rehearsal for `sokar-project` PJ25**, Sokar on Apple Containers. A container on macOS always has a kernel
  of its own, so that requirement asks the same four questions this one does: where per-container
  egress filtering lives and whether it fails closed, whether a host socket reaches the workload,
  whether the host's loopback can be mapped in for the gate, and how much is genuinely shared.
  Answering them here costs one Linux machine with KVM and the acceptance suite that already runs;
  answering them there costs a second implementation on another platform.
- **Snapshot and restore.** A task comes back after a machine restart through `task start
  --restarted`, and its conversation with it where B46 ([index](README.md)) can. A runtime that can freeze and restore a workload would change
  what those can promise, and it is not available through podman today.
