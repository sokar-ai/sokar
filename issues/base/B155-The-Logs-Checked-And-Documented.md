# B155 — The Logs Checked And Documented

**Status:** open.

**What must be true.** What broker, gate and journals write is documented field by field: no prompt contents, tokens or personal data, or redacted; where each log lies and who can read it.

## Why

Logs are sensitive data themselves; nobody has checked all of Sokar's at once.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §6/§8; NCSC (logs as sensitive data). From the operator's review of security guidelines, 2026-10-09.

## The shape

A page listing each log; a test that the broker's and the gate's logs contain no credential; file rights as B50 sets them.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- None.
