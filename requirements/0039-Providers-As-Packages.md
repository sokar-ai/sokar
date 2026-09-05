# 0039 — Providers As Packages

**Status:** open, but no longer ahead of the evidence. The trigger this was waiting for -
a second agent reaching a provider the first does not - is [0025](0025-Pi-Forge-Subscription.md),
now being built. The extraction should follow it rather than precede it, from two real
implementations rather than one.

An agent declares its provider inline today: one upstream, one auth header, one
prefix per credential kind, all inside the agent's own definition. With one agent
that is right. With several it stops being right, because the same provider is then
described in as many places as there are agents that reach it, and the descriptions
drift.

The proposal is to make a provider what an agent already is: its own declaration,
its own package, discovered by a directory scan, naming itself. An agent then says
which providers it can drive rather than restating each one.

## What belongs to which

The split is not obvious and getting it wrong is the main risk, so it is written
down before anything is built:

| Knowledge | Belongs to | Why |
|---|---|---|
| how a session is started, what a fresh container must be told | the **agent** | its own first-run behaviour, nothing to do with who serves the model |
| which command line runs a prompt, how output is formatted | the **agent** | its interface |
| upstream endpoint, auth header, value prefix | the **provider** | the same for every agent reaching it |
| how a credential is obtained, stored, and renewed | the **provider** | a sign-in belongs to whoever the account is with |
| whether the endpoint can be redirected | **both** | the provider must offer it and the agent must honour it |

The last row is why this is not a clean cut. Redirection is a property of the pair,
not of either side, and the pair is what a task actually runs.

## Acceptance

- A provider is declared once and used by more than one agent without being restated.
- Nothing outside a provider's own directory names it, enforced the way agent names
  already are.
- An agent that drives several providers gets one credential per provider, and the
  vault can express that.
- The broker forwards to the provider the task chose, not to a constant compiled
  into an agent.
- Adding a provider needs no change to Sokar and no rebuild of any agent.

## Notes

Evidence this is real rather than tidy-minded: a provider serving a compatible
dialect ([0038](0038-Provider-Zhipu.md)) is already reachable only by making the
broker's upstream a variable, and two of the candidate agents authenticate per
provider rather than per agent ([0031](0031-Agent-OpenCode.md),
[0032](0032-Agent-Pi.md)).

There is already one place where the two are mixed: what the first agent writes into
a fresh container is partly its own first-run state and partly the shape its
provider expects a stored credential to have. Those are two different things in one
file.

## What is actually data, measured 2026-09-04

The first agent's hand-written Java is 303 lines, and it does not divide evenly:

| file | lines | shape |
|---|---|---|
| `ClaudeStreamJsonFormatter` | 87 | real logic - parsing a stream format |
| `ClaudeCredentialExtractor` | 86 | a path and a field name in the host's file |
| `ClaudeContainerSetup` | 73 | two file templates with a token substituted |
| `ClaudeAgent` | 50 | wiring |

Around 160 of those lines say "put this value in this field of this file". `credentials()`
is `{"apiKey": token}` or `{"claudeAiOauth": {"accessToken": token, ...}}` chosen by
credential kind - data wearing a method. Only the formatter is genuinely code, and it
belongs to the agent rather than the provider in any case.

So a declarative form is plausible: a provider names the variable it wants, and the file it
expects a credential in, with its path, format and placeholder. That is the candidate shape
to test - **against the second agent, not the first**. Designing it now would encode one
agent's assumptions: JSON, a single token, two files, a fabricated far-future expiry. Pi
already breaks two of those - its endpoint is redirected by a TypeScript extension it
auto-discovers, not by a variable or a credential file.

## The mixing, named

`ClaudeContainerSetup` writes two files for two different owners: `.claude.json` is the
agent's own first-run state - onboarding answered, workspace trusted - while
`.credentials.json` is the shape *the provider* expects a stored credential to have. One
class, two owners. Splitting those is worth doing on its own, before any package boundary
exists, because it makes the eventual boundary obvious rather than arbitrary.

## The endpoint belongs to the pair, and now there is proof

The table above says redirection is a property of both sides. [0025](0025-Pi-Forge-Subscription.md)
shows what that costs in practice: one agent takes a socket path in a variable, the other can
only address a URL and needs a listener bound inside its container's namespace. The provider
is the same in both cases. So a provider declaration cannot carry "how to reach it" alone -
the agent has to declare what shape of endpoint it can use, and Sokar satisfies it.

## To be checked

- **Whether the duplication is real yet.** With one agent it is not. The first case
  where two agents reach the same provider is [0025](0025-Pi-Forge-Subscription.md);
  until it exists, this is abstraction ahead of evidence, and doing it early would
  be guessing at a boundary rather than observing one.
- Whether a provider needs to be a separate *package* or only a separate
  *declaration*. A package buys independent versioning and release, which is what
  the agent split was for; it also doubles the number of things to install.
- Whether an agent can drive a provider it has never heard of, given only a
  declaration, or whether each pairing needs something written by hand.
