# B146 — One Stop Of Every Task On A Node

**Status:** open.

**What must be true.** One command stops every task of an account on a machine at once, and the stop is journalled.

## Why

An incident needs everything stopped now; today each task is stopped by name.

**Guideline points it answers:** AISVS 9.1.3, 12.4.3. From the operator's review of security guidelines, 2026-10-09.

## The shape

`sokar task stop --all`, also as a daemon method; each task's work kept as `task stop` keeps it; one journal line with who and when.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether it also revokes the tasks' tokens.
