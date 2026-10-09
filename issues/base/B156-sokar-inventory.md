# B156 — sokar inventory

**Status:** open.

**What must be true.** `sokar inventory` lists the agents, providers, models, credentials and their last use on this machine, also as JSON for central collection; the register itself stays the organisation's.

## Why

An organisation asked to keep a register of its AI use has to collect it from each machine.

**Guideline points it answers:** BSI catalogue 2.1; NCSC asset management; Acalvio 1. From a review of security guidelines, 2026-10-09.

## The shape

Built from B144, B148 and B154; human and JSON output.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Which fields an organisation's register needs.
