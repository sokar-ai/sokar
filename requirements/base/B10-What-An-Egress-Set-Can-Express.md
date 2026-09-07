# B10 — What An Egress Set Can Express

**Status:** open. Left over from the egress editor, which is finished: these are questions about
what a set *is*, not about editing one, and each of them changes what an editor would have to
edit.

A set is a name for a list of host names, resolved by dnsmasq and turned into firewall elements as
each answer arrives. That shape is what makes a set reviewable - a file of names anybody can read -
and it is also the limit of what a set can say. Four things do not fit inside it, and each is a
different decision.

## Acceptance

- A destination that cannot be expressed as a host name is either supported or refused with a
  reason, rather than silently unreachable.
- Two machines naming the same set reach the same hosts, or can be told that they do not.
- Where a set cannot be complete, the interface says so rather than presenting it as equivalent to
  the others.

## To be checked

- **Whether a set may carry more than domains.** A CDN that answers a different address per
  request is fine, because dnsmasq adds each answer as it answers. One that is reached by address,
  without a name, is not, and would need something else - which changes what a set is and what an
  editor edits.
- **Whether an undeclared name should resolve and prompt** rather than NXDOMAIN. It would make the
  failure legible and reuse the clearance path, at the cost of telling the container that a host
  exists and of turning every stray lookup into a question. It decides whether the egress editor is
  mostly used before a task runs or during one.
- **Whether a set can be versioned or pinned**, so "the maven set" means the same thing on two
  machines. An operator's own file of the same name already wins over the packaged one, which is
  the feature and the hazard: a task that works here and fails there is explained by a file nobody
  looked at.
- **`os-packages-fedora` cannot be complete**, and an interface has to say so rather than
  presenting it as equivalent to the others: `dnf` fetches from mirrors named by a mirrorlist,
  which differ by region and by day, so each one arrives as a clearance prompt. Pinning a baseurl
  in the image snippet is the way out, and that is not something a set can express.

## Notes

The set contents were adapted from the project acknowledged in the
[README](../../README.md), except `maven`, which was measured here: 193 artifacts resolved into an
empty local repository through a logging proxy, which saw `repo.maven.apache.org` and, for
snapshots, `central.sonatype.com`.
