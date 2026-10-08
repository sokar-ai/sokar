# How Sokar works

<img align="left" width="300" src="images/dummy.svg" alt="A crash-test dummy hammering away at a laptop inside a sealed container, while outside the wall a padlock hangs shut and a sulking little cloud stands beside a signpost reading NXDOMAIN">

A plain-language tour of what Sokar does and why. No knowledge of containers, firewalls or git
internals is assumed: every word is explained where it first appears, and again in the
[glossary](#glossary) at the end.

If you are not sure this kind of tool is for you, read [three ways of working](way-of-working.md)
first.

<br clear="left"/>

## The problem

An **AI agent** is a program that writes and changes code for you. Normally it asks before it does
anything real: "May I edit this file?", "May I run this command?". Answering all day is tiring, so
most agents have a mode where they stop asking. Then the agent edits files, deletes things,
installs software and runs commands on its own, with your computer, your files and your passwords
in reach.

That is convenient, and it is the whole risk. Sokar lets the agent work unattended, but in a room
it cannot get out of.

**In one sentence:** each agent works in a sealed container; it can use the tools and the copy of
your project inside it, it reaches the internet only where you said so, it never holds your real
credentials, and nothing it produces leaves until somebody has read it.

> **Linux only, podman 5 or newer.** The sealing is done by features of the Linux kernel, which
> macOS and Windows do not have. Podman is the program that builds the containers; older versions
> cannot connect your machine's loopback into one, and Sokar refuses to start rather than offer
> less than it promises. Ubuntu 24.04 ships podman 4; Fedora, Debian 13 and Ubuntu 25.10 or later
> are fine. `sokar doctor` tells you whether your machine is ready. See
> [getting started](getting-started.md).

## A task

A **task** is one container doing one piece of work for one project, with one agent inside.

A **container** is a sealed room for a program. From the inside it looks like a whole computer; from
the outside it is one process on your machine with the doors shut. Sokar's containers are
**rootless**: your ordinary user account builds them, not the machine's administrator, so even the
worst case inside has no more power over the machine than you do, and much less. The container is
granted none of the special powers a container can have, and nothing inside can gain powers it did
not start with. The agent runs as an unprivileged user called `agent`.

A task has a name, unique within its project. Without one, it is named after its repository. That
name is the container's name, the name of its work at the gate, and the name of its log files.
The name is not what makes a container a task, though: anything that runs as your account can start a
container with any name and any labels. **Sokar records the id podman gives the container when it is
made**, and a container is one of its tasks only while its id is that one. Every act on a task - start,
stop, remove, attach, its screen - goes to the recorded id, and podman's hooks check it before they give
a container a resolver and a firewall. A container that only looks like a task is not listed, not
stopped and not touched.

When you run `sokar task start`, in order:

1. Sokar reads the project's settings: which Linux to start from, how locked down the project is,
   and what the agent may reach.
2. It builds the **image**, the recipe the container is made from: the Linux the project named,
   then the agent, pinned and checked against a fingerprint of its exact bytes, then the project's
   own tools. `--dry-run` shows the recipe without building it.
3. It writes a firewall and a list of allowed names for this one task, and loads them before the
   agent starts. If that fails, the container does not start at all. This is **failing closed**:
   the failure is "nothing runs", never "runs unprotected".
4. It puts a copy of your repository inside as `/workspace`, cloned from the gate rather than from
   your working directory, so nothing the agent does can change your own files.
5. It prints what it wired up: the agent, the stand-in token, the ruleset, how many names resolve,
   the gate. Those lines are the security model in short; read them.
6. The agent works. By default you are in its session; `--attach shell` gives you a shell instead,
   and `-P "do this and that"` runs it **unattended**, with nobody watching.

**A task is kept until you remove it.** Leaving it does not end it, and `sokar task stop` keeps the
container, the workspace and any work that never reached the gate. `sokar task start` with its name
brings it back. Only `sokar task remove` destroys anything, and it refuses while work has not
reached the gate unless you say what to do with it. `--rm` at start throws the container away when
you leave. After a reboot nothing comes back by itself: `sokar task start --restarted` brings back
what went down. Where its agent can name the session, a task that comes back continues the
conversation it was having, not only the files; `sokar task remove` forgets it with everything else the
task owns. See [commands](commands.md#task).

## A project, and the project `default`

A **project** is what tasks are started *for*. It is a repository with a `project.yml` in it, which
this machine **follows**: it pulls the repository and takes the project's settings from the file
there, checked against a signing key you gave it. A project can span several repositories; a task
works on exactly one of them.

The file is short. For the common case:

```yaml
project:
  name: "myproject"
  security_class: "guarded"
image:
  base_image: "ubuntu:24.04"
egress:
  sets: [maven]
```

Every key is in [the project file](project-file.md). A project is not a container and never runs;
its name is how everything else finds its things.

**You do not need a project to start.** Every machine has the built-in project **`default`**, with
Sokar's own settings. A `sokar task start` in a checkout no followed project names works in
`default`, and the checkout's `origin` becomes where approved work goes. `default` is `guarded`,
reaches only the agent's provider, refuses blocked connections without asking, and has no
conversation between tasks. Its settings cannot be changed: for other settings, make a project
repository and follow it.

## The gate: work leaves only after review

The agent does not push its work to GitHub. It pushes to the **gate**: a copy of your repository on
your own machine, the **mirror**. Pushes land in a holding area, not on any branch, so nothing you
are looking at changes under your feet. The gate listens on your machine's loopback only; nothing
else on your network can reach it, and only the task's own container is connected to it.

Then, on your side:

```
sokar gate pending                       # what is waiting
sokar gate review -p myproject backend   # what it would change
sokar gate reject -p myproject backend   # discard it, and tell the task
sokar gate approve -p myproject backend  # forward it upstream
```

`approve` is the only command that sends work anywhere. The **upstream** it goes to is the real
remote, named in the project file.

Each project has a **security class**, and the class decides, never a flag on a task. `offline`:
nothing resolves and nothing leaves; the mirror is the end of the line, and `approve` refuses.
`guarded`: only what the project declares resolves, and work leaves only through the gate.
`online`: the gate passes the task's own branch on to the upstream at once, and nothing is reviewed. A class is a promise
about what cannot happen, so it is never widened: adding a destination to an `offline` project does
not open it. See [security](security.md).

## The vault and the broker: the container never holds the credential

An agent needs a credential, an API key or a subscription login, to talk to its AI **provider**.
Handing that key to the container would mean that any bug, any malicious instruction the agent read,
any careless log line could leak it, and a leaked key works for anyone until you revoke it.

Sokar does it differently:

- Your real credential lives in the **vault**: an encrypted file on your machine, unlocked with a
  passphrase. The vault answers with names, kinds and lengths, never with a value. Lose the
  passphrase and there is no recovery.
- The container gets a **phantom token** instead: random, valid only for this one task, expiring
  with it, and worth nothing to anybody else.
- On the way out, the agent's requests pass through the **broker**, a small process of Sokar's on
  your side that only this container can reach. It drops the phantom token, adds the real
  credential and sends the request on.
- No secret is ever put on a command line, where every user on the machine could read it.

So the provider's website *is* reachable from inside: what is kept in is the credential, not the
traffic. An agent that leaks everything it holds has leaked something that stops working when the
task ends. A key for git stays on your machine the same way, in every class: the gate on the host
uses it, and the container holds neither the key nor a socket that answers with it. See
[credentials](credentials.md).

## Egress: nothing is reachable unless named

**Egress** is the container's outbound traffic. It is denied by default. Before the agent runs,
Sokar loads a firewall that blocks everything, then opens exactly what the project declared:

```yaml
egress:
  sets: [maven, git-hosting]
  domains: ["nexus.corp.example"]     # a host no set covers
```

An **egress set** is a named, reviewed group of hosts: `maven`, `nodejs`, `python`, `git-hosting`
and others; `sokar shield sets` lists them. Naming a set means "reaching npm" is the same thing in
every project. `domains` is for a host no set covers. **Declaring nothing reaches nothing**, and a
new Sokar release with more sets does not quietly widen a project.

The refusal happens one step early, at the **resolver**, the phone book that turns a name like
`github.com` into an address. It answers "no such host" for every name you did not declare, so no
connection is even attempted:

```
Could not resolve host: repo.maven.apache.org
```

An address found some other way opens nothing: the firewall lets through only addresses Sokar's
resolver handed out or you approved. Declared names open only the web ports 80 and 443, so allowing
a code-hosting site does not hand the agent an `ssh` channel. Every blocked connection is written
to the task's log as it happens, whether or not anybody is watching. See [reach](reach.md).

**The clearance prompt.** When a connection is blocked to an address that is not allowed yet, a
**clearance** decides what happens. With `prompt`, the default, a desktop notification asks
**Allow** or **Deny**, once per destination and never again, in either direction, so an agent cannot
wear you down by retrying. `allow`, `deny` and `off` (do not ask) are the other modes; choose one
with `sokar task start --clearance` on a machine without a desktop, or change a running task with
`sokar task clearance`. If your build really needs a host, declare it in `project.yml` rather than
clicking Allow: a clearance is one address on one run, a declaration is reviewed and applies to
everyone.

## Messages between tasks

The tasks of one project can write to each other by task name, through the project's conversation:
the transport its file names under `mail.transports`. That is how work across several repositories is
coordinated. A project with no transport is standalone, and its tasks do not message each other.
Where the conversation lives is in [messages between tasks](messages.md#where-the-conversation-lives);
a transport is a package of its own, and [writing a transport](transports.md) says what it answers.

A message passes a filter on the way, both ways. **The filter refuses** what does not belong in a
message between agents - an encoded payload, a key, a file - and the sender is told why. A person can
still read a refused message and deliver it. A project may set `mail.outgoing_filter: reporting`, and
then a task's outgoing message is only reported, never refused; a person holding it is shown what the
filter would have refused. A person can hold a peer and read or release what is held, and nothing is
ever edited. `sokar talk` shows and moves them; see [commands](commands.md#talk).

## What Sokar does not do

- **The gate is a review step, not a network control.** It holds because the container has no
  credential for your code-hosting account, not because that host is unreachable. A usable token
  left in your work tree could push, whatever the class says. Keep credentials out of the
  repository.
- **Declaring a code-hosting set makes that host reachable.** That is legitimate, agents clone
  dependencies, but it removes one of the two reasons the gate holds. Sokar says so at task start.
- **The image build is not behind the firewall.** The firewall governs the running task; the build
  is when you install your tools, and it can fetch from anywhere.
- **The Fedora package set cannot be complete.** Fedora's package manager downloads from mirrors
  that differ by region and by day, and each new one raises a prompt. Debian and Ubuntu are covered.
- **A resolver on the internet still answers.** A program can skip Sokar's resolver and ask one
  outside. The address it gets opens nothing, but the question itself can carry data out.

## Glossary

| Term | In one sentence |
|---|---|
| Agent | The tool that does the work inside the container, installed as a package Sokar discovers rather than knows by name. |
| Broker | The process on your side that swaps a task's phantom token for the real credential on the way out. |
| Clearance | What happens when a task reaches something its rules do not allow: `prompt`, `allow`, `deny` or `off`. |
| Client | Software that talks to one or more nodes and decides nothing itself, such as the CLI. |
| Container | A sealed, rootless room for one task, built by podman. |
| `default` | The project every machine has without a file, `guarded`, with settings that cannot be changed. |
| Egress | A task's outbound traffic: denied unless the project declared it. |
| Egress set | A named, reviewed group of hosts a project may reach, such as `maven`. |
| Gate | The one crossing where work leaves your machine, and where somebody reads it first. |
| Helper | A process on your side serving exactly one task, such as the broker or the gate. |
| Image | The recipe a task's container is built from: base Linux, agent, project tools. |
| Mirror | The repository's copy on your machine that a task clones from and pushes to. |
| Mode | How a person is involved in a task: `shell`, `agent` or `unattended`. |
| Node | A running `sokard` and everything it owns, one per user account, not one per machine. |
| Operator | The person who owns a node, the only one who can unlock its vault or approve at its gate. |
| Phantom token | The worthless stand-in a task gets instead of a credential, valid for that task only. |
| Project | A followed repository with a `project.yml`: one security class and one set of destinations. |
| Provider | Who serves the models; a credential belongs to the provider, not to the agent. |
| Reconciliation | A machine pulling a followed project's repository and applying what it says, signed, never touching the vault, running tasks or a person's decisions. |
| Repository | One git repository of a project; a task works on exactly one. |
| Security class | Whether work leaves at all and who reads it first: `offline`, `guarded` or `online`. |
| Task | One container doing one piece of work for one project, kept until removed. |
| Upstream | The real remote approved work is forwarded to. |
| Vault | The encrypted credential store, which answers with names, kinds and lengths, never a value. |
| Workspace | Where a task's work lives inside the container, a clone, never a mount of your checkout. |

**Words we do not use.** *Host* means a destination in an egress set, never a machine; the machine
is a **node**. *Box* is informal for a task's container. *Cluster* and *control plane* describe
nothing here: nodes never talk to, join or discover each other. A *peer* is only ever who a task's
messages are addressed to.
