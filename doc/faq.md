# FAQ

## Can an agent reach Maven Central, npm or PyPI while it works?

**Yes, if the project declares it:**

```yaml
egress:
  sets: [maven, git-hosting]
  domains: ["nexus.corp.example"]     # a private mirror, if you have one
```

`sokar shield sets` lists the sets. Use `domains` for a host no set covers.

A task may reach only what comes from these four places, printed when the task starts:

| Source | Example |
|---|---|
| the agent's own `allowed_domains` | `platform.claude.com` |
| the provider it uses | `api.anthropic.com` |
| the project's `upstream`, for an `online` project | your git host |
| the project's own `egress` | `repo.maven.apache.org` |

- **No `egress` section means nothing extra.** There is no default.
- **Ports 80 and 443 only.** Declaring `git-hosting` does not open ssh, so no `git push` around the
  gate.
- **An `offline` project cannot declare egress**, and says so.
- **`os-packages-fedora` is incomplete**: `dnf` fetches from mirrors that change by region and day,
  and each one raises a clearance prompt. Pin a baseurl in your image snippet. Debian and Ubuntu are
  covered.

**Declaring a forge weakens the gate.** In a `guarded` project the gate holds because the container
has no credential for the upstream, not because the upstream is unreachable. An agent that finds a
usable token in the work tree can push anyway. Sokar warns about this at task start.

**Seed the image too.** The image build is not behind the firewall, so you can pre-fetch
dependencies there. It makes builds fast and is the only option for an `offline` project:

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

In an `offline` project, build offline so a missing artifact is named clearly: `mvn -o`,
`npm ci --offline`, `pip install --no-index`, `go build -mod=vendor`, `cargo build --offline`.

## Why does an undeclared host fail silently instead of prompting me?

Because the name never resolves. The task's resolver answers only declared domains, so no
connection is tried and there is nothing to prompt about. You see a DNS error:

```
Could not resolve host: repo.maven.apache.org
```

The clearance prompt is for a name that did resolve to an address not yet allowed, such as a bare
IP address. See [DNS](security.md).

## Why does the `dnsmasq nftset` line in `sokar doctor` matter?

Without nftset support, a declared domain resolves but nothing connects, and no error says why. If
`sokar doctor` shows anything but `yes`, install dnsmasq 2.87 or later with nftset support before
your first task.

## Can I use a proxy or a mirror instead?

- **A mirror on the network: yes.** Put it in `egress.domains` and point the build at it
  (`settings.xml`, `.npmrc`). Drop the `maven` set if all traffic must go through the mirror.
- **A mirror on your own machine: no.** A task can reach only the gate on the host, not
  `localhost`. Give the mirror a name the container can resolve, or run it elsewhere.
- **At image build time: either works**, since the build is not behind the firewall. Use this for
  an air-gapped machine.

## How is Sokar protected against tool poisoning (an MCP server's rug pull)?

**Tool poisoning** hides instructions in a tool's description or answers. **A rug pull** changes a
tool after it was approved. Sokar does not read tool definitions, but it limits what a poisoned tool
can make an agent do:

1. **No real key in the container.** The task's token is worthless elsewhere; the broker on the
   host adds the real key. Requests that would mint a new token are refused, and answers carrying a
   credential are withheld. The refusal reads a request body as text, so a compressed body is not
   looked into.
2. **Deny by default.** A task reaches only its agent, its provider, an `online` project's upstream
   and the project's `egress`. In `default`, blocked connections are refused without asking.
3. **Work leaves a `guarded` project only through the gate**, after a person reviews it. An
   `offline` project's work stays on the machine. An `online` project pushes by itself.
4. **Messages between agents pass a filter.** A person can still deliver a refused one, and the
   record says so.
5. **The agent cannot change what a project may reach.** A followed project's configuration applies
   only when signed with its pinned key. A project followed `--unverified` is marked as such.

**Not covered:** Sokar does not read MCP traffic, so a changed tool set goes unnoticed. A poisoned
tool can still send data to its own MCP server, which is an allowed destination. An MCP server
started inside the container is not checked.

See [why you should use Sokar](corporate-security.md) for the other questions a security officer
asks.
