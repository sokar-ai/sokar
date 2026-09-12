# A01 — Pi Forge Subscription

**Status:** the first half is **built and verified**. Pi runs as a packaged agent against
OpenRouter, answers a real prompt, and the credential never enters the container. The forge
subscription with its browser sign-in is the remaining half.

The agent is [Pi](A04-Agent-Pi.md) - `earendil-works/pi`, not the separate project called
Oh My Pi ([A05](A05-Agent-Oh-My-Pi.md)), which these files named by mistake until
2026-09-05. Nothing built was affected: the module, the package and the definition always
said `pi` and always installed `@earendil-works/pi-coding-agent`.

The second agent to build, chosen deliberately rather than by convenience. It is a
provider-agnostic agent authenticating against a forge's own subscription over a
browser sign-in, which exercises three things the first agent never touched:

- **a credential belonging to a provider, not to the agent** - the vault is keyed by
  agent name today, and this is the first case where that is wrong;
- **a browser sign-in**, which cannot happen inside a box that has no browser and no
  path to the operator's desktop, so the credential must be obtained on the host and
  imported;
- **a short-lived token**, which is where [B01](../base/B01-Refreshable-Task-Tokens.md)
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

A host-side listener is therefore either unreachable or bound to every interface. The git gate
had the same problem and was solved by mapping the host's loopback into the containers Sokar
starts itself, but a broker is not a gate: it carries the operator's real credential, so it is
served **inside the container's network namespace** and never listens on the host at all.

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

## Measured, 2026-09-05 - a real sign-in with a personal subscription

Run by the operator on a VM, in a container, with the credential never leaving that machine.

**There are three sign-in modes, and only one of them needs a browser.** `copilot login --help`,
read after the fact:

| mode | what it does |
|---|---|
| `--web-flow` | loopback redirect with PKCE. Default **on a local desktop** |
| `--device-code` | a short code usable from any device. Default in **remote or headless environments** - SSH, dev containers, CI |
| `--with-token` | reads a token from standard input |

So the original "device flow" was half right and the correction "not a device flow" was half
wrong. Which one runs is detected, and **the detection was wrong in a container over SSH**: it
chose the web flow there and waited for a callback nothing could deliver. `--device-code` forces
the useful one.

**None of this needed solving the browser problem.** `--with-token` reads a credential from
standard input, which is exactly the host-side import this requirement was asking for, and it
means a task container never needs a browser, a tunnel or a callback listener at all. The web
flow was measured the hard way before that was found:

```
https://github.com/login/oauth/authorize?client_id=…&redirect_uri=http%3A%2F%2F127.0.0.1%3A40905%2Fcallback
    &scope=read:user+read:org+repo+gist+codespace&code_challenge=…&code_challenge_method=S256
```

It opens an HTTP server on a random high port and waits to be called back there. That matters:
a device flow prints a short code usable from any device, while this needs the browser and the
listener on the same host - so the sign-in cannot be done from anywhere but the machine running
the CLI, and the test itself only worked through an ssh tunnel. The PKCE challenge means the
callback must reach the same process that began the flow, so replaying the URL elsewhere does
not work either.

**What it stores, and it is storable.** One file, mode 0600, JSON with comments:

```
~/.copilot/config.json
  authTokens["https://github.com:<login>"].token   a 40-character gho_… token
```

That is the whole of it. No refresh token, no expiry field, nothing else secret in the
directory - the session store and state files hold none. The CLI warns that it is saved
somewhere insecure, which is accurate: it is plaintext in a file.

**Consequences, in order of how much they change:**

- **The credential is portable**, so obtaining it on the host and importing it is possible.
  This was the open question and the answer is yes.
- **Nothing here expires**, so [B01](../base/B01-Refreshable-Task-Tokens.md) is **not** a
  prerequisite for this provider, which is the opposite of what was assumed.
