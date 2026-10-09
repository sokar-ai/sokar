# B139 — An Alert On What Looks Wrong

**Status:** open.

**What must be true.** Sokar raises an alert on anomalies a person should see - very large prompt bodies, many clearance attempts, unusual name patterns under declared domains - and a project may choose to contain the task on an alert: freeze or stop it and revoke its token.

## Why

Today these are journalled (`reach.md`) and nobody is told while it happens.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §5/§6; Acalvio 7, 10, 11; ACSC (frequent repetitive prompts, a baseline); AISVS 9.1.3, 9.6.1, 9.6.3. From a review of security guidelines, 2026-10-09.

## The shape

Which signals, with which thresholds per project; the alert through the channels Sokar already has (notification, the interface); the containment option per project, off by default.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Near B26 and B38; which baselines are measured before a threshold is set.
