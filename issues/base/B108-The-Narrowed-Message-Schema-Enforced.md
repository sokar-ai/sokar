# B108 — The Narrowed Message Schema Enforced

**Status:** soon.

**What must be true.** An agent in a task can send only messages of the narrowed schema B14's design describes, and
the guide it reads tells it exactly that schema, not a looser one.

## Why

B14's design narrows A2A 1.0 for Sokar's messages, and nothing enforces it; decided that it is to
be enforced. The mailbox guide (`/run/sokar/mail/README.md`, in `doc/messages.md`) tells agents the looser rules that are enforced today. The filter's side, if it checks it, is
`sokar-message-sluice`'s.

## The shape

- The narrowed schema of B14's design, enforced where a message leaves a task:
  - `metadata` holds `kind`, `to`, `from`, `thread` and nothing else; `kind` is one of `question`, `answer`,
    `review-request`, `handover`, `status`, each with its fixed data part.
  - Exactly one text part within the peer's limit, at most one data part; no file, raw or url part.
  - `extensions` exactly a Sokar messaging extension URI, which has to be defined first.
- The mailbox guide and `doc/messages.md` say it the day it is enforced, from the same constants.

## Acceptance

- A message whose `metadata` holds a key other than `kind`, `to`, `from`, `thread`, or whose `kind` is not one of
  the five, or whose data part does not match its kind, is refused where it leaves the task. Seen to fail: a test
  sending each such message is accepted.
- A message with no text part, more than one text part, a text part over the peer's limit, more than one data part,
  or any file, raw or url part, is refused. Seen to fail: a test sending each such message is accepted.
- A message whose `extensions` is not exactly the Sokar messaging extension URI is refused. Seen to fail: a test
  sending a message with no or another extension URI is accepted.
- The mailbox guide and `doc/messages.md` state the narrowed schema from the same constants the check uses. Seen to fail:
  a test comparing the guide's kinds and limits with the enforcing constants finds a difference.

## To be checked

- Which side checks what: the host, the filter, or both.
- Whether a message from before is refused or converted.