- **The sign-in does not have to happen in a task at all.** `--with-token` on standard input,
  and three environment variables that outrank anything stored - `COPILOT_GITHUB_TOKEN`,
  `GH_TOKEN`, `GITHUB_TOKEN`, in that order of precedence. The first of those is the hook a
  broker would use, and it is the same shape as the first agent's.
- **A fine-grained PAT with the "Copilot Requests" permission is accepted**, and is a much better
  credential to hold than the OAuth token: the sign-in yields one carrying `repo`, `gist`,
  `codespace` and `read:org`, while a scoped PAT carries only what Copilot needs. If Sokar
  supports this provider, that is the credential kind to ask for.

**Hosts contacted**, from a CONNECT log that sees names and not traffic:

| host | calls |
|---|---|
| `api.individual.githubcopilot.com` | 4 - the model API |
| `api.github.com` | 4 |
| `telemetry.individual.githubcopilot.com` | 2 |
| `exp.individual.githubcopilot.com` | 2 |
| `github.com` | 1 - the sign-in itself |

`telemetry.` is a refused-domain candidate, the same call as the first agent's Datadog intake.

**The two-stage shape, now measured but still not proven.** A second run on a desktop VM, one
real prompt through a CONNECT-logging proxy:

| host | calls |
|---|---|
| `api.github.com` | 2 |
| `api.individual.githubcopilot.com` | 2 |
| `telemetry.individual.githubcopilot.com` | 2 |

The model API is never reached without `api.github.com` being called the same number of times,
and **nothing new is written to disk**: after the prompt, `config.json` still holds exactly one
`gho_` token and no cache file contains anything token- or expiry-shaped. So whatever comes back
from that exchange lives in memory for the length of a session.

Request paths were not captured - that needs a TLS intercept, which was not worth doing to
somebody's personal account - so this remains strong evidence rather than proof.

**What it means for the broker, if it holds.** The credential Sokar swaps in is presented at
`api.github.com`, not at the model API. The short-lived token that comes back is minted for the
agent and held by it, so a task would hold a real credential - scoped to Copilot and short-lived,
but real - for the requests that actually cost money. That is weaker than the property every
other provider gives, where the container never holds anything but a phantom token, and it has
to be stated plainly rather than discovered later.

Settling it needs the request path at `api.github.com`, which a disposable account can supply.

## To be checked

- ~~Whether this agent's endpoint can be redirected for this provider.~~ **Answered for
  OpenRouter:** yes, by an extension rather than a variable. Still open for a forge
  subscription, which may not use the same dialect.
- **Whether this agent reaches a forge subscription at all.** The requirement assumed it
  does and never checked. Oh My Pi advertises GitHub Copilot among its providers; Pi's own
  list does not say so. If it does not, [A05](A05-Agent-Oh-My-Pi.md) is the cheaper
  vehicle for this question than a new agent.
- ~~Whether the sign-in yields something storable at all.~~ **Answered:** a 40-character
  `gho_` token in a file. Storable and portable.
- ~~How long the token lasts.~~ **Answered:** nothing stored expires, so
  [B01](../base/B01-Refreshable-Task-Tokens.md) is not a prerequisite.
- **Where the broker sits.** The exchange is at `api.github.com` and the model API takes what
  it returns, so brokering the model API alone is not enough - and brokering the exchange leaves
  a real short-lived token in the container. Needs the request path to confirm.
- **Why the web flow runs twice.** Measured on a desktop: after the first sign-in completed and
  the token was stored, a second authorization opened by itself, on a different port. Both were
  the same client and the same shape. The CLI's own logs would say why, and they were deleted
  with the credential before anyone looked - so next time, keep `~/.copilot/logs/` first. Not on
  the critical path, since `--with-token` avoids the web flow entirely.
- Whether `COPILOT_GITHUB_TOKEN` is honored for a phantom token, which is the whole design if
  it is. Untested: it needs a credential, and the one used here was wiped.

## Notes

The survey this was chosen from, and the matrix of what else exists, is
[the comparison](../Agents-And-Providers-Compared.md).
