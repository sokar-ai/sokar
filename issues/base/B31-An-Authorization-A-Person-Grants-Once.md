# B31 — An Authorization A Person Grants Once

**Status:** open, and the only kind with a human in it. Driven by remote MCP servers, where the
agent is expected to inherit a person's permissions rather than a service account's. Depends on
[B28](B28-More-Than-One-Credential-In-A-Task.md) and, for everything after the first consent, on
[B30](B30-Credentials-The-Broker-Has-To-Fetch.md). Compared with the other kinds in
[Credential Types Compared](Credential-Types-Compared.md).

## The kind

OAuth 2.1 authorization code with PKCE, or a device code. A person authenticates, reviews the scopes
and consents; what comes back is an access token bound to *them*, usually with a refresh token
beside it. The specifications the MCP ecosystem has settled on are explicit about it — protected
resource metadata at `/.well-known/oauth-protected-resource` (RFC 9728), PKCE (RFC 7636), dynamic
client registration (RFC 7591), revocation (RFC 7009) — and equally explicit that this is *not*
machine-to-machine: client credentials "would violate the passthrough principle", because the point
is that the agent acts as the human rather than as a service.

Foundry draws the same line as a choice the operator makes: **shared authentication**, where every
user of an agent authenticates as one identity, against **individual authentication**, where each
person's own context persists. The first two kinds in this set are shared. This one is individual,
and that difference is the whole requirement.

## Three collisions with the box

**There is no browser.** A container has none, and an unattended run has nobody to use one.
First-run consent - the dialogs the box has already answered - is each agent repository's own
business since 2026-09-13; this is a question the box genuinely cannot answer alone.

**There is no way back in.** An authorization code is delivered to a redirect URI, conventionally
`http://localhost:<port>`. The container has no inbound path, and when the person is not at this
machine at all ([B06](B06-Remote-Access.md)) the code has to land somewhere that can still hand it
back.

**And if the agent completes the flow, the token is in the box.** Audience-scoped and short-lived,
which is far better than a static key — but not something Sokar minted, and not something that dies
with the task.

## The shape that fits

**The person authorizes once, outside the task; the vault holds what comes back; the broker attaches
it.** The container gets a phantom token and an endpoint, and the one rule holds.

This is not a new pattern here or elsewhere. **Sokar already runs a human login outside a task
container**: `AgentLogin` runs an agent's own login verb in a throwaway container that carries no
Sokar annotation, so no hook fires — no ruleset, no resolver, no clearance watcher, no broker — with
ordinary network access for the seconds a login takes, removed afterwards. It exists because a clean
machine has no binary to log in with. It is the right shape for the wrong purpose, and the purpose
is the only part that has to change.

**Foundry does the same thing for the same reason**, and its mechanism is worth copying rather than
reinventing: the platform holds the connection, and the *first* time a person needs to authorize, the
run surfaces a consent link as a protocol item — `oauth_consent_request` carrying a `consent_link` —
which the caller shows to the person; the run then continues from where it stopped. Consent is per
service, per project, per person, and asked once.

**Sokar already has that mechanism under another name.** A clearance prompt is a question naming the
project, the task and what was reached, answered out of band, taking effect on the connection that is
waiting — and it is never asked twice for a task, across a resume. A consent link is the same object
with a URL in it. B06 already owns the hard half of both: how long a prompt waits for somebody who is
not at the machine.

## Where the code lands, which is the part with three answers

- **A loopback listener on the node**, for an operator sitting at it. Simplest, and useless remotely.
- **Forwarded over ssh**, reusing what B06 settled for the daemon socket: the browser is on the
  operator's machine, the redirect comes back down the tunnel.
- **A device code, which has no redirect at all.** The person is shown a URL and a code and uses any
  browser anywhere; the broker polls. This is the only one of the three that works when nobody is
  near the node, and where a service offers it, it should be preferred rather than merely permitted.

## What must be true

**A person grants an authorization once, where a person actually is, and every task afterwards uses
it without holding it and without asking again.**

## Acceptance

- An operator authorizes a service once, outside any task container, and a task then uses it.
- The authorization survives across tasks and across a resume, and the person is not asked again
  until the grant itself ends.
- Neither the access token nor the refresh token is ever in a task container.
- A run that needs an authorization nobody has granted says so as a question with a link, naming the
  service, the project and the task — not as an authentication failure.
- A grant that has expired or been revoked is reported as needing authorization again, distinctly
  from a wrong credential and from an expired task token.
- Refresh happens through [B30](B30-Credentials-The-Broker-Has-To-Fetch.md)'s machinery, host-side,
  and a scope list that would not permit refresh is refused when the authorization is set up rather
  than discovered when a task dies.
- The record says which person granted it, and that record outlives the task.
- The consent link reaches an interface **whole** — one string to render and open — never assembled
  by a client from parts. A client computing a link out of two lists is the mistake the interface
  has already caught four times in other fields.

## Notes

**The interface half exists, and its owner has spoken.** The frontend agent confirmed on 2026-09-09
that a clearance prompt at their end is already *"a question raised by work that is now blocked,
answered by somebody who may not be looking, with the work waiting on the answer."* What this
requirement adds to it is a link to open, and a wait that ends somewhere other than in the interface
— which is the open question below, not a detail.

**Asking for refresh is not automatic and its absence fails late.** Foundry's own guidance is to add
`offline_access` to the scopes, and its troubleshooting entry for the omission reads *"your session
has expired, please reauthenticate"* — mid-run, on a task that started fine. A declaration that
cannot express the scopes needed to stay alive produces exactly the failure
[B01](B01-Refreshable-Task-Tokens.md) was written about, in new clothes.

**A task acting as a person is an audit fact, not only a credential fact.** With shared
authentication the record says a machine did something; with individual authentication it says a
named person did, through an agent, unattended. That belongs in
[B26](B26-What-This-Machine-Has-Been-Doing.md) rather than being invented here, and it is a reason to
be slow rather than a reason not to build it.

**Dynamic client registration has to be held somewhere.** If the agent registers, the registration
lives in the container and dies with it, re-registering per task — which servers rate-limit. Held
host-side it is one more stored record, which is what [B28](B28-More-Than-One-Credential-In-A-Task.md)
is making the vault able to hold anyway.

## To be checked

- **Whether the broker can carry an MCP session at all.** It bounds the start of a response and then
  streams, which server-sent events tolerate; a long-lived session with a server that expects to keep
  one open is unmeasured. [B14](B14-Talking-Between-Tasks.md) asks the neighboring question — MCP over
  a unix socket from inside a rootless container — and has not answered it either.
- **How the wait ends when the person is elsewhere.** The grant completes in a browser, and nothing
  in an interface observes it. Either the daemon raises a second event when the authorization lands,
  or a client polls — and polling a consent flow is the shape that produces two grants for one
  question. The interface's owner named the first as the one to build; it is the daemon's to build.
- **Whether consent can reuse the clearance prompt outright**, or only its shape. A clearance answer
  is a verdict on a waiting connection; a consent answer arrives out of band, after the person has
  been somewhere else entirely.
- **Whether an authorization is per operator or per machine.** Individual authentication says the
  first; a node that several people start tasks on then holds several grants for one service, and a
  task has to be told which one it acts as.
- **What revocation at the end looks like.** RFC 7009 revocation is the tidy answer for a grant that
  is finished with, and a task ending is not the grant being finished with. The two must not be
  confused.
- **A refusal that may be the honest answer for some servers**: one that requires interactive consent
  and will accept it only from the agent's own browser cannot be brokered at all. It would be the
  fifth of that shape in this set, after the agent roster, hardware access, key routing and
  instruction files.
