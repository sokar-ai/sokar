# Credential Types Compared

What the kinds of credential have in common and where they part, set side by side — the comparison
no single requirement can hold, the way
[Agents And Providers Compared](../Agents-And-Providers-Compared.md) holds the one that spans two
sets. Four files own the work: [B28](B28-More-Than-One-Credential-In-A-Task.md) the part they share,
then [B29](B29-Keys-Presented-As-They-Are-Stored.md),
[B30](B30-Credentials-The-Broker-Has-To-Fetch.md) and
[B31](B31-An-Authorization-A-Person-Grants-Once.md), one per kind.

## Why they were split

They were one requirement for a day, and the acceptance criteria would not sit together. A key is
attached to a request; a client secret is spent on a token before anything is attached; an
authorization needs a person, once, and then never again. Those are three different builds sharing
one set of plumbing, and a single file would have had a *"to be checked"* section longer than the
requirement.

## The set

Closed, deliberately. A kind that is not here is a kind nothing has asked for, and the way to add one
is to argue it in — not to widen an existing kind until it covers something it was not shaped for.

| Kind | What Sokar holds | What Sokar does | What the container gets | Who is involved | Owner |
|---|---|---|---|---|---|
| **Key** | the value itself | strips, attaches, forwards | a phantom token and an endpoint | nobody | [B29](B29-Keys-Presented-As-They-Are-Stored.md) |
| **Grant** | a client secret or a key, plus a token URL and scopes | exchanges it for a short-lived token, caches it, renews it | a phantom token and an endpoint | nobody | [B30](B30-Credentials-The-Broker-Has-To-Fetch.md) |
| **Authorization** | what a person's consent produced, with its refresh token | attaches it, and refreshes it the way a grant is refreshed | a phantom token and an endpoint | a person, once | [B31](B31-An-Authorization-A-Person-Grants-Once.md) |
| **Minted** | nothing | issues it | the token itself, in full | nobody | built |
| **Signed** | a key that never leaves | performs the operation on request | a socket | nobody | built for ssh, generalized by [B30](B30-Credentials-The-Broker-Has-To-Fetch.md) |
| **Out of band** | — | — | — | — | refused, below |

The last column of the container's side is the point: **four of the five supported kinds put the same
thing in the box** — a task-scoped token that is worthless the moment the task ends — and the fifth
puts a socket there. That is what makes them one subsystem despite being three builds.

## The two kinds that are already built, and are the precedent

**Minted** is the git gate token. Sokar issues it, it is scoped to the task, and it is in the
container's environment in full because there is nothing to protect: it is worth nothing anywhere but
the gate on this machine. It is the case where the answer to "how do we keep this out of the box" is
"we do not have to".

**Signed** is commit signing. The vault holds an ssh key and the container gets an agent socket, so a
task signs without ever holding what signs. It is the case where the credential cannot be handed over
even in principle, and it is the shape `private_key_jwt` and mTLS client authentication reuse.

Neither is a requirement. Both are here because a new kind should be checked against them first — if
a proposed mechanism is neither "task-scoped and worthless outside" nor "a socket to something that
holds it", it is not a variant of anything Sokar does.

## Shared, and individual

Cutting across the kinds is a question about *whose* credential it is, and Foundry names it well:
**shared authentication**, where every task authenticates as one identity, against **individual
authentication**, where each person's own context persists.

Keys and grants are shared: a project holds one, and everyone who can start a task in that project
can spend it. Authorizations are individual by construction — that is why they exist. The difference
is not in the plumbing, it is in what the record has to say afterwards, and it is why
[B31](B31-An-Authorization-A-Person-Grants-Once.md) reaches into
[B26](B26-What-This-Machine-Has-Been-Doing.md) and the other two do not.

## What is refused

**A credential with no request to stand in the middle of.** A database password, a registry login a
build uses, an SSH password to somewhere that is not the gate. There is no exchange to intercept, so
the only way to make one work inside a container is to put the real secret there — which is the one
thing every other kind exists to avoid. Refusing it is better than a design that quietly makes an
exception for one kind and keeps the guarantee for the rest.

This is the fifth refusal of that shape in this set, after the agent roster, hardware access, key
routing and instruction files. Each time the honest answer was *"that is not a thing this system
has"*, and saying so cost a paragraph and bought a screen that is not lying.

**What to do instead, when it comes up:** the thing needing the password usually runs outside the
container anyway — a build on the node, a migration an operator runs. Where it does not, the question
is whether that work belongs in a task at all.

## What order to build them in

**B29 first, and B28 immediately behind it — the other way round from how the dependency reads.**
B28 is the foundation and the largest: the other three wait on the same two changes in it, a vault
that can hold a credential made of fields rather than one string, and a broker that serves more than
one route. B29 is the smallest, two of its three findings are already-latent bugs rather than new
work, and it is the kind that proves the plumbing with nothing else moving. A set that begins with
its own foundation tends to sit unstarted; doing the small one first makes the foundation's first
user exist before the foundation is finished, which is how its shape gets corrected while correcting
it is still cheap. The backend agent's argument, decided 2026-09-09, and the order the index shows.

**Then B30**, because it is [B01](B01-Refreshable-Task-Tokens.md)'s unbuilt half and that requirement
has been waiting on a credential kind that actually expires. It is the largest of the three and the
one that introduces a clock the broker has to keep.

**B31 last**, because it needs B30's refresh to be worth anything, because it is the only one that
needs a person, and because the question it turns on — whether the broker can carry an MCP session at
all — is unmeasured, and answering it may change the shape of the file.

## Decided 2026-09-09, for all four at once

**A service that is not a model provider is a `destination`** — a second kind of declaration, not a
widened `ProviderDefinition`. A provider is *which models it serves and in which wire format*; a
forge, a search endpoint and an MCP server have no wire format in that sense, so widening would leave
`dialects` meaningless for half its instances. A provider is one kind of destination and keeps its
own file. The argument is in [B28](B28-More-Than-One-Credential-In-A-Task.md); every kind above
needed the answer and none of them owned it.

## What is not settled anywhere yet

One question belongs to no single kind, which is why it is here rather than in one of the four:

- **Whether a task can be given a credential its project did not declare.** It decides whether the
  project file is the authority on what a task may reach and spend, or only the default — and the
  same question was already answered one way for egress, where a running task can be widened.
