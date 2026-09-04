# 0020 — Narrow The Git Endpoint

**Status:** open

While a task runs, the endpoint serving its repository binds every interface, so
anything on the local network can reach it. A per-task token is what keeps it shut,
which is one defence where there should be two.

## Acceptance

- The endpoint is unreachable from any other machine.
- The task still clones and pushes.
- Outbound filtering is unaffected, verified by the acceptance suite rather than by
  inspection.

## Notes

A loopback bind is measurably unreachable from inside a task. The container network
can be told to map one address to the host's loopback, which works, but the obvious
way of asking for it discards the runtime's own networking defaults and silently
opens outbound filtering. The next thing to try is the configuration drop-in that is
already written at setup time, which adds to those defaults instead of replacing
them.

## To be checked

- **The proposed next step is untried.** Whether the configuration drop-in adds to
  the runtime's networking defaults, rather than replacing them the way the direct
  option did, is an assumption. Verify it with the acceptance suite before believing
  it, because the failure mode last time was silent: outbound filtering was open while
  everything reported success.
