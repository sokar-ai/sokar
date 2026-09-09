# B28 — More Than One Credential In A Task

**Status:** open, and the foundation the other three stand on.
[B29](B29-Keys-Presented-As-They-Are-Stored.md), [B30](B30-Credentials-The-Broker-Has-To-Fetch.md)
and [B31](B31-An-Authorization-A-Person-Grants-Once.md) each describe one kind of credential. None
of them can be finished while a task holds exactly one and the vault can hold only a string. This is
the part all three share: what the vault stores, how a task is told which credentials it gets, and
what stands between them and the container. The four are compared in
[Credential Types Compared](Credential-Types-Compared.md), which also argues why this one is built
*second*, after B29: so that the foundation's first user exists while its shape is still cheap to
correct.

## The shape of it

A task gets one credential, and the singular is in the code rather than only in the description.
Read on 2026-09-09:

- `CredentialChoice.provider()` resolves one provider per run and remembers it, deliberately, so
  that asking twice cannot answer differently.
- `CredentialWiring` starts one `vault serve` with one `--credential`, on one socket, `vault.sock`
  (`app/src/main/java/org/fuin/sokar/app/CredentialWiring.java:244`).
- The container mounts that socket at one path, `/run/sokar/vault.sock`, and a URL agent gets one
  port, 9419 (`app/src/main/java/org/fuin/sokar/app/TaskWiring.java:23`, `:48`).
- One phantom token goes into one variable, and `SetupContext` — what an agent is handed to prepare
  a fresh container — carries `token`, `credentialType`, `endpoint` and `provider`, every one of
  them singular.
- One upstream host is named in the start report as reachable.

None of that is wrong. It is one credential's plumbing, built when one was all any supported agent
needed, and it does not become plural by being edited into the plural everywhere by hand.

Confirmed by the backend agent on 2026-09-09: no broker with more than one route and no vault entry
that is not a single string exist anywhere, built or planned.

## A credential stopped being a value

`VaultEntry` is `(value, type)` — one string and a label. Everything above it assumes a credential
is an opaque token that goes in a header.

Two of the three kinds are not. Foundry's own configuration for a custom OAuth connection asks for
**client ID, client secret, auth URL, token URL, refresh URL and scopes** — six fields, of which
exactly one is a secret. A store that holds a string cannot express that, and `VaultEntry.suspicious()`
would question a token URL for containing no whitespace and being under twenty characters as though
somebody had pasted a placeholder.

So the unit of storage changes, and it changes first, because all three kinds above it are waiting
on it:

- **Only some fields are secret.** A client ID, a token URL and a scope list are configuration. They
  can be printed, logged and shown in a listing; the client secret and any derived token cannot. An
  entry that cannot tell them apart either leaks the second or hides the first.
- **Judging a value has to become judging a field.** The check that catches a placeholder is worth
  keeping and is currently written for one string.
- **What a listing says has to stay a name, a kind and a length.** More fields must not become more
  surface.

This lands on [B23](B23-Secrets-In-This-Process-Memory.md), and it makes one finding there worse
rather than better: adding a credential today builds a single `String` holding the *entire decrypted
vault* (`Json.write(document(entries)).getBytes(UTF_8)`, recorded in
[Secrets From Elsewhere](Secrets-From-Elsewhere_design.md)). Records are bigger than strings.

## How a task is told which credentials it gets

Three candidates, and it matters which:

- **The project**, in `project.yml`, beside the egress sets. That file already says what a task may
  reach, and a credential is the other half of reaching it.
- **The run**, as a repeatable flag, for what this task needs and the next one does not.
- **The agent's definition**, which is refused. It would make an agent declare a secret belonging to
  the project rather than to itself, and an agent's file is where the agent's own facts go.

Likely the first two, with the run adding to what the project declares rather than replacing it.

**And the consequence has to be said out loud rather than discovered.** A credential named by a
project is usable by anyone who can start a task in that project. Foundry says the same thing about
its own connections — *"people who have access to the project can access an API key stored in a
project connection"* — and it is the kind of fact that is obvious in the design and invisible on
the screen.

## One broker, several routes

Rather than a listener, a socket, a mount and a port per credential, which multiplies the part that
is awkward already: a URL endpoint has to be bound inside the task's own network namespace by a
relay, because a host-side listener is either unreachable from a rootless container or bound to
every interface.

