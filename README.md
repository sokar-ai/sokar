# Sokar

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

<img align="left" height="400" width="260" src="doc/sokar-400.png" alt="Sokar with AI agent in podman">

Normally, an AI agent will stop and ask you: "Can I edit this file?" or "Can I run this terminal command?". 
In YOLO mode, it skips those questions and directly modifies code, deletes files, installs packages, 
or runs terminal scripts completely unattended.

Sokar makes that safe anyway: It runs each agent inside a hardened, rootless container with default-deny
outbound networking, a credential vault that keeps real keys on the host, a git checkpoint for every run, and
a desktop notification path for live allow/deny decisions.

Use the agent as you want: Work locally: interactive in a shell, or headless and unattended.

Supervise agents from a Flutter client — wherever they run :construction:

<br clear="left"/>

### Hardening

- **Fail-closed egress** — a default-deny nftables ruleset is loaded into the container's network namespace before
  the workload runs, and if it cannot be loaded the container does not start
- **Nothing to escalate to** — every capability dropped and `no-new-privileges` set at create time, inside a rootless
  container whose agent is an unprivileged account
- **The key stays on the host** — the container holds a task-scoped phantom token, a proxy on a unix socket swaps it
  for the real credential, and the provider's own host is firewalled off so nothing can go around it; git signing
  works the same way, over an agent socket
- **Work leaves only through review** — an `offline` or `guarded` task pushes to a host-side mirror under
  `refs/sokar/incoming/`, and nothing reaches an upstream until you approve it
- **Recorded before anyone is asked** — drops land in a JSON-per-line audit file whether or not a prompt is running,
  and the desktop Allow/Deny appears once per destination and is never re-asked

### Features

- **A file beside your code** — `project.yml` names the base image, the security class and anything else you want
  baked in; each task is a container built from it and thrown away afterwards
- **The security class belongs to the project** — `offline` forwards nothing upstream ever, `guarded` and `online`
  forward only what you approve, and no task can talk its way up
- **Interactive or unattended** — a shell by default, or `-P "…"` to run the agent headlessly and format what it says
- **Three image layers, the middle one pinned** — your base, then the agent's CLI fetched from a fixed URL and checked
  against a SHA-256, then your own lines
- **An agent is a package, not a patch** — its own binary and its own `.deb`/`.rpm`, discovered by a directory scan,
  with an ArchUnit test failing the build if anything in Sokar ever names one; Codex arrives the same way :construction:

> Sokar is inspired by [Terok AI](https://github.com/terok-ai/terok) — not a fork and not a port, but it owes that
project a great deal: the architecture, and a lot of hard-won knowledge about how podman, nftables and D-Bus actually
behave. Big kudos to its developers. If you are more at home in Python, use it. It's a cool project!
Why build this if Terok is so cool? See [why](why.md)

## Getting started
See [getting started](getting-started.md).

## Adding your tools to a container
See [your tooling](your-tooling.md).

## Adding a new agent
See [Onboarding a new agent](agents/README.md#onboarding-a-new-agent)

## Building the project
See [build](build.md).

-----

> [!NOTE]  
> <img src="doc/ai-powered.svg" alt="A little robot peeking out of its sandbox" align="left" height="62">
> This project is fundamentally powered by AI.
> <br clear="left"/>
