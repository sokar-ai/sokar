# 0025 — Oh My Pi Forge Subscription

**Status:** the first half is **built and verified**. Pi runs as a packaged agent against
OpenRouter, answers a real prompt, and the credential never enters the container. The forge
subscription with its browser sign-in is the remaining half.

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

## Design, as built

- **The agent declares what shape of endpoint it can address**, socket or URL. Claude keeps
  the socket; Pi says `url`, and the difference is visible in the definition rather than
  buried in Sokar.
- **Only the listening end moves into the task's namespace.** A relay
  (`sokar vault relay`) binds `127.0.0.1:9419` inside the container's network namespace and
  forwards to the broker's socket on the host. It resolves nothing and connects to nothing
  but a local file.
- **The broker stays on the host**, with the host's resolver, the host's egress and the
  credential.
- **The tool is shipped in the agent's own package** rather than downloaded: 162 npm
  packages and a Node runtime have no single URL to pin, so verification happens once where
  the package is built - against a lockfile pinning every dependency by integrity hash - and
  the image build fetches nothing.
- **Pi is redirected by a file, not a variable.** An extension in
  `~/.pi/agent/extensions` calls `registerProvider` with the base URL and the task token;
  Sokar passes the endpoint to the agent and the agent decides the shape.

### What was tried first and does not work

**Binding the broker itself inside the task's namespace.** It keeps the host's *mount*
namespace, so it reads the host's `/etc/resolv.conf` and tries a resolver that does not
exist there; every request failed as "could not reach the provider". Its egress would also
have been governed by the task's own firewall rather than the host's. The relay has neither
problem, and is the reason the earlier version of this section was wrong.

## Measured, 2026-09-04 (second pass)

Four failures found by running it, none of them visible to unit tests:

| what failed | why |
|---|---|
| the broker could not reach the provider | it was in the task's network namespace with the host's `resolv.conf` |
| the container held a token nothing could redeem | two brokers minted two tokens; the environment got the dead one |
| Pi got a connection refused | `localhost` resolves to `::1` first for Node; the relay binds IPv4. The endpoint is now a literal address |
| the relay could not reach the broker's socket | `podman unshare` runs as `container_runtime_t`, which the policy did not grant `connectto` |

The last one corrects a claim made when the policy was written: that Sokar needed only two
domains because every socket is bound by a host process the operator started. True of
binding, false of connecting.

## To be checked

- ~~Whether this agent's endpoint can be redirected for this provider.~~ **Answered for
  OpenRouter:** yes, by an extension rather than a variable. Still open for a forge
  subscription, which may not use the same dialect.
- Whether the sign-in yields something storable at all, or only a session belonging
  to a browser profile.
- How long the token lasts. If it is shorter than a task, [0024](0024-Refreshable-Task-Tokens.md)
  is a prerequisite rather than a follow-up.

## Notes

The survey this was chosen from, and the matrix of what else exists, is
[0021](0021-More-Agents-Providers.md).
