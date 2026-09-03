# Installing additional tooling in your box

First, an ambiguity worth clearing up, because two different things get called
"the agent":

- **`sokar-agent-claude`** is the `.deb`/`.rpm`, and it installs **on the host**,
  into `/usr/libexec/sokar/agents/`. It is Sokar's *adapter*: it knows how to talk
  to Claude Code — which flags it takes, where it writes its credentials, how to
  read its output. It is about 6 MB and contains exactly one file.
- **`claude`** is the CLI itself, and it goes **inside the task image**, because
  that is where it has to run. It is about 320 MB.

The package does not contain the CLI. It carries the *instructions* for installing
one: a pinned URL and a SHA-256. Sokar asks the installed adapter for those over
varlink and folds them into the generated `Containerfile`. That keeps the package
small and lets podman's layer cache download the CLI once per pinned version
rather than once per build.

So a task image is built in three layers, in this order:

1. **base** — the distro image the project names, plus an unprivileged `agent`
   user, a `/workspace`, and `curl` + CA certificates;
2. **agent** — the pinned, digest-verified download the installed agent adapter
   asked for;
3. **project** — your own lines.

Note that layer 2 needs network access **at image build time**, to the vendor's
download host. The egress firewall governs the running task container, not the
build, so an air-gapped machine needs a mirror for that URL.

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

`sokar task run --dry-run` shows what would be built without building it, and the
generated `Containerfile` is left in `$XDG_DATA_HOME/sokar/build/<project>/` — it
is meant to be read.

Nothing you add here escapes the rest of the model: the container still starts
with no capabilities and `NoNewPrivs`, and the egress firewall still applies. If
your tooling needs to reach a host the project does not allow, the connection is
blocked and you are prompted — installing something does not widen the network.

