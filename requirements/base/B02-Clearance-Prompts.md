# B02 — Clearance Prompts

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
this requirement unmeetable — see [B06](B06-Remote-Access.md).

## Measured

2026-09-04, captured from the notification bus rather than described from the code:

```
summary   Sokar: <project>/<task> blocked
body      The agent tried to reach 1.1.1.1:443 over tcp.
actions   allow -> Allow,  deny -> Deny
```

Two things that reading the code would not have shown:

- The summary named the **container**, pid and all, which identifies nothing an
  operator would recognise. It now names the project and task.
- The destination was a bare address, correctly - nothing had resolved it. Looking
  an address back up to the name the container was told only helps in a narrow band:
  a **declared** name is now allowed outright, so it never reaches a prompt, and an
  **undeclared** name is never answered, so no mapping to look up exists. The case it
  was built for - a name resolved but blocked anyway - was a firewall bug fixed the
  same day. That path is therefore not well exercised and should not be assumed to
  work.

A notification is a message on a bus before it is pixels, so the content is testable
without a display. That is the instrument to use here; a screenshot would assert on
a theme.

## To be checked

- How long does a prompt stay answerable, and is that long enough for a person who
  is not at the machine? The answer decides whether remote clients can serve
  decisions at all.
