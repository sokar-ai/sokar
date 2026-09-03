# Getting started

By the end of this you will have an agent working inside a hardened container,
with your real credential still on the host, and its work waiting for you to
review before it goes anywhere.

Every command below was run against a real installation while writing this. Where
something has a trap in it, the trap is named rather than left for you to find.

## Before you start

Rootless podman has to work as your own user — `podman info` should succeed
without `sudo`.

Sokar shells out to `podman`, `nft`, `dnsmasq`, `git` and `nsenter`, so those must
be installed on the host. The packages declare them as dependencies, so installing
the `.deb` or `.rpm` pulls them in; if you run from a build instead, install them
yourself.

## 1. Install

```
sudo apt install ./sokar_0.1.0~SNAPSHOT_amd64.deb ./sokar-agent-claude_1.0.0~SNAPSHOT_amd64.deb
```

or, on Fedora:

```
sudo dnf install ./sokar-0.1.0~SNAPSHOT-1.x86_64.rpm ./sokar-agent-claude-1.0.0~SNAPSHOT-1.x86_64.rpm
```

**Two packages, and that is the point.** `sokar` is the tool; an agent is a
separate package that Sokar discovers at runtime. Installing an agent needs no new
release of Sokar, and Sokar contains no reference to any agent. To build both, see
[build](build.md).

## 2. Register the hooks — once per user

```
sokar setup
```

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

**Unlock first, then store. In that order.**

```
sokar vault unlock
printf '%s' 'sk-ant-your-real-key' | sokar vault put claude
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
- `unlock` caches the passphrase in the kernel keyring for the rest of the
  session, so you type it once. `sokar vault unlock --forget` clears it.
- For automation, `--passphrase-command 'pass show sokar'` or
  `--systemd-credential <file>` replace the prompt entirely.
- The **name must be the agent's name** — `sokar agents` lists what is installed.
  Sokar looks the credential up by that name and by nothing else.
- Use `printf`, not `echo`: `echo` appends a newline, and the newline becomes part
  of your key.
- Never pass a credential as a command-line argument to anything. A command line
  is visible to every process on the machine. Standard input is not.

**Which credential?** An agent may accept more than one kind, and they are not
interchangeable — an API key and a subscription token go in different headers, and
sending one as the other fails in a way that looks exactly like a wrong key. Claude
Code takes an API key by default, or a subscription OAuth token if you add
`--credential-type oauth` to `sokar task run`. `sokar agents --verbose` shows what
each installed agent needs to reach.

## 4. Describe your project

`project.yml`, beside your code:

```yaml
project:
  name: "myproject"
  security_class: "guarded"
  # upstream: "git@github.com:you/myproject.git"   # required when security_class is online
image:
  base_image: "ubuntu:24.04"
```

`security_class` is one of:

| Class | The agent's remote | Review |
|---|---|---|
| `offline` | the gate, on this machine | nothing is ever forwarded upstream |
| `guarded` | the gate, on this machine | you review, then `approve` forwards |
| `online` | **the real upstream** | none — the agent pushes to it directly |

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

```
task           shell
project        myproject
security class guarded
base image     ubuntu:24.04
agent     claude 2.1.236
vault     /run/user/1000/sokar/sokar-myproject-shell-1669652/vault.sock -> https://api.anthropic.com
token     ANTHROPIC_API_KEY=sokar_pt_lYyo...
denied    api.anthropic.com (reachable only through the credential proxy)
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
