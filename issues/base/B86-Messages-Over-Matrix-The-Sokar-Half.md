# B86 — Messages Over Matrix, The Sokar Half

**Status:** specified 2026-09-29; on 2026-09-30 the operator moved everything Matrix-specific into the
transport behind a generic lifecycle (below), which Agent Matrix builds. **Sokar's generic side is
built (2026-09-30)**, proven against a stand-in transport; the first run with the Matrix transport waits
on its `setup`, `enroll`, `retire` and `join`. Written 2026-09-29 from `sokar-project` PJ02 at the operator's word, relayed by Agent
Coordinator. Priority: after B28, B30 and B31. The transport itself is `sokar-message-matrix`'s (Agent
Matrix), and so is starting the homeserver, which ships with the transport's package (operator, 2026-09-29).

## What is decided, and not re-argued here

- A Matrix room is the project's conversation: one room per project, every task of it posts there, and the
  person is in it too. The room is the confidentiality boundary; two tasks that must not read each other's
  messages belong in two projects.
- Room membership is not authorization. What arrives is delivered only if its signature verifies against a
  key listed for that peer; anything else is held. Matrix peers are `external`, so inbound content goes
  through the filter.
- One Matrix account per task, made when the task starts and deleted when it is removed. The signature,
  not the account, carries authorship.
- No end-to-end encryption, on a homeserver the operator runs; the accepted risk is written down in
  `sokar-message-matrix`'s `doc/decisions.md`.
- Matrix is the only channel, on one machine too. `transport-local` and `transport-spool` are retired once
  the Matrix transport is in place, not before.

## The contract as agreed with the transport, 2026-09-29

