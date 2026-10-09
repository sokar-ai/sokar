# B132 — A Subscription Granted With One Command

**Status:** implemented here, but for the offer at `task start`; the agents' grants are theirs to declare.

**What must be true.** For a provider Sokar knows, `sokar vault authorize <provider>` is the one command that grants
it: on an empty vault it creates the entry with the provider's own settings and goes straight into the device flow.
A task that cannot start for want of a credential names the command that gets one. The OAuth app a grant goes
through is decided and written down, with its owner.

## Why

On 2026-10-09 the operator started a task with `--provider github-copilot` on a fresh machine and was told only:

    sokar: the vault holds no credential for 'github-copilot'

`doc/credentials.md` then asks for a `vault put` with four settings before `vault authorize`. Three of them are
GitHub's fixed values, the same for everybody: `device_authorization_url=https://github.com/login/device/code`,
`token_url=https://github.com/login/oauth/access_token`, `scopes=read:user`. The fourth, `client_id`, the document
leaves as `<an OAuth app Copilot accepts>`, so a person cannot finish the step from it alone.

## The shape

- **The provider carries its grant.** `providers/github-copilot.yaml` gains a `grant:` block: kind `oauth-device`,
  the two URLs, the scopes and where the client id comes from (below). `sokar providers` shows that it can be
  granted.
- **`vault authorize <name>` on a name the vault does not hold** looks for a provider of that name with a `grant:`.
  If there is one, it creates the entry from it and starts the device flow at once. If not, it refuses as today.
  An entry that exists is used as it is, so `vault put` with other settings keeps working for whoever needs them.
- **The refusal at `task start`** names the command for the provider's kind of credential: `sokar vault authorize
  github-copilot` for a grant, `sokar vault login <agent>` for a subscription sign-in, `sokar vault put … --type
  api-key` or `vault import` for a key. On a terminal it offers to run the grant there and then, and starts the task
  when it is granted. Without a terminal it only names it, exit 65 as today.

- **`vault put` for a grant asks for what it means.** For an `oauth-device` or `oauth-code` entry, the value is the
  client secret. A public client, Copilot's among them, has none. Today `vault put` asks `Value for '<name>':`,
  refuses an empty answer ("nothing on standard input"), and takes `-` as "no secret" (`DeviceGrant.Client.of`)
  without saying so anywhere. The operator could not tell what to type (2026-10-09). For these kinds it asks
  `Client secret for '<name>' (none for a public client: press Enter):` and takes an empty answer as a public
  client. Without a terminal, empty standard input means the same. `-` keeps working. **The built-in grant of
  `vault authorize` asks nothing**: a provider's `grant:` is a public client unless it says otherwise.

## The client id

What is known:

- GitHub's device flow needs the client id of an OAuth app, and no secret.
- **Neither agent signs in with an app of its own vendor.** The setup that works on the operator's machines uses the
  client id Oh My Pi signs in with. That is the OAuth app of another, unrelated tool, read from Oh My Pi's source on
  2026-10-01. Pi signs in with an id widely cited as GitHub's own editor-plugin app, owner not verified, and
  presents itself to GitHub as that editor in its request headers. So "the agent's own app" would still ship
  somebody else's client id in an agent package, only one step removed.
- Copilot accepts tokens from the app Oh My Pi uses. Not measured: whether it accepts a token from an OAuth app
  Sokar owns, and whether an editor's app is accepted without the editor's headers.

On 2026-10-09 the operator first chose the agent's app on the premise that it was the agent vendor's own. That
premise was wrong, as above. **Knowing whose ids they are, the operator decided the same day:**

1. **The client id comes from the agent package that will use the grant.** It is carried in the agent's description
   file (B129), so Sokar signs in the way the agent itself would without Sokar. `vault authorize github-copilot` takes
   it from the agent installed for that provider, and asks which one when there are several.
2. **No OAuth app of Sokar's own, and no measurement of one.**
3. **Written down with its real owner.** `doc/credentials.md` and each agent's documentation say which app the id
   belongs to, as far as known, and "owner not verified" where it is not. They say that Sokar only passes on what
   the agent itself uses, and where a person revokes the grant.

The description file's sign-in field gets its shape when this is built. It holds **one client id per host**:
Oh My Pi signs in with one id on `github.com` and another on a GitHub Enterprise host. The agent packages fill it,
the Pi family first, each id with its owner as read from the agent's source.

## Which other providers fit

- **`github-copilot`:** the only provider today whose credential is a grant (`oauth-device`). It fits the whole shape.
- **`anthropic`:** its subscription is a sign-in `sokar vault login` already does in one command, and an API key is
  a `vault put`. Only the refusal at `task start` changes: it names the command.
- **`openrouter`:** an API key. Only the refusal at `task start` changes.
- **A later provider or destination with a device or redirect flow (`oauth-device`, `oauth-code`):** it declares a
  `grant:` and gets the same one command, with no code of its own.

## Acceptance

- On an empty vault, `sokar vault authorize github-copilot` shows the link and code, and after the grant
  `vault list` holds `github-copilot`, kind `oauth-device`, with the decided client id. Seen to fail first: today's
  refusal of an unknown entry.
- `task start --provider github-copilot` on an empty vault names `sokar vault authorize github-copilot`. On a
  terminal it offers to run it, and the task starts after the grant.
- A Copilot request through the broker succeeds with the granted token, on a machine whose account has Copilot.

## As built, 2026-10-09

- **The provider's grant:** `ProviderDefinition.grant()`, read from `grant:` (kind, URLs, scopes, never a client id);
  `providers/github-copilot.yaml` declares GitHub's device flow and `read:user`.
- **The agent's app:** `AgentDefinition.grants()`, read from `login.grants.<provider>.hosts` (client id by host,
  `"*"` for any other) and `owner`, carried in the interface's JSON. `AgentGrant.clientIdFor(host)`.
- **`vault authorize <provider>`** on a vault without that entry makes it (`GrantEntry`): the provider's grant, the
  client id of the one installed agent that signs in, `client_owner` from its owner, and `-` as a public client's
  secret; `--agent` chooses among several. A provider reached with a key, no agent that signs in, or none for the
  host is refused with the command that does instead. An entry that is there is used as it is.
- **`vault put`** asks a grant's `Client secret for '<name>' (none for a public client: press Enter)` and stores an
  empty answer as `-`.
- **`task start`** refused for want of the credential names `sokar vault authorize <provider>` for a provider with a
  grant, `sokar vault login <agent>` for an agent's sign-in, `sokar vault put <name>` for a key.
- **Tested:** `GrantEntryTest`, `ProviderDefinitionReaderTest`, `AgentDefinitionReaderTest` (with the JSON round
  trip), `VaultPutCommandTest`, `CredentialWiringTest`.
- **Open:** at a terminal, `task start` offering to run the grant and then starting the task; the agents' own
  `login.grants`, which Agent Smith's packages declare.

