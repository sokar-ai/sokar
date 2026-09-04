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
| OpenCode | many, chosen per session | **no** | per-provider API keys | by construction, unverified |
| Oh My Pi | many, 40+ | **no** | per-provider API keys | by construction, unverified |

Ordered by reported usage among professional developers, except the last, which is
included because it is provider-agnostic and small enough to be a fair test of the
onboarding path.

Only the first row is established: everything in it was measured while building the
first agent. Every "unverified" is a claim to check before committing to that agent,
not a plan.

## To be checked

- **Which of these can actually be redirected**, and how. It is the single fact that
  decides whether an agent can be brokered, and it is not reliably documented -
  for the one agent already supported it took a local listener and a raw socket to
  establish, and two wrong conclusions before that.
- An agent that authenticates by account sign-in rather than by a key may verify the
  session with its vendor before use. That check does not go through a redirected
  endpoint, so it has to be reachable, which widens what the box may contact.
- A provider-agnostic agent needs one credential per provider rather than one per
  agent. The vault is keyed by agent name today, which does not express that.
