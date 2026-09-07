# F19 — Host Readiness And Remediation

**Status:** open

Whether this machine can actually run anything, established on first launch and
checkable at any time afterwards, with the fix offered rather than described.

## Acceptance

- On first launch the interface establishes whether the machine is ready, states the
  verdict in plain terms, and offers to do the preparation.
- Preparation can be declined, and declining does not repeat the same question every
  launch.
- The same check can be run again later on demand.
- Anything missing or misconfigured is named specifically, along with what it prevents.
- Where the interface can fix a problem itself, it offers to; where it cannot, it says
  what a person has to do.
- Known environment-specific obstacles are recognised as such and get their own
  guided fix rather than a generic failure.
- Progress and output of the preparation are visible while it runs.

## Notes

Related: [Health And Diagnostics](../base/B05-Health-And-Diagnostics.md). The distinction
here is that the interface does not merely report — it offers the remedy in place.
