# B149 — A Local Provider Template

**Status:** open.

**What must be true.** Sokar ships an example provider definition for a model served on this machine, an OpenAI-compatible endpoint on the host, so a person can keep prompts on the machine; choosing and running the model stays theirs.

## Why

Data sovereignty obligations may rule out a remote provider; Sokar can make the local one easy to use.

**Guideline points it answers:** Federal LLM guidance sheet; DSB ZH. From a review of security guidelines, 2026-10-09.

## The shape

A provider file under `providers/` with the endpoint on the host and no credential; a page that says how a task reaches it.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- How a task reaches a host port while its firewall allows none.