- `describe`: `scheme: matrix`, `confirms: read`,
  `credentials: [{"name":"SOKAR_MATRIX_ACCESS_TOKEN","as":"env"}]`, `hosts: []` (changed 2026-09-29: the
  egress comes from the project's declared homeserver, below), `attests: []` until `sender` has its evidence file.
- `send <file> <sig> --to <room id>` prints `{"reference":"<event id>"}`; Sokar keeps it as
  `sent/<message>.receipt.json`. A room id may have no `:server` part; Sokar splits an address at its first
  colon only.
- `poll --into <inbound>` writes `<event id>.json` and `.json.sig`; a message without a signature gets no
  `.sig` and is held; a person's plain chat is not delivered, and is counted on stderr.
- `read <reference>` (added 2026-09-29, QM9; built in the transport's `5bfa8a1`), run with the reading
  task's own token, posts the read receipt for that event: 0 posted (a second time too), 75 temporary,
  77 no room of the account holds the event, nothing on stdout.
- `receipt <reference> --by <account>` prints `{"state":"read"|"delivered"|"unknown", "at": …}`, exit 0 for
  all three.
- Exit codes: 0 done, 75 temporary, 64 usage, 65 not carriable, 76 answer not understood, 77 refused by the
  homeserver, 78 configuration missing. The homeserver's address comes from `SOKAR_MATRIX_HOMESERVER`.

## What must be true, in `sokar`

1. **The peer table resolves every peer of a Matrix project to the project's room**, and delivery on the
   way in keeps only what is signed by a key listed for a peer *of that project* and names one of that
   project's tasks in `metadata.to`. Anything else is held, and the operator told.
2. **No task touches Matrix.** A task's account, its token and every call to the homeserver are the
   host's; the container has neither the token nor a route to the homeserver.
3. **A task's account is made when the task starts and deactivated when it is removed**, by the account's
   provisioning account, which is the homeserver's administrator. No per-task password exists after the
   account is made.
4. **The transport's token reaches it from the vault as `SOKAR_MATRIX_ACCESS_TOKEN`**, never on a command
   line and never in a container.
5. **The homeserver is one endpoint Sokar names**: the project's declared URL, or the account's own on
   loopback. The host's egress for a project's messages is that URL and nothing else; `describe.hosts`
   stays `[]`.
6. **`receipt` is asked about what was sent, by the account of the task it was for**, and the answer is
   recorded as the message's read state.
7. **An `offline` project messages through loopback only.**
8. **A person joins a project's room in one command**, with an account made for them.
9. **Only a transport a peer names is polled.**

## Decided 2026-09-29, by the operator

- **Two projects on one homeserver are kept apart by the host acting and the signature deciding.**
  No task ever touches Matrix: its account's token stays in the host's vault, and only the host-side
  transport posts and polls for it. The host joins a task's account to its own project's room and to no
  other. What arrives is delivered only if it is signed by a key listed for a peer *of that project* and
  names one of that project's tasks in `metadata.to`; a message crossing from another project's room is
  held, not delivered. The homeserver is one endpoint Sokar names, as it names a gate - never "the machine".
- **An `offline` project messages through a homeserver on loopback only.** Sokar names that one
  endpoint as it names the gate; a declared homeserver URL that is not loopback is refused for an offline
  project, so its messages never leave the machine.
- **A task's account is deactivated by the provisioning account's administrator rights.**
  The provisioning account registers first on a fresh homeserver and so is its administrator; it
  deactivates a task's account through the admin API when the task is removed. No per-task password is
  kept: a task's account is made with a random password that is thrown away, and only its access token
  lives in the vault, until removal.
- **A central homeserver is the project's, as Agent Matrix proposed.** `project.yml`
  names the homeserver's URL; absent, it is the account's own on loopback. The project may name a CA file
  (the transport's `SOKAR_MATRIX_CA_FILE`) and, for development only, `tls_verify: off`
  (`SOKAR_MATRIX_TLS_VERIFY`). Each machine holds in its vault its own provisioning account on the central
  server, with the rights to deactivate its tasks' accounts, so one machine can be revoked alone. The
  host's egress for a project's messages comes from the declared URL, and `describe.hosts` stays `[]` -
  a change to what was agreed with the transport.
- **The local homeserver is the account's.** Each account that runs Sokar has its own,
  a user unit, on a port Sokar picks and keeps in the account's state - as a grant and the vault are the
  account's. No root, and two work users never share a server's administrator.
- **A person joins by an account made for them.** `sokar messages join <project> <person>` has
  the provisioning account make one on the project's homeserver, invite it into the room, and print the
  login once; the person uses any Matrix client. No registration stays open. Their messages are delivered
  only once their key is listed as a peer, as for anyone.
- **Only a transport a peer names is polled.** A daemon polls a transport only if a peer
  of one of the account's projects names its scheme. One that is named and answers 78 is reported once as
  misconfigured, not on every cycle.

## Where the Matrix logic lives - decided 2026-09-30, by the operator

**Sokar knows transports, conversations, identities and opaque secrets; the transport knows Matrix.** No
Matrix client, room alias, registration flow, admin command or homeserver unit is in `sokar`. Everything
below that names Matrix is the transport's (`sokar-message-matrix`); Sokar's side is a generic lifecycle
any transport can implement, so a second transport adds nothing to `sokar`.

### The lifecycle a transport may offer

`describe` says which verbs it has: `"lifecycle": ["setup", "enroll", "retire", "join"]`, absent for a
transport that has none (the local and spool transports). Every verb:

- takes the project's settings for this transport **as JSON on stdin** - what `project.yml` says under
  `mail.transports.<scheme>`, passed through verbatim; Sokar reads none of it;
- takes the secrets Sokar kept for it **as environment variables**, never as arguments;
- prints one JSON object on stdout; any `secrets` in it are Sokar's to keep in the account's vault and
  hand back, and are never shown, logged or put into a task;
- exits with the codes agreed for `send` (0, 64, 65, 75, 76, 77, 78).

| Verb | When Sokar runs it | Given | Prints |
|---|---|---|---|
| `setup --project <p>` | before the first task of a project that names the transport starts, and whenever `setup`'s secrets are missing | the transport's account-level secrets, if any | `{"secrets": {...}, "account": {...}, "conversation": "<id>", "reaches": ["<host>", ...]}` |
| `enroll --project <p> --task <t>` | when a task of the project starts, before its container exists | account- and project-level secrets | `{"secrets": {...}, "address": "<how the task is named in the conversation>"}` |
| `retire --project <p> --task <t>` | when the task is removed | account-, project- and the task's secrets | `{}` |
| `join --project <p> --person <n> [--reset]` | on `sokar messages join`, and `JoinMessages` | account- and project-level secrets | `{"login": {...}, "shown": "<the text a person reads>"}` |

- **`account`** secrets are the account's, across projects (Matrix: the provisioning account on the
  account's own homeserver). **`secrets` from `setup`** are the project's (Matrix: its relay). Sokar keeps
  them in the vault under `transport/<scheme>/account`, `transport/<scheme>/project/<p>` and
  `transport/<scheme>/task/<t>`, hidden like a task's tokens.
- **`reaches`** is what the project's messages would reach through this transport. Sokar lets the transport
  reach those hosts and nothing else, and **refuses an `offline` project whose `reaches` names anything but
  loopback**, before a task exists - so the offline rule stays Sokar's, without Sokar reading a URL. It is
  why `describe.hosts` stays `[]`.
- **`join`** prints `login` for the interface (`JoinMessages` passes it through, answered once, never
  kept: homeserver, user, room, password for Matrix, and `loopback`/`port` when it is on the machine's
  loopback) and `shown` for the command line.
- A verb a transport does not list is not run; a transport with no lifecycle works as today.

**The secrets, as agreed with Agent Matrix (2026-09-30):** every map a verb prints is complete for the
verbs that use it, so Sokar never assembles one. `setup`'s project `secrets` are exactly what `poll` reads
(for Matrix: `SOKAR_MATRIX_HOMESERVER`, the relay's `SOKAR_MATRIX_ACCESS_TOKEN`, and
`SOKAR_MATRIX_CA_FILE`/`SOKAR_MATRIX_TLS_VERIFY` when set); a task's from `enroll` are the same keys with
its token, for `send`, `read` and `receipt`; the `account` secrets (for Matrix `SOKAR_MATRIX_ADMIN_TOKEN`)
go only to `setup`, `enroll`, `retire` and `join`, never to `poll` or `send`. `setup` is run again and
again and keeps what exists; a homeserver whose first account exists while Sokar holds no account secrets
is refused (78), not guessed around.

### What Sokar does with it, for any transport

- **Acting as someone:** `send`, `read` and `receipt` run with the sending or reading task's secrets;
  `poll` runs **once per conversation** (one per project) with the project's secrets, so everything it
  brings is known to be that project's.
- **Handing out:** what `poll` brings goes to the task of that project that `metadata.to` names - by its
  short name or its container's - and only among that project's tasks on this machine; anything else is
  not this machine's and is dropped. Delivery then checks the signature against that project's peers, as
  for every transport.
- **Read on take:** a message a task's agent has moved from `inbox/new` to `inbox/cur` gets `read
  <reference>` with that task's secrets, once, when the transport's `describe` says `confirms: "read"`.
- **Polled only when named:** a transport is polled only for a project a peer of which names its scheme.
- **Said once:** 77 and 78 from a lifecycle verb or a poll are said once per cause, not every cycle.
- **A task's own tasks through the conversation:** a project whose peers use a transport with a
  lifecycle has its tasks' siblings addressed through it too (`<scheme>:`), not through the local
  transport.

### `project.yml`

```yaml
mail:
  transports:
    matrix:                                    # passed to the transport verbatim, on stdin
      homeserver: https://matrix.example.org   # the transport's own settings; for Matrix these three
      ca_file: certs/intranet-ca.pem
      tls_verify: on
  peers:
    reviewer: { address: "matrix:", trust: vouched }   # "matrix:" - the project's conversation
```

A peer address `<scheme>:` with nothing after the colon means the project's conversation on that
transport; `enroll` and `setup` tell Sokar what that is.

### What the Matrix transport does with each verb (Agent Matrix's, for review)

- `setup`: with no account secrets, makes sure the account's own homeserver runs (port kept in the
  account's state and written to `homeserver.conf`, `systemctl --user enable --now`, waits for
  `/_matrix/client/versions`), registers the provisioning account first with `registration-token` and
  returns it as `account`; then makes the project's room (invite-only, no guests, unpublished, alias
  `#sokar-<project>:<server>`), registers the project's relay and joins it, and returns the relay as the
  project's `secrets`; `reaches` is the homeserver's host.
- `enroll`: registers `@<task>:<server>` with a password used once, invites and joins it into the room,
  returns its token.
- `retire`: `!admin users deactivate @<task>:<server>` in `#admins:<server>`.
- `join`: makes `@<person>:<server>` (or resets its password with `--reset`), invites and joins it, and
  returns the login.

## The shape, as first specified on 2026-09-29

*Where this differs from "Where the Matrix logic lives" above, that section holds*: the homeserver,
accounts and rooms described here are the Matrix transport's to make through `setup`, `enroll`, `retire`
and `join`, not Sokar's; the settings sit under `mail.transports.matrix`, not `messages:`; and there is a
relay per project, not one per homeserver, so a poll's arrivals are known to be that project's.

### `project.yml`

```yaml
messages:
  homeserver: https://matrix.example.org   # optional; absent: this account's own, on loopback
  ca_file: certs/intranet-ca.pem            # optional; PEM trusted besides the built-in authorities
  tls_verify: on                            # optional; off for development only, never with ca_file
peers:
  reviewer: { address: "matrix:!room", trust: vouched }
```

- A peer whose address has the scheme `matrix` is a Matrix peer; the address after the first colon is the
  room id, which may itself contain no `:server` part.
- Every Matrix peer of one project names the same room: the project's. Two rooms in one project is refused
  when the project file is read, naming both.
- `messages.homeserver` must be https, or http on loopback. For an `offline` project anything but loopback
  is refused before a task exists, as a gate outside the machine would be.
- `ca_file` is resolved against the project's repository and read by the host; `tls_verify: off` with a
  `ca_file` is refused, as the transport refuses it (78).
- The transport is given `SOKAR_MATRIX_HOMESERVER`, `SOKAR_MATRIX_ACCESS_TOKEN` and, where declared,
  `SOKAR_MATRIX_CA_FILE` / `SOKAR_MATRIX_TLS_VERIFY` - in its environment, from the host.

### The account's own homeserver

- It is the transport package's user unit, `sokar-matrix-homeserver`, listening on `127.0.0.1` only.
- Sokar picks its port once, keeps it in the account's state, writes it as `SOKAR_MATRIX_PORT` into
  `~/.config/sokar/matrix/homeserver.conf` (the unit's `EnvironmentFile`, the package's own file - no
  second one), and restarts the unit. `SOKAR_MATRIX_SERVER_NAME` stays `localhost`: it is fixed once the
  database exists.
- Registration is never open. The unit makes `~/.config/sokar/matrix/registration-token` (0600) before
  its first start; Sokar reads it and registers the provisioning account **first**, which makes it the
  administrator. Its password and token go into the vault as reserved entries, hidden like a task's.
- Sokar enables and starts the unit (`systemctl --user enable --now`) the first time a project of the
  account needs it, not at install: an account with no Matrix project runs no homeserver. Enabled, it comes
  back after a boot without a login, as the daemon does; only started, it would be gone until something
  started it again (measured by Agent Matrix after the ubuntu VM's restart).
- Sokar waits until `/_matrix/client/versions` answers before registering anything: the unit is running
  when the container is, and the first start pulls the image (5-6 s measured).

### A central homeserver

- Each machine has its own provisioning account there, with administrator rights, its token in that
  machine's vault - so one machine is revoked alone. How that account is made is the central server's
  operator's business; Sokar is given its token with `sokar vault put` like any credential, under a
  name the project file does not need to know (one per homeserver URL).
- Not measured on a central server yet: that the admin room's `deactivate` holds there as on loopback.

### A task's account

- Made at start, before the task's container exists, by the provisioning account: `@<task>:<server>`,
  a random password used once to log in and then forgotten; the access token goes into the vault as a
  reserved entry, like the task's own token, and survives a reboot as that does.
- Joined to the project's room (made by the provisioning account the first time, invite-only, no guest
  access, published nowhere) and to no other room.
- Deactivated when the task is removed: the provisioning account writes
  `!admin users deactivate @<task>:<server>` into the admin room `#admins:<server>` (Tuwunel has no HTTP
  call for it; measured by Agent Matrix, 2026-09-29), then the token is dropped from the vault. After
  that the token is refused (`M_UNKNOWN_TOKEN`) and the login too (`M_USER_DEACTIVATED`).
- A start whose account cannot be made - homeserver down, provisioning account missing - is refused
  before anything exists, naming which, as a credential nobody can reach is.

### Sending, receiving, reading

- Sending a message of a task to a Matrix peer runs `send <file> <sig> --to <room id>` with that task's
  token; the `{"reference": ...}` it prints is kept as `sent/<message>.receipt.json`.
- Polling runs `poll --into <inbound>` once per homeserver, not once per task, with the token of the
  homeserver's **relay account**: an ordinary account the provisioning account makes after itself, joined
  to every project room it makes and to nothing else. One poll sees every room its account is in,
  and the relay cannot join the admin room (measured by Agent Matrix with the relay: two project rooms,
  both delivered with their `.sig`, nothing skipped while the admin room had traffic; joining `#admins`
  answered 403). The relay is not the
  administrator on purpose: the transport then never holds the administrator's token, and the admin room's
  own traffic - the reply to every `!admin` command - never reaches a poll. What arrives is sorted into the
  tasks' mailboxes by `metadata.to` and the signature, as point 1 says.
- **A task has read a message when its agent takes it** - moves it out of its inbox's `new/` into `cur/`,
  the moment Sokar already counts as taken (operator, 2026-09-29). Sokar then runs the transport's
  `read <event id>` with that task's own token, which posts the Matrix read receipt for it; the event id is
  the name `poll` gave the file. The person's client shows it read, and `describe` keeps
  `confirms: "read"` truthfully. A `read` that fails is retried on 75 like a send, and never stops the
  task from having the message.
- `receipt <reference> --by <task's account>` is asked for what a task sent, and `read` / `delivered` /
  `unknown` becomes the message's read state; `unknown` is not an error.
- Exit codes as agreed: 75 is retried later; 77 and 78 are said once, as a misconfiguration of that
  homeserver, and not retried every cycle - a certificate the transport does not trust is 78, so a
  token is never sent to it again and again.

### Polling

- A daemon polls a transport only if a peer of one of the account's projects names its scheme; with no
  Matrix project, the Matrix transport is never started, whatever `describe` says about `poll`.

### A person joins

- An account per person: `sokar messages join <project> <person>` has the provisioning account make
  `@<person>:<server>` on the project's homeserver, invite it into the project's room, and print the
  homeserver, the user, the room and a password once. Nothing of it is kept but the account's name and
  whom it is for, so the password is shown once and never again.
- A second `join` for the same person says the account exists; `--reset` sets a new password and shows it
  once, the same way. `sokar messages members <project>` lists who has joined.
- One person in several projects of one homeserver is one account, invited into each room.
- Being in the room lets the person read and write there; their messages are delivered to a task only
  once their key is listed for a peer, as anyone's.
- For the account's own homeserver the printed address is loopback, and the command says so: a person at
  another computer forwards the port first (`ssh -L <port>:127.0.0.1:<port>`), as for the redirect flow of
  an authorization. An `offline` project may be joined that way too: the person's client reaches the
  homeserver through their own ssh connection, and the project's messages still never leave the machine.

### What the daemon answers an interface

```
# A person joins a project's room, or gets a new password with reset. Answered once: the password is in
# this reply and nowhere else, never kept.
method JoinMessages(project: string, person: string, reset: ?bool) -> (
  # The homeserver as the person's client reaches it. For the account's own, http://127.0.0.1:<port>.
  homeserver: string,
  # true when homeserver is this machine's loopback: a client elsewhere forwards port first.
  loopback: bool,
  port: int,
  user: string,
  room: string,
  password: string
)

# Who has joined a project's room, by the name join was given.
method MessageMembers(project: string) -> (members: []MessageMember)
type MessageMember (person: string, user: string)

# Added to Project:
#   messages: ?ProjectMessages - absent for a project with no Matrix peer.
type ProjectMessages (
  # The declared URL, or empty: this account's own homeserver on loopback.
  homeserver: string,
  room: string,
  # Whether this machine can carry the project's messages now: the account's unit answering, or the
  # central server reachable with this machine's provisioning account. With detail when not.
  ready: bool,
  detail: string,
  # true for an offline project: loopback only.
  loopbackOnly: bool
)

error NoSuchProject(name: string)
error MemberExists(person: string, user: string)
error HomeserverUnreachable(homeserver: string, detail: string)
error ProvisioningMissing(homeserver: string)
error OfflineHomeserver(project: string, homeserver: string)
```

- `MemberExists` answers a second join without `reset`; `OfflineHomeserver` a declared homeserver that is
  not loopback for an `offline` project. An `offline` project is joined from elsewhere through a forwarded
  port, so being away from the machine is not itself a refusal.

### Retiring the old transports

- `transport-local` and `transport-spool` stay until the Matrix transport is released and a project on
  it has run on both VMs; then they are removed in one change that says so, and a project still naming
  `local:` or `spool:` is refused with the sentence that says what to write instead.

## As built in Sokar, 2026-09-30

- **`project.yml`:** `mail.transports.<scheme>` is kept as written and handed to the transport; a peer
  address `<scheme>:` means the project's conversation (`Mail.Peer.conversation()`, `Mail.conversations()`).
- **`TransportDescription`** reads `confirms` and `lifecycle`; a transport that offers `setup` keeps a
  conversation.
- **`TransportLifecycle`** runs the four verbs - settings on stdin, secrets in the environment - and keeps
  what they print in the vault as `transport/<scheme>/account`, `.../project/<p>`, `.../task/<t>` (hidden like
  a task's tokens) and the conversation and what it reaches in `$XDG_STATE_HOME/sokar/transport/<scheme>/<p>.json`.
- **A task's start** (`TaskConversations.enroll`) runs `setup` and `enroll` for each conversation before
  anything exists, and refuses (69) when a transport refuses or an offline project's conversation reaches
  beyond loopback. **Its removal** runs `retire`; a refusal is said and does not stop the removal.
- **Each message pass** (`TransportConversations`) polls each project's conversation once with the
  project's secrets, hands what arrived to the project's task `metadata.to` names (short or container
  name), drops the rest, and runs `read` with the task's secrets for what its agent moved into `inbox/cur`,
  once, when the transport confirms reading. Failures are said once, not every pass.
- **Sending** acts as the task: its secrets in the environment, and the conversation as `--to`.
- **A mailbox's own poll** asks only transports a peer of its project names, and never a conversation's.
- **A project's own tasks** are addressed through its conversation when it has one, not the local
  transport.
- **`sokar talk join PROJECT PERSON [--reset]`**, the daemon's **`JoinMessages`** and **`MessageMembers`**,
  and **`Project.messages: ?ProjectMessages`**. Errors `NoConversation`, `MemberExists`,
  `ConversationRefused`.
- **Proven:** `TransportConversationsTest` against a stand-in, `ProjectMailTest`. Not yet on a VM: that waits
  for the Matrix transport's verbs.
- **Not built yet:** asking `receipt` for what a task sent and recording the read state (point 6).

## Acceptance

- Two projects of one account on its homeserver: a message signed by a peer of project A, posted into
  project B's room, is held in B and never delivered; the operator is told which peer and which room.
- A task's container has no `SOKAR_MATRIX_*` variable and cannot reach the homeserver's port.
- Starting a task makes `@<task>:localhost`, in the project's room and no other; removing it deactivates
  the account, after which its token is refused.
- A task sends; the reference is kept; once the person's client has read it, the message's read state is
  `read`. A person sends to a task; once the task's agent takes it, the person's client shows it read.
- An `offline` project naming a non-loopback homeserver is refused before anything exists; with none,
  it messages through the account's own.
- `sokar messages join` prints a login that works in a Matrix client, into that project's room only; a
  second join without `--reset` is refused as existing, and `members` lists it.
- The transport is never given the administrator's token: polling uses the relay account.
- After a reboot without a login, the account's homeserver answers again.
- An account with no Matrix project runs no homeserver and never starts the Matrix transport; one whose
  homeserver is misconfigured says so once, not every cycle.
- No token appears on a command line, in a log, or in `vault list`.

## Still open

- **Several machines on one central homeserver: who lets the second machine into the room?** (Agent
  Matrix, 2026-09-30.) The room is invite-only; the first machine's provisioning account made it, and the
  second machine's accounts get in only if someone in the room invites them. For the operator to decide
  before the central case is built; the single-machine case is built first.
- **A central homeserver's first registration** takes the central server's registration token from the
  vault as `SOKAR_MATRIX_REGISTRATION_TOKEN` in `setup`'s environment; how the operator puts it there is
  decided with the central case.


- **A central homeserver, measured.** Everything above for one is specified, none of it is measured: the
  admin room's `deactivate` on a central Tuwunel, and a provisioning account per machine there.
- **Where a central homeserver's provisioning token is looked up** - one vault entry per homeserver URL
  is the leaning; its naming is decided when it is built.
