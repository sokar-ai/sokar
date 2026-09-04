# 0004 — Clearance Prompts

**Status:** open

When a task is refused an outbound destination, the person decides. The prompt has
to say what asked, for what, and on whose behalf, and the decision must be
recordable as a standing answer rather than asked again per connection.

## Acceptance

- The prompt names the destination, the task, and the project.
- A decision applies immediately to the blocked connection.
- The same destination is never asked twice for one task.
- A prompt that expires is shown as expired, not silently dropped.
- Decisions are written to the task's audit record whether answered or not.

## Notes

Prompts are time-bounded. Any transport that cannot deliver them promptly makes
this requirement unmeetable — see [0017](0017-Remote-Access.md).

## To be checked

- How long does a prompt stay answerable, and is that long enough for a person who
  is not at the machine? The answer decides whether remote clients can serve
  decisions at all.
