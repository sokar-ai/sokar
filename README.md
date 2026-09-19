# Sokar

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

<img align="left" height="400" width="260" src="doc/images/sokar-400.png" alt="Sokar with AI agent in podman">

Normally, an AI agent will stop and ask you: "Can I edit this file?" or "Can I run this terminal command?". 
In YOLO mode, it skips those questions and directly modifies code, deletes files, installs packages, 
or runs terminal scripts completely unattended.

Sokar makes that safe anyway: It runs each agent inside a hardened, rootless container with default-deny
outbound networking, a credential vault that keeps real keys on the host, a git checkpoint for every run, and
a desktop notification path for live allow/deny decisions.

Use the agent as you want: Work locally: interactive in a shell, or headless and unattended.

Supervise agents from a Flutter client — wherever they run :construction:

<br clear="left"/>

> [!NOTE]  
> **Sokar runs on Linux only.** The containment is kernel machinery — an nftables
> ruleset loaded into the container's network namespace, OCI hooks, user namespaces,
> the kernel keyring — none of which exists on macOS or Windows, where a container
> runtime would put all of it on the far side of a virtual machine.

### Hardening

- **Fail-closed egress** — a default-deny nftables ruleset is loaded into the container's network namespace before
  the workload runs, and if it cannot be loaded the container does not start
- **Nothing to escalate to** — every capability dropped and `no-new-privileges` set at create time, inside a rootless
  container whose agent is an unprivileged account
- **The key stays on the host** — the container holds a task-scoped phantom token, a proxy on a unix socket swaps it
  for the real credential, and the provider's own host is firewalled off so nothing can go around it; git signing
  works the same way, over an agent socket
- **Work leaves only through review** — an `offline` or `guarded` task pushes to a host-side mirror under
  `refs/sokar/incoming/`, and nothing reaches an upstream until you approve it
- **Recorded before anyone is asked** — drops land in a JSON-per-line audit file whether or not a prompt is running,
  and the desktop Allow/Deny appears once per destination and is never re-asked

### Features

- **A file beside your code** — `project.yml` names the base image, the security class, what the build may reach and
  anything else you want baked in; each task is a container built from it and thrown away afterwards
- **Your build can still fetch dependencies** — name what it needs and nothing else resolves: `egress: {sets: [maven]}`
  opens Maven Central on ports 80 and 443, `sokar shield sets` lists the rest, and an undeclared registry is `NXDOMAIN`
- **The security class belongs to the project** — `offline` forwards nothing upstream ever, `guarded` forwards only
  what you approve, `online` gives the agent the upstream directly, and no task can talk its way up
- **Interactive or unattended** — a shell by default, or `-P "…"` to run the agent headlessly and format what it says
- **Three image layers, the middle one pinned** — your base, then the agent's CLI fetched from a fixed URL and checked
  against a SHA-256, then your own lines
- **An agent is a package, not a patch** — its own binary and its own `.deb`/`.rpm`, discovered by a directory scan,
  with an ArchUnit test failing the build if anything in Sokar ever names one; Codex arrives the same way :construction:

