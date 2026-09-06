# 0049 — What A Project May Reach

**Status:** open. **Blocking:** Sokar cannot be used for ordinary development without it.

An agent editing a Java project runs `mvn test` while it works, and Maven fetches a plugin or
a dependency the moment the build needs one. `npm install`, `pip install`, `go get` and
`cargo build` do the same. A task can reach none of them.

What a task may resolve comes from three places, and a project controls none of them: the
agent's own `allowed_domains`, the provider it is pointed at, and — for an `online` project —
its git upstream. `project.yml` has no key for it and `sokar task run` has no flag. The
resolver answers `NXDOMAIN` for everything else, so the failure is a name that does not
exist rather than a connection that was refused.

**The mechanism is already there; only the input is missing.** `DnsPolicy` writes one
`server=` line per domain and an `nftset=` line that adds each answered address to the
firewall's allow set. `TaskRunCommand` assembles that list. Nothing needs inventing — a
fourth source has to reach the same list, and be governed on the way.

**Seeding the image is not a workaround, it is a different thing.** `dependency:go-offline`
at build time covers what the project needed when the image was built. The dependency the
agent *adds* is exactly the one that is missing, so the case this requirement exists for is
the case seeding cannot reach.

## Acceptance

- A project declares the hosts its tooling needs, in `project.yml`, and a task can reach
  them.
- **`offline` refuses to declare any**, and says so rather than ignoring the declaration.
- What a task may reach is printed when it starts, with where each entry came from — the
  agent, the provider, the upstream, or the project.
- A destination deliberately refused stays distinguishable from one nobody added.
- Adding a host is a change to a file that is reviewed like any other, not a prompt answered
  once and forgotten.

## Two shapes, and probably both

**Named sets** are what the security classes already promise: `SecurityClass` says *"curated
egress sets only"*, which describes sets Sokar ships and a project opts into.

```yaml
egress:
  sets: [maven, npm]
```

A set is written once, reviewed once, and cannot be typed wrong in a way that silently opens
something else. `providers/` is the precedent: data, scanned from a directory, extensible
without a rebuild.

**An explicit list** is the escape hatch a private registry needs, and no shipped set can
cover:

```yaml
egress:
  domains: ["nexus.corp.example"]
```

The risk is that it becomes the path everyone uses, and each project re-derives what
"reaching Maven" means — badly. Sets should be the ergonomic default and the list the
exception.

## This is the most dangerous knob in the product

A package registry is a code-execution channel. `mvn` downloads plugins and runs them; `npm
install` runs install scripts. Allowing Maven Central does not merely let an agent read
artifacts, it lets an agent cause arbitrary third-party code to run inside the task.

That does not argue against the feature — it is what development *is*, and refusing it makes
Sokar unusable for the thing it exists for. It argues for the shape:

- **Declared in a file, in the repository**, so it is reviewed and it is diffable. Not a
  prompt, and not a flag that lives in one person's shell history.
- **Printed at task start**, beside the credential and firewall lines that are already there.
- **Never widened by an agent.** An agent declares what *it* needs; a project declares what
  *the project's tooling* needs. Neither may declare the other's.

## To be checked

- **What a registry actually contacts.** Maven Central redirects to a CDN; npm serves
  metadata from one host and tarballs from another. A set has to be measured against a real
  build, not written from documentation — the same way the agents' `allowed_domains` were.
- **Whether a set may carry more than domains.** A CDN that answers a different address per
  request is fine, because dnsmasq adds each answer as it answers. One that is reached by
  address, without a name, is not, and would need something else.
- **Whether an undeclared name should resolve and prompt** rather than NXDOMAIN. It would
  make the failure legible and reuse the clearance path, at the cost of telling the container
  that a host exists and of turning every stray lookup into a question.
- **Whether `guarded` and `online` should differ here.** They differ today only in whether
  the gate pushes upstream. If they do not differ in what may be reached, the classes say
  less than their names suggest.
- Whether a set can be versioned or pinned, so "the maven set" means the same thing on two
  machines.

## Notes

[0013](0013-Egress-Sets-Editor.md) is the interface over this: its first acceptance criterion
lists destinations "with their origin: **project**, agent, or a decision", which presumes a
project can declare them. It was written as though this existed. It does not, and 0013 cannot
be built before it.
