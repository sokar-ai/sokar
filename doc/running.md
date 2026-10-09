# Running Sokar on a machine

This page is for whoever runs the machine: how a fresh machine becomes a Sokar machine, how the daemon
runs and how you reach it from another computer, and how to add your own tools to a task's image.

## Preparing a machine

A machine you have just rented, reachable over ssh as a user who can become root, becomes a Sokar machine
with one script: `sokar-setup.sh`. It is published beside the packages and versioned with them, as
`sokar-dist-deb/setup/sokar-setup-<version>.sh` and `sokar-setup-latest.sh`. Fetch the one that matches
what you want to install.

It is the one part of Sokar that is a file and not a command. A machine being prepared has no packages, no
daemon and no account for the daemon to run as yet. It is not a package either: a package needs a package
manager that already knows Sokar's repository, and telling it is one of the things the script does.

### What it does, as root

```
sh sokar-setup-latest.sh [--user agents] [--distribution releases] [--with <package>]... [--show] [--list [--json]]
```

- **Configures Sokar's package repository** and installs Sokar, the message filter, and every package
  `--with` names. `--list` says what there is to choose from: agents, transports and homeservers.
  `--list --json` says it as one JSON object, for a program.
- **Creates the account tasks run as**, `agents` by default (`--user` names another), with **linger**, so
  its user services survive a logout, and with **subuid and subgid ranges**, without which rootless podman
  cannot map a container's users. These are where a hand-made account usually goes wrong.
- **Checks what no package can promise**: that the repository really offers Sokar (an already installed
  package would hide a broken source), and that dnsmasq can fill the firewall's allow set.
- **`--show` prints every command and runs none of them**, so you see what root will do before you give it
  root.

It reads `/etc/os-release` and **refuses an operating system it does not know** instead of trying its best:
a half-prepared machine is worse than an unprepared one, because the next step believes it. **Running it
twice changes nothing the second time**, and says so, so a run abandoned halfway can simply run again.

| Exit | Meaning |
|---|---|
| 0 | the machine is prepared, or already was |
| 2 | the arguments do not make sense |
| 3 | this operating system is not one Sokar knows how to prepare |
| 4 | not running as root |
| 5 | a check failed, and the machine is not usable as it stands |

The acceptance tests prepare their rented machines with this script and nothing else, so the path you
take is the path that is tested.

### Then, as each user: `sokar setup`

**The script does not start the account's daemon.** A root script starting another user's service would
be wrong about the session it runs in. What is left belongs to the account, and is one command in its own
session, at a terminal (over ssh: `ssh -t`):

```
sokar setup
```

