# F17 — Network Exposure Control

**Status:** open

What running work is allowed to reach, changeable while it runs, plus the live view of
what it is being refused.

## Acceptance

- The exposure level of running work can be changed from where that work is listed,
  without restarting it.
- The available levels are named by what they permit, not by internal terms, and the
  current level is always visible on the work itself.
- Turning enforcement off entirely is possible, distinct from every other level, and
  visibly marked wherever that work appears.
- Refused connections can be watched live, with enough context to tell what was being
  attempted and by which piece of work.
- A refusal awaiting a person's decision can be allowed or denied from the interface,
  and the answer reaches the work that is waiting.
- Several pieces of work can be watched at once in one view rather than one view each.

## Notes

Related: [Clearance Prompts](../base/B02-Clearance-Prompts.md) for the decisions
themselves, and [Egress Sets Editor](../base/B04-Egress-Sets-Editor.md) for the standing
rules. This file covers the live controls attached to running work.
