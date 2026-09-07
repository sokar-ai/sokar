# 0023 — McSokar Apple Containers

**Status:** open

A sibling project, **McSokar**, offering the same behaviour on Apple Containers so
that macOS is a first-class host rather than an unsupported one. The graphical
client is one codebase and connects to either, without the person using it having to
know which is on the other end.

Today the containment is Linux kernel machinery — a firewall ruleset loaded into the
container's network namespace, runtime hooks, user namespaces, the kernel keyring —
so a second implementation is a re-derivation of the guarantees, not a port of the
code. What must be preserved is the guarantees themselves: default-deny egress that
fails closed, no credential inside the container, and work leaving only through
review.

## Acceptance

- The same project file runs a task on either host, or says plainly which parts do
  not apply.
- Default-deny egress is enforced by the platform and fails closed: if the policy
  cannot be applied, no task starts.
- The real credential stays outside the container, and the provider's own endpoint
  is unreachable from inside it.
- The client discovers and drives both kinds of host through one contract, with no
  branch on host type in its own screens.
- Each side is verified by its own acceptance suite, running the same scenarios.
- Where a guarantee cannot be met on one platform, the client says so rather than
  presenting an equal-looking task that is less contained.

## Notes

The contract in [0001](0001-Local-Daemon-API.md) becomes the boundary between the
two, so it has to be written without assuming one platform's primitives. The same
applies to [0017](0017-Remote-Access.md): a person on one host driving tasks on the
other is the normal case, not an edge case.

## To be checked

- **Which isolation primitives actually exist there**, and whether per-container
  egress filtering that fails closed is among them. If it is not, this project can
  offer convenience but not the guarantee, and that has to be said out loud rather
  than discovered by a user.
- Whether a host socket can be mounted into a container, which is how the credential
  proxy and the signing agent work today. Without it, both need a different shape.
- **Whether the host's loopback can be mapped into a container.** On Linux the git gate binds
  `127.0.0.1` and is reachable from a task only because pasta is told to send the container's
  address for the host there. Without an equivalent, the endpoint a task pushes to is either
  unreachable or on the operator's network, and that has to be said out loud rather than left
  to look like the same guarantee.
- Whether the two backends can share one contract without it degrading to the
  weaker platform's capabilities everywhere.
- How much is genuinely shared. If it turns out to be only the contract and the
  client, that is still worth doing — but it should be a decision, not a surprise
  discovered halfway.
