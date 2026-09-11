# Installing additional tooling in your box

First, an ambiguity worth clearing up, because two different things get called
"the agent":

- **`sokar-agent-claude`** is the `.deb`/`.rpm`, and it installs **on the host**,
  into `/usr/libexec/sokar/agents/`. It is Sokar's *adapter*: it knows how to talk
  to Claude Code — which flags it takes, where it writes its credentials, how to
  read its output. It is about 6 MB and contains exactly one file.
- **`claude`** is the CLI itself, and it goes **inside the task image**, because
  that is where it has to run. It is about 320 MB.

**How the CLI gets there is the agent's choice, and there are two.** Claude Code's
package does not contain the CLI: it carries the *instructions* for installing one, a
pinned URL and a SHA-256, which Sokar folds into the generated `Containerfile`. That
keeps the package small and lets podman's layer cache download the CLI once per pinned
version rather than once per build.

Pi's package carries the tool itself - 162 npm packages and a Node runtime, which have
no single URL to pin. Verification happens once where the package is built, against a
lockfile pinning every dependency by integrity hash, and the image build then downloads
nothing at all. That is a stronger guarantee than a pinned URL rather than a weaker one:
the same package cannot install different bytes on different days. It costs size - about
70 MB against 6 MB.

So a task image is built in three layers, in this order:

1. **base** — the distro image the project names, plus an unprivileged `agent`
   user, a `/workspace`, and `curl` + CA certificates;
2. **agent** — the pinned, digest-verified download the installed agent adapter asked
   for, or a copy of what its package already carries;
3. **project** — your own lines.

Note that layer 2 needs network access **at image build time** for an agent that
downloads its CLI, to the vendor's download host. The egress firewall governs the running
task container, not the build, so an air-gapped machine needs a mirror for that URL - or
an agent whose package carries the tool, which needs no network for that layer at all.

Your tooling goes in the third layer. Either inline in `project.yml`:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends ripgrep jq \
        && rm -rf /var/lib/apt/lists/*
```

or in a file beside it, for anything longer than a few lines:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet_file: "tooling.dockerinclude"
```

The two are mutually exclusive and Sokar says so rather than silently preferring
one.

**Your lines run as root, before the image drops to the `agent` user**, because
installing packages is what they are almost always for. They also run *after* the
agent layer, so they can rely on the agent CLI already being present.

`sokar task start --dry-run` shows what would be built without building it, and the
generated `Containerfile` is left in `$XDG_DATA_HOME/sokar/build/<project>/` — it
is meant to be read.

Nothing you add here escapes the rest of the model: the container still starts
with no capabilities and `NoNewPrivs`, and the egress firewall still applies —
installing something does not widen the network.

**But note where the two layers differ.** These lines run at *build* time, which the
firewall does not govern, so they can fetch from anywhere. At *run* time the task's
resolver answers NXDOMAIN for any name that is not declared, so a tool that reaches out
while the agent is working fails to resolve rather than being blocked and prompted about.

Two ways out, and they are not alternatives. Fetch what the tool needs here, in the build, and
the running task needs no network for it. If it genuinely has to reach out while the agent
works - a package registry is the usual case - name that in the project's `egress` section,
where the grant is reviewed like any other change. See [the FAQ](faq.md).

