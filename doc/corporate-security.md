# Why you should use Sokar: a guide for corporate security

For the person who decides whether a coding agent may run in an organisation. It answers the usual
questions, and says where Sokar does not help.

## The problem Sokar solves

A coding agent reads and changes files, runs commands, and sends workspace content to a model
provider. Normally its safety rests on **its own configuration**, which the developer controls. That
is why many organisations refuse such agents.

**Sokar moves the enforcement out of the agent.** The agent runs in a hardened, rootless container. Its
network, credentials and outputs are controlled from outside, by the project's configuration and the
person running Sokar. How the agent is configured no longer matters.

**Sokar holds the agent, not the developer.** The developer controls Sokar on their own machine. To
hold the developer too, use device policy, as for any other software.

## The questions, answered

### Which data does the agent process, and where does it go?

- **It sees one repository**, cloned inside its own container. Not the home directory, other projects,
  ssh keys or credentials. No private key enters the container; an `online` task signs through a
  socket to a key in the host's vault.
- **Prompts and content go to the model provider**, and to whatever the task is allowed to reach.

### Does it use only the approved AI services?

- **Outgoing network is denied by default.** A task resolves only the names its agent and provider
  need, the `upstream` of an `online` project, and what the project's `egress` names. Any other
  provider is unreachable, whatever the agent is configured to use.
- **The person starting a task chooses the provider.** Sokar has no list of providers an organisation
  approved; that is device policy today.
- **The agent never holds a provider credential.** A broker on the host adds the real key. The
  container holds a token worthless elsewhere, cannot mint its own, and never sees a credential in an
  answer. See [authentication](credentials.md).
- **A followed project's configuration is signed** by its maintainers and checked on every machine. A
  project followed `--unverified` is marked so wherever shown. The built-in project `default` has fixed
  settings.

### Telemetry, crash reports, analytics?

- **Blocked by default**: a name that is not allowed does not resolve.
- **Visible**: `sokar task logs` shows every name the task asked for, allowed or refused. One run shows
  which endpoints an agent tries to contact.

### Can it be locked down so a developer cannot override it?

- **The agent, yes.** Nothing inside the container can widen its network, take a credential, or push
  past the gate.
- **The developer, no.** They can choose the provider, follow an unsigned project, allow a blocked
  connection, widen a running task's network, give one run a credential, or run the agent without
  Sokar. Requiring Sokar is a matter of device policy.

### Does the agent get more rights than today's tools?

Fewer: no host access, no real credentials, only allowed destinations, and in a `guarded` project every
change is reviewed before it leaves. Anything the agent starts (a browser, a language server) runs in
the same box.

### Approval per command, or none?

Sokar is built for agents running without per-command approval; the box replaces it. A person stays in
control at three points the agent cannot switch off:

1. **The gate.** In a `guarded` project, work leaves only after a person reviews it, riskiest changes
   first. `offline` work never leaves; an `online` project pushes itself, by choice. See
   [security classes](security.md).
2. **Clearance prompts.** A connection to an address not yet allowed waits for a person, unless the
   task refuses such connections (as every task in `default` does).
3. **The message filter.** Messages between agents pass fixed rules (not another model) that hold back
   encoded payloads, credentials and the like. A person can still deliver a refused message, and that
   is recorded.

### Prompt injection

Sokar **does not stop an agent from reading** untrusted content - web pages, READMEs, dependencies,
logs. **It limits what an injected agent can do:**

| Concern | In Sokar |
|---|---|
| Trust boundary | the container; credentials stay on the host |
| Tool gating | network denied by default; names only from the agent's and provider's needs and the project's configuration |
| Confirmation before network access | a clearance prompt for an address not yet allowed; an undeclared name does not resolve |
| Human in the loop on exfiltration | the gate for code, the filter for messages |

**What remains:** data sent to an allowed destination - above all the model provider, and any MCP
server the project names. [How far a task can get](reach.md) lists every bound and every gap.

### Tool poisoning and MCP rug pulls

**Sokar limits the damage but does not detect the attack.** A poisoned tool cannot take a credential,
reach an undeclared host or slip a change past review. But Sokar does not read MCP tool definitions,
so a misleading description, or a server that changes its tools later, goes unnoticed. See the
[FAQ](faq.md#how-is-sokar-protected-against-tool-poisoning-an-mcp-servers-rug-pull).

## What Sokar does not do

- **It does not replace device management.** An agent run outside Sokar is unprotected, and the
  developer controls Sokar on their own machine.
- **It does not keep a list of approved providers**; the provider is chosen per task.
- **It does not stop prompt injection**; it limits the consequences.
- **It does not inspect MCP tool definitions.**
- **It does not limit spending.** A task can use whatever the provider account allows.
- **It works only with providers Sokar can broker**: an API key, or a subscription sign-in that the
  broker [renews on the host](credentials.md#a-sign-in-that-ends-renewed-on-the-host).

## In one sentence

Sokar lets an organisation allow a capable coding agent without trusting its configuration: the agent
runs in a box it cannot change, credentials never enter it, the network is closed by default, and in a
`guarded` project nothing leaves without a person's review.
