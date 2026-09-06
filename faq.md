# FAQ

## Can an agent reach Maven Central, npm or PyPI while it works?

**Not today, and that is a real limitation rather than a setting you have missed.**

This is the case that matters most: an agent editing a Java project runs `mvn test` as it
goes, and Maven fetches a plugin or a dependency the moment the build needs one. The same
is true of `npm install`, `pip install`, `go get` and `cargo build`. A sandbox that cannot
reach a package registry cannot let an agent iterate on dependencies.

What a task may resolve comes from three places, and none of them is yours to set:

| Source | Example |
|---|---|
| the agent's own `allowed_domains` | `platform.claude.com` |
| the provider it is pointed at | `api.anthropic.com` |
| the project's `upstream`, for an `online` project | your git host |

`project.yml` has no key for it, `sokar task run` has no flag for it, and the firewall opens
nothing toward the host except the git gate's own port — so a proxy running on your machine
is not reachable either. [Requirement 0013](requirements/0013-Egress-Sets-Editor.md) assumes
a project *can* declare destinations, which is the interface for a mechanism that does not
exist yet.

### What works in the meantime

**Seed the dependency cache when the image is built.** The image build is not governed by
the firewall, so this fetches normally:

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

That covers the dependencies and plugins the project has **at the moment the image is
built**. It does not cover one the agent adds, a version it bumps, or a plugin a goal
pulls in that `go-offline` did not resolve — each of those fails inside the task.

**Then make the build offline on purpose**, so the failure is immediate and legible:

```
mvn -o test
```

Offline mode says `The repository system is offline but the artifact ... is not available
in the local repository`, naming the artifact. Without `-o` Maven tries to resolve, the
name does not exist, and you get a DNS error some way down a stack trace instead.

The equivalents: `npm ci --offline`, `pip install --no-index`, `go build -mod=vendor`,
`cargo build --offline`.

**Rebuild the image when dependencies change.** `sokar task run` builds the image each
time, so a `pom.xml` the agent already changed is picked up on the next run — which turns
"add a dependency" into a round trip through the host rather than something the agent does
alone. That is the cost, stated plainly.

**An `offline` project is not what this is about.** The security class decides whether the
gate may push upstream and whether any egress set is consulted at all; a `guarded` project
is already the permissive end for package registries, and it still resolves nothing that is
not declared.

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

**At image build time, yes** — point the build at your mirror in the snippet, with a
`settings.xml` for Maven or an `.npmrc` for npm. The mirror is contacted during the build,
where there is no firewall, and an air-gapped machine needs exactly this.

**At run time, no**, including a mirror on your own machine. The firewall opens one thing
toward the host — the git gate's address and port — and nothing else, so a repository proxy
listening on `localhost` is as unreachable from inside a task as Maven Central is.
