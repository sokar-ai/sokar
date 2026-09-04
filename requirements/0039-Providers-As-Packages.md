# 0039 — Providers As Packages

**Status:** open

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
[0032](0032-Agent-Oh-My-Pi.md)).

There is already one place where the two are mixed: what the first agent writes into
a fresh container is partly its own first-run state and partly the shape its
provider expects a stored credential to have. Those are two different things in one
file.

## To be checked

- **Whether the duplication is real yet.** With one agent it is not. The first case
  where two agents reach the same provider is [0025](0025-Oh-My-Pi-Forge-Subscription.md);
  until it exists, this is abstraction ahead of evidence, and doing it early would
  be guessing at a boundary rather than observing one.
- Whether a provider needs to be a separate *package* or only a separate
  *declaration*. A package buys independent versioning and release, which is what
  the agent split was for; it also doubles the number of things to install.
- Whether an agent can drive a provider it has never heard of, given only a
  declaration, or whether each pairing needs something written by hand.
