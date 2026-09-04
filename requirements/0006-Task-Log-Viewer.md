# 0006 — Task Log Viewer

**Status:** open

A task writes several separate records: what it was refused, what its credential
proxy did, what its git endpoint served, and what the agent printed. All of them are
readable in one place, and can be followed while the task runs.

## Acceptance

- Each log is selectable and can be followed live.
- The audit record renders as structured rows, not raw text.
- Searching within a log works while it is being followed.
- A log that does not exist yet says so rather than showing an empty pane.
- Nothing in any log view reveals a credential.

## Notes

Depends on [0001](0001-Local-Daemon-API.md) streaming. Log volume from a long run is
significant; the client must not hold it all in memory.

## To be checked

- How large do these get over a long unattended run, and does following one need
  server-side windowing rather than sending everything and letting the client cope?
