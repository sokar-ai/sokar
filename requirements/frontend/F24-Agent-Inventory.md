# F24 — Agent Inventory

**Status:** open

An agent is installed separately from the tool that runs it, so what is installed is a real
question. So is what it may reach, and which build of its own binary it will fetch.

Moved here from the central index, where it was 0011.

## Acceptance

- Every installed agent is listed with its version and where it was found.
- The destinations it may reach are shown, including those deliberately refused.
- The pinned tool version and its digest are shown.
- An agent shadowed by another copy is reported as not in use.

## Notes

**Nothing in the interface may name a specific agent**; the list is discovered. The daemon's
`Agents` call answers it, including the agents that failed to describe themselves - one that
cannot answer is installed and unusable, and leaving it out would read as absent.
