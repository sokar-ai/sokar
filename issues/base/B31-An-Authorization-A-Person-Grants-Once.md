# B31 — An Authorization A Person Grants Once

**Status:** soon; the device code flow, the redirect flow, revocation, `AUTHORIZATION_NEEDED`, the grant's record and the "authorization needed" event built, dynamic client registration decided and not built.

**What must be true.** A person grants an authorization once, where a person actually is, and every task afterwards
uses it without holding it and without asking again.

## Why

The only kind with a human in it. Driven by remote MCP servers, where the
agent is expected to inherit a person's permissions rather than a service account's. Depends on
[more than one credential in a task](../../doc/credentials.md#more-than-one-credential-in-a-task), which is built, and, for everything after the first consent, on
[B30](B30-Credentials-The-Broker-Has-To-Fetch.md). Compared with the other kinds in
[Credential Types Compared](Credential-Types-Compared.md).

**Where the other half lives.** Rendering the consent link whole, opening it, and showing a wait that ends outside
the interface is the interface's work, sokar-frontend F31. It is **blocked by this file**.

### The kind

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

### Three collisions with the box

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

### Notes

**The interface half exists, and its owner has spoken.** The frontend agent confirmed
that a clearance prompt at their end is already *"a question raised by work that is now blocked,
answered by somebody who may not be looking, with the work waiting on the answer."* What this
requirement adds to it is a link to open, and a wait that ends somewhere other than in the interface
— which is the open question below, not a detail.

**Asking for refresh is not automatic and its absence fails late.** Foundry's own guidance is to add
`offline_access` to the scopes, and its troubleshooting entry for the omission reads *"your session
has expired, please reauthenticate"* — mid-run, on a task that started fine. A declaration that
cannot express the scopes needed to stay alive produces exactly the failure of a credential that
expires mid-run, in new clothes.

**A task acting as a person is an audit fact, not only a credential fact.** With shared
authentication the record says a machine did something; with individual authentication it says a
named person did, through an agent, unattended. That belongs in
[B26](B26-What-This-Machine-Has-Been-Doing.md) rather than being invented here, and it is a reason to
be slow rather than a reason not to build it.

**Dynamic client registration has to be held somewhere.** If the agent registers, the registration
lives in the container and dies with it, re-registering per task — which servers rate-limit. Held
host-side it is one more stored record, which the vault holds already: a credential made of fields,
not one string.

## The shape

### The shape that fits

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

### Where the code lands, which is the part with three answers

- **A loopback listener on the node**, for an operator sitting at it. Simplest, and useless remotely.
- **Forwarded over ssh**, reusing what B06 settled for the daemon socket: the browser is on the
  operator's machine, the redirect comes back down the tunnel.
- **A device code, which has no redirect at all.** The person is shown a URL and a code and uses any
  browser anywhere; the broker polls. This is the only one of the three that works when nobody is
  near the node, and where a service offers it, it should be preferred rather than merely permitted.

### Decided

- **The device code flow first.** Sokar shows a URL and a short code; the person uses any browser
  anywhere, and the broker polls until it is granted. It is the one flow that works when nobody is near
  the machine. The redirect flow - authorization code with PKCE, its redirect coming back through the ssh
  tunnel the interface already holds - comes second, for a service that offers no device code.
- **A grant is the account's**, held in that account's vault and used by that account's tasks, like
  every other credential. Two people on one machine each grant their own, and the record says which
  person a task acted as.
- **The daemon raises the event.** It streams "authorization needed" with the link and the code, and
  then "granted", "refused" or "expired" on the same stream, so no client polls a consent flow and no
  question gets two grants.
- **A task ending does not revoke the grant.** `sokar vault remove` revokes it at the service (RFC 7009)
  and deletes it. A grant the service revoked or let expire is reported as needing authorization again.
- **Consent reuses the clearance prompt's shape only**, not the prompt itself: a clearance answer is a verdict on a
  waiting connection, a consent answer arrives out of band. "Authorization needed" is answered by `Authorize`.

### The shape, against what the broker and the vault already do

- **The credential** is a vault entry of kind `oauth-device`: the client id and, where the service issues
  one, the client secret; as settings the device authorization URL, the token URL and the scopes.
- **Granting:** `sokar vault authorize <name>`, and the daemon's `Authorize(name)` as a stream, runs the
  device flow on the host. The refresh token that comes back is stored in the account's vault, hidden
  like a task's tokens, with who granted it and when. It never reaches a task.
- **Using:** the broker buys each access token with the refresh token, host-side, as B30 buys one with a
  client secret: per task, in memory, once however many requests wait. A refresh token the service
  rotates is written back to the vault.
- **A task started without a grant:** an unattended run is refused before anything exists, and an
  attended one is told, with the link, as a question rather than as an authentication failure.

### As built, 2026-09-29

- **The credential** is a vault entry of kind `oauth-device`: its value is the client secret, or `-` for a
  public client; its settings are `client_id`, `device_authorization_url`, `token_url` (both https) and
  `scopes`.
- **`sokar vault authorize <name>`** runs the device flow (`DeviceGrant`, RFC 8628): it shows the link -
  the complete one where the service gives it - and the code, waits as the service asks, slowing down
  when told to, and keeps the refresh token in the account's vault as `grant/<name>`, hidden from every
  listing and choice of credential, with `granted_by` and `granted_at` beside it. A grant without a
  refresh token is refused at setup: it would end within the hour.
- **The daemon's `Authorize(name)`** is the same flow as a stream: "needed" with the link and the code,
  then "granted", "refused", "expired" or "failed". Called without streaming it is refused.
- **The broker spends the grant** with the `refresh_token` grant, per task and in memory, as B30 buys
  with a client secret, and writes a rotated refresh token back to the vault. A grant the service revoked
  or let expire answers that it needs authorizing again, not that a key is wrong. A credential nobody has
  granted answers with the command that grants it.
- **A start without a grant** refuses an unattended or agent run before anything exists; a shell task
  warns and starts. The check does not spend the grant, which could rotate it under the broker.
- **The redirect flow** is a vault entry of kind `oauth-code`: settings `client_id`, `authorization_url`,
  `token_url` (both https), `scopes` and `redirect_port` (default 9420). `CodeGrant` (RFC 6749 with PKCE
  S256, RFC 7636) listens on the machine's loopback, `http://127.0.0.1:<port>/callback` (RFC 8252), for
  the one answer carrying its `state`; a stale tab's answer is turned away and the wait goes on. The code
  is exchanged with the verifier, and what comes back is kept and spent exactly as a device grant is.
  `sokar vault authorize` says which port to forward (`ssh -L`); the daemon's `Authorize` stream carries
  it as `port` in the "needed" reply, with an empty `code`, so an interface forwards it through the tunnel
  it holds before opening the link. A port already taken is said with the setting to change.
- **`sokar vault remove <name>`** revokes the grant at the service first (RFC 7009, the entry's
  `revocation_url` setting, https) and then removes the entry and its grant together. A service that
  cannot be told - no revocation endpoint, refused, unreachable - keeps the entry, and says to revoke it
  there and remove with `--without-revoking`: deleting a refresh token the service still honours leaves a
  grant nobody here can see.
- **Proven:** `DeviceGrantTest` against a fake service (the link and code, pending, slow down, granted,
  refused, expired, time running out), `TokenPurchaseTest` for the refresh and its rotation, the daemon
  test for the stream's refusals, and on the VM: an unattended run without a grant is refused and nothing
  exists afterwards, an unreachable service is named, and no grant appears in `vault list`. A real grant
  has not run on a VM: no device authorization service is reachable from it.

### Decided, for the interface (F31)

- **An endpoint may be plain http on loopback only** - `127.0.0.1`, `::1` or `localhost` - and must be
  https everywhere else, for `device_authorization_url`, `authorization_url`, `token_url` and
  `revocation_url` alike, and for `client-credentials` too. The same rule as B86's homeserver: nothing
  leaves the machine unencrypted, and a stand-in service on the machine can be measured against.
- **`CanStart` answers `AUTHORIZATION_NEEDED`** for a credential of kind `oauth-device` or `oauth-code`
  that nobody has granted: its own outcome, never `CREDENTIAL_MISSING` or `CREDENTIAL_UNUSABLE`, with the
  entry's name and a `detail` that gives the command that grants it (`sokar vault authorize <name>`). It
  refuses an unattended or agent run, and warns a shell, as `Start` already does. The check does not spend
  the grant. **A grant expired or revoked at the service** is not visible without spending it, so it is
  not a `CanStart` answer; the broker says it when it spends one - "needs authorizing again" - and the
  daemon raises the event below.
- **A `Credential` row of an oauth entry carries `grant: ?(grantedBy: string, grantedAt: string)`**,
  absent when nobody has granted it. The grant itself stays hidden; who and when are not secrets.
- **The daemon raises "authorization needed" on its prompt stream**, so a second person sees it: when a
  start is refused for want of a grant, and when the broker finds a grant no longer valid. It names the
  entry, the project and the task, and says whether it was never granted or has ended; answering it is
  running `Authorize` for that entry. It is raised once per entry until a grant lands, not once per
  refused start.

### As built, an unreachable service named

- A service that cannot be reached was said as
  "could not be reached: null". It now names the cause: no such host, the connection was refused, timed
  out, no route, or its TLS was refused. A failure before the first reply of `Authorize` is the error
  `Failed`; the state `failed` is only ever a last reply after "needed".

### Decided, dynamic registration, MCP and the record

- **Dynamic client registration (RFC 7591) is built now.** Where a service offers it, Sokar registers a
  client itself when the entry is set up - on the host, never in a task - and keeps the registration
  (`client_id`, a client secret if issued, the registration access token if any) in the account's vault
  beside the entry, so it is done once and not per task, which servers rate-limit. An entry may then name
  only the service; the endpoints come from its metadata: the protected resource's
  `/.well-known/oauth-protected-resource` (RFC 9728) names the authorization server, whose
  `/.well-known/oauth-authorization-server` (RFC 8414) names the registration, device, authorization,
  token and revocation endpoints. A service without registration still takes a `client_id` the operator
  registered by hand, as today. Removing the entry deletes the registration too, at the service where it
  offers that (RFC 7592).
- **An MCP session through the broker is measured, then fixed if it breaks.**
  Measured against a stand-in on loopback: streamable HTTP with server-sent events, and a stream held open
  for an hour. If it breaks, the broker streams without a bound once the response's headers arrived, and
  closes when either side does.
- **A task acting as a person is recorded in the task's record now.** Per credential it keeps who granted
  the grant it spends and when; `Task.credentials` shows it, and the record outlives the task in the
  account's state. [B26](B26-What-This-Machine-Has-Been-Doing.md) takes it over when its machine log is
  built.
- **"A service that accepts consent only from the agent's own browser" is closed, not decided**: no such
  service is known, and the shape does not hold. A task behind the broker never sees a 401, so its agent
  never starts a login of its own; the service sees the broker as the client. Sender-constrained tokens
  (DPoP, RFC 9449; mutual TLS, RFC 8705) are brokered the same way: the broker makes the request, so the
  broker holds the key that signs it. If a real service turns up that cannot be brokered, it is written
  down then, with its name.

### As built, 2026-09-30

- **`Grants.secure`**: every endpoint of a grant or a bought token is https, or plain http to `127.0.0.1`,
  `::1` or `localhost`. A stand-in service on the machine can be measured against.
- **`CanStart` answers `AUTHORIZATION_NEEDED`** (the last value of `StartOutcome`) for a credential the
  project or the run names whose entry is `oauth-device` or `oauth-code` and has no grant, with the entry's
  name and `sokar vault authorize <name>`. It is asked after `UNKNOWN_DESTINATION`, which is now asked
  before a missing or unusable agent credential too: it refuses in every mode.
- **A `Credential` row carries `grant: ?Authorization (grantedBy, grantedAt)`** for an entry a person granted.
- **`Task.grants: [string]Authorization`**: whom the task acts as, for each credential a person granted, as it
  stood when the task started (`grants.json` in the task's state, kept across a restart). Each also goes as
  one JSON line into the account's `$XDG_STATE_HOME/sokar/grants.log`, which removing the task does not
  touch: `at`, `task`, `project`, `credential`, `grantedBy`, `grantedAt`.
- **`NoSuchDestination(name)`**: from `Start`, `name` is the credential's; from `Destination`, the
  destination's. The contract says so.

### As built, the event, 2026-09-30

- **A stream of its own, `Authorizations()`, not the prompt stream:** `Prompts` answers the network
  clearance's `Prompt` and a varlink method has one reply type, so the question is `AuthorizationNeeded
  (credential, task, project, state, at)` on its own stream - what is open now first, then what changes.
- **`never`** when a start is refused or warned for want of a grant, **`ended`** when the broker finds a
  grant revoked or expired at the service (`TokenPurchase.Ended`) - and `never` too when the broker of a
  shell task meets one nobody granted. **`granted`** when a grant lands, by `sokar vault authorize` or
  `Authorize`.
- **Once per credential until answered:** each is a file in `$XDG_STATE_HOME/sokar/authorizations/`, which a
  second refusal for the same credential and reason leaves as it was; the grant removes it.
- **Proven:** `AuthorizationsNeededTest`, the daemon test for the stream, and on the VM the refused
  unattended run's question on the stream.

### As built, a token that never expires, 2026-10-01

Decided for GitHub Copilot through Oh My Pi, whose sign-in is GitHub's device flow for an OAuth
app: an access token, no refresh token, no expiry.

- **Kept as it is.** When a service grants no refresh token and states no expiry, `vault authorize` keeps the
  access token itself (`grant/<entry>`, type `access-token`, with who granted it and when), and the broker attaches
  it as the entry's credential on its routes. One with a stated expiry and no refresh token is refused, as before:
  it would end mid-task. A missing `expires_in` used to be read as five minutes, which made such a token look like
  that one.
- **It cannot be revoked from here.** GitHub revokes an OAuth app's token only for the application's owner, which a
  machine using a public client is not. `vault remove` keeps such an entry, saying where to revoke it - on GitHub,
  Settings → Applications → Authorized OAuth Apps - and forgets it with `--without-revoking`, saying it still works
  there until it is revoked.
- **Found on the way:** `vault remove` looked a grant up among the credentials, which hide grants, so it never found
  one: every grant was deleted here without the service being told. It is now found where grants are kept.
- **Proven:** `DeviceGrantTest` (a grant with no expiry is reported as none), `VaultRemoveCommandTest` (kept, then
  forgotten with the place to revoke it named). Not measured: a Copilot request through the broker, which needs an
  account with Copilot.

## Acceptance

- An operator authorizes a service once, outside any task container, and a task then uses it. Seen to fail:
  `DeviceGrantTest` and the redirect flow's test against a fake service go red when the grant is not kept as
  `grant/<name>` or the broker does not spend it.
- The authorization survives across tasks and across a resume, and the person is not asked again
  until the grant itself ends. Seen to fail: a second task, or a restarted one, using the same entry raises
  "authorization needed" again.
- Neither the access token nor the refresh token is ever in a task container. Seen to fail: a scan of a running
  task's environment and files finds either token, or a grant appears in `vault list`.
- A run that needs an authorization nobody has granted says so as a question with a link, naming the
  service, the project and the task — not as an authentication failure. Seen to fail: `CanStart` answers
  `CREDENTIAL_MISSING` or `CREDENTIAL_UNUSABLE` for an ungranted `oauth-device` or `oauth-code` entry, or the
  refused unattended run's question is missing from the `Authorizations()` stream.
- A grant that has expired or been revoked is reported as needing authorization again, distinctly
  from a wrong credential and from an expired task token. Seen to fail: `TokenPurchaseTest`'s ended grant is
  answered as a wrong key.
- Refresh happens through [B30](B30-Credentials-The-Broker-Has-To-Fetch.md)'s machinery, host-side,
  and a scope list that would not permit refresh is refused when the authorization is set up rather
  than discovered when a task dies. Seen to fail: a grant with a stated expiry and no refresh token is kept at
  setup.
- The record says which person granted it, and that record outlives the task. Seen to fail: after the task is
  removed, `grants.log` has no line for the credential it spent, or `Task.grants` lacks `grantedBy`.
- The consent link reaches an interface **whole** — one string to render and open — never assembled
  by a client from parts. A client computing a link out of two lists is the mistake the interface
  has already caught four times in other fields. Seen to fail: the "needed" reply of `Authorize` carries the
  link in parts rather than as one string.
- Dynamic client registration (RFC 7591), as decided above: registered once on the host where a service offers
  it, kept in the account's vault, endpoints taken from the service's metadata, and deleted at the service on
  removal where it offers that (RFC 7592). Not built. Seen to fail: an entry naming only a service with
  registration registers again per task, or cannot be authorized without a hand-registered `client_id`.
- An MCP session through the broker holds: every method, `Mcp-Session-Id` both ways, and server-sent events
  passed as they arrive. Seen to fail: `VaultProxyTest`'s small event followed by a pause reaches the agent only
  after the pause.

## To be checked

- **Whether the broker can carry an MCP session at all - measured 2026-10-01, and one defect fixed.** It
  passes every method (GET and DELETE as well as POST), the `Mcp-Session-Id` header both ways, `Accept`,
  and `text/event-stream` chunk by chunk with no bound on the body. **It held an answer back until its first
  8 KiB had arrived**, so a server that sent a small event and then waited reached the agent only after the
  pause (measured: 3 s held for a 3 s pause, `VaultProxyTest`); it now passes the first bytes as soon as they
  arrive and scans what follows as before. A stream held open for an hour against a real server is still
  to be seen.
