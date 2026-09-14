# B57 — Names Asked Past The Resolver

**Status:** open. Found on 2026-09-14 by reading the ruleset generator. **Not yet measured in a
running task**; everything below about what a task can do comes from the rules the code writes.
The documentation was corrected the same day to describe the gap instead of promising it was closed.

## What the documentation promised

`doc/dns.md` said an undeclared site never gets an address, and that the NXDOMAIN default closes
DNS exfiltration. The generated ruleset still carries the comment *"DNS goes to the resolver Sokar
runs, and nowhere else."*

## What the ruleset permits

- **The resolver runs inside the task's network namespace**, so its own upstream queries pass
  through the same output chain as the agent's traffic.
- **To let those through, `TaskRunner.rulesetFor` adds** `ip daddr <upstream> udp dport 53 accept`
  and the same for TCP, once per upstream resolver.
- **The upstreams come from `hostResolvers()`**: the host's `nameserver` lines, without loopback
  and IPv6 addresses, falling back to `8.8.8.8` when none are left. A host running systemd-resolved
  has only `nameserver 127.0.0.53`, so there the upstream is always `8.8.8.8`.
- **The rule matches the destination and nothing else.** Every process in the namespace can use it,
  so the agent can too.

So a task can send `dig @8.8.8.8 anything.example` and get a real answer.

## What still holds

**The connection is still refused.** Only two things add addresses to the allow sets:
the resolver's `nftset=` lines, for names it answered itself, and an operator's approval. An
answer from somewhere else puts nothing in them. The connection is dropped, logged and raised
as a clearance prompt for a bare address. DNS over TLS on 853, and DNS over HTTPS to a resolver
nobody declared, are dropped the same way.

## What does not

- **Data can leave in query names.** A name under a domain someone controls reaches that domain's
  authoritative server by way of the upstream. There is no prompt, because nothing is dropped. There
  is no log line either: the chain logs only drops, and Sokar's resolver never sees the query.
- **Whether an undeclared host exists can be confirmed**, which the NXDOMAIN default was meant to
  withhold.

It is a smaller channel than the provider channel B38 names ([index](README.md)), and unlike that
one the documentation told readers it was closed.

## What must be true

**Port 53 out of a task is open to Sokar's resolver and to nothing else in the task, so an
undeclared name gets no answer from anywhere.**

## Acceptance

- From inside a task, a DNS query sent directly to an upstream resolver over UDP or TCP is dropped
  and logged. That holds as the agent user and as every other uid the agent can run as.
- Declared names still resolve, including an answer large enough that the resolver retries over TCP.
- The comment in the generated ruleset says what the rule does.
- An acceptance scenario queries the upstream directly and fails if it gets an answer.
- `doc/dns.md` and `doc/firewall.md` no longer carry the TODO for this.

## Ways to close it, none of them measured

1. **Match the resolver's uid**: `meta skuid 0` on the two port-53 rules. The resolver's
   configuration says `user=root`, and the image ends with `USER agent`. Three conditions must hold:
   nftables has to compare the uid within the namespace's own user namespace; no process the agent
   can reach may run as uid 0; and `no-new-privileges` with `--cap-drop ALL` has to keep it that way.
   **This is the one to try first**, because it changes two lines and nothing else.
2. **Match the resolver's cgroup** with `socket cgroupv2`. A hook starts the resolver from outside,
   so it may sit in a different cgroup from the container's processes. Where it lands under rootless
   podman is not known.
3. **Pin the resolver's source port below 1024**, with `query-port=` in the resolver and
   `udp sport` in the rule. The agent has no capability to bind a low port. This covers UDP only,
   because the resolver's TCP queries use an ephemeral port. It also depends on
   `ip_unprivileged_port_start` staying at 1024 in the namespace.
4. **Move the upstream query out of the namespace**, so the resolver forwards to something on the
   host through a path only it holds. This is the largest change, and the resolver would no longer be
   self-contained in the task.

## Not in scope

- **A permitted address serves every name at that address.** The filter matches addresses, so a
  declared host behind a shared CDN address makes other sites at that address reachable on 80 and
  443 as well. That is part of B38's *"every declared host is a second channel"*
  ([index](README.md)).
- **DNS over HTTPS through a declared host that is itself a resolver.** Same item, same file.

## To be checked

- **Whether it happens as read.** Run `dig @<upstream> example.org` from a running task as
  `agent`, and run the counter-test after any fix.
- **Whether `meta skuid` in a rootless namespace sees the container's uids** (option 1), on
  podman 4 and podman 5.
- **Which uids a task's processes can run as**: an attached shell, `podman exec`, and anything a
  hook starts inside the namespace besides the resolver.
