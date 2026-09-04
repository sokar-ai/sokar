# 0014 — Project Setup Wizard

**Status:** open

Creating a project means naming it, choosing a security class, choosing a base
image, and deciding how the repository reaches the task. Each has a failure that is
hard to diagnose after the fact.

## Acceptance

- The project file is written with comments preserved when it already exists.
- Invalid names are rejected with the reason, before anything is built.
- The repository the work starts from is chosen explicitly and reported.
- The result is a project that runs without further editing.

## Notes

Writes to a file a person owns and may have commented. Patch it; do not regenerate
it.
