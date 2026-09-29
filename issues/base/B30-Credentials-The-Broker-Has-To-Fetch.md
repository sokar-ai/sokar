# B30 — Credentials The Broker Has To Fetch

**Status:** built for `client_credentials` 2026-09-29; `private_key_jwt` and mTLS wait for a service that
needs them. This is the kind where the stored secret is not the credential: it buys one. It is
also the machinery [B01](B01-Refreshable-Task-Tokens.md) already chose and could not build for want
of a provider that expires — this kind is that provider. Depends on
[B28](B28-More-Than-One-Credential-In-A-Task.md). Compared with the other kinds in
[Credential Types Compared](Credential-Types-Compared.md).

## The kind

No human is involved, at any point. Something the machine holds is presented to an authorization
server, which answers with a short-lived access token, which is what the work actually uses. Three
shapes of it, and they differ only in what is presented:

- **A client secret** — OAuth 2.0 `client_credentials`. A client ID and secret, a token URL, a scope
  list, sometimes an audience.
- **A key rather than a secret** — `private_key_jwt`, or mTLS client authentication. Nothing is
  handed over at all; something is signed. Sokar already has this component: the vault holds an ssh
  key and the container gets an agent socket, so a task signs without holding anything it could
  leak.
- **An identity the platform already has** — Foundry's agent identity and project managed identity,
  where there is no stored secret anywhere and the platform's own identity is exchanged for a token.
  On a self-hosted node that property comes from somewhere Sokar does not sit, and saying so is more
  useful than pretending to offer it.

## What happened until 2026-09-09, which was worse than nothing

A task that attempted this got halfway through it. Read on 2026-09-09, before the fix below:

- The outbound guard matches `grant_type=refresh_token` only
  (`supervisor/src/main/java/org/fuin/sokar/supervisor/VaultProxy.java:88`), so a
  `client_credentials` body passes it.
- The request is forwarded, and `reissue()` attaches the **real** credential to it (`:254`, `:339`).
  That is precisely the hazard the refresh guard was written to prevent, one grant type away: a
  request whose answer is a new credential, going upstream carrying the real one.
- The authorization server mints a token. The response body contains `"access_token":`, the peek
  check catches it, and the container is answered 403 (`:80`).

So the exchange **happened** — counted, rate-limited, possibly rotating something — and the caller
got nothing.

**Fixed the same day.** `REFRESH_GRANT` became `MINTING_GRANT` and matches both grant types, and the
refusal now says the credential cannot be *"renewed or exchanged for another"* from inside the
container. Two things the backend agent checked before changing it, recorded because *latent* is a
claim about reachability: `reissue()` really would have attached the real credential, since the
exchange sits above the guard; and the refusal does not pre-empt this requirement, because what it
refuses is the *container* asking — the broker's own call to an authorization server is made on this
side of the socket and never passes through it. The comment beside the pattern says so, so that
whoever builds this does not read the guard as a decision against it. A test holds it, and narrowing
the pattern back to `refresh_token` alone fails that test.

## The answer, which B01 already chose

**The container never performs the exchange.** The vault holds the client secret, the token URL and
the scopes; the broker exchanges host-side, caches the access token against its expiry, and attaches
it to outbound requests. The container presents its phantom token and sees neither the secret nor
the derived token. Nothing about the existing guards relaxes — there is no token response crossing
the boundary to withhold, because the exchange never crosses it.

B01 states the same thing for renewal: *"the vault holding the renewal ticket, the broker going to
the provider, the container never seeing anything but its phantom token"*, waiting on a credential
kind that expires. That wait is over. **The two are one build, and the split between the files is
decided:** B01 narrows to the agent inside the box that believes it must renew — a requirement that
stands on its own — and points here for the machinery it parked. Folding this back into B01 would
have made one file about two audiences. The backend agent makes that edit to B01.

## Three things that only appear once the broker holds a clock

**Single-flight.** Twenty requests arriving with no valid token must produce one exchange, not
twenty. The reference implementation locks per credential across supervisors —
`acquire_refresh_lock`, a lock file named
`refresh-<credential_set>-<provider>.lock` — because several containers may share one credential.
Whether Sokar shares a derived token between tasks at all is [B28](B28-More-Than-One-Credential-In-A-Task.md)'s
question; the lock is needed either way, because a task is not one request.

**Two hosts, with different reachability.** The API host is reached by the task, through the broker.
The token endpoint is reached by the broker alone and has no business being resolvable inside the
container. Today the report knows one class of host.

