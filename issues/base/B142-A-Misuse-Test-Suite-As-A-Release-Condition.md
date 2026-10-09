# B142 — A Misuse Test Suite As A Release Condition

**Status:** open.

**What must be true.** A release passes a suite of misuse scenarios - escaping the container, exfiltration past the firewall and the broker, bypassing the approval - before it is made.

## Why

The guarantees are tested one by one today; a suite that tries to break them as an attacker would is what a release should have to pass.

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §10; Acalvio 12. From a review of security guidelines, 2026-10-09.

## The shape

Scenarios in the acceptance suite, tagged, run by the release workflow; each names the guarantee it attacks and the page that states it.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Which scenarios first; how a found hole is handled before the release.
