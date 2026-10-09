# B140 — A Secret Scan Of The Workspace At Start

**Status:** open.

**What must be true.** When a task starts, its workspace is scanned for credentials, and a found one is named to the person before the agent works.

## Why

A token committed in the repository is in the agent's hands: it can push past the gate with it and it reaches the provider with the text (`how-it-works.md`, `faq.md`).

**Guideline points it answers:** OWASP AI Agent Cheat Sheet §5; AISVS AC.12.2; the guidance to keep secrets away from AI tools. From the operator's review of security guidelines, 2026-10-09.

## The shape

The scan on the host over what the mirror hands the task; the finding said with file and line, the value never shown; whether a finding stops the start or only warns, per project.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- Which detector; how a false finding is acknowledged for a project.