It registers podman's hooks, starts the daemon and creates the vault (see
[the command reference](commands.md#setup)). The script's exit code says its steps ran; only
`sokar doctor` can say the machine works.

## The daemon

`sokard` serves what the CLI does over an owner-only unix socket, so an interface on this machine, or on
another one through ssh, can use it. **Nothing in the CLI needs it running.**

```
sokard --help        # says what it is
sokard --version     # says which build
sokard               # serves, until it is stopped
```

It takes no other options, and an option it does not know is refused, not ignored.

### The systemd user unit

`sokard.service` is a **user** unit, on purpose. **A Sokar node is an OS user, not a machine**: the vault,
the containers in rootless podman's per-user storage, the hooks podman reads and the socket under
`$XDG_RUNTIME_DIR` all belong to one user. Two people with their own logins are two nodes, and each runs
their own daemon.

```
sokar setup                      # enables and starts it, unless it already runs
systemctl --user status sokard
```

`sokar setup` runs `systemctl --user enable --now sokard`. The package installs the unit but enables
nothing: starting a daemon is each account's own decision.

**A daemon that must outlive your session needs lingering**, or systemd stops everything you run when you
log out. `sokar-setup.sh` turns it on for the account it creates; for any other account:

```
loginctl enable-linger "$USER"
```

The unit restarts the daemon when it fails. It deliberately leaves out three things:

- **No `NoNewPrivileges=yes`.** The first rootless podman call after a boot needs the setuid `newuidmap`
  and `newgidmap`, which no-new-privileges forbids. With it, the daemon works only while something else
  has already run podman, and reports podman missing on a fresh boot (`newuidmap: write to uid_map failed:
  Operation not permitted`).
- **No `RuntimeDirectory=sokar`.** The same directory holds each task's state, and systemd removes what
  it created when the unit stops. A stopped daemon would take running tasks' state with it.
- **No `sokard.socket`, so no socket activation.** The first connection does not start the daemon by
  itself. Start it with `systemctl --user start sokard`, over ssh if need be.

### After an update

**Nothing to do.** The package starts nothing when it installs, so the daemon restarts itself: within a few
seconds of an update it notices that its binary was replaced, and asks the account's own systemd to reload the unit
definitions and restart it. Every account that runs `sokard` as its unit does this by itself. Running tasks keep
running; they take the new version when they are next started.

`sokar doctor` says when the daemon runs another version than the command - a daemon started by hand, which nothing
restarts, says so once in its own output. By hand it is:

```
systemctl --user daemon-reload && systemctl --user restart sokard
```

Without `daemon-reload` first, systemd warns that the unit file changed on disk, and runs the new binary under the
old definition.

### A task outlives the daemon

Stopping a unit stops every process in its control group. So everything a task leaves running - the
container's monitor (`conmon`), its network, its resolver, its helpers - is started in a scope of its own
under `sokar.slice`, by the daemon and by the CLI alike. Stopping, restarting or losing the daemon does not
end a task. `systemctl --user status sokar.slice` shows one scope per helper and one for the container's
monitor, each named after its task. A scope ends by itself once nothing in it runs, so removing a task
leaves none behind. On a machine without a user manager the processes are started as they are.

### The socket

The daemon binds `$XDG_RUNTIME_DIR/sokar/sokard.sock`, readable only by its owner.

- **A daemon that is stopped or signalled removes it.** Only `SIGKILL` can leave one behind.
- **A second daemon does not take the socket from the first.** Starting one while another listens is
  refused, naming the path.
- **A socket left by a `SIGKILL` or a reboot is taken over** by the next start, which removes it only after
  checking that nothing answers on it.

### A daemon that answers nothing

If `sokard` accepts a connection but answers no call, have it print where its threads are before restarting it:

```
systemctl --user kill -s QUIT sokard
journalctl --user -u sokard
```

The dump goes to the daemon's journal, names its background work (`sokar-follow`, `sokar-upstream`,
`sokar-messages`), and the daemon keeps running under the same pid. Keep that dump with the report of the hang.

### Reaching it from another computer

Nothing listens on the network. You reach the daemon through ssh as the account it runs for, in one of two
ways:

- **Through the ssh session itself.** `sokar daemon connect` joins its standard input and output to the
  socket, so `ssh HOST sokar daemon connect` (or the same as an ssh `ProxyCommand`) gives a client the
  daemon's stream. No socket file is made on your side, and the connection lasts as long as the ssh
  command. If no daemon runs, it says so on standard error and exits with 69.
- **Through a forwarded socket.** `ssh -L` can forward a local socket file to the remote
  `$XDG_RUNTIME_DIR/sokar/sokard.sock`, and a client then opens the local file.

If the daemon is not running, `ssh HOST systemctl --user start sokard` starts it under systemd, supervised
and restarted on failure, instead of leaving an unsupervised process behind an ssh command. For it to keep
running after you disconnect, the account needs lingering.

## Handing a file to a running task

A task's work comes from its repository, but some things a task needs never belong in git: a build log, a
screenshot, a specification, a data extract. `sokar task give TASK FILE` hands such a file to a running task, and
the agent finds it in `/sokar/files`.

- **`/sokar/files` holds nothing else**, in every task, whatever the agent and the image, from the moment the task
  starts. So a prompt can say "read what arrives in `/sokar/files`" before anything has arrived, and an agent can be
  told to read all of it.
- **A file appears whole or not at all**, owned by root and readable by the agent, which cannot change or remove
  it. It is never in `/workspace`, so nothing the agent commits takes it out through the gate.
- **The same name replaces the file**, and `sokar task take-back TASK NAME` removes it.
- **It lives as long as the container**: a stop and a start keep it, since the container keeps its files, and
  removing the task removes it. A task created again under the same name starts with an empty `/sokar/files`.
- **The largest file a task takes** is `limits.hand_in` in the [project file](project-file.md), 64 MiB unless it
  says otherwise, fixed when the task starts like its other limits.
- **Only into a task, never out.** Nothing here takes a file out of a task; work leaves through the gate.
- **Every hand-in is written down**: who, when, which task, the name, the size and the sha256, never the content.
  `sokar task files TASK` shows it, also after the task is gone, and `sokar task status TASK` lists what the task
  holds now. It records what Sokar did: a file copied into a container with `podman cp` is not in it.
