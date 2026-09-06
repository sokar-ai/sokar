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
- **Declaring a forge in a `guarded` project warns**, and the warning says what it costs: the
  gate is a review workflow, not a network control, and it now rests on the container holding
  no credential for that host.
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

## How Terok does it, read rather than guessed

Terok has this, and the shape matches what is proposed above closely enough that the
differences are the interesting part. `examples/projects/uc/project.yml`:

```yaml
shield:
  sets: [git-hosting, python, os-packages]
```

From `src/terok/lib/core/egress_sets.py`:

| Set | Hosts |
|---|---|
| `git-hosting` | github.com, codeload.github.com, objects.githubusercontent.com, raw.githubusercontent.com, gist.github.com, gitlab.com, bitbucket.org, codeberg.org |
| `python` | pypi.org, files.pythonhosted.org |
| `node` | registry.npmjs.org, registry.yarnpkg.com, nodejs.org |
| `rust` | crates.io, static.crates.io, index.crates.io, static.rust-lang.org |
| `go` | proxy.golang.org, sum.golang.org, index.golang.org |
| `containers` | registry-1.docker.io, auth.docker.io, index.docker.io, production.cloudflare.docker.com, quay.io, cdn0{1,2,3}.quay.io, ghcr.io, pkg-containers.githubusercontent.com, registry.fedoraproject.org |
| `os-packages` | **dynamic** - the distro repos for the image's package family |

Four things worth taking, and one worth arguing about:

- **A custom list exists beside the sets.** Terok grants "the project's git remote host and
  its custom `shield.allow`" alongside the selected sets. Both shapes, as proposed.
- **`os-packages` is resolved rather than listed**, from the image's detected package family -
  `apt` and `dnf` need different hosts, and which ones is a property of the image, not of the
  project. A static set could not express it.
- **Allows are tiered, and a deny wins.** The sets feed an "ordinary allow" tier; a security
  deny overrides it. That is how a curated set and the credential proxy's deliberate refusal
  of the provider host can coexist without one quietly undoing the other.
- **The sets are discoverable** - `terok shield sets` lists them, and a chooser writes the
  key rather than the operator authoring host lists.
- **There is no Java or Maven set.** The gap that started this is a gap there too, so those
  hosts have to be measured rather than copied.

**The one to argue about: Terok's default is generous.** Unset means *every* curated set,
"so that under a shield-up posture the common workflows keep working out of the box"; `[]`
disables all curated content. Sokar's instinct everywhere else is default-deny - the firewall,
the resolver, the vault, publishing. Adopting a permissive default here would be the first
place that reverses, and it should be a decision made out loud rather than inherited.

The set contents are facts about public registries and can be reused; Terok is Apache-2.0,
which is compatible with this project's GPL-3.0-or-later, and it is already credited in the
README.

## What the declaration does not do

The allow set is **port-aware since the ruleset gained `allowed_ports`** - a declared name
opens 80 and 443 to the addresses it resolves to, not the host. That was measured on real
nftables: with the previous `ip daddr @allowed_v4 accept` an agent reached port 22 on any
declared host, which is a `git push` that never passes the gate.

What remains, and cannot be fixed here: **the gate is a review workflow, not a network
control.** In a `guarded` project it holds because the container has no credential for the
upstream, not because the upstream is unreachable - and a declaration that names a forge takes
away the unreachability. Hence the warning above. An agent that finds a usable token in the
work tree can still push, in any security class, and no egress policy changes that.

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

## Decided

- **A guarded project that declares a forge gets a warning**, not a refusal. Declaring
  `git-hosting` makes github.com resolvable in a project whose gate exists to review what
  leaves, so the operator should be told that the gate now rests on credential absence alone.
  Refusing would be wrong - an agent legitimately clones dependencies from a forge - so this
  is said once, at task start, beside the other origin lines.

- **Both shapes ship together.** `sets:` for curated names, `domains:` for the private mirror
  no shipped set can cover. They are one mechanism underneath, so the second costs parsing and
  printing rather than firewall work, and a corporate Nexus is the case most likely to be met
  first.
- **Absence means nothing is reachable**, as it does for the firewall, the resolver and the
  vault. The generous default is rejected: a grant that is invisible in the file, and that
  widens on upgrade, is the wrong thing to have in the most dangerous key in the product.
- **The wizard writes a starter selection**, which is what keeps that strict default usable.
  A project created by `sokar task run` gets a commented `egress:` block rather than a missing
  one, so the common case works immediately *and* the grant is visible in a reviewed file.
  A hand-written `project.yml` with no key still reaches nothing.
- **`guarded` and `online` grant identically**; `offline` refuses a declaration and says so.
  This is what `SecurityClass` already documents - both are "curated egress sets only", and
  the two differ in whether the git gate pushes upstream, not in what the network reaches.

## To be checked

- ~~**What a Maven build actually contacts.**~~ **Measured, and the set ships.** 193 artifacts
  resolved into an empty local repository through a logging proxy that recorded every host the
  resolver tunnelled to: `repo.maven.apache.org`, and `central.sonatype.com` for snapshots.
  Plugins, parent POMs and transitive dependencies all came from the first - Central's CDN
  answers under its own name, so the redirect this was expected to reveal does not exist.
  `repo1.maven.org` is in the set as the alias older poms hard-code. A project with repositories
  of its own reaches those too, and they belong in its `egress.domains`.
- **What `dnf` contacts, which no set can answer.** A Fedora image asks
  `mirrors.fedoraproject.org` for a mirrorlist and then fetches from whichever mirrors it names -
  arbitrary hosts that differ by region and by day. `os-packages-fedora` says so in the file and
  cannot do better; a project that needs reproducible installs should pin a baseurl in its image
  snippet. Debian and Ubuntu use stable CDN names and are covered.
- **Whether a set may carry more than domains.** A CDN that answers a different address per
  request is fine, because dnsmasq adds each answer as it answers. One that is reached by
  address, without a name, is not, and would need something else.
- **Whether an undeclared name should resolve and prompt** rather than NXDOMAIN. It would
  make the failure legible and reuse the clearance path, at the cost of telling the container
  that a host exists and of turning every stray lookup into a question.
- Whether a set can be versioned or pinned, so "the maven set" means the same thing on two
  machines.

## Notes

[0013](0013-Egress-Sets-Editor.md) is the interface over this: its first acceptance criterion
lists destinations "with their origin: **project**, agent, or a decision", which presumes a
project can declare them. It was written as though this existed. It does not, and 0013 cannot
be built before it.
