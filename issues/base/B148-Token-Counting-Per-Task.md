# B148 — Token Counting Per Task

**Status:** open.

**What must be true.** The broker counts each task's tokens per request, from the provider's own answer, and keeps the counts structured per task.

## Why

Nothing counts what a task used; a ceiling (B138) and an inventory (B156) need the count first.

**Guideline points it answers:** AISVS 12.1.3, 12.2.5. From the operator's review of security guidelines, 2026-10-09.

## The shape

Counted where the broker relays the answer; written per task beside its journal; shown by `task status` and the daemon.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Providers that do not report usage in their answer.
