# Sokar

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

Sokar runs each agent task inside a hardened, rootless container with default-deny outbound networking, a credential
vault that keeps real keys on the host, a per-task git checkpoint, and a desktop notification path for live allow/deny
decisions.

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

