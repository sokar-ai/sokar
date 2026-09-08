# Getting started on Debian and Ubuntu

By the end of this you will have an agent working inside a hardened container, with your
real credential still on the node, and its work waiting for you to review before it goes
anywhere. For the whole thing as one block to paste, see
[the README](../README.md#getting-started).

On Fedora or RHEL instead? [Getting started on Fedora](getting-started-fedora.md).

## Before you start

**Sokar runs on Linux only.** The containment is kernel machinery — an nftables ruleset
loaded into the container's network namespace, OCI hooks, user namespaces, the kernel
keyring — none of which exists on macOS or Windows, where a container runtime would put
all of it on the far side of a virtual machine.

Rootless podman has to work as your own user — `podman info` should succeed without
`sudo`.

**Podman 5 or newer is required, and Ubuntu 24.04 LTS cannot provide it.** 24.04 ships
podman 4.9.3 in `universe`, and a stable Ubuntu release never changes a package's major
version — so it will still be 4.9.3 in 2029. Podman 4 has no `pasta`, which is what maps
this machine's loopback into a container; without it the git gate a task pushes to would
have to bind every interface and would sit on your local network. Sokar refuses to start a
task rather than run with less than it promises, and `sokar doctor` says so. Ubuntu 25.10
and 26.04 ship podman 5, as do current Fedora and Debian 13.

Sokar shells out to `podman`, `nft`, `dnsmasq`, `git` and `nsenter`. The `.deb` declares
them as dependencies, so `apt` pulls them in; if you run from a build instead, install
them yourself.

## 1. Install

Two packages: `sokar` is the tool, `sokar-agent-claude` is one agent. Sokar discovers
agents at runtime, so installing an agent needs no new release of Sokar.

```
sudo apt install -y ca-certificates curl gnupg
curl -fsSL https://fuinorg.jfrog.io/artifactory/api/security/keypair/sokar-packages/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/sokar.gpg
echo "deb [signed-by=/usr/share/keyrings/sokar.gpg] https://fuinorg.jfrog.io/artifactory/sokar-dist-deb snapshots main" \
  | sudo tee /etc/apt/sources.list.d/sokar.list
sudo apt update
sudo apt install sokar sokar-agent-claude
```

**The key is dearmored, not the `.asc`.** `apt` wants the binary form at that path, and
handing it the armored file fails with a verification error that never mentions the
format.

**`snapshots` is the only distribution so far.** Nothing is released yet, so that is the
word you write; a release will publish to `stable` and you change the one line.

**Reinstalling after a new snapshot.** A snapshot keeps its version string, so `apt` sees
nothing to do even when the bytes have changed — it says `sokar is already the newest
version` and you keep running the old binary while believing you replaced it. One flag
covers both the already-installed and the not-yet-installed package:

```
sudo apt install --reinstall sokar sokar-agent-claude
```

### From a local build

**An agent is built in its own repository** — Claude Code in
[sokar-claude-code](https://github.com/fuinorg/sokar-claude-code) — so a build of Sokar
produces Sokar and no agent. Sokar's own package lands in `dist-deb/target`. Copy it
somewhere readable first:

```
mkdir -p /tmp/sokar-pkgs
cp dist-deb/target/sokar_*.deb /tmp/sokar-pkgs/
sudo apt install /tmp/sokar-pkgs/*.deb
```

**Why the copy.** apt fetches even a local file as `_apt` — uid 42, group `nogroup` —
and that user cannot traverse a `0750` home directory. Point it straight at `target/`
and it says so:

```
N: Download is performed unsandboxed as root as file '/home/you/git/sokar/dist-deb/target/sokar_0.1.0~SNAPSHOT_amd64.deb' couldn't be accessed by user '_apt'. - pkgAcquire::Run (13: Permission denied)
```

It is a notice, not an error — apt falls back to reading the file as root and the install
goes through. But the sandbox it drops is worth keeping, and `/tmp` costs nothing.

## 2. Register the hooks — once per user

```
sokar setup
```

It prints one line per file it writes:

```
installed /home/you/.config/containers/containers.conf.d/50-sokar.conf
installed /home/you/.config/containers/oci/hooks.d/sokar-hook-nft-createRuntime.json
installed /home/you/.config/containers/oci/hooks.d/sokar-hook-nft-poststop.json
...
```

The package deliberately does not do this. Podman reads OCI hook descriptors per
user, so a package installing them system-wide would fire them for every container
you run, including ones that have nothing to do with Sokar. Re-running `sokar
setup` is safe.

`sokar doctor` confirms where everything lives, and answers one question worth
asking before your first task:

```
dnsmasq nftset      yes
```

That is how a declared domain becomes reachable rather than merely resolvable —
dnsmasq adds each address it answers to the firewall's allow set. A dnsmasq
without it accepts the configuration and silently never opens anything, so names
resolve, nothing connects, and there is no error to read. If `doctor` says
anything but `yes`, install a dnsmasq built with nftset support (2.87 or later)
before going further.

## 3. Put a credential in the vault

> [!WARNING]
> **Never log in from inside the box.** If an agent offers you a login prompt or a
> sign-in URL, something is wrong here, not there — the container has no browser,
> and the sign-in endpoints are firewalled off on purpose. Completing such a flow
> would also mint a fresh key *inside* the container, which is the one thing this
> whole design exists to prevent. Authenticate on the node, once, and store the
> result below.

**Three ways in, and the first works on a machine where nothing is installed yet:**

| Your situation | How to get a credential into the vault |
|---|---|
| **anything** | **`sokar vault login <agent>`** — runs the agent's own login for you |
| the agent is already signed in on this node | `sokar vault import <agent>` — **prefer this**: it copies what is already there and logs in nowhere |
| you have an API key | `sokar vault put <provider> --type api-key` — asks for it without echoing it |

**If the agent is already signed in here, `vault login` refuses and says so.** It checks
this machine for a credential first — with the same reader `vault import` uses — and points
you at importing instead, because a second authorization is not free: the container has its
own home and never touches the one on this node, so nothing local is disturbed by Sokar, but
whether a second authorization invalidates the first is the provider's business, not ours.
Some issue many concurrent tokens; some revoke the previous one.

`vault import` copies the credential that already exists and performs no login at all, so
the question does not arise. `--force` logs in anyway, for somebody who means it.

**For a subscription there is no key to copy from a web page.** The value is produced by
the agent's own login, and on a machine where you have never installed that agent by hand
there is nothing to run it with: the Sokar agent package installs a definition in
`/usr/libexec/sokar/agents`, and the agent's tooling goes into the **task image** rather
than onto the node.

That is what `sokar vault login` is for:

```
sokar vault login claude
```

It builds a throwaway image containing the agent and runs **the agent's own login** in it, on
your terminal. Sokar does not perform the login and cannot: what the agent asks for, and how,
is its own. When the login exits, Sokar collects the credential it wrote and puts it in the
vault, and the container is removed.

**Two things to expect, because the login happens in a container:**

- **There is no browser in there.** The agent prints a URL and you open it yourself, on
  whatever machine you are sitting at.
- **If that machine is not this one, forward the port first.** A redirect back to `localhost`
  otherwise lands on the machine running the container rather than on the one with your
  browser. In a second terminal:

  ```
  ssh -L <port>:localhost:<port> <this machine>
  ```

  The container shares this machine's network, so the callback arrives here and the forward
  carries it to your browser. The port is the one in the URL the agent printed.

**That container is not a task container**, and the difference is the point: it carries no
Sokar annotation, so none of the hooks fire. No egress ruleset, no resolver, no clearance
watcher, no broker and no vault mounted into it. It has ordinary network access for the
seconds a login takes, because a login has to reach the provider directly — which is
exactly what a *task* is prevented from doing, so that an agent must go through the broker
and never holds the real credential.

**An agent has to say how it logs in**, in its own manifest, and one that does not is
answered as unsupported rather than guessed at — Sokar never guesses a verb, because
hardcoding one agent's would be wrong for every other.

For Claude Code the declared login is the binary on its own: running `claude` without
credentials starts its own authentication and writes them where Sokar collects them. It is
deliberately **not** `setup-token`, which prints a token to the terminal and writes no
credentials file — that would leave you copying a value by hand, which is the thing this
removes.

**If the agent is already logged in here, import instead of typing.** An agent that
keeps its own credentials on this node can hand them over:

```
sokar vault unlock
sokar vault import claude
```

That reads what the agent already has, records the kind for you, and never puts the
value through your shell — which is where the placeholder above tends to end up
verbatim. `sokar task run` also says so when the agent's own credential has moved on
and the vault's copy is behind.

**Otherwise unlock and store, in that order.**

```
sokar vault unlock
sokar vault put anthropic --type api-key      # asks, and does not echo what you type
```

**Type or paste it at the prompt rather than piping it in.** A pipe puts the key in
your shell history; the prompt reads it the way a password is read, so it does not
appear on screen or in the terminal's scrollback. The piped form still works, for
scripts:

```
printf '%s' 'sk-ant-...' | sokar vault put anthropic --type api-key
```

The order is not a style preference. When the credential is piped in, `vault put`
has nothing left to read a passphrase from — do it the other way round and you get:

```
sokar: No passphrase available, tried: kernel-keyring, prompt
```

which does not obviously mean "run unlock first".

Some details worth knowing:

- The **first** `unlock` on a machine with no vault sets the passphrase. There is
  no separate init step.
- `sokar vault remove <name>` deletes an entry. Removing one that is not there is
  not an error, so a cleanup script can run twice.
- `unlock` caches the passphrase in the kernel keyring for the rest of the
  session, so you type it once. `sokar vault lock` drops it again, and the next
  command asks. Locking needs nothing restarted, but it does not reach a task that
  is already running: its proxy read the credential when it started and holds it
  until the task stops. `sokar vault lock` says so when any task is up.
- When a vault already exists, `unlock` opens it before caching and refuses a
  passphrase that does not fit, so a typo fails there rather than at the next
  command.
- For automation, `--passphrase-command 'pass show sokar'` or
  `--systemd-credential <file>` replace the prompt entirely.
- **`--type` belongs to the credential, not to the run.** A provider that accepts
  more than one kind puts them in different headers, so the kind is recorded once
  here and every task uses it without being told. `sokar task run
  --credential-type` overrides it; nothing else needs to.
- The **name must be the agent's name** — `sokar agents` lists what is installed.
  Sokar looks the credential up by that name and by nothing else.
- Use `printf`, not `echo`: `echo` appends a newline, and the newline becomes part
  of your key.
- Never pass a credential as a command-line argument to anything. A command line
  is visible to every process on the machine. Standard input is not.

**Lost the passphrase?** There is no recovery — that is the point of the vault.
Start again:

```
sokar vault lock
mv ~/.local/share/sokar/vault.bin ~/.local/share/sokar/vault.bin.old
sokar vault unlock
printf '%s' 'sk-ant-your-real-key' | sokar vault put anthropic
```

Lock first, or a cached passphrase keeps being used ahead of anything you
type. Move the file rather than deleting it, in case the passphrase comes back to
you; nothing else reads it, and you can delete it once the new vault works.

The old file also tells you how much you are giving up without opening it: it is
52 bytes of header plus a 16-byte tag, so a 106-byte vault holds 38 bytes of JSON,
which is less than one API key. `ls -l ~/.local/share/sokar/vault.bin`.

**Which credential?** An agent may accept more than one kind, and they are not
interchangeable — an API key and a subscription token go in different headers, and
sending one as the other fails in a way that looks exactly like a wrong key. Claude
Code takes an API key or a subscription OAuth token; say which when you store it
(`--type api-key` or `--type oauth`) and no task has to repeat it — see
[the Claude Code guide](https://github.com/fuinorg/sokar-claude-code#readme) for where to get each.
`sokar agents --verbose` shows what each installed agent needs to reach.

## 4. Describe your project

**You do not have to write this file.** `sokar task run` in a directory without one offers
to write it, taking the project name from the directory and Enter for every default:

```
No project definition here yet. One line each, Enter takes the default.

  project name [my-project]:
  security class (offline/guarded/online) [guarded]:
  base image [ubuntu:24.04]:

Write project.yml? [Y/n]
```

It only asks when there is a terminal to answer from. A script that lands here without a
project file is more likely in the wrong directory than wanting one written, so it gets an
error saying where to run this instead.

`project.yml` is an ordinary file beside your code, and everything in it can be changed:

```yaml
project:
  name: "myproject"
  security_class: "guarded"
  # upstream: "git@github.com:you/myproject.git"   # required by online; optional otherwise
image:
  base_image: "ubuntu:24.04"
egress:                     # what the build may reach; nothing else resolves
  sets: [os-packages-debian, git-hosting]
  # domains: ["nexus.corp.example"]   # a private mirror, if you have one
limits:                     # optional; these are the defaults
  memory: "8g"              # "none" to opt out on purpose
  pids: 2048
  # cpus: "2.0"             # unset means no CPU limit
```

### What the build may reach

**Nothing that is not named here.** The block the wizard writes is the whole mechanism:

```yaml
egress:
  sets: [os-packages-debian, git-hosting]
```

`sets` are curated lists of hosts, written once so that "reaching npm" means the same thing in
every project. See what is installed:

```sh
sokar shield sets              # names and how many hosts each grants
sokar shield sets --verbose    # and the hosts themselves
```

Add what your build needs — `maven`, `nodejs`, `python`, `rust`, `go`, `containers` — and, for a
host no set covers, name it directly. Either edit the file, or let Sokar do it and tell you what
the change opens in hosts rather than in set names:

```sh
sokar shield egress                              # what this project may reach, and who decided
sokar shield egress --add-set maven --dry-run    # the hosts it would open, writing nothing
sokar shield egress --add-set maven --add-domain nexus.corp.example
```

It edits those two keys and leaves the rest of your file — comments included — where it was. A set
this machine does not have is refused rather than written, and the change applies to the next task,
not to one already running. By hand it looks like this:

```yaml
egress:
  sets: [maven]
  domains: ["nexus.corp.example"]
```

Four things worth knowing before you widen it:

- **The package set follows the base image, not your machine.** A Fedora node running a task on
  `ubuntu:24.04` needs `os-packages-debian`; the wizard picks the right one from the base image
  you chose.
- **Ports 80 and 443 only.** A declared name opens web ports at the addresses it resolves to,
  not the host. Declaring `git-hosting` does not open ssh, so it does not hand an agent a
  `git push` that goes around the gate.
- **An `offline` project refuses this section** rather than ignoring it, and says so.
- **`os-packages-fedora` cannot be complete.** `dnf` fetches from mirrors named by a mirrorlist,
  which differ by region and by day, so each one raises a clearance prompt. Pin a baseurl in
  your image snippet if that matters. Debian and Ubuntu use stable CDN names and are covered.
- **Declaring a forge is a real decision.** `git-hosting` makes github.com resolvable, and in a
  `guarded` project the gate then rests on the container holding no credential for it rather
  than on the host being unreachable. Sokar prints that at task start. It does not refuse: an
  agent legitimately clones dependencies from a forge.

What a task may reach is printed when it starts, with where each entry came from — the agent,
the provider, the upstream, or this file.

**Why the limits are there.** An agent in YOLO mode runs commands nobody reviewed,
so a runaway build is a normal outcome rather than an attack. `--pids-limit` also
contains a fork bomb; podman defaults it to 2048, and Sokar pins it so the
protection does not depend on a distribution default. Memory is the one that takes
the node down, so it is capped by default and a project raises it when it needs to.
CPU is left alone: starving a task only makes it slow.

Sokar also passes `--init`. The container's main process is `sleep infinity`, which
never calls `wait()`, so without an init every process the agent orphans would stay
a zombie holding a PID until the container died — and those count against the
process limit.

**What the gate is**, since the classes are defined in terms of it: a bare mirror
of your repository on your own machine, at
`~/.local/share/sokar/mirrors/<project>.git`, served to the container over HTTP.
Every request must carry a per-task token, and the port is bound on `127.0.0.1`, so
nothing on your local network can reach it at all — the container gets in because
Sokar maps your machine's loopback into the containers it starts itself. Where podman
cannot do that, `task run` binds every interface instead and says so on that run's
output. The container's `origin` points at that mirror rather than at your real
remote, and a push lands in `refs/sokar/incoming/<task>`, where it waits for you.

`security_class` is one of:

| Class | The container's `origin` | How work reaches your real remote |
|---|---|---|
| `offline` | the gate on your machine | it does not, ever |
| `guarded` | the gate on your machine | you read the diff, then `sokar gate approve` pushes it |
| `online` | **your real remote** | the agent pushes there itself, unreviewed |

**How your code gets into the box.** The mirror is created the first time the gate
is used, and seeded from the first of these that applies:

| Sokar clones from | when |
|---|---|
| `--upstream <url>` | you passed it |
| `upstream:` in `project.yml` | it is set |
| the repository you are standing in | neither of the above and the current directory is a git work tree |
| nothing, so the mirror starts empty | you are not in a repository |

The third is the usual case and needs no flag — `sokar task run` prints
`seed  <path>` when it uses it, so the choice is never silent. A local path works
exactly like a URL; git does not care.

Two things follow from it being a *bare clone*, and both surprise people:

- **Only committed history is copied.** A bare repository has no working tree, so
  uncommitted changes stay on your side. Commit before you run.
- **Seeding happens once.** `initialize` returns early if the mirror is already
  there, so later commits on the node do not flow in by themselves, and re-running
  with a different `--upstream` changes nothing. To start over, delete
  `~/.local/share/sokar/mirrors/<project>.git`.

`sokar gate pending` says what a mirror was built from, so you never have to
remember:

```
seeded from /home/you/git/myproject
```

That comes from the mirror itself — `git clone --bare` records it — not from
configuration that may have changed since.

`upstream:` is also where an approved push is forwarded, for `guarded` as well as
`online` — set it there once rather than repeating `--upstream` on every
`sokar gate approve`.

An `online` project must name its `upstream`, and Sokar refuses to load one that
does not. That class takes the gate out of the path entirely, which is the whole
of what it means: the agent clones from and pushes to your real remote, and
nothing waits for you. It gets a key to do that through an agent socket, so the
private key stays in the vault — store one with
`sokar vault put ssh.default`, taking a base64 Ed25519 seed, and add the public
key (`sokar vault agent --print-public-key`) to your forge.

The class belongs to the project. A task cannot raise it from the command line.

To add your own tooling to the image, see [your tooling](your-tooling.md).

## 5. Run a task

```
sokar task run
```

By default it starts the agent for you and leaves a shell behind when the agent
exits, so the workspace is still there to look at and its work can still be pushed
by hand. `--attach shell` skips the agent and gives you the shell straight away.

It builds the image, starts the container, and reports what it wired up:

```
task           shell
project        myproject
security class guarded
base image     ubuntu:24.04
agent     claude 2.1.236
vault     /run/user/1000/sokar/sokar-myproject-shell-1669652/vault.sock -> https://api.anthropic.com
token     ANTHROPIC_API_KEY=sokar_pt_lYyo...
provider  api.anthropic.com reachable; the credential is not, only a task-scoped token
policy    .../ruleset.nft
resolver  .../dns.conf (2 domains)
container sokar-myproject-shell-1669652
started   yes
gate      http://host.containers.internal:38359/myproject.git
workspace /workspace ready
```

Four of those lines are the security model, so they are worth reading rather than
scrolling past:

- **`token`** — the agent gets a phantom token, not your key. It is scoped to this
  one task and stops working when the task ends.
- **`vault`** — the socket that swaps that token for the real credential on the way
  out. Your key never enters the container.
- **`denied`** — the provider's own host is removed from the firewall, so an agent
  that tries to go around the socket is blocked rather than quietly leaking the
  token.
- **`gate`** — where the agent pushes its work, on your machine.

You are now in a shell inside the container. The container is removed when you
leave; `--keep` keeps it. `-P "your prompt"` runs the agent headlessly instead of
giving you a shell.

**A task that fails is kept without being asked.** You cannot know in advance which
run you will want to look at, so a non-zero exit stops the container instead of
removing it — workspace, logs and unpushed commits all still there. `sokar task list`
shows it, `sokar task resume <name>` puts you back inside, and
`sokar task stop <name> --purge` discards it. A purge also says how many files the
agent had installed inside the container, because those have nowhere to go and
nothing else records that they existed.

**If something is going wrong and you do not yet know what**, `sokar panic` stops
every running task and every helper it started. It takes no names, and it removes
nothing: everything can be resumed afterwards.

## 6. Hand the work back

`/workspace` inside the container is a clone from the gate, with a remote already
configured:

```
cd /workspace
git add -A && git commit -m "what the agent did"
git push sokar HEAD:"$SOKAR_TASK_REF"
```

`$SOKAR_TASK_REF` is set for you. The push lands under `refs/sokar/incoming/`,
never on a branch, so nothing you are looking at moves underneath you.

## 7. Review it

Back on the node, in the project directory:

```
sokar gate pending
```

lists what is waiting:

```
NAME                 WAITING    COMMIT     SUBJECT
shell                0m         a466348    agent: add report
```

```
sokar gate review shell      # the full diff
sokar gate reject shell      # discard it
sokar gate approve shell --upstream git@github.com:you/myproject.git
```

`approve` is the only command that sends anything anywhere, and it needs the
upstream named. It refuses outright in an `offline` project. `--branch` picks the
upstream branch; the default is `main`.

## When something is blocked

Egress is default-deny, and it fails one layer earlier than most people expect. The
task's resolver answers **NXDOMAIN for every name that is not declared**, so an
undeclared host does not resolve, no connection is attempted, and nothing is dropped.
Inside the container it looks like this, and there is no prompt because there is
nothing to ask about:

```
Could not resolve host: repo.maven.apache.org
```

The clearance prompt is for the other case: a name that *did* resolve, to an address
the firewall does not yet allow — a bare IP address, or a declared domain whose answer
arrived before the firewall was told. Then the connection is dropped and you get a
desktop notification with Allow and Deny. You are asked once per destination and never
asked again in either direction, so an agent cannot keep retrying until you click the
wrong thing.

**If the host is one your build legitimately needs, declare it** rather than clicking
Allow — `egress.sets` or `egress.domains` in `project.yml`, above. A clearance is for one
address on one run; the declaration is reviewed, diffable, and applies to everyone who
clones the repository.

`--clearance` changes that for one task: `prompt` (the default), `allow`, `deny`,
or `off`. **On a machine with no desktop session, use one of the other three** —
`prompt` needs a session bus and will not start without one.

Everything dropped is written to `events.jsonl` in the task's state directory,
whether or not anyone is watching, so the audit trail does not depend on you being
at the keyboard.

What you decided is written to `~/.local/state/sokar/clearance/<container>.jsonl`,
including the questions nobody answered. That file outlives the task - the state
directory goes when the task is removed, this does not - and a resumed task reads it
back, so nothing you have already answered is asked a second time.

## Where things are

| Path | What |
|---|---|
| `/run/user/<uid>/sokar/<container>/` | one task's state: firewall ruleset, resolver config, `events.jsonl`, `vault.log`, `clearance.log` |
| `~/.local/state/sokar/clearance/<container>.jsonl` | what that task was allowed and refused, kept after the task is gone |
| `~/.local/share/sokar/vault.bin` | the encrypted credential store |
| `~/.local/share/sokar/mirrors/<project>.git` | the gate's mirror, where pushes wait |
| `~/.local/share/sokar/build/<project>/` | the generated `Containerfile`, meant to be read |
| `~/.config/containers/oci/hooks.d/` | the hook descriptors `sokar setup` wrote |

The state directory is under `/run`, so the kernel clears it when your session
ends and no stale task state can be picked up by a later run.

## Removing Sokar

**The order matters, and one step has to come first.**

```
sokar setup --uninstall
```

That takes back the six hook descriptors and the podman drop-in. **Do it before removing the
package**: those files live in your home, podman reads them per user, and no package can
remove them - so uninstalling the package first leaves them behind pointing at binaries that
are gone, with nothing left to run that would clean them up. It names each file it removes,
and they are not all in one directory.

Then the packages:

```
sudo apt purge sokar 'sokar-agent-*'
```

**What is left after that is yours, and nothing removes it for you:**

| Path | What |
|---|---|
| `~/.local/share/sokar/vault.bin` | your credentials. **There is no recovering this** |
| `~/.local/share/sokar/mirrors/` | the gate's mirrors, including any push nobody reviewed |
| `~/.local/share/sokar/projects/` | which project files this machine knows about |
| `~/.local/share/sokar/agents/` | agents you installed yourself |

The mirrors are the ones to look at before deleting anything: an unreviewed push exists only
there, and `sokar gate pending` still answers while the package is installed.
