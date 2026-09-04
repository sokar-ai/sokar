# 0021 — Second Agent

**Status:** open

The architecture claims an agent is a package rather than a patch. One agent does
not demonstrate that; two do.

## Acceptance

- The second agent ships as its own binary and its own package.
- Nothing outside the agent directory names it, enforced by the build.
- Installing it makes it usable without rebuilding or reinstalling anything else.
- Its credential kinds and destinations are declared, not coded.

## Notes

The enforcement already exists and already fails the build when violated. This
requirement is about exercising it.

## Candidates

Two axes that are easy to conflate: the **agent** is the harness that runs in the
box, the **provider** is who serves the model. Some agents are tied to one
provider, some are deliberately not, and the difference decides how much of the
credential machinery can be reused.

The last column is the one that matters most here. Brokering only works if the
agent can be pointed at a different endpoint - that is what lets a task hold a
task-scoped token while the real credential stays on the host. An agent that can
only talk to its vendor's own endpoint cannot be brokered at all, and would have to
be given the real credential or not supported.

| Agent | Provider(s) | Tied to one? | Authentication | Redirectable endpoint? |
|---|---|---|---|---|
| Claude Code | Anthropic; also Bedrock, Vertex, Foundry | model yes, endpoint no | subscription token or API key | **yes** - base URL and a unix socket, both verified |
| Codex CLI | OpenAI | yes | account sign-in or API key | likely, unverified |
| Gemini CLI | Google | yes | account sign-in or API key | likely, unverified |
| GitHub Copilot CLI | GitHub Copilot, which fronts several models | yes | forge account, device flow | unlikely, unverified |
| OpenCode | many, chosen per session | **no** | per provider: pasted key in one store, browser sign-in, or env var | **documented** - a base URL per provider |
| Oh My Pi | many, 40+ | **no** | per provider: env var, stored key, or sign-in with refresh | yes for common API dialects, by design |

Ordered by reported usage among professional developers, except the last, which is
included because it is provider-agnostic and small enough to be a fair test of the
onboarding path.

Only the first row is established: everything in it was measured while building the
first agent. Every "unverified" is a claim to check before committing to that agent,
not a plan.

## What a provider-agnostic agent already models

The agents in the lower half of the table treat the provider as a first-class thing
of its own, separate from the agent, and that shape is worth adopting rather than
rediscovering:

- **A provider carries its own identity, default model, and credential lookup** -
  typically an ordered list of environment variables to fall back through.
- **Authentication is per provider and comes in three shapes**: an environment
  variable, a stored static key, and an interactive sign-in that yields a token
  **plus a refresh flow**.
- **The endpoint is overridable per provider**, at least for the ones speaking a
  common API dialect. That is the property brokering depends on, and it exists by
  design there rather than by accident.
- **Adding one is a declaration plus a registry entry**, not a change to the agent.
- **Credentials land in one store** rather than one file per provider, and at least
  one agent lets a stored value be written as a reference to an environment variable
  instead of a literal. That indirection is the cleanest thing Sokar could ask for:
  no file to seed, no format to imitate, just a variable already being injected.

Two of those land directly on Sokar's own design:

- The vault is keyed by **agent** name. An agent that talks to several providers
  needs one credential per provider, so the key has to become agent *and* provider.
  Every agent supported so far has hidden this by having exactly one.
- A **refresh flow is a problem for a task-scoped token**. An agent holding what it
  believes is an expiring credential will try to renew it, and a phantom token
  cannot be renewed by anyone but the broker that minted it. Either the proxy
  answers refresh as well as use, or the token must be presented in a form the agent
  will not try to refresh.

## To be checked

- **Which of these can actually be redirected**, and how. It is the single fact that
  decides whether an agent can be brokered, and it is not reliably documented -
  for the one agent already supported it took a local listener and a raw socket to
  establish, and two wrong conclusions before that.
- An agent that authenticates by account sign-in rather than by a key may verify the
  session with its vendor before use. That check does not go through a redirected
  endpoint, so it has to be reachable, which widens what the box may contact.
- Token refresh, which is its own requirement now:
  [0024](0024-Refreshable-Task-Tokens.md). Unanswered, it limits the candidates to
  those authenticating with a static key.
