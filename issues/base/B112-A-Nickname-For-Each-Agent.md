# B112 — A Nickname For Each Agent

**Status:** later

**What must be true.** A person can call each agent by a nickname given when its work starts: it is
shown in the window, the room and the direct chat, named with `@`, and the agent knows when a
message names it by either its nickname or its task.

## Why

From walk 10; this is Sokar's side of the window's F88 in sokar-frontend. Decided: Later.

## The shape

Each agent has a nickname, given when its work starts, shown in the window, the room and the direct chat, and named
with `@`. Sokar's part:

- **The nickname is the task's label.** `@label` in the room resolves to that task, as its task name does now.
- **`agent-card.json` says who the agent itself is:** `"you": {"task": …, "nickname": …}`, so it knows when a message
  names it by either.
- **The display name in the conversation** goes through the transport: `enroll --display` at enrolment and
  `rename --display` when the nickname changes. Only a transport whose `describe` says `"display": true` is asked.
  `sokar-message-matrix` has both since MX15.
- **`metadata.to` and `metadata.from` stay task names**, so the filter and every record keep one name per task.

## Acceptance

- `@label` in the room reaches the task whose label it is, as `@` with its task name does.
- `agent-card.json` carries `"you": {"task": …, "nickname": …}`.
- The transport is asked to set the display name at enrolment and when the nickname changes, and only when its
  `describe` says `"display": true`.
- `metadata.to` and `metadata.from` carry task names, never a nickname.
- **Seen to fail:** a test that writes `@label` in the room goes red when the task is not reached; a test of the
  card goes red when `you` is missing either name; a test with a transport that does not declare `"display": true`
  goes red when it is asked; a test of a message to a nickname goes red when its metadata carries the nickname.
