# FAQ

## How do I let a task reach Maven Central, npm, PyPI or any other host?

**Install what it needs at image build time.** The egress firewall governs the running task
container, not the image build, so the third layer of the image can fetch whatever it likes:

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

The dependencies are then in the image, and the task builds with `mvn -o`. See
[your tooling](your-tooling.md) for what the layers are and how `snippet_file` works.

**There is no way to add a host to a *running* task.** A project cannot declare extra
domains today — `project.yml` has no key for it. What a task can resolve comes from three
places and no others:

| Source | Example |
|---|---|
| the agent's `allowed_domains` | `platform.claude.com` |
| the provider it is pointed at | `api.anthropic.com` |
| the project's `upstream`, for an `online` project | your git host |

A per-project egress list is [requirement 0013](requirements/0013-Egress-Sets-Editor.md), and
it is not built.

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

Yes, and for an air-gapped machine it is the only option. Point the build at your mirror in
the image snippet — a `settings.xml` for Maven, an `.npmrc` for npm — and the mirror's host
is then contacted at *build* time, where there is no firewall. Nothing about that needs
Sokar's permission.

At run time the same rule applies as above: the mirror is a host like any other, and a task
cannot reach one that is not declared.
