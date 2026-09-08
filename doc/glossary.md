# Glossary

The words this product uses, and — where it matters more — the words it deliberately does not.

Most of these were defined where they were first needed: in a class comment, in a requirement, in
the interface description. That worked while there was one reader. This page is the single place to
disagree with.

---

## Node

**A machine running `sokard`.** Its tasks, projects, mirrors and vault belong to it and to nothing
else.

**A node is not a cluster member.** There is no cluster, no membership, no joining, no discovery
and no shared control. Nodes do not know that other nodes exist and never talk to each other: the
daemon binds no network interface in any configuration, and there is no daemon-to-daemon protocol
to add one to. The only thing that spans nodes is a **client**, which holds an ssh connection to
each and carries bytes — it decides nothing, and a node applies its own policy and keeps its own
records whatever a client says.

So a question of the form *"how do nodes find each other"* has no answer here, on purpose. The
answer to *"which nodes are there"* is *"the ones you have configured a way to reach"*, and that
list lives in the client.

A node does not yet name itself. `GetInfo` reports a vendor and a version, not an identity, so a
client describes a node by how it reaches it.

> **Not to be confused with `nodejs`**, which is the egress set granting npm, yarn and the Node.js
> runtime downloads. The set was called `node` until this page was written; it was renamed because
> one word for two things is one too many.

## Project

**A directory with a `project.yml` in it.** The file declares a name, a description, a security
class, the base image a task is built from, an optional image snippet, an optional upstream, the
limits a task runs under, and the egress a task may reach.

A project is not a container and does not run. It is what tasks are started *for*, and what a
security class applies to.

A project's name is how everything else finds its things: its image is `sokar/<name>`, its gate
mirror is `<name>.git`, and its tasks are named after it.

## Task

**One container doing one piece of work for one project.** It has a name, unique within the
project, and that name is its identity in four places at once: the container, the gate ref its work
is pushed to, its workspace, and its log files.

A task outlives its run. Stopping one keeps its workspace, its logs and whatever never reached the
gate, and `Resume` brings it back. Only removing it destroys anything.

**A task's label is not its name.** A label is a changeable caption a person gives it, shown beside
the name and never instead of it — the name is what every method takes and what somebody types at
the machine.

## Mode

**How a person is meant to be involved in a task.** Three, chosen when it starts:

| | |
|---|---|
| `shell` | a terminal in the container, driven by hand |
| `agent` | the agent's own session, attached, with a person working through it |
| `unattended` | started with a prompt and left to run; nobody is expected to be watching |

The mode is recorded, not guessed. Deriving it from "was a prompt given" would be the same rule in
a second place.

## Agent

**The tool that does the work inside the container** — the thing that reads an instruction and
writes code. Sokar does not know which agents exist: it scans a directory for binaries that
describe themselves, and nothing outside `agents/` may name one.

An agent declares what it needs to reach, what it is deliberately refused, which provider it drives,
and what it fetches when its image is built.

## Provider

**Who serves the models.** An agent speaks a dialect; a provider serves one. The distinction earns
its place at the credential: **a credential belongs to the provider, not to the agent**, so two
agents pointed at one provider find one stored secret rather than two copies of it.

## Security class

**How much a project's tasks are trusted with the outside world.** The class decides, never a flag
on a task:

| | |
|---|---|
| `offline` | nothing resolves and nothing leaves; the mirror is the end of the line |
| `guarded` | only what the project declares resolves, and work leaves through the gate |
| `online` | the agent's remote *is* the upstream; nothing is reviewed |

A class is a promise about what cannot happen, so it is refused rather than widened: adding a
domain to an offline project does not open it.

See [The three security classes](security-classes.md) for more details.

## Egress set

**A named, pre-reviewed group of hosts** — `maven`, `nodejs`, `python`, `git-hosting` and the rest
— that a project names in its file so nobody has to author a host list.

A project's egress is the sets it names plus what its agent needs. What a set may express is
deliberately narrow: a name, never an open-ended selection, because "everything installed" would
widen a project the next time a release added a set.

## Gate

**The one crossing where work leaves a node.** An agent pushes a branch to a local bare repository
— the project's **mirror** — where it waits under `refs/sokar/incoming/` until a person reads it.
`approve` forwards it to the **upstream** and deletes the incoming ref; `reject` deletes it without
forwarding.

The agent never holds the upstream's credentials and never reaches the upstream. The gate is
host-local, so review needs no network and `offline` stays usable.

## Vault

**The credential store**, an encrypted file this node's owner unlocks. It answers with names, kinds
and lengths — **never with a value**.

A credential never enters a container. A task is given a **phantom token**: random, scoped to that
task, worth nothing to the provider. A **broker** on the host swaps the real credential in on the
way out, over a socket mounted into the container.

## Clearance

**What a task does when it reaches something it may not.** Four modes, the same four a conversation
would use: `prompt` asks a person and the answer takes effect on the waiting connection, `allow`
lets it through, `deny` refuses it, `off` does not ask at all.

`off` is the most consequential state a task can be in and is therefore reported, not inferred:
until a task said so, nothing an interface listed could mark it.

## Helper

**A host-side process serving one task** — the credential broker, the git gate, the clearance
watcher. Each is the `sokar` binary re-invoked, holding a unix socket that is mounted into the
container. That mount is the only hole in a task that Sokar controls, and everything a container can
reach beyond its own namespace arrives through one.

A helper is not part of the container and does not die with it, which is why stopping a task means
stopping its helpers too, and why a surviving helper is named rather than counted.

## Hook

**The three OCI hooks podman runs around a container** — `sokar-hook-nft`, `sokar-hook-reader`,
`sokar-hook-supervisor` — which install the packet filter, the resolver and the supervision. They
are registered per user, not system-wide, and a container started without them would have no
firewall at all, so a run that cannot find them is refused rather than started.

## Sidecar

**The file the hooks read**, written by the launcher into a task's state directory. It is a contract
between the launcher and three binaries that are installed once and outlive any release of the CLI,
so adding an operator-facing field to it would be a protocol change. Operator-facing facts about a
task live in its **profile** instead.

## Workspace

**Where a task's work lives, inside the container.** Deliberately not a bind mount from the host: a
mounted checkout would hand an agent the host's `.git/hooks`, which execute as whoever runs git.

---

## Words we do not use

- **Host** means a destination in an egress set — `EgressHost`, `upstreamHost` — and never a
  machine. The machine is a **node**.
- **Box** appears in older prose for a task's container. It is informal, and *container* or *task*
  is meant.
- **Cluster**, **peer**, **join**, **control plane** describe nothing in this product. See *Node*.
