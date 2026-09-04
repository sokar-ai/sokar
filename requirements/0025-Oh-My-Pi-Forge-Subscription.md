# 0025 — Oh My Pi Forge Subscription

**Status:** open, and split in two. The agent is built first against an API-key provider
that can be tested today ([OpenRouter](0021-More-Agents-Providers.md)); the forge
subscription with its browser sign-in follows. An agent that only works with the awkward
credential proves nothing about the agent.

The second agent to build, chosen deliberately rather than by convenience. It is a
provider-agnostic agent authenticating against a forge's own subscription over a
browser sign-in, which exercises three things the first agent never touched:

- **a credential belonging to a provider, not to the agent** - the vault is keyed by
  agent name today, and this is the first case where that is wrong;
- **a browser sign-in**, which cannot happen inside a box that has no browser and no
  path to the operator's desktop, so the credential must be obtained on the host and
  imported;
- **a short-lived token**, which is where [0024](0024-Refreshable-Task-Tokens.md)
  stops being theoretical.

Picking the awkward combination on purpose: an agent that is easy to add proves
only that an easy agent can be added.

## Acceptance

- The agent installs as its own package and Sokar needs no change to discover it.
- The credential is obtained on the host, stored, and never entered in the box.
- A task authenticates without the agent asking anyone to sign in.
- The real credential is not in the container, checked the same way as for the first
  agent.
- Both agents are installed at once and neither disturbs the other.

## Measured, 2026-09-04

Pi 0.85.0, installed from npm as `@earendil-works/pi-coding-agent`, run in a container and
asked rather than read about:

| | |
|---|---|
| headless | `pi --print --mode json --model <m> --provider <p> "<prompt>"`, prompt positional |
| sessions | `--session`, `--continue`, `--resume` |
| OpenRouter | known natively; wants `OPENROUTER_API_KEY` |
| readiness | `pi auth check --provider <p> --json` answers machine-readably |
| config | `~/.pi/agent/`, holding `auth.json` and `models-store.json` |

**Its endpoint cannot be redirected by an environment variable.** Only Azure has a base-URL
variable; every other provider's endpoint is fixed unless an extension overrides it:

```typescript
pi.registerProvider("openrouter", { baseUrl: "…/api/v1" });
```

Extensions are auto-discovered from `~/.pi/agent/extensions/*.ts`, so this is a file to
place and not a command to run - which the container-setup interface the first agent
introduced can already do.

**The endpoint has to be a URL, and that is the hard part.** The broker binds a unix
socket; a `baseUrl` is an HTTP address. Measured on the same machine:

```
host itself                                : 200
container -> host loopback via 169.254.1.2 : refused
container -> its own 127.0.0.1             : refused
```

A host-side listener is therefore either unreachable or bound to every interface, which is
the unsolved problem in [0020](0020-Narrow-The-Git-Endpoint.md) and not one to repeat. The
endpoint has to be served **inside the container's network namespace**.

## Design

- **The agent declares what shape of endpoint it needs**, socket or URL. Claude keeps the
  socket it already uses; an agent that can only address a URL says so in its own
  definition, where the difference is visible rather than buried in Sokar.
- **A URL endpoint is bound into the container's namespace from the host**, the way the
  ruleset and the resolver already are. The proxy process stays on the host with the vault;
  only its listening socket lives in the container. Nothing is exposed on the host or the
  network, and no forwarder has to exist inside the image.
- Such a proxy needs the container to exist first, so it starts after it - which the helper
  phases added for resume already express.
- **Consequence to accept:** a proxy listening in that namespace also sends from it, so its
  call to the provider is subject to the task's own firewall rather than the host's. The
  provider's domain is allowed anyway, but that moves where the broker's egress is
  governed, and it should be moved deliberately rather than noticed later.

## To be checked

- **Whether this agent's endpoint can be redirected for this provider.** It is
  documented for the common API dialect; a forge subscription may not use it.
- Whether the sign-in yields something storable at all, or only a session belonging
  to a browser profile.
- How long the token lasts. If it is shorter than a task, [0024](0024-Refreshable-Task-Tokens.md)
  is a prerequisite rather than a follow-up.

## Notes

The survey this was chosen from, and the matrix of what else exists, is
[0021](0021-More-Agents-Providers.md).