**A third failure that must not lie.** "The authorization server refused these client credentials"
is not "the presented token is not this task's token" and not "your key is wrong". B01 had to split
two of those apart once already, after an expired task token sent an operator looking for a
credential problem that did not exist.

## What must be true

**A task uses a credential that has to be bought before it can be spent, and neither the price nor
the purchase is ever inside the container.**

## Acceptance

- A task authenticates against a service that issues short-lived tokens, for longer than one of
  those tokens lasts, without anything in the container performing an exchange.
- The client secret, the signing key, and every derived token are absent from the container: from its
  environment, its filesystem, and any command line that created it.
- A request from the container asking for a grant is refused before it is forwarded, and the refusal
  says the broker holds this credential — not that the token is wrong.
- Concurrent requests with no valid token produce one exchange. Proven by making them concurrent,
  not by reading the code.
- A refused or unreachable authorization server is reported as itself, distinctly from an expired
  task token and from a rejected key.
- The token endpoint is not reachable from the container, and the API host is.
- A credential authenticated by a key rather than a secret works without the key leaving the vault,
  the way commit signing already does.

## Decided 2026-09-29, by the operator

- **The derived token is bought per task and kept in memory**, never written to disk and never shared
  between tasks. A live token resting outside the vault, and every task depending on one cache, cost
  more than an exchange per task per hour.
- **A purchase that fails at start is a missing key.** The launch tries it once before anything exists:
  unattended and agent runs are refused, and a shell task warns and starts.
- **`client_credentials` first.** `private_key_jwt` and mTLS come as their own step when a service needs
  them; the question of whether they are one mechanism waits for that.
- **Replacing a stored key at its provider is refused here**, stated rather than left as an omission. For
  a bought credential, every exchange already rotates the token it attaches. Issuing and revoking a
  stored key through each provider's own API is per-provider code in Sokar, which the agent and provider
  split exists to avoid.

## As built, 2026-09-29

- **The credential** is a vault entry of kind `client-credentials`: its secret is the client secret, its
  settings are `token_url` (https), `client_id`, `scopes` and optionally `audience`, stored with
  `sokar vault put <name> --type client-credentials --setting ...` (B28).
- **The purchase** (`TokenPurchase`) posts the grant from the host, keeps the access token in memory until
  a minute before it lapses, and buys again. It is synchronized, which is the single flight: waiting
  requests find the token the first one bought. `vault serve` attaches the bought token for such a
  credential and never the secret.
- **Failures are said as themselves:** "the authorization server at <host> refused these client
  credentials (401, invalid_client)", or "could not be reached". Neither echoes what the server sent
  back, which may carry the secret. Both answer the container as 503, distinct from an expired task token
  and from a rejected key.
- **A grant asked for from inside the container** is refused before it is forwarded: "the broker holds
  this credential and buys or renews its tokens itself".
- **The token endpoint** is reached by the broker alone; nothing adds it to what the task can reach.
- **Proven:** `TokenPurchaseTest` against a fake authorization server - the purchase, twenty concurrent
  requests making one exchange, the purchase again before expiry, a refusal that does not echo the
  secret, an unreachable server. On the VM, `credential-several.feature`: an unattended run whose token
  cannot be bought is refused, and no container exists afterwards. A successful purchase has not run on a
  VM, because no authorization server is reachable from it with a certificate the broker trusts.

## To be checked

- **Whether the derived token is shared between tasks.** Sharing is cheaper and couples them;
  minting per task multiplies exchanges and can hit a rate limit that then reads as a broken
  credential.
- **What happens when the exchange fails at task start rather than during the run.** A task that
  cannot authenticate at all should be refused before a container exists, the way a missing vault
  entry already is; one that loses its token mid-run cannot be.
- **Whether the authorization server's own answer needs the same withholding treatment as a
  provider's.** It carries a real token by definition, and the difference is that here it is
  answering the broker rather than the container.
- **Whether replacing the stored secret belongs here.** A security review on 2026-09-10 asked for
  a `vault rotate` that mints a new credential and revokes the old one. Half of it is this kind
  already - a stored secret that buys a credential is what this file describes, and rotating the
  bought one is what the exchange does every time. The other half, calling each provider's own API
  to issue and revoke a key, is per-provider code in Sokar: the cost the agent and provider split
  exists to avoid paying twice, and it should be refused here in the same words rather than left to
  look like an omission.
- **Whether `private_key_jwt` and mTLS are one mechanism or two.** Both mean the secret is a key and
  the broker performs an operation with it; they differ in where in the connection it happens, and
  the second may not be expressible through a proxy that terminates TLS itself.
