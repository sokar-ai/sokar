# B147 — A Wall-Clock Limit For Unattended Runs

**Status:** open.

**What must be true.** An unattended run ends at a time limit its project or its start sets, and says so.

## Why

An agent left to itself can run for as long as nobody looks.

**Guideline points it answers:** AISVS 9.1.1. From a review of security guidelines, 2026-10-09.

## The shape

A limit per project and per start; the run stopped as `task stop` stops it, its work kept; the reason in the task's status.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether a task with a person attached is exempt.
