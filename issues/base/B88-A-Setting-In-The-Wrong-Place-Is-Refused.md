# B88 — A Setting In The Wrong Place Is Refused

**Status:** built 2026-09-30, narrowed by the operator the same day (below).

## Decided 2026-09-30, by the operator

**Refuse what is provably a mistake, warn about the rest.** A test pinned an older rule - a project file
written for a later Sokar must still run on an older one - which refusing every unknown key would break.
So: a key that belongs in another section, or one spelt within two letters of a key that belongs where it
is, is refused, naming where it goes or what was meant; any other unknown key is accepted, and `task start`
warns that it has no effect.

## As built

- `ProjectSchema` holds the keys of each section; `ProjectReader.read` refuses the provable mistakes;
  `ProjectReader.unknownKeys` lists the rest for a warning.
- Open by design: the names under `credentials` and `repositories`, and everything under
  `mail.transports.<scheme>`.
- Proven by `ProjectMailTest` and `ProjectReaderTest` (a later version's section still reads).


## What happened

Agent Matrix, running B86's first end-to-end test, put `unread_work_may_leave: true` under `mail:`. It
belongs under `project:`. `sokar project follow` took the file without a word, the setting looked applied,
and `talk hold ... --mode=allow` kept answering "Set 'unread_work_may_leave: true' in the project" - which
it was, one section too low. The project file ignores every key it does not know, so a setting in the
wrong place, or misspelt, is silently no setting at all.

## What must be true

**Reading a project file refuses a key it does not know**, naming the key, where it was, and - when a key
of that name exists elsewhere in the file's schema - the section it belongs in:

    project.yml: 'mail.unread_work_may_leave' is not a setting; it belongs under 'project:'

- The same refusal wherever a project file is read: `project follow` (so a wrong file is never followed),
  `task start`, `project list`, the daemon's `Projects` row (which says the file is unreadable, with the
  sentence, rather than dropping the project).
- **What a transport is handed is not Sokar's to check:** `mail.transports.<scheme>` is passed through as
  written (B86), and the transport refuses what it does not know (the Matrix transport answers 78).
- A project that followed a file with a stray key stops working until the file is fixed; the refusal says
  exactly what to change, and `project follow --dry-run` shows it before anything is followed.

## Acceptance

- A key under the wrong section is refused with its right section named; a misspelt one with the nearest
  known key suggested where one is close.
- Every key `doc/project-file.md` documents is accepted where it documents it, checked by a test that
  reads the page, as the commands page is checked.
