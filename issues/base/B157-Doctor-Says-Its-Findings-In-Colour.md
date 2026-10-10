# B157 — Doctor Says Its Findings In Colour

**Status:** implemented here.

**What must be true.** `sokar doctor` colours what it found: a failure red, a warning yellow, what is fine green. The
words stay as they are, so colour is never the only signal - for a reader who cannot tell the colours apart, a screen
reader, or a log.

## Why

A machine that cannot run a task says so in one line among twenty, `MISSING - ...`, in the same plain text as every
line that is fine. Found in the joint test, 2026-10-10: the failing line had to be searched for.

## The shape

- Red: `MISSING`. Yellow: `DEGRADED`, `UNKNOWN`, a copy not used or a description not taken, and the note that a command
  failed unexpectedly. Green: `OK`.
- Only the state word and its line's name are coloured; what follows is plain.
- Colour only when standard output is a terminal: piped or redirected output carries no escape codes.
- `NO_COLOR` set to anything turns colour off (no-color.org). `--color=auto|always|never`, `auto` the default;
  `always` and `never` win over both the terminal and `NO_COLOR`, since they are what a person asked for on this
  command.

## Acceptance

- Seen red first: a terminal stood in gets the codes; a pipe and `NO_COLOR` get none; `--color=always` and
  `--color=never` do what they say.
- `doc/commands.md` says it.