- **A base image that already has `/sokar` is refused** when the task's image is built, naming the image: Sokar
  keeps that directory for itself, so that everything in it arrived the same way.

An interface on another computer hands a file over the daemon's socket in parts, so the same works from wherever
the socket reaches.

## Being told what the build of a push did

A task in an `online` project pushes to its gate, which passes the task's own branch on to the upstream at once as
`sokar/<task>`, and the forge builds it somewhere the task cannot see. When the project file names the forge, Sokar follows every commit the task pushes and hands the build's verdict
into the task as it changes - the task asks for nothing and holds no forge credential:

```yaml
builds:
  forge: github               # the build reader to ask, as its package installs it
  credential: github-actions  # the vault entry it reads the forge with; it never enters a task
  # api: https://ghe.example/api/v3   # a forge of your own; unset is the forge's public API
  # logs: all                 # every job's log once the build is finished; default: each failed job's
```

- **Only in `online`**: in `guarded` the forge builds nothing until a person approves the work at the gate, and in
  `offline` there is no build. A project of either class with a `builds` section is refused when it is read.
- **What arrives in `/sokar/files`**, with `by` "sokar": `build-<first 12 of commit>.txt`, the verdict - one of
  `queued`, `running`, `success`, `failure`, `cancelled`, `unknown` - and, once it is `failure` or final, one line per
  job with its result and its log's file; and `build-<first 12 of commit>-<n>.log`, the last 64 KiB of a job's log
  as text, for each failed job, or for every job with `logs: all`. `<n>` is the job's place in that list, so the
  failed job's log is the file its `job:` line names, not `-1.log`. The file is replaced as the verdict changes.
- **A wait ends**: at a verdict that will not change, after two hours of a commit's build, or when the task stops.
  A rate limit, a credential the forge refused, a repository it does not show and a shut vault are each said in the
  verdict's detail, never as a failed build.
- **Sokar keeps no copy of a log.** What it writes down is which builds it delivered - commit, verdict, each job's
  result and its log's name, size and sha256 - and that outlives the task. `sokar task status TASK` shows the builds.
- **The forge is read from the host**, by a build reader Sokar starts beside the task; its API host is not something
  the task can reach. Readers are packages of their own, installed beside Sokar from the same repositories - for
  GitHub, `sudo apt install sokar-build-github` or `sudo dnf install sokar-build-github`, which puts it at
  `/usr/libexec/sokar/builds/github`. Writing one is in [writing a build reader](build-readers.md).

## What a task's agent is told

