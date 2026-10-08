# Sokar

Sokar runs an AI coding agent in a hardened container on your own machine: it reaches only what it was
allowed to, never holds your credentials, and hands its work back through a gate you review before
anything reaches your repository.

## Where to start

1. **[Getting started](getting-started.md)** - install it on Debian, Ubuntu or Fedora, sign an agent in,
   start a first task and approve its work.
2. **[How it works](how-it-works.md)** - tasks, projects, the gate, the vault and egress, in plain
   language, with a glossary at the end. New to coding agents altogether?
   [Three ways of working](way-of-working.md) first.
3. **[Commands](commands.md)** - every command, and what to type for a given job. With
   [the project file](project-file.md) and the [FAQ](faq.md) beside it.

## What it does

- **A container per piece of work**, kept until you remove it: the agent in your terminal by default, or
  unattended with a prompt.
- **A project file when you want one.** `project.yml` names the base image, the security class and what the
  build may reach; without one, a task works in the built-in project `default`.
- **Your build can still fetch what it needs.** `egress: {sets: [maven]}` opens Maven Central and nothing else
  resolves; `sokar shield sets` lists the rest.
- **The security class belongs to the project.** `offline` sends nothing upstream, `guarded` only what you
  approve, `online` passes the task's own branch on at once - through the gate on your machine, so no class
  puts a key in the container.
- **Three image layers, the middle one pinned**: your base, the agent's CLI checked against a SHA-256, your own
  lines.
- **An agent is a package**, found by a directory scan; adding one needs no change to Sokar.

## The rest

- **How it keeps an agent in its place:** [security classes, the firewall and names](security.md),
  [what a task can reach](reach.md), and [credentials](credentials.md).
- **For whoever answers for security in an organisation:**
  [what Sokar holds, and what it does not](corporate-security.md).
- **Running it:** [preparing a machine, the daemon, and your own tooling in a task](running.md).
- **Building it from source:** [build](build.md). **Why it is built this way:** [why](why.md).
- **The choices it rests on:** [decisions](decisions.md), each with why it holds and what would change it.
