# Getting started

By the end of this you will have an agent working inside a hardened container,
with your real credential still on the host, and its work waiting for you to
review before it goes anywhere.

Every command below was run against a real installation while writing this. Where
something has a trap in it, the trap is named rather than left for you to find.

## Before you start

**Sokar runs on Linux only.** The containment is kernel machinery — an nftables
ruleset loaded into the container's network namespace, OCI hooks, user namespaces,
the kernel keyring — none of which exists on macOS or Windows, where a container
runtime would put all of it on the far side of a virtual machine.

Rootless podman has to work as your own user — `podman info` should succeed
without `sudo`.

Sokar shells out to `podman`, `nft`, `dnsmasq`, `git` and `nsenter`, so those must
be installed on the host. The packages declare them as dependencies, so installing
the `.deb` or `.rpm` pulls them in; if you run from a build instead, install them
yourself.

## 1. Install

A build leaves the two packages in two different modules — Sokar's own under
`dist-deb/target`, the agent's under `agents/claude/target`. Collect them
somewhere readable first, then install them together, from the project root:

```
mkdir -p /tmp/sokar-pkgs
cp dist-deb/target/sokar_*.deb agents/claude/target/sokar-agent-claude_*.deb /tmp/sokar-pkgs/
sudo apt install /tmp/sokar-pkgs/*.deb
```

or, on Fedora:

```
mkdir -p /tmp/sokar-pkgs
cp dist-rpm/target/sokar-*.rpm agents/claude/target/sokar-agent-claude-*.rpm /tmp/sokar-pkgs/
sudo dnf install /tmp/sokar-pkgs/*.rpm
```

**Why the copy.** apt fetches even a local file as `_apt` — uid 42, group
`nogroup` — and that user cannot traverse a `0750` home directory. Point it
straight at `target/` and it says so:

```
N: Download is performed unsandboxed as root as file '/home/you/git/sokar/dist-deb/target/sokar_0.1.0~SNAPSHOT_amd64.deb' couldn't be accessed by user '_apt'. - pkgAcquire::Run (13: Permission denied)
```

It is a notice, not an error — apt falls back to reading the file as root and the
install goes through. But the sandbox it drops is worth keeping, and `/tmp` costs
nothing. dnf has no such sandbox; the copy is there so both routes read alike.

**Installing again after a rebuild.** A snapshot keeps the same version string, so
neither tool sees anything to do — apt says `sokar is already the newest version
(0.1.0~SNAPSHOT)`, dnf says `Nothing to do`, and you keep running the old binary
while believing you replaced it. On Debian one flag covers both the already-installed
and the not-yet-installed package:

```
sudo apt install --reinstall /tmp/sokar-pkgs/*.deb
```

On Fedora, remove first — `dnf reinstall` exits 0 but silently skips any package
that is not installed yet, which is how you end up with a fresh `sokar` and no agent:

```
sudo dnf remove -y sokar sokar-agent-claude
sudo dnf install /tmp/sokar-pkgs/*.rpm
```

**Two packages, and that is the point.** `sokar` is the tool; an agent is a
separate package that Sokar discovers at runtime. Installing an agent needs no new
release of Sokar, and Sokar contains no reference to any agent. To build both, see
[build](build.md).

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
> whole design exists to prevent. Authenticate on the host, once, and store the
> result below.

**First, get the credential — on the host.** Which one depends on how you pay:

| You have | Where the value comes from | Store it with |
|---|---|---|
| an API key | your provider's console, as a long `sk-…` string | `--type api-key` |
| a subscription | the agent's own login on this machine (see below) | `--type oauth` |

For a subscription there is no key to copy from a web page: the value is produced by
the agent's login, run **on the host**. For Claude Code that is `claude setup-token`,
which needs the agent installed on the host and prints a long-lived token to paste
below — see [the Claude Code guide](agents/claude/README.md). This is also why
logging in inside the container is both blocked and pointless: the value has to end
up in the vault, on the host, where the box cannot reach it.