> Sokar is inspired by [Terok AI](https://github.com/terok-ai/terok) — not a fork and not a port, but it owes that
project a great deal: the architecture, and a lot of hard-won knowledge about how podman, nftables and D-Bus actually
behave. Big kudos to its developers. If you are more at home in Python, use it. It's a cool project!
Why build this rather than use it? See [why](doc/why.md)

## Supported providers and agents

<table>
<tr>
<td valign="top">

<table>
<tr><th>Provider</th><th>State</th></tr>
<tr><td><a href="https://docs.anthropic.com">Anthropic</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://openrouter.ai">OpenRouter</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://platform.openai.com">OpenAI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://ai.google.dev">Google</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/features/copilot">GitHub Copilot</a></td><td>:construction:</td></tr>
<tr><td><a href="https://docs.x.ai">xAI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://z.ai">Zhipu</a></td><td>:construction:</td></tr>
</table>

</td>
<td valign="top">

<table>
<tr><th>Agent</th><th>State</th></tr>
<tr><td><a href="https://github.com/anthropics/claude-code">Claude Code</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://github.com/earendil-works/pi">Pi</a></td><td>:white_check_mark:</td></tr>
<tr><td><a href="https://github.com/openai/codex">Codex CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/google-gemini/gemini-cli">Gemini CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/github/copilot-cli">GitHub Copilot CLI</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/xai-org/grok-build">Grok Build</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/sst/opencode">OpenCode</a></td><td>:construction:</td></tr>
<tr><td><a href="https://github.com/can1357/oh-my-pi">Oh My Pi</a></td><td>:construction:</td></tr>
</table>

</td>
</tr>
</table>

## Getting started

Everything needed to go from nothing to a running agent. Paste it into the project you want
an agent to work on.

> [!NOTE]  
> **Nothing has to be installed on this machine first.** Sokar puts an agent's tooling into
> the task image rather than onto the node, so `vault login` starts **the agent's own login**
> in a throwaway container and collects what it produces. You still complete that login: the
> agent asks the questions, and since there is no browser in the container it prints a URL for
> you to open. Working over ssh? Forward the port it names, or the redirect has nowhere to
> land.
>
> **If Claude Code is already signed in here, use `vault import claude` instead.** It copies
> what that install is holding and logs in nowhere. `vault login` would start a second,
> independent authorization — the container has its own home and nothing local is touched by
> Sokar, but whether a second one invalidates the first is the provider's business.

**Debian and Ubuntu**

```sh
# What is needed to fetch the repository key and check it. Usually already there.
sudo apt install -y ca-certificates curl gnupg

# The key apt verifies the repository with, in the binary form apt wants at that path.
curl -fsSL https://fuinorg.jfrog.io/artifactory/api/security/keypair/sokar-packages/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/sokar.gpg

# Where to get Sokar, and which key must have signed it. 'snapshots' until there is a release.
echo "deb [signed-by=/usr/share/keyrings/sokar.gpg] https://fuinorg.jfrog.io/artifactory/sokar-dist-deb snapshots main" \
  | sudo tee /etc/apt/sources.list.d/sokar.list

# Two packages: the tool, and one agent. Sokar finds agents by scanning, so an agent needs
# no new release of Sokar - and podman, nftables and dnsmasq come along as dependencies.
sudo apt update
sudo apt install -y sokar sokar-agent-claude

# The OCI hooks, once per user. Starting a task does this itself if you skip it - the package
# cannot, because podman reads hook descriptors per user and root does not know whose.
sokar setup

# Whether this machine can actually do it, before anything is stored or started. Nine
# checks, and each failure names the one thing to do about it. Every one of them fails far
# from its cause otherwise: without nft a container comes up with no firewall, and a
# dnsmasq without nftset support opens nothing while resolving everything.
sokar doctor

# The vault, and a credential in it. The first unlock sets the passphrase.
#
# 'vault login' runs the agent's own login in a throwaway container and stores what it
# produces - so nothing has to be installed here first, and no key goes through your shell
# or your history. Already have Claude Code signed in on this machine? Then
# 'sokar vault import claude' copies what it is holding and is quicker. Have an API key
# instead? 'sokar vault put anthropic --type api-key' asks for it without echoing it.
#
# However it gets there, it never enters the container: the agent is given a task-scoped
# token, and a proxy swaps in the real key on the way out.
sokar vault unlock
sokar vault login claude

# Follow the project's repository. A project exists on a machine because the machine
# follows it - Sokar writes no project.yml anywhere. Somebody wrote that file in the
# repository and committed it.
sokar project follow <project> <git-url> --signed-by "ssh-ed25519 AAAA..."

# Then start a task in one of the repositories that project declares. -p names the
# project, -r the repository in it. Sokar never picks either.
sokar task start -p <project> -r <project>
```

**Fedora and RHEL**

```sh
# Where to get Sokar. 'snapshots' until there is a release. gpgcheck=0 because the
# repository metadata is signed but the RPMs themselves are not yet.
sudo tee /etc/yum.repos.d/sokar.repo >/dev/null <<'EOF'
[sokar]
name=Sokar
baseurl=https://fuinorg.jfrog.io/artifactory/sokar-dist-rpm/snapshots
enabled=1
gpgcheck=0
EOF

# Two packages: the tool, and one agent. Sokar finds agents by scanning, so an agent needs
# no new release of Sokar - and podman, nftables and dnsmasq come along as dependencies.
sudo dnf install -y sokar sokar-agent-claude

# The OCI hooks, once per user. Starting a task does this itself if you skip it - the package
# cannot, because podman reads hook descriptors per user and root does not know whose.
sokar setup

# SELinux. Without this a task container is denied connectto on its own vault socket,
# the denial is dontaudit'ed, and it looks like an agent that cannot authenticate.
sudo /usr/share/sokar/selinux/install-selinux-policy.sh

# Whether this machine can actually do it, before anything is stored or started. Nine
# checks, and each failure names the one thing to do about it. Every one of them fails far
# from its cause otherwise: without nft a container comes up with no firewall, and a
# dnsmasq without nftset support opens nothing while resolving everything.
sokar doctor

# The vault, and a credential in it. The first unlock sets the passphrase.
#
# 'vault login' runs the agent's own login in a throwaway container and stores what it
# produces - so nothing has to be installed here first, and no key goes through your shell
# or your history. Already have Claude Code signed in on this machine? Then
# 'sokar vault import claude' copies what it is holding and is quicker. Have an API key
# instead? 'sokar vault put anthropic --type api-key' asks for it without echoing it.
#
# However it gets there, it never enters the container: the agent is given a task-scoped
# token, and a proxy swaps in the real key on the way out.
sokar vault unlock
sokar vault login claude

# Follow the project's repository. A project exists on a machine because the machine
# follows it - Sokar writes no project.yml anywhere. Somebody wrote that file in the
# repository and committed it.
sokar project follow <project> <git-url> --signed-by "ssh-ed25519 AAAA..."

# Then start a task in one of the repositories that project declares. -p names the
# project, -r the repository in it. Sokar never picks either.
sokar task start -p <project> -r <project>
```

**No Claude Code on this host?** Then there is nothing to import, and the credential goes in
by hand — an API key from your provider's console, or a subscription token from the agent's
own login. Both are in the step-by-step guides, which also cover what each line above does and
what goes wrong when it is skipped:
[Debian and Ubuntu](doc/getting-started-debian.md) · [Fedora and RHEL](doc/getting-started-fedora.md).

## Every command, and what to type for a given job
See [the cheat sheet](doc/cheat-sheet.md) — arranged by what you are doing, not by the command
tree — and [commands](doc/commands.md) for the complete list with a line each.

## Everything a project file can say
See [the project file](doc/project-file.md) — one annotated example with every key, what it is
for, and what is deliberately not in it. You write the file once in the project's repository; this
is for
when you want to know what else is possible.

## What each security class actually does
See [the three security classes](doc/security-classes.md) — one picture each for `offline`,
`guarded` and `online`: who is involved, how work gets in and out, and where somebody reads it.

## Running the daemon, and reaching it from elsewhere
See [running the daemon](doc/daemon.md) — what `sokard` is for, what happens to its socket when it
stops or is killed, and the systemd **user** unit the packages install but do not enable.

## Where the network is actually cut off
See [the firewall](doc/firewall.md) — where a task's packet filter sits, who may change it, and why
it refuses to start the container when it cannot be loaded — and [DNS and the resolver](doc/dns.md),
the layer that says no first, before any traffic is attempted.

## Where credentials live, and what never holds one
See [authentication](doc/authentication.md) — what a container actually gets instead of your
credential, the three ways one reaches the vault, and how an API key or an OAuth login works when
you are not sitting at the machine.

## What the words mean
See [the glossary](doc/glossary.md) — node, project, task, agent, provider, gate, vault and the
rest, including the ones this product deliberately does not use.

## How this kind of tool is used at all, and where Sokar sits
See [three ways of working](doc/way-of-working.md) — what a tool puts at the centre, the project,
the agent or the person, and why that says more than how much the agent does on its own. Written
for somebody who has never used one.

## Not sure what any of that meant
See [Sokar for dummies](doc/sokar-for-dummies.md) — the hardening and the features explained point by point, assuming no prior knowledge.

## Questions
See [FAQ](doc/faq.md).

## Adding your tools to a container
See [your tooling](doc/your-tooling.md).

## Adding a new agent
See [Onboarding a new agent](agents/README.md#onboarding-a-new-agent)

## What is planned
See [requirements](issues/README.md).

## Building the project
See [build](doc/build.md).

## Licence
GNU General Public License v3.0 or later — see [LICENSE](LICENSE).

-----

> [!NOTE]  
> <img src="doc/images/ai-powered.svg" alt="A little robot peeking out of its sandbox" align="left" height="62">
> This project is fundamentally powered by AI.
> <br clear="left"/>
