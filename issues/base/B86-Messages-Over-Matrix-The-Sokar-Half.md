# B86 — Messages Over Matrix, The Sokar Half

**Status:** open, written 2026-09-29 from `sokar-project` PJ02 at the operator's word, relayed by Agent
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
  `credentials: [{"name":"SOKAR_MATRIX_ACCESS_TOKEN","as":"env"}]`, `hosts` from the package's installed
  configuration, `attests: []` until `sender` has its evidence file.
- `send <file> <sig> --to <room id>` prints `{"reference":"<event id>"}`; Sokar keeps it as
  `sent/<message>.receipt.json`. A room id may have no `:server` part; Sokar splits an address at its first
  colon only.
- `poll --into <inbound>` writes `<event id>.json` and `.json.sig`; a message without a signature gets no
  `.sig` and is held; a person's plain chat is not delivered, and is counted on stderr.
- `receipt <reference> --by <account>` prints `{"state":"read"|"delivered"|"unknown", "at": …}`, exit 0 for
  all three.
- Exit codes: 0 done, 75 temporary, 64 usage, 65 not carriable, 76 answer not understood, 77 refused by the
  homeserver, 78 configuration missing. The homeserver's address comes from `SOKAR_MATRIX_HOMESERVER`.

## What must be true, in `sokar`

1. **The peer table resolves every peer of a Matrix project to the project's room**, and delivery on the
   way in keeps only what names one of this host's tasks in `metadata.to`.
2. **A task's account is provisioned when it starts and deleted when it is removed**, by a host-side
   credential that can create accounts, kept in the vault. The first account registered on a fresh
   homeserver becomes its administrator, so the host's provisioning account registers first. Deleting an
   account needs its own password or the administrator's rights; whichever is used is kept in the vault,
   never in a task.
3. **The transport's token reaches it from the vault as `SOKAR_MATRIX_ACCESS_TOKEN`**, never on a command
   line and never in a container.
4. **`describe.hosts` feeds what the host lets the transport reach**, and a task reaches none of it.
5. **`receipt` is asked about what was sent, by the account of the task it was for**, and the answer is
   recorded as the message's read state.
6. **One homeserver per machine is not a bridge between projects.** This is PJ02's one open question, and
   it is this issue's to answer: the gate precedent gives the mechanism - one endpoint named by Sokar, never
   "the machine" - but a gate is per task and a homeserver is per machine. The answer has to say what keeps
   two projects' tasks apart on it: separate rooms are not enough on their own, since room membership is
   not authorization.
7. **An `offline` project and messaging**: whether it may use a homeserver on loopback, named by Sokar as
   the gate is, or may not message at all - decided here, not by the first implementation.

## To be checked

1. The answer to point 6.
2. The answer to point 7.
3. Whether the provisioning account's password or the administrator's rights delete a task's account.
