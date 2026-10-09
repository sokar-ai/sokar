# B144 — AI Provenance In The Commit

**Status:** open.

**What must be true.** Approved work carries in its commit who made it: a trailer with the agent, the provider, the model and the task, set on the host at approve, not by the agent.

## Why

Guidance asks that AI-made work be labelled; the commit is where a reader of the history looks.

**Guideline points it answers:** AISVS AC.9/AC.10; labelling per BSI catalogue 2.6, Myni Gmeind §5, the federal LLM guidance sheet. From the operator's review of security guidelines, 2026-10-09.

## The shape

Trailers added by the gate on approve (and by the online gate on passing on), from the task's profile; a signed approve signs them too.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Whether a rebased or squashed history keeps them; the trailers' names.
