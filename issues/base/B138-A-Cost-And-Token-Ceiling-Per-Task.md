# B138 — A Cost And Token Ceiling Per Task

**Status:** open.

**What must be true.** A task cannot spend more than its project allows: the broker stops forwarding to the provider once a task's token or cost ceiling is reached, and says so to the task and to the person.

## Why

`corporate-security.md` says Sokar does not limit spending. A convinced or looping agent spends the account's money without a bound.

**Guideline points it answers:** OWASP LLM10 (unbounded consumption); OWASP AI Agent Cheat Sheet §9; Sysdig 7/8; AISVS 9.1.2. From a review of security guidelines, 2026-10-09.

## The shape

Built on B148's counting. Where the ceiling is set (project, task start), what the agent is told when it is reached, and whether a person can raise it for a running task.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- How cost is known per provider and model; a ceiling in tokens alone may be the first step.
