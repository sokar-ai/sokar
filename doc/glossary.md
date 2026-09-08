# Glossary

The words this product uses, and — where it matters more — the words it deliberately does not.

Most of these were defined where they were first needed: in a class comment, in a requirement, in
the interface description. That worked while there was one reader. This page is the single place to
disagree with.

| term | in one sentence |
|---|---|
| [Node](#node) | A running `sokard` and the state it owns — one per OS user, not one per machine. |
| [Operator](#operator) | The person who owns a node — the only one who can unlock its vault or approve at its gate. |
| [Client](#client) | Software that talks to one or more nodes and decides nothing itself. |
| [Project](#project) | A body of work with one upstream, one security class and one set of destinations. |
| [Task](#task) | One run of one agent on one project, in its own container. |
| [Mode](#mode) | How a person is meant to be involved in a task. |
| [Agent](#agent) | The tool that does the work, declared by a manifest rather than known to Sokar. |
| [Provider](#provider) | The model API an agent talks to, and the rules for authenticating to it. |
| [Security class](#security-class) | Whether work leaves a node at all, and who reads it first. |
| [Egress set](#egress-set) | A named, pre-reviewed group of destinations a project may reach. |
| [Gate](#gate) | The one crossing where work leaves a node, and where somebody reads it. |
| [Mirror](#mirror) | The node-local bare repository a task pushes to and the gate serves from. |
| [Upstream](#upstream) | The real remote a project's work is eventually forwarded to. |
| [Vault](#vault) | The credential store, which answers with names, kinds and lengths and never a value. |
| [Phantom token](#phantom-token) | The worthless stand-in a task is given instead of a credential. |
| [Broker](#broker) | The node-side process that swaps the real credential in on the way out. |
| [Clearance](#clearance) | Asking a person about a destination a task reached that its rules do not allow. |
| [Helper](#helper) | A node-side process serving exactly one task. |
| [Hook](#hook) | A binary the container runtime runs at a fixed point in a container's life. |
| [Sidecar](#sidecar) | The file the hooks read — a contract with binaries that outlive the CLI. |
| [Profile](#profile) | What a task is, written down at the one moment it is known. |
| [Workspace](#workspace) | Where a task's work lives, inside the container. |
| [Words we do not use](#words-we-do-not-use) | Host, box, cluster, peer, join, control plane. |

---

## Node

**A running `sokard` and the state it owns** — its tasks, projects, mirrors and vault, which belong
to it and to nothing else.

**Not a machine.** The boundary is the OS user, not the hardware, and everything a node owns is
keyed to that user: the vault at `$XDG_DATA_HOME/sokar`, the socket at `$XDG_RUNTIME_DIR/sokar`
owner-only, the cached passphrase in that uid's kernel keyring, the containers in rootless podman's
per-user storage, the hooks in that user's `containers/oci/hooks.d`. Even the firewall is narrower
than the machine: the ruleset is loaded inside each container's own network namespace, so two tasks
cannot collide over it, let alone two people.

So **two developers with their own accounts on one machine are two nodes**, with two vaults and two
sets of credentials that never meet. One hostname, two nodes — and a client that lists nodes by
hostname alone would merge two people's work.

**Two developers sharing one account are one node**, with one vault. Unlocking it for either
unlocks it for both. That is the case where credentials really do overlap, and nothing here can
detect it.

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
client describes a node by how it reaches it — which, given the above, is the honest identifier
anyway: the way you reach a node names the user as well as the machine, and a hostname does not.

> **Not to be confused with `nodejs`**, which is the egress set granting npm, yarn and the Node.js
> runtime downloads. The set was called `node` until this page was written; it was renamed because
> one word for two things is one too many.

## Operator

**The person who owns a node.** Not a role Sokar assigns and not an account it manages — whoever
can run `sokar` on that machine as the user the daemon belongs to.

Some things are deliberately theirs alone, and that is why the word keeps appearing: unlocking the
vault, storing a credential, approving at the gate, installing an agent, deciding a clearance. Each
of those is a decision, and a decision needs somebody who can be wrong about it.

**An operator is not the agent and not a client.** The agent acts; the operator decides what it may
act on. A client only carries their decision to a node.

## Client

**Software that talks to one or more nodes.** The CLI is one, an interface is another.

**A client decides nothing.** It holds an ssh connection to each node and carries bytes; every node
applies its own policy and keeps its own state. This is the only thing that spans nodes — and it is
worth being exact about, because "one client, several nodes" is the shape people mistake for a
cluster.

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
node-local, so review needs no network and `offline` stays usable.

## Mirror

**A project's bare repository on the node.** A task clones from it and pushes to it, and the gate
serves review out of it. One per project, and it is the only git remote a task can reach.

## Upstream

**The real remote a project's work is eventually forwarded to.** GitHub, a company forge, anything
git can push to. It is named in the project file, and in `guarded` nothing reaches it except an
`approve`.

## Vault

**The credential store**, an encrypted file this node's owner unlocks. It answers with names, kinds
and lengths — **never with a value**.

A credential never enters a container. A task is given a **phantom token**: random, scoped to that
task, worth nothing to the provider. A **broker** on the node swaps the real credential in on the
way out, over a socket mounted into the container.

See [Authentication](authentication.md) for the kinds of credential, how each one gets into the
vault, and how that works from another machine.

## Phantom token

**What a task is given instead of a credential.** Random, scoped to that one task, accepted for a
bounded time, and worth nothing to the provider.

The point is what it makes true: an agent that leaks everything it holds has leaked something that
stops working when the task ends.

## Broker

**The node-side process that turns a phantom token into the real credential**, on the way out. It
listens on a socket mounted into one container, drops every credential header the agent sent, adds
the right one and reissues the request upstream.

A broker is a [helper](#helper): one per task, and it dies with it.

## Clearance

**What a task does when it reaches something it may not.** Four modes, the same four a conversation
would use: `prompt` asks a person and the answer takes effect on the waiting connection, `allow`
lets it through, `deny` refuses it, `off` does not ask at all.

`off` is the most consequential state a task can be in and is therefore reported, not inferred:
until a task said so, nothing an interface listed could mark it.

## Helper

**A node-side process serving one task** — the credential broker, the git gate, the clearance
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
task live in its [profile](#profile) instead.

## Profile

**What a task is, written down when it starts.** Which agent, which mode, what an unattended task
was asked to do, which ref its work goes to.

None of it can be recovered afterwards — the container knows only that it is up — so it is recorded
at the one moment it is known. It survives a resume and outlives the container.

**Not the sidecar.** This file has no readers outside Sokar, which is exactly why operator-facing
facts go here rather than into a contract with three installed binaries.

## Workspace

**Where a task's work lives, inside the container.** Deliberately not a bind mount from the node: a
mounted checkout would hand an agent the node's `.git/hooks`, which execute as whoever runs git.

---

## Words we do not use

- **Host** means a destination in an egress set — `EgressHost`, `upstreamHost` — and never a
  machine. The machine is a **node**.
- **Box** appears in older prose for a task's container. It is informal, and *container* or *task*
  is meant.
- **Cluster**, **peer**, **join**, **control plane** describe nothing in this product. See *Node*.