**The reference implementation does this**, and it is the closest thing to a specification
available. Its sandbox's `vault/daemon/token_broker.py` loads a route table of name to
upstream, auth header and prefix; each phantom token records which credential it belongs to, and the
broker reads that, picks the route, loads the credential and injects it. Apache-2.0, and the debt is
to the design rather than to the code — Sokar's broker is a rewrite.

## A token is good for one destination, and that stops being free

With one route, "this token is this task's" and "this credential goes only here" are the same
sentence. With several they are not, and the second has to be enforced rather than inherited.

Foundry enforces exactly this and says so in an error: `Cannot pass Microsoft token to untrusted MCP
endpoint`, with the accompanying instruction not to build a server that relies on passing its own
token to a downstream service. A credential a task holds for one upstream must not be attachable to
a request bound for another, whichever route the container asks for.

## What reaches the container, and what does not

**The rule that does not bend: what enters a container is either task-scoped and worthless outside
it, or it is a socket to something else that holds the secret.** Two secrets already obey it, and
they are the precedent rather than the gap:

- **The git gate token** is in the container's environment as `SOKAR_GATE_TOKEN`. Safe because Sokar
  minted it, it is scoped to the task, and it is worth nothing anywhere but the gate on this machine.
- **The ssh signing key** is never in the container at all. The vault holds it and the task gets an
  agent socket, so a task signs without holding anything it could leak.

A second credential arriving as the stored value in a variable would break a guarantee the first one
keeps, and that guarantee is the product.

## Two classes of host, where there is one today

The start report says the provider's host is reachable, and argues correctly that leaving it
reachable costs nothing because the phantom token is worthless. With several credentials there are
two kinds of destination and only one of them belongs in that sentence:

- **Upstreams the task reaches through the broker** — reachable, as today.
- **Hosts only the broker talks to** — a token endpoint, an authorization server. The container has
  no business resolving them at all, and a report that lists them as reachable is describing
  something that should not be.

## What must be true

**A task holds as many credentials as the work needs, holds the real value of none of them, and each
one is usable only where it was meant to go.**

## Acceptance

- A credential made of several fields is stored, listed and removed like any other, and a listing
  still shows a name, a kind and a length rather than anything that reconstructs the secret.
- Fields that are not secret are usable and printable as configuration; fields that are secret are
  treated as the single value is treated today, including the placeholder check.
- A task is given more than one credential in a single run, and the work inside it authenticates
  with each.
- Every one of them reaches the container as a task-scoped token. No stored value enters it — not in
  a variable, not in a file, not on a command line.
- A token is honored only for the credential it stands for and only against that credential's
  destination. Presented elsewhere it is refused, and the refusal says which of the two happened.
- The start report names every credential the task was given and where each one goes, and carries no
  stored value.
- Every destination the task may reach appears in the egress report with the origin that granted it;
  a host only the broker talks to is not presented as something the task can reach.
- A named credential the vault does not hold refuses an unattended run before the container exists,
  the way the agent's own already does.
- Nothing is asked of an agent that takes one credential: no new flag, no definition change, and a
  client that names none starts the task it starts today.
- When the task ends, none of its tokens is usable.

## Decided 2026-09-09

- **A service that is not a model provider is a `destination`: a second kind, not a widened
  `ProviderDefinition`.** A provider is *which models it serves and in which wire format*, and a
  forge, a search endpoint or an MCP server has no wire format in that sense. Widening the provider
  would leave `dialects` meaningless for half its instances, and a field that is meaningless half the
  time is one nobody can trust anywhere; two things to read is the honest cost of two things
  existing. A provider is then one kind of destination and keeps its own file, so nothing that reads
  providers today has to change. Decided by the backend agent, who owns provider data.

## To be checked

- **Whether the route is picked by the token or by the path.** The token is what the reference
  implementation does and asks nothing of the consumer; a path prefix works whenever the consumer can be handed a base URL, which
  is the ordinary case in [B29](B29-Keys-Presented-As-They-Are-Stored.md), and it keeps one token per
  task rather than several.
- **Whether a derived value is shared between tasks or minted per task.** Sharing is cheaper and
  couples tasks; not sharing multiplies exchanges and can hit a rate limit that then looks like a
  broken credential.
- **What happens when the project and the run both name credentials**, and whether a run can withdraw
  one the project declared.
- **What unlocking costs when entries are records.** The whole decrypted vault already passes through
  one `String`; this makes each entry bigger, and B23 owns the consequence.
