# B122 — No Direct Chat Between Tasks

**Status:** now.

**What must be true.** Two tasks never talk in a direct chat: a direct chat exists only between a
task and a person who joined the project's conversation, so what tasks say to each other is said
where the operator reads along.

## Why

A conversation server may encrypt end to end, and then whoever is not in a chat cannot read it. The
operator is in the project's conversation, so what tasks say and hand each other there stays visible
to them; a chat between two tasks would not be.

Within a project this already holds: every task of the project is reached through its conversation,
and a direct chat is used only to answer a person who wrote directly. The gap is a peer declared in
the project file with an address of an account, `<transport>:@account`, which `sokar talk peers`
shows as "a direct chat with" it. Nothing checks that the account is a person, so a task of another
installation declared that way, and a message with `"via": "direct"`, makes a chat between two tasks
the operator is not in. The default `others: deny` holds it back until a project allows it; nothing
forbids it.

## Acceptance

- A peer whose address names an account is accepted only for a person who joined the project's
  conversation, and a project file that names any other account as a peer is refused when it is
  read, naming the peer and why. Seen to fail: a project file with a peer at an account that is no
  person of the conversation is read without a refusal.
- A message with `"via": "direct"` to anything but such a person is refused where it leaves the
  task, naming why, never sent into a direct chat. Seen to fail: a unit test sending one to another
  task's account finds it handed to the transport.
- A task still answers a person in their direct chat, and a person still writes to one task
  directly. Seen to fail: the existing direct-chat scenarios.
- The mailbox guide and `doc/messages.md` say that a direct chat is only ever with a person, and
  why.
