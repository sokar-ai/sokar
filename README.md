# Sokar

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

<img align="left" height="300" width="217" src="doc/sokar-300.png" alt="Sokar with AI agent in podman">
Sokar runs each agent task inside a hardened, rootless container with default-deny outbound networking, a credential
vault that keeps real keys on the host, a per-task git checkpoint, and a desktop notification path for live allow/deny
decisions.

A project is a `project.yml` beside your code — a name, a security class, a base image, and any extra lines you want
in the task image. A task is one container built from that, and it is removed again when the shell exits unless you
ask for it to stay. Everything on that path is a single binary: `sokard` exists for a desktop client, and
`sokar task run` never waits for it.

Anything marked **TODO** below is not built yet. Everything else is exercised by
`buildtools/e2e-tier1.sh`, which runs 23 checks against a real container and needs no provider account.

### Hardening

- **Rootless podman** — runs as your own user, and as an unprivileged account inside the container
- **No capabilities, no privilege gain** — `--cap-drop ALL` and `no-new-privileges`, verified in a live container
- **Default-deny egress** — nftables loaded into the container's netns by a hook that fails closed
- **DNS you can read** — the resolver answers only declared domains, so a block names a host, not an address
- **Live Allow / Deny prompts** — drops go to NFLOG, then to a desktop notification, once per destination
- **Per-task git gate** — the agent pushes to a mirror on the host; you review before anything leaves
- **The real credential never enters the container** — a phantom token, swapped for the key by a proxy on a unix
  socket, with the provider's own host firewalled off so there is no way around it
- **Signed commits without a key in the container** — signing happens in the vault, over an agent socket **TODO**

### Features

- **Projects ⊃ Tasks** — a `project.yml` beside your code, one throwaway container per task
- **Three security classes** — `offline`, `guarded`, `online`; set by the project, not raisable by a task
- **Shell or headless** — a shell by default, or `-P "…"` to run the agent and format its output
- **Layered images** — base distro · the agent's pinned CLI, checked against a SHA-256 · your own lines
- **Agents are separate packages** — own binary, own `.deb`/`.rpm`, found by a directory scan; nothing in Sokar
  names one, and an ArchUnit test fails the build if that changes
- **Codex alongside Claude Code** — same install path, no change to Sokar itself **TODO**

<br clear="left"/>

> Sokar is inspired by [Terok AI](https://github.com/terok-ai/terok) — not a fork and not a port, but it owes that
project a great deal: the architecture, and a lot of hard-won knowledge about how podman, nftables and D-Bus actually
behave. Big kudos to its developers. If you are more at home in Python, use it. It's a cool project!
Why build this if Terok is so cool? See [why](why.md)

## Getting started
-TBD-

## Adding your tools to a container
See [my tooling](my-tooling.md).

## Adding a new agent
See [Onboarding a new agent](agents/README.md#onboarding-a-new-agent)

## Building the project
See [build](build.md).

