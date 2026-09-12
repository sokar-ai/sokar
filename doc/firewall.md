# The firewall

Every task container has its own packet filter. This page says where it sits, who is allowed to
change it, what it does when something goes wrong, and what a rule actually permits — because the
answer to *"is the firewall inside or outside the container?"* is neither one on its own.

## Where it sits

**The rules live inside the container's network namespace. They are put there from outside, and
nothing inside can reach them.**

Those are three separate facts, and the guarantee is all three together:

1. **Generated on the host, before the container exists.** Sokar renders the complete nftables
   ruleset from the project's security class and its declared egress, and writes it to a file
   while there is still nothing to protect. The component that loads it makes no policy decision
   of its own, which means an operator can read the ruleset before anything runs.
2. **Loaded into the container's namespace by an OCI hook**, at the `createRuntime` stage — after
   the runtime has created the namespaces, before it has pivoted into the container's filesystem.
   The hook runs `nsenter --target <pid> --net nft --file -`.
3. **Unreachable from inside.** The container is created with `--cap-drop ALL` and
   `--security-opt no-new-privileges`. Changing a namespace's rules needs `CAP_NET_ADMIN` in that
   namespace, and the agent has no capabilities at all. It can be stopped by the rules; it cannot
   read them away or widen them.

Point 3 is the one that makes the other two worth anything, and it is why *"inside or outside"* is
the wrong question: the rules are enforced in the container's own namespace — which is what makes
them per task rather than per machine, so two tasks cannot collide over them — and the authority
to write them stays on the host.

## Why it is a filter per task and not one for the machine

Every task gets its own namespace and its own ruleset. Nothing Sokar does touches the host's own
firewall, and two tasks on one machine — or two people in two accounts — never share a rule. A
machine-wide filter would have to be the union of everything every task is allowed, which is the
opposite of what a per-project declaration is for.

## What the rules say

**The output chain drops by default.** Everything else in the ruleset widens that and nothing
narrows it, which is deliberate: a bug that loses a rule fails towards *no connectivity*, not
towards an open container.

**A permitted name opens web ports, not the host.** Addresses reach the allow sets from two places
that both name a *host* and never a port — the resolver, which adds each answer as it answers, and
an operator approving one address at a time. Accepting every port to those addresses would grant
far more than either meant: notably git over ssh, which would let an agent push straight past the
gate. So ports 80 and 443 are matched explicitly. The gate's own endpoint is the exception, and it
binds loopback.

**Drops are logged, not silent.** Every dropped packet is recorded through NFLOG with Sokar's own
prefix, which is what turns a block into an audit trail and into the clearance question an operator
can answer.

## What happens when it cannot be loaded

**The container does not start.** The hook is fail-closed on purpose: a task container that comes
up without its egress policy is exactly the situation Sokar exists to prevent, and it would look
completely normal to the operator. There is no window in which the agent runs and the filter does
not.

Compare that with the resolver, which is [soft-fail](dns.md#what-happens-when-it-cannot-be-started)
for a reason given on that page. The difference between the two is the difference between a
containment property and a convenience.

## Opening something while a task runs

An approval adds **one element to an existing set** — `nft add element inet sokar allowed_v4 …` —
rather than reloading the ruleset. Reloading would drop conntrack state and kill every connection
the task already had open, which is an expensive way to permit one address.

The command takes the same route in: `podman unshare nsenter --target <pid> --net nft …`. The
`podman unshare` is needed because a rootless container's namespaces belong to the operator's user
namespace, and `nsenter` cannot join them from outside it.

## Two implementation notes worth keeping

- **`nsenter` rather than a thread that joins the namespace.** `setns()` is per-thread and a JVM
  offers no control over which thread anything runs on. A child process is the only way to be sure
  the ruleset lands in the container's namespace rather than the host's.
- **The ruleset goes in on standard input, not as a path.** Under SELinux, running `nft`
  transitions into a confined domain that cannot open the operator's runtime files — and that
  denial is `dontaudit`'ed, so the path form fails as *"Permission denied"* with nothing in the
  audit log at all.

## Where to go next

- [DNS and the resolver](dns.md) — the other half, and the layer that blocks first
- [The three security classes](security-classes.md) — what each class permits
- [Sokar for dummies](sokar-for-dummies.md) — the same ideas without the mechanics
- [FAQ](faq.md) — network questions in particular
