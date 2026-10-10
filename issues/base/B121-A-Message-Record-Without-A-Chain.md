# B121 — A Message Record Without A Chain

**Status:** blocked by B154: the chain is the only tamper-evident record today, so it goes only once
B154's event log replaces it.

**What must be true.** A task's message record says what Sokar did with each message on this host - filtered, held,
refused, delivered - as plain state, and nothing in it or about it claims to prove what was said or in which order;
that is the homeserver's to keep.

## Why

Every line of `MessageRecord`'s `log.jsonl` carries the hash of the line before it, and `sokar talk verify` names the
first line that does not follow. The chain is answering a question the conversation no longer puts to the host:
order and immutability of what was said are kept by the homeserver, locally or remote, which holds the room's
history. What the chain proves on its own is little - anybody who can write the file can write a whole new chain,
nothing signs or publishes its head, and `task remove` deletes it with the mailbox.

What the record is used for stays: duplicate suppression reads the ids already delivered from it, budgets count a
peer's events in it, a person's refusal stays final because a `refused` line is in it, and `sokar talk log` replays
it. None of that reads `previous` or `hash`. The homeserver never sees what happens after a message reaches the host,
so this state is Sokar's.

Signatures are not part of this. A message is signed on the host and checked against a peer's listed keys before it
is delivered; that decides who may reach a task, not whether a history was changed, and it stays. Checking a kept
message against its `.sig` again later adds nothing to the check made when it arrived, so `sokar talk verify` goes
with the chain.

## Acceptance

- A line written to the record has no `previous` and no `hash`. Seen to fail: a unit test reading a freshly written
  line, against the code that still writes them.
- No code reads, migrates or tests a record written with the chain; backward compatibility with one is not kept.
- `sokar talk verify` is gone: the command, its daemon method if it has one, its row in `doc/commands.md`, and every
  scenario that runs it. A message's signature is checked when it arrives, as today. Seen to fail: `sokar talk
  verify` is still answered by anything but "unknown command".
- `doc/messages.md`, `doc/commands.md` and the decision "Each host keeps its own record" say what the record is now:
  the host's own state, with the conversation's history on the homeserver. B14's design says the same, and B15 keeps
  its own journal without a record of B14's to share.

