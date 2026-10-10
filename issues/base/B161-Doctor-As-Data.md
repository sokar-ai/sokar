# B161 — Doctor As Data

**Status:** open. Blocks `sokar-frontend` F106.

**What must be true.** `sokar doctor --json` says every finding as data - the check, its state, the words and the
remedy - from the same checks the text runs, for a client to read; the exit code is the text's.

## Why

The interface is to show what `sokar doctor` finds on a machine it adds, in its words and colours (B157), so the checks
live in one place. Reading the text would tie a client to its layout and its colours.

## The shape

- One JSON object on standard output, nothing else on it:
  `{"version": "...", "findings": [{"check": "hooks", "state": "OK|DEGRADED|MISSING|UNKNOWN", "detail": "...",
  "remedy": "..." | null}], "notes": [{"kind": "not-used|not-taken|failures", "detail": "..."}]}`.
- The same probes, in the same order, as the text; the text and the JSON come from one list, so they cannot disagree.
- The exit code as without `--json`: 69 when anything is `MISSING`, 0 otherwise.
- No colour and no escape codes in it, whatever `--color` says.

## Acceptance

- Seen to fail first: `--json` refused as an unknown option today; afterwards every probe of the text is in it, with
  its state and remedy, and a machine with a missing part is `MISSING` and exits 69.
- `doc/commands.md` gives the shape.

## To be checked

- Whether the daemon answers the same as a method, for a client that is already connected.
