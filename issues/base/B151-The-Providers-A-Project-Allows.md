# B151 — The Providers A Project Allows

**Status:** open.

**What must be true.** A project names the providers its tasks may use, and a start with another is refused.

## Why

An organisation's rules can permit only some providers; today any provider the vault holds can be chosen.

**Guideline points it answers:** Stadt Zürich KI-Richtlinie Art. 6. From a review of security guidelines, 2026-10-09.

## The shape

`providers:` in the project file; refused at start with the allowed ones named.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Near PJ16, which sets this per machine.