An agent learns what Sokar gives it in its task from one guide, which it takes into its standing instructions at
every start when its definition declares how (`instructions`): Sokar writes it to `/run/sokar/guide/README.md`,
mounted into the task and read only there. Where the task has a mailbox, the mailbox's text from
[messages between tasks](messages.md#what-the-agent-is-told) follows it in the same file. A task made before
this version keeps its mailbox's guide until it is made again.

When a file arrives in `/sokar/files` - handed in by a person, or a build's verdict and its logs - and the agent waits
at its prompt, Sokar types one line naming it, as it does for a message: never while a question to a person is
open, and never for an agent whose definition says nothing about what its screen shows at rest. A file that arrived
while the agent worked is announced once it rests; a verdict that changes is announced again.

The text is the same for every task of one Sokar version, word for word:

## Sokar in this task

This task runs in Sokar. What follows is what Sokar gives you here besides your workspace:
files handed to you, the builds of what you push, and a mailbox where the task has one.

**When your work is done, commit it and push it with `git push sokar`, and name no branch.** The push
goes to this task's own place, `$SOKAR_TASK_REF`, at the gate on this machine: where a person reviews
it, or, in an online project, from where it goes on at once to this task's own branch at the forge -
never onto a branch you name. A person takes back only what you pushed; what is only in your
workspace stays in this task.

**When you are told the repository you work from moved on, `git fetch sokar` brings it.**
Rebase or merge your work onto it before you push again.

## Files handed to you

- **`/sokar/files` holds the files handed to this task**, by a person or by Sokar, and nothing
  else. A file appears there whole, never half written.
- You can read them but not change or remove them. Copy one into your workspace to change it.
- A file handed in again under the same name replaces the one before.
- **Look there when you start, and whenever you come to rest.** When a file arrives while you
  wait at your prompt, a line may appear there naming it; not every agent is given one.

### The builds of what you push

Where the project follows its builds, Sokar watches what the forge builds for each commit you
push to the task's branch, and hands in what it did:

- `build-<commit>.txt`, `<commit>` the first 12 characters of the commit: lines of
  `key: value` - `commit`, `verdict`, one `job` line per job, `since`, and `detail` when
  there is more to say.
- `verdict` is `queued` or `running` while the build goes on, then `success`, `failure` or
  `cancelled`. It is `unknown` when there is no build or Sokar cannot read it, and `detail`
  says why.
- A `job` line names the job and what became of it, and the file holding the end of its log
  when one was handed in: `build-<commit>-<n>.log`. A failed job's log always is, when the
  forge has one.
- The file is replaced each time the verdict changes.

**After you push, read `build-<commit>.txt` until its verdict is final, before you call the work
done.** On `failure`, read the log each failed `job` line names, fix the cause, and push again.

## Adding your own tooling to a task

Two things are called "the agent", and they live in different places:

- **The agent package** (for example `sokar-agent-claude`) installs **on the host**, under
  `/usr/libexec/sokar/agents/`, or anywhere with one description file in `/usr/share/sokar/agents.d/` that names it. It is Sokar's adapter: it knows the agent's flags, where it keeps its
  credentials and how to read its output.
- **The agent's CLI** (for example `claude`) runs **inside the task image**.

How the CLI gets into the image is up to the agent package. Claude Code's package (about 6 MB) carries a
pinned URL and SHA-256, which Sokar puts into the generated `Containerfile`; podman's layer cache then
downloads the CLI (about 320 MB) once per pinned version. Pi's package (about 70 MB) carries the tool
itself, verified once where the package is built against a lockfile that pins every dependency by hash, so
the image build downloads nothing for it.

A task image is built in three layers, in this order:

1. **base**: the distribution image the project names, plus an unprivileged `agent` user, `/workspace`,
   and `curl` with CA certificates;
2. **agent**: the pinned, verified download the agent package asked for, or a copy of what it carries;
3. **project**: your own lines.

`sokar task prepare -p PROJECT` builds a project's task image without starting a task, and
`--rebuild` says how much of it to discard:

| `--rebuild` | What it keeps |
|---|---|
| `CACHED` (the default) | whatever podman's cache still considers valid, exactly as a task start does |
| `AGENT` | the base image and its packages; the agent's tooling is rebuilt |
| `EVERYTHING` | nothing: the base image's packages are downloaded again |

`CACHED` still runs the build and lets the cache decide layer by layer, so preparing and starting
never disagree about what the image should contain. An unknown depth is refused rather than taken as
the cheapest. `--agent` names whose tooling to install; with none installed, the image is prepared
without an agent, for working inside the container by hand. The build's output is shown line by line
while it runs, and `--dry-run` says what it would do.

Layer 2 needs network access **while the image is built**, to the vendor's download host, for an agent that
downloads its CLI. The egress firewall governs the running task, not the build. An air-gapped machine
needs a mirror for that URL, or an agent whose package carries the tool.

Your tooling goes in layer 3, either inline in `project.yml`:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends ripgrep jq \
        && rm -rf /var/lib/apt/lists/*
```

or, for anything longer, in a file beside it:

```yaml
image:
  base_image: "ubuntu:24.04"
  snippet_file: "tooling.dockerinclude"
```

Use one or the other; Sokar refuses both together.

- **Your lines run as root**, before the image switches to the `agent` user, because installing packages is
  what they are almost always for.
- **They run after the agent layer**, so the agent's CLI is already there.
- `sokar task start --dry-run` shows what would be built without building it. The generated
  `Containerfile` is kept in `$XDG_DATA_HOME/sokar/build/<project>/` for you to read.

Nothing you add escapes the rest of the model: the container still starts with no capabilities and with
`NoNewPrivs`, and installing something does not widen what the task may reach.

**Build time and run time differ.** Your lines run while the image is built, which the firewall does not
govern, so they can fetch from anywhere. While the task runs, its resolver answers NXDOMAIN for every name
the project has not declared, so a tool that reaches out then fails to resolve. Two remedies, and you may
need both:

- fetch what the tool needs here, in the build, so the running task needs no network for it;
- if it really must reach out while the agent works (a package registry is the usual case), name the host
  in the project's `egress` section, where the grant is reviewed like any other change. See
  [what a task can reach](reach.md) and [the project file](project-file.md).
