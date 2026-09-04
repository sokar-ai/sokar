# 0015 — Health And Diagnostics

**Status:** open

Several dependencies fail silently rather than loudly: a missing capability in a
resolver, a policy that blocks a mount, a stale copy of a binary shadowing the
installed one. Each produces a symptom far from its cause.

## Acceptance

- Every external dependency is probed and reported by name.
- A capability that is present but compiled out is detected, not assumed from a
  version number.
- Shadowed binaries are named, with what is hiding what.
- Each failure carries the single next action.

## Notes

Where a probe cannot answer, it must say so rather than assume the good case.
