# Writing a transport

A transport is a package on the host that carries signed messages between Sokar installations. Sokar
finds it, asks it to describe itself, and runs it as a command; it never runs inside a task. This page is
for whoever writes one; what a project's conversation is for the people using it is in
[messages between tasks](messages.md#where-the-conversation-lives).

## What every transport answers

- **`describe`** reads nothing and prints one JSON object: `scheme`; `poll`; `confirms` (`handover`, `receipt`
  or `read`); `max_bytes`; `credentials` (for example `[{"name":"SOKAR_MATRIX_ACCESS_TOKEN","as":"env"}]`);
  `hosts`; `attests`; and, if it keeps a conversation, `lifecycle` (the verbs below it offers) and `takes`
  (`["machine"]` to be told this machine's name). A transport whose description cannot be read is not polled
  and attests nothing.
- **`send <file> <sig> --to <rest>`** hands one message over. `<rest>` is the peer's address after its first
  colon, never a path Sokar invented. It prints its receipt as one JSON object, `{"reference": "..."}`.
- **`poll --into <dir>`** writes what arrived into that directory, each message with its detached signature as
  `<message file name>.sig`, and prints how many. A message without a signature gets no `.sig` and is held.
- **`read <reference>`**, for a transport that confirms `read`, marks a message read with the reading task's
  secrets, printing nothing; posting twice is still 0.
- **`receipt <reference> --by <address>`** prints `{"state": "read" | "delivered" | "unknown", "at": ...}`, exit
  0 for all three.

`describe` may also announce options Sokar then passes, each as `true`; an option a transport does not
announce is never passed, since an unknown one is its 64:

| Announced | What Sokar then does |
|---|---|
| `"shown": true` | passes `--shown <file>` to `send`: a file beside the message, written by the host from the checked message, holding the line a person is shown for it |
| `"mention": true` | passes `--mention <account>` to `send`: the account of the task or person the message is for, so the conversation shows whom it is meant for; never in a direct chat |
| `"persons": true` | passes `--persons` to `poll`: hand over what people say in the conversation as well |
| `"direct": true` | also polls each task's direct chats, with `poll --into <dir> --direct` and that task's secrets, and hands what arrived to that task alone |
| `"waits": true` | passes `--wait <seconds>` to `poll` (at most 30): the server may hold its answer open until something arrives |

Nothing is read from standard input except a lifecycle verb's settings: messages are files. A transport never
changes a message's bytes, never decides whether a message may be sent, never reads another transport's queue,
and never creates a directory: it delivers into the `tmp/` Sokar made and renames, and refuses or defers when
something is missing.

**Exit codes:** 0 done; 64 usage; 65 not carriable; 75 temporary (the message is retried); 76 answer not
understood; 77 refused by the server; 78 configuration missing. Every code but 0 and 75 is a refusal, said once.

**`attests`** lists facts a transport proves about a message from something the host cannot see afterwards; it is
`[]` for a transport that only carries bytes. A transport that claims a fact and hands over a message without it
has the message held; one that claims nothing has such a file ignored.

## A transport that keeps a conversation

Such a transport offers a lifecycle. Every verb takes the project's settings (what `project.yml` says under
`mail.transports.<scheme>`) as JSON on standard input, takes the secrets Sokar kept for it as environment
variables, never as arguments, prints one JSON object, and exits with the codes above. Any `secrets` it prints are
kept in the account's vault and handed back; they are never shown, logged or put into a task.

| Verb | When Sokar runs it | Given | Prints |
|---|---|---|---|
| `setup --project P` | before the first task of the project starts, and whenever its secrets are missing | the account's secrets | `secrets` (the project's), `account` (the account's), `conversation`, `reaches`, and on a shared server `machine`, `address`, `room`, `admitted` |
| `enroll --project P --task T` | when a task starts, before its container exists | account and project secrets | `secrets` (the task's), `address` |
| `retire --project P --task T` | when the task is removed | account, project and task secrets | `{}` |
| `join --project P --person N [--reset]` | on `sokar talk join` | account and project secrets | `login` (for an interface, answered once), `shown` (for the command line) |
| `settings` | when a project file is checked | nothing: no secrets, no network | `refused`, `warnings` |

- **Every map of secrets is complete** for the verbs that use it: a project's are exactly what `poll` reads, a
  task's are what `send`, `read` and `receipt` read, and the account's go only to `setup`, `enroll`, `retire` and
  `join`, never to `poll` or `send`.
- **`setup` is run again and again** and keeps what exists. A server whose first account exists while Sokar holds
  no account secrets is refused (78), not guessed around.
- **`reaches`** is every host the project's messages reach; Sokar lets the transport reach those and nothing else.
  For an offline project Sokar adds `--loopback-only` to `setup`, `enroll` and `join`, and the transport refuses
  (78) before it contacts anything but loopback.
- **`--machine NAME`** is added to `setup`, `enroll`, `retire` and `join` for a transport that `takes` it. The
  transport keeps the name it first used, so a renamed machine keeps its accounts, and refuses (78) a name another
  machine already holds.
- **`"admitted": false`** from `setup`, exiting 0 with its `account` secrets, means a person still has to let this
  machine in; Sokar starts no task of the project until it is true, and absent means true.
- A verb a transport does not list is never run.

Sokar polls each conversation once per pass with the project's secrets, hands what arrived to every task of that
project on this machine but the one that posted it, drops what no task here is to read, runs `read` with a task's secrets once its agent has moved a
message into `inbox/cur`, and asks `receipt` about what a task sent, by the recipient's address.
