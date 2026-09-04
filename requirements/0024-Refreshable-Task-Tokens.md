# 0024 — Refreshable Task Tokens

**Status:** open

An agent given a credential it believes will expire tries to renew it. The token a
task holds is minted for that task and is not renewable by anyone but the broker
that minted it, so the renewal fails - and it fails as an authentication error,
which reads as a wrong credential rather than as a design limit.

Either the broker answers a renewal the way it answers a use, or the token is
presented in a form that no agent attempts to renew. Without one of the two, only
agents that authenticate with a static key can be supported, which excludes a large
part of the field.

## Acceptance

- An agent whose credential kind normally expires runs a task longer than that
  expiry without re-authenticating.
- A renewal attempt is answered or made unnecessary; it never reaches the provider
  carrying a task token.
- A failed renewal says what it is, rather than surfacing as a rejected credential.
- Nothing in the container's copy is usable after the task ends, whatever the
  renewal produced.
- The real credential is still never in the container, including during renewal.

## Notes

Where a token carries its own expiry, the value written into the container today is
given a distant one so nothing tries to renew it. That works and is not a design:
it is a guess that happens to hold, and it will stop holding the first time an agent
checks the expiry against the provider rather than against the clock.

Renewal is also where the credential is most exposed: it is the one exchange whose
answer is a *new* credential. Whatever answers it has to mint a task-scoped value
rather than pass one through.

Two shapes of renewal exist, and only one is interceptable. Some agents renew over
the same API path they use, which a redirected endpoint would cover. Others delegate
renewal to a separate tool on the machine - a cloud vendor's own CLI, say - which
never passes through the agent's endpoint at all and would have to be either present
in the container or answered another way.

## Measured

2026-09-04, on a machine where the same agent was in daily use on the host. A
subscription credential was imported into the vault in the morning; by the afternoon
a task using it failed with the provider reporting the token revoked. The host agent
had renewed its own credential in between, and the stored copy decayed with it.

Two things follow, and the second was not obvious:

- **The copy decays on the host's schedule, not the task's.** This is not only about
  an agent inside the box trying to renew. A credential can go stale while nothing
  is running at all, and the next task inherits a dead one.
- **The staleness warning added the same day caught it**, and said what to do,
  before the failure appeared. That is a detector, not a fix: it turns a confusing
  authentication error into an instruction, and nothing more.

For this credential kind, "import once" is therefore not a working model.

## To be checked

- **Which credential kinds actually expire in practice**, and over what period. A
  task usually outlives nothing; if every kind in use lasts longer than any task,
  this requirement is smaller than it looks and can wait.
- Whether a renewal exchange goes to the same endpoint as ordinary use. If it goes
  somewhere else, redirecting the agent does not put the broker in that path, and
  the answer has to come from somewhere else entirely. The agent already supported
  does exactly that for a different exchange: its start-up check contacts the
  provider directly, ignoring the endpoint it was given.
- Whether an agent can be told its credential does not expire, and whether that is
  honoured or merely recorded.
- **Whether the broker should read the host's credential at task start** rather than
  serve a copy stored earlier. It would end the decay for every credential an agent
  already holds on this machine, and would make the vault the place for credentials
  with no host agent - a service key, a signing key, a machine with no login - rather
  than the primary store. It also moves a live read into the path of every task,
  which is a cost and a new failure mode.
- Whether an agent that accepts a credential **by reference to an environment
  variable** sidesteps this entirely. If the stored value is a pointer rather than a
  token, there may be nothing for the agent to consider expired.