**If the agent is already logged in here, import instead of typing.** An agent that
keeps its own credentials on this host can hand them over:

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
printf '%s' 'sk-ant-...' | sokar vault put anthropic --type api-key   # your real key here
```

The order is not a style preference. `vault put` reads the credential from
standard input, so it has nothing left to read a passphrase from — do it the other
way round and you get:

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
  session, so you type it once. `sokar vault unlock --forget` clears it.
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
sokar vault unlock --forget
mv ~/.local/share/sokar/vault.bin ~/.local/share/sokar/vault.bin.old
sokar vault unlock
printf '%s' 'sk-ant-your-real-key' | sokar vault put anthropic
```

`--forget` first, or a cached passphrase keeps being used ahead of anything you
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
[the Claude Code guide](agents/claude/README.md) for where to get each.
`sokar agents --verbose` shows what each installed agent needs to reach.

## 4. Describe your project

`project.yml`, beside your code:

```yaml
project:
  name: "myproject"
  security_class: "guarded"
  # upstream: "git@github.com:you/myproject.git"   # required by online; optional otherwise
image:
  base_image: "ubuntu:24.04"
limits:                     # optional; these are the defaults
  memory: "8g"              # "none" to opt out on purpose
  pids: 2048
  # cpus: "2.0"             # unset means no CPU limit
```

**Why the limits are there.** An agent in YOLO mode runs commands nobody reviewed,
so a runaway build is a normal outcome rather than an attack. `--pids-limit` also
contains a fork bomb; podman defaults it to 2048, and Sokar pins it so the
protection does not depend on a distribution default. Memory is the one that takes
the host down, so it is capped by default and a project raises it when it needs to.
CPU is left alone: starving a task only makes it slow.

Sokar also passes `--init`. The container's main process is `sleep infinity`, which
never calls `wait()`, so without an init every process the agent orphans would stay
a zombie holding a PID until the container died — and those count against the
process limit.

**What the gate is**, since the classes are defined in terms of it: a bare mirror
of your repository on your own machine, at
`~/.local/share/sokar/mirrors/<project>.git`, served to the container over HTTP.
Every request must carry a per-task token, which is what keeps it shut: while a
task runs the port is bound on all interfaces, so it is reachable from your local
network. Narrowing that is an open problem — see [AGENT.md](AGENT.md). The container's `origin` points at that mirror
rather than at your real remote, and a push lands in `refs/sokar/incoming/<task>`,
where it waits for you.

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
- **Seeding happens once.** `initialise` returns early if the mirror is already
  there, so later commits on the host do not flow in by themselves, and re-running
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

Back on the host, in the project directory:

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

Egress is default-deny, so the first time an agent reaches for a host it has not
declared, the connection is dropped and you get a desktop notification with Allow
and Deny. You are asked once per destination and never asked again in either
direction, so an agent cannot keep retrying until you click the wrong thing.

`--clearance` changes that for one task: `prompt` (the default), `allow`, `deny`,
or `off`. **On a machine with no desktop session, use one of the other three** —
`prompt` needs a session bus and will not start without one.

Everything dropped is written to `events.jsonl` in the task's state directory,
whether or not anyone is watching, so the audit trail does not depend on you being
at the keyboard.

## Where things are

| Path | What |
|---|---|
| `/run/user/<uid>/sokar/<container>/` | one task's state: firewall ruleset, resolver config, `events.jsonl`, `vault.log`, `clearance.log` |
| `~/.local/share/sokar/vault.bin` | the encrypted credential store |
| `~/.local/share/sokar/mirrors/<project>.git` | the gate's mirror, where pushes wait |
| `~/.local/share/sokar/build/<project>/` | the generated `Containerfile`, meant to be read |
| `~/.config/containers/oci/hooks.d/` | the hook descriptors `sokar setup` wrote |

The state directory is under `/run`, so the kernel clears it when your session
ends and no stale task state can be picked up by a later run.
