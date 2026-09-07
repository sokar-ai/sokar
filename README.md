# Sokar

**Sandboxing AI agents in YOLO mode using Podman and native Java/GraalVM.**

<img align="left" height="400" width="260" src="doc/sokar-400.png" alt="Sokar with AI agent in podman">

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
Why build this if Terok is so cool? See [why](doc/why.md)

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
> This is the quick path for the case where **Claude Code is already installed and signed
> in on the host you run Sokar from** — `vault import` copies the credential it is holding,
> whether that is an API key or a subscription token, so nothing is retyped.

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

# The OCI hooks, once per user. The package deliberately does not do this: podman reads
# hook descriptors per user, so a system-wide install would fire them for every container.
sokar setup

# Your credential, copied from the Claude Code already installed on this host - so no key
# goes through your shell or your history. The first unlock sets the vault passphrase.
# It never enters the container: the agent gets a task-scoped token, and a proxy swaps in
# the real key on the way out.
sokar vault unlock
sokar vault import claude

# Run one. With no project.yml here it offers to write one, taking the project
# name from this directory - Enter accepts every default. What it writes includes an
# 'egress' block: a task reaches only what the file names, so add 'maven', 'node' or
# whatever your build needs. 'sokar shield sets' lists them.
sokar task run
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

# The OCI hooks, once per user. The package deliberately does not do this: podman reads
# hook descriptors per user, so a system-wide install would fire them for every container.
sokar setup

# SELinux. Without this a task container is denied connectto on its own vault socket,
# the denial is dontaudit'ed, and it looks like an agent that cannot authenticate.
sudo /usr/share/sokar/selinux/install-selinux-policy.sh

# Your credential, copied from the Claude Code already installed on this host - so no key
# goes through your shell or your history. The first unlock sets the vault passphrase.
# It never enters the container: the agent gets a task-scoped token, and a proxy swaps in
# the real key on the way out.
sokar vault unlock
sokar vault import claude

# Run one. With no project.yml here it offers to write one, taking the project
# name from this directory - Enter accepts every default. What it writes includes an
# 'egress' block: a task reaches only what the file names, so add 'maven', 'node' or
# whatever your build needs. 'sokar shield sets' lists them.
sokar task run
```

**No Claude Code on this host?** Then there is nothing to import, and the credential goes in
by hand — an API key from your provider's console, or a subscription token from the agent's
own login. Both are in the step-by-step guides, which also cover what each line above does and
what goes wrong when it is skipped:
[Debian and Ubuntu](doc/getting-started-debian.md) · [Fedora and RHEL](doc/getting-started-fedora.md).

## Not sure what any of that meant
See [Sokar for dummies](doc/sokar-for-dummies.md) — the hardening and the features explained point by point, assuming no prior knowledge.

## Questions
See [FAQ](doc/faq.md).

## Adding your tools to a container
See [your tooling](doc/your-tooling.md).

## Adding a new agent
See [Onboarding a new agent](agents/README.md#onboarding-a-new-agent)

## What is planned
See [requirements](requirements/README.md).

## Building the project
See [build](doc/build.md).

## Licence
GNU General Public License v3.0 or later — see [LICENSE](LICENSE).

-----

> [!NOTE]  
> <img src="doc/ai-powered.svg" alt="A little robot peeking out of its sandbox" align="left" height="62">
> This project is fundamentally powered by AI.
> <br clear="left"/>
