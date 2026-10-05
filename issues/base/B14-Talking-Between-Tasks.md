# B14 — Talking Between Tasks

**Status:** later; blocked by sokar B86 (another machine).

**What must be true.** A person can address a group of peers as one - held, released and closed as one - and a
task's message reaches a task of the same project on another machine with the same guarantees it has on one:
signed on the host, filtered, attributed against a key list, delivered once.

Built and measured on one machine, over a transport that keeps a conversation (B86): the mailbox, the signature,
the sluice both ways, dispatch, delivery against a key list, the record, duplicate suppression, bounces, budgets,
holding and releasing with the four modes, `sokar talk`, and the daemon's message methods. A message addressing
more than one peer is refused with a reason today. How groups would be built is
[B14-Talking-Between-Tasks_design.md](B14-Talking-Between-Tasks_design.md).

## Acceptance

**Groups**

- A group is a list of peers on the host; a message to it reaches every member over whatever transport each uses.
  Seen to fail: a member missing the message, or a message to a group still refused as addressing more than one
  peer.
- A group can be held, released and closed while it runs; a held message reaches no reader and its sender is
  told. Seen to fail: a member reading a message sent while the group was held.
- Inside a group, the keys that may speak for a peer come from a directory signed by an operator key and carried
  in the project repository; a directory signed by any other key, or older than the one in force, is refused.
- A peer outside the group is confirmed once by a person; a changed key is held with both fingerprints and asked
  again. Seen to fail: a message under a changed key delivered without a person.

**Another machine**

- Two machines on one homeserver run one project: a task on each writes to the other, and each message arrives
  byte for byte (hashed in `outbox/new/` and again at the far end), its signature verified against the sending
  machine's listed key. Seen to fail: a message signed by the other machine's unlisted key delivered rather than
  held.
- A message retried by the transport between machines is delivered once.
