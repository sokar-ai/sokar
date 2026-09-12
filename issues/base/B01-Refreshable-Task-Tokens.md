# B01 — Refreshable Task Tokens

**Status:** partly built, and **narrowed on 2026-09-09**. The expiry that provably exists today -
Sokar's own `--token-hours` - now names itself instead of reading as a wrong credential.

**This requirement is the agent-inside-the-box half.** What an agent does when it believes its
credential is expiring, and what it is told, belongs here. The machinery it once parked - the
vault holding a renewal ticket, the broker going to the provider for a new credential - is
[B30](B30-Credentials-The-Broker-Has-To-Fetch.md), which is about a credential kind that expires
by design and so has the thing this file could never test with. Keeping both here would make one
file answer to two audiences: the agent that is failing, and the broker that would fetch.

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

## Built, 2026-09-04

The first expiry to handle turned out not to be a provider's at all. An API key does not
expire; the token every task holds does, after `--token-hours`, whatever kind of credential
is behind it. Until now that failed as
`the presented token is not this task's token` - which was false, and sent an operator
looking for a credential problem that did not exist.

The exchange now separates a token this broker issued and that ran out from one it never
issued, because the fixes differ:

```
401  sokar: this task's token expired at 2026-09-04T17:25:04Z;
     resume the task to issue it again, or start tasks with a longer --token-hours
```

The advice is real rather than decorative: resuming a task adopts the same token with a
fresh lifetime, which is exactly what a container whose environment cannot be changed
needs. Measured on Fedora and Ubuntu by minting a token with `--token-hours 0`, so it was
already out of time when the container started.

This does nothing for a credential that decays on the host while no task is running, which
is the case recorded above and still the harder half.

## Built, 2026-09-07

**A renewal's answer is itself a credential, and it was being handed to the container.** The
broker attaches the real credential on the way out and returned the provider's answer verbatim on
the way back, so an agent that renewed over the provider's own endpoint would have been given a
live credential - the one thing the phantom token exists to prevent. Measured against a stub
provider: the container received `{"access_token":"sk-live-RENEWED-9f3","refresh_token":"rt-live-8b2"}`
unchanged. Latent rather than live, because no supported provider renews today.

Two blocks, one in each direction:

- **A request asking for a renewal is refused before it is forwarded.** A body carrying
  `grant_type=refresh_token` gets 403 and a sentence saying the task's token is minted per task
  and ends with it. Refused on the way out on purpose: forwarding it would attach the real
  credential to a request whose answer is a new one, and the provider may rotate what Sokar holds
  as a side effect of a question nobody wanted asked.
- **An answer that carries a credential is withheld.** The start of every answer is examined
  before any of it is passed on; a JSON `access_token`, `refresh_token` or `id_token` field stops
  it with 403. Bounded and then streamed, because a completion arrives as a long series of events
  and buffering all of it would make every answer wait for its last byte.

The distinction that keeps this from firing on ordinary work: inside a JSON string the quotes
arrive escaped, so a model *talking about* OAuth does not match while a token response does. Both
directions and that false positive are covered by tests, and the acceptance suite still passes on
Fedora 44.

This is a guard, not the answer. It enforces "the real credential is never in the container,
including during renewal" and turns a silent credential hand-over into a refusal that says what
it is. The agent still cannot renew. **The broker doing the renewal itself is the chosen answer**
- the vault holding the renewal ticket, the broker going to the provider, the container never
seeing anything but its phantom token - and it waits for a provider that actually expires, since
building it now would mean proving it against a stub and nothing else.

## Decided

**The vault keeps serving its stored copy** rather than reading the host agent's live credential
at task start. The decay recorded above is real, and the staleness warning is what answers it: it
turns a confusing authentication failure into an instruction, before the failure appears. Reading
live would end that decay for credentials an agent already holds on this machine, at the price of
a live read in the path of every task and a new failure mode when that read fails - and it would
make a task depend on the vendor's own tool being logged in.

## To be checked

- **Which credential kinds actually expire in practice**, and over what period. A
  task usually outlives nothing; if every kind in use lasts longer than any task,
  this requirement is smaller than it looks and can wait.
- **Whether a stored credential that expires should say so before a task needs it.** This
  requirement is about a credential expiring under running work; the same fact is worth having
  beforehand, and a vault entry made of fields
  ([B28](B28-More-Than-One-Credential-In-A-Task.md)) has somewhere to put an expiry. Raised by a
  security review on 2026-09-10 as a `vault check` that names what is close to its end. It is only
  worth building for kinds that carry the date, which is the first question above.
- Whether a renewal exchange goes to the same endpoint as ordinary use. If it goes
  somewhere else, redirecting the agent does not put the broker in that path, and
  the answer has to come from somewhere else entirely. The agent already supported
  does exactly that for a different exchange: its start-up check contacts the
  provider directly, ignoring the endpoint it was given.
- Whether an agent can be told its credential does not expire, and whether that is
  honored or merely recorded.
- Whether an agent that accepts a credential **by reference to an environment
  variable** sidesteps this entirely. If the stored value is a pointer rather than a
  token, there may be nothing for the agent to consider expired.
