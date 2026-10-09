# B153 — Unused Slots And Keys Reported

**Status:** open.

**What must be true.** A device slot of the vault or a deploy key not used for a set period is reported, and may be locked.

## Why

Access that nobody uses stays open until somebody notices.

**Guideline points it answers:** ACSC (privileged access after inactivity). From the operator's review of security guidelines, 2026-10-09.

## The shape

Last use recorded per slot and key; `sokar doctor` names the unused; locking per setting.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Near B117.
