# F03 — Project Environment Preparation

**Status:** open

Making a project runnable, and rebuilding it when something underneath has moved.
The distinction that matters is how much gets thrown away, because the cheapest
rebuild takes seconds and the most thorough takes many minutes.

## Acceptance

- Preparing a project's environment from scratch is one action, and it completes or
  fails with a stated reason.
- Rebuilds are offered at distinct depths, described by what each one replaces and
  roughly what it costs in time — reuse what exists, replace only the agent tooling,
  or discard everything and rebuild.
- Progress is visible while a build runs, and the rest of the interface stays usable
  meanwhile.
- A build that fails names the step that failed, and the failure is still readable
  after the fact.
- Starting work against a project whose environment is stale or absent says so
  before anything is started, not after.

## Notes

The depth choice is the whole requirement. A person who cannot tell the three apart
picks the slowest one every time, or the fastest one and gets a stale result.
