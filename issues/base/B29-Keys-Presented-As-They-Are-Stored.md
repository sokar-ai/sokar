# B29 — Keys That Are Presented As They Are Stored

**Status:** open; its three findings are fixed for one credential (2026-09-29). What remains is the second
key in one task, which is [B28](B28-More-Than-One-Credential-In-A-Task.md)'s. Built first, before B28, at
the operator's word of 2026-09-29, as the order below argues. Before that it was built for exactly one
credential; what was missing was more than one, the second place a key can go, and a header name nobody
declared.
Depends on [B28](B28-More-Than-One-Credential-In-A-Task.md). Compared with the other kinds in [Credential Types Compared](Credential-Types-Compared.md).

## The kind

A stored string, sent as it is stored. No exchange, no expiry Sokar can see, nobody to ask. An API
key, a personal access token, a subscription token. The broker's whole job is to remove what the
container sent, attach the real value where the service expects it, and forward.

It is the kind that already works — `providers/anthropic.yaml` declares `x-api-key` for an
`api-key` credential and `Authorization` with a `Bearer ` prefix for an `oauth` one — and the kind
every other integration reaches for first. Foundry calls it key-based authentication and stores it
as a **credential name and a credential value**: the header is data, not a constant.

## Where the key goes, and why that is a declared field

**A header, whose name is the service's fact.** Anthropic says `x-api-key`, Google says
`x-goog-api-key`, a forge says `Authorization: Bearer`. Foundry's troubleshooting table lists
getting this wrong as its own failure — *"the credential header name or value format doesn't match
what the MCP server expects"*, resolved by reading the server's documentation — which is the
argument for declaring it rather than inferring it.

**Or the URL.** Google's REST examples for Gemini pass the key as `x-goog-api-key` today, and the
`?key=` form has been in use on those endpoints for years. `ProviderDefinition` can express the
first and cannot express the second at all.

## What is in the way, read from the code on 2026-09-09

**A key in a header the broker has never heard of is refused as somebody else's token.**
`VaultProxy.presented()` looks for what the container sent in exactly four header names —
`authorization`, `x-api-key`, `private-token`, `proxy-authorization`
(`supervisor/src/main/java/org/fuin/sokar/supervisor/VaultProxy.java:58`, `:301`) — and does not
consult the route's own auth header. A container that puts its phantom token where Google says to
put it presents nothing, and gets `401 the presented token is not this task's token`. That sentence
is false, and it is the same false sentence [B01](B01-Refreshable-Task-Tokens.md) already had to fix
for a different cause. The outbound half is fine: `reissue()` strips the route's auth header along
with the four (`:336`).

**A key in the URL is forwarded verbatim.** `reissue()` builds the upstream URI as
`upstream + head.target()` (`:329`), and the target carries the query string. A phantom key in
`?key=` would travel to the provider unchanged while the real one is attached wherever the route
names it — one request, two credentials, one of them wrong. It fails closed rather than leaking,
because `presented()` never finds a token to accept, but it fails.

**And a key in the URL is a key in the log.** The broker logs `method + target` on every outcome
(`:224`, `:246`, `:261`, `:287`), and the target is the query string. Only the phantom token would
land there — but this project has already paid once for a token printed in full, when `gate serve`
wrote `token.value()` into a log the daemon streams to whoever is tailing it.

**The first and the third were confirmed by the backend agent on 2026-09-09** and left to this
requirement on purpose: neither is one line, both are reachable the moment a provider of that shape
is supported, and a requirement can cite a confirmation where it could only cite a report. The
neighboring finding — the outbound guard one grant type short — belonged to
[B30](B30-Credentials-The-Broker-Has-To-Fetch.md) and is fixed.

## What must be true

**A key reaches its service wherever that service expects it — a header nobody had to guess, or the
URL — and more than one key can do so in the same task.**

## Acceptance

- A task uses two keys for two different services in one run, each arriving as a task-scoped token.
- The header a key is presented in is declared, and a key presented in a declared header is
  recognized as this task's token rather than refused as nobody's.
- A key that belongs in the URL is removed from the request the container sent and placed in the
  request that goes upstream. Neither the phantom nor the real value is forwarded in the wrong one.
- Nothing a credential travels in reaches a log — the query string included — and the test for it
  fails when the redaction is removed.
- A key is attached only to requests bound for its own service.
- A worked case passes end to end: a task that holds its agent's provider credential and a second
  key for a search endpoint, using both in the same run.

## Notes

**This is the kind where routing by path is cheapest.** A consumer of this kind is handed a base URL
by whoever configures it, so the URL can carry which credential is meant — the container dials
`…/<service>/…` and the broker knows. That is not true of the other two kinds, and it is why
[B28](B28-More-Than-One-Credential-In-A-Task.md) holds that question rather than this file.

**The consumer still has to be pointable.** An agent is redirectable because its definition declares
a base-URL variable; a script or a tool is redirectable when it reads one. A binary with the host
compiled in is not, and there is no honest way to reach it short of intercepting TLS, which would
mean a certificate authority in the image. **Supported when the consumer's endpoint can be
configured** is a limit to state, not a gap to close later.

**A header sourced from a credential's field is data elsewhere already.** The reference
implementation added `oauth_credential_headers` on 2026-09-10: a route names which field of a
credential goes into which upstream header, validated where the route is read. It is the shape this
file argues for, built on the record-shaped credential
[B28](B28-More-Than-One-Credential-In-A-Task.md) describes, and it is evidence that the header name
need not be code.

## Decided 2026-09-09

- **A service that is not a model provider is declared as a `destination`**, a second kind beside
  the provider rather than a widening of it — decided in
  [B28](B28-More-Than-One-Credential-In-A-Task.md). This kind is the first to declare one.

## Built 2026-09-29, for one credential

- **A key in the route's own header is this task's token.** The broker reads the header the route names
  first, then the four it always knew. `x-goog-api-key` is no longer refused as "not this task's token".
- **A key can travel in the URL.** A provider declares `auth_query` by credential kind, beside
  `auth_header`. The broker then takes the phantom out of that parameter, puts the real key into the same
  parameter of the upstream request, and adds it to no header. `vault serve --auth-query` carries it.
  `ProviderDefinition` and `ProviderRoute` keep their earlier constructors, so nothing built against
  `sokar-agent-api` breaks.
- **No value in a query string reaches the broker's log.** Every logged target keeps its path and the
  names of its parameters, never their values. `VaultProxyKeyPlacementTest` fails when the redaction is
  removed, watched.

**Still open here:** two keys for two services in one task, the declared `destination`, and the worked
case. All of them wait for B28.

## To be checked

- **Whether a key carried in the request body is in scope.** Some services take one there. The broker
  bounds a body and forwards it without understanding it; rewriting one is a different thing than
  rewriting a header, and the honest answer may be that this form is refused.
- **Whether a stored value may be a reference rather than a literal.** At least one candidate agent
  lets a credential be written as the name of an environment variable
  ([A10](../agents/A10-Agent-OpenCode.md)). If Sokar sets that variable to a phantom token, there may
  be nothing to place in the container at all.
