# B154 — A Tamper-Evident Event Log Per Node

**Status:** open.

**What must be true.** Every security-relevant event of a node - approve, reject, clearance, hand-in, runtime extensions, and every change of permissions (a clearance at run time, `--credential`, a change of `project.yml`) - is written to a log that shows when it was changed afterwards.

## Why

Today these are in separate journals that can be edited without a trace; regulation asks for event logs.

**Guideline points it answers:** Swisscom 3.3.2 (EU AI Act Art. 12); Lumenalta step 2. From the operator's review of security guidelines, 2026-10-09.

## The shape

One append-only log per account, hash-chained or kept where the account cannot rewrite it; read by a command and the daemon.

## Acceptance

- Seen to fail first, then green: the behaviour above, in a test of the module that carries it.
- The documentation page that describes the area says it.

## To be checked

- It replaces what B121 removes from the message record; near B26. Where the anchor of the chain is kept.
