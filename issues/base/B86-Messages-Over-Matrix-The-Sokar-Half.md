# B86 — Messages Over Matrix, The Sokar Half

**Status:** later; blocked by sokar-message-matrix MX12.

**What must be true.** Several machines run one project on one central homeserver: each is admitted by a person,
each acts only on the accounts it made, and one can be revoked alone while the others keep messaging.

Sokar's side is built and has never run against a central server: `--machine` to a transport whose `describe`
lists `"takes": ["machine"]`, `setup`'s `admitted`, `machine`, `address` and `room`, no task started while
`admitted` is false, and the daemon's `SetUpMessages(project)`. A central homeserver has no administrator: each
machine holds its own provisioning account there, with each task's password kept beside its token. The
transport's side is MX12.

## Acceptance

- Two machines run one project on one central homeserver: a task of the same name on each gets an account of
  its own. Seen to fail: the second machine's `enroll` taking the first one's account over, or a transport
  accepting (not refusing with 78) a machine name another machine already holds.
- A machine a person has not let into the room starts no task of the project and says whom to let in and where;
  once admitted, it starts them.
- Removing a task on one machine deactivates that task's account and no other machine's.
- One machine's access is revoked alone, and the other machines' tasks keep messaging.
- No token appears on a command line, in a log, or in `vault list`, on either machine.

## To be checked

- How the operator puts a central homeserver's registration token into the vault, from where `setup` takes it as
  `SOKAR_MATRIX_REGISTRATION_TOKEN` for the first registration.
- Whether the central server's admin-room `deactivate` works as specified on a central Tuwunel, with a
  provisioning account per machine.
- Where a central homeserver's provisioning token is looked up: one vault entry per homeserver URL is the leaning;
  its naming is decided when it is built.
