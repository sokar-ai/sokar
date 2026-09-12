# DNS and the resolver

Every task container has its own DNS resolver, and it is the layer that blocks first. This page
says where it sits, what it answers, why a refusal happens at the phone book rather than at the
wire, and why this one is allowed to fail where [the firewall](firewall.md) is not.

## Where it sits

**A dnsmasq of Sokar's own runs inside the container's network namespace, listening on
`127.0.0.1:53`, and the container's `resolv.conf` points at it.**

- **The configuration is generated on the host** from the project's security class and its declared
  domains, before the container exists — the same shape as the ruleset.
- **The process is started by an OCI hook** at `createRuntime`, in the container's namespace, and
  reaped when the container is gone.
- **It answers only the container**, on loopback only (`listen-address=127.0.0.1`,
  `bind-interfaces`). The nftables chain already permits loopback, so nothing extra has to be
  opened for it.
- **It ignores the host's `resolv.conf`** (`no-resolv`, `no-hosts`). Which upstream resolvers
  allowed queries are forwarded to is chosen by Sokar, not inherited.

## What it answers

**The default answer is NXDOMAIN**, for every name. A name resolves only if the project allows it,
which makes DNS the same shape as the packet filter: deny by default, widen deliberately.

That also closes **DNS exfiltration** — the trick of encoding data into names nobody ever asked to
be resolvable. A resolver that forwarded every query would carry that data out no matter what the
packet filter did afterwards.

For an `offline` project nothing resolves at all: there is no name to look up.

## Why the block is here rather than at the firewall

A resolver that answered every name and let the firewall drop the traffic afterwards would do two
harmful things. It would **confirm to the agent that a host exists**, and it would turn every stray
lookup into a **question for the operator**. Blocking at the name means an undeclared site never
gets an address and no connection is ever attempted. Inside the container it simply looks like:

```
Could not resolve host: repo.maven.apache.org
```

**Every query is logged** (`log-queries`), so a refused name appears in the audit trail instead of
failing silently somewhere inside the agent.

**Nothing is cached** (`cache-size=0`). A cached answer would let a name keep resolving after the
operator revoked it, for as long as its TTL said.

## Resolving and being allowed to reach are one decision

A declared domain is not merely resolvable — every address the resolver answers for it is added to
the firewall's allow set **as it is answered**, through dnsmasq's `nftset=` option, for IPv4 and
IPv6 both.

This is not an optimization. Without it a declared host resolves and is then dropped, producing a
clearance prompt about something the project already declared — measured on `github.com`, dropped
twenty times while the operator saw only a hang. The prompt exists for what an agent reached for
that *nobody* declared.

**Resolved rather than pinned**, because pinning does not work: a large host rotates addresses, and
the address the host machine resolves at task start is measurably not the one the container gets a
minute later. dnsmasq adds whatever it actually answered, so what the container was told and what
it may reach cannot drift apart.

The IPv6 half is there for the same measured reason: a declared name that answered AAAA was
reachable by name and blocked by address, and that reached the operator as a clearance prompt for a
bare IPv6 address they could not place.

### `--nftset` has to be compiled in

A dnsmasq built without it **fails silently**: the configuration is accepted, names still resolve,
and every declared host is then dropped by the firewall — which looks like a network fault, or like
a clearance prompt that will not stop coming. `sokar doctor` probes for the capability and reports
it, rather than leaving an operator to discover it.

## Widening a running task by name

The names that may resolve live in a **file of their own**, beside the configuration, because that
is the only part dnsmasq re-reads on `SIGHUP`. Appending a line there and signaling the process
makes the name resolve immediately: same process, no restart, and no window in which the container
resolves nothing.

That costs a separation worth knowing about. A servers file may contain nothing but `server=` and
`rev-server=` lines, so the `nftset=` lines stay in the configuration and **cannot be added later**.
The firewall half of a live widening therefore goes through the clearance watcher — which is why
opening something at runtime is [two operations](firewall.md#opening-something-while-a-task-runs)
rather than one.

## What happens when it cannot be started

**The container starts anyway, degraded.** This is the opposite of the firewall's rule, and the
reason is the asymmetry between the two: a missing resolver is not a containment property. Nothing
escapes because a name failed to resolve — the packet filter is still in place and still drops by
default. Refusing to start would turn a degraded run into no run, which is the wrong trade.

The failure is visible at the point of use: with nothing listening on the container's loopback, the
agent cannot resolve anything at all, and the hook records why.

## Where to go next

- [The firewall](firewall.md) — the other half, and the one that is fail-closed
- [The three security classes](security-classes.md) — what each class permits
- [Sokar for dummies](sokar-for-dummies.md) — the same ideas without the mechanics
- [FAQ](faq.md) — network questions in particular
