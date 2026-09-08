# FAQ

## Can an agent reach Maven Central, npm or PyPI while it works?

**Yes, if the project says so.** Declaring it is the whole mechanism:

```yaml
egress:
  sets: [maven, git-hosting]
  domains: ["nexus.corp.example"]     # a private mirror, if you have one
```

`sokar shield sets` lists the names. `sets` is the ergonomic path — a set is written once and
reviewed once, so "reaching npm" means the same thing in every project instead of each one
re-deriving it. `domains` is the escape hatch for a host no shipped set covers.

**What a task may reach comes from four places, and it is printed when the task starts:**

| Source | Example |
|---|---|
| the agent's own `allowed_domains` | `platform.claude.com` |
| the provider it is pointed at | `api.anthropic.com` |
| the project's `upstream`, for an `online` project | your git host |
| **the project's own `egress`** | `repo.maven.apache.org` |

**Declaring nothing reaches nothing.** An absent `egress` section is not a generous default
that quietly widens when a release adds a set — it is deny. A project file created by
`sokar task run` gets a starter block written into it for that reason: the common case works
immediately, and the grant is still visible in a file you review.

**Ports 80 and 443 only.** A declared name opens web ports at the addresses it resolves to,
not the host. In particular it does not open ssh, so declaring `git-hosting` does not hand an
agent a `git push` that bypasses the gate.

**An `offline` project refuses to declare any**, and says so rather than ignoring the section.

### What the sets contain, and what they cannot

The host lists were adapted from the project acknowledged in the
[README](../README.md), which solves the same problem — except `maven`, which it has
no set for, so that one was measured here: 193 artifacts
resolved into an empty local repository through a logging proxy, which saw
`repo.maven.apache.org` and, for snapshots, `central.sonatype.com`. Central's CDN answers under
its own name, so there is no redirect to a third host.

**`os-packages-fedora` is incomplete and cannot be completed.** `dnf` asks
`mirrors.fedoraproject.org` for a mirrorlist and then fetches from whichever mirrors it names —
arbitrary hosts that differ by region and by day. Each one raises a clearance prompt. Pin a
baseurl in your image snippet if that matters; Debian and Ubuntu use stable CDN names and are
covered.

### Declaring a forge is worth understanding

`git-hosting` makes github.com resolvable. In a `guarded` project the gate exists so an
operator reviews what leaves, and it holds because the container has no credential for the
upstream — not because the upstream is unreachable. Declaring a forge removes the second of
those. Sokar says so at task start rather than refusing, because an agent legitimately clones
dependencies from a forge.

The gate is a review workflow, not a network control. An agent that finds a usable token in
the work tree can push regardless of security class.

### Seeding the image is still worth doing

Declaring egress and pre-fetching dependencies solve different halves. Seeding makes a build
start fast and work when a registry is down; the declaration is what lets an agent add a
dependency at all. The image build is not governed by the firewall:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends maven \
        && rm -rf /var/lib/apt/lists/*
    COPY --chown=agent:agent pom.xml /workspace/pom.xml
    USER agent
    RUN mvn -B -f /workspace/pom.xml dependency:go-offline
```

**An `offline` project has no other option**, and there `mvn -o` is the right setting: offline
mode says `The repository system is offline but the artifact ... is not available in the local
repository`, naming the artifact, where a resolver failure gives you a DNS error some way down
a stack trace. The equivalents: `npm ci --offline`, `pip install --no-index`,
`go build -mod=vendor`, `cargo build --offline`.

## Why does an undeclared host fail silently instead of prompting me?

Because it fails one layer earlier than most people expect.

The task's resolver is configured `address=/#/`, which is dnsmasq for *everything is
NXDOMAIN*, with one `server=` line per declared domain. So an undeclared name never
resolves, no connection is attempted, and the firewall is never consulted — there is
nothing to prompt about. What you see inside the container is a DNS failure:

```
Could not resolve host: repo.maven.apache.org
```

The clearance prompt is for the other case: a name that *did* resolve, to an address that
is not in the allow set. That happens for a bare IP address, or for a declared domain
whose answer arrived before the firewall was told about it.

This is deliberate. A resolver that answered every name and let the firewall drop the
traffic would leak the fact that a host exists, and would turn every stray lookup into a
question for you.

## Why is `dnsmasq nftset` in `sokar doctor` worth caring about?

Because without it a declared domain resolves and is then dropped: names work, nothing
connects, and there is no error that says why. dnsmasq adds each address it answers to the
firewall's allow set as it answers — that is what keeps *what the container was told* and
*what it may reach* from drifting apart. A dnsmasq built without `--nftset` accepts the
configuration and silently never opens anything.

If `sokar doctor` says anything but `yes`, install dnsmasq 2.87 or later with nftset
support before your first task.

## Can I use a proxy or a mirror instead?

**A mirror on the network, yes** - name it in the project's `egress.domains` and it is reachable
like any other host:

```yaml
egress:
  domains: ["nexus.corp.example"]
```

Point the build at it as usual, with a `settings.xml` for Maven or an `.npmrc` for npm. Drop the
`maven` set at the same time if everything is meant to go through the mirror: what is not
declared is not reachable, which is what makes the mirror the only route rather than the
preferred one.

**A mirror on your own machine, no.** The firewall opens one thing toward the node - the git
gate's address and port - so a repository proxy listening on `localhost` is as unreachable from
inside a task as an undeclared registry. Give it a name the container can resolve, or run it
somewhere the container can reach.

**At image build time either works**, since the build is not governed by the firewall. An
air-gapped machine needs exactly that.
