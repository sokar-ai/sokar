# 0007 — Attach To A Task

**Status:** open

Attaching hands off to the operating system's terminal rather than embedding one.
An embedded terminal widget is a large amount of surface for a worse result, and the
handoff is the established answer rather than a compromise.

## Acceptance

- Attach opens a working interactive session in the task's container.
- The chosen terminal is discovered, with a documented fallback order.
- Failure to find a terminal produces a copyable command instead of an error.
- Detaching leaves the task running.

## Notes

Where no local terminal exists — a remote or mobile client — this requirement is met
by [0017](0017-Remote-Access.md) instead, or not at all.

## To be checked

- What does attach mean where there is no local terminal — a remote or mobile
  client? Either it is absent there, or it needs a different mechanism entirely.
  Decide which rather than letting it fail on the day.
