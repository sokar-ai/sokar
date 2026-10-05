# B55 — The Changelog Entry A Change Has To Bring

**Status:** soon.

**What must be true.** A change to what ships or builds brings a changelog entry, or says on purpose
that it does not, and the build checks it in every repository that keeps a changelog.

## Why

Nothing is blocked by it: the gap it describes is deliberate for now.

## Decided

- **logchange is adopted, in Sokar first** - `dev.logchange:logchange-maven-plugin`, configured in
  Sokar's own root POM. Putting it in the fuinorg parent POM, `org.fuin:pom`, which Sokar and the three
  agent repositories all inherit (`2.0.2` in each), would reach all four from one place; decided: `fuinorg`
  is not changed for now. A change is recorded as **one YAML file per entry** under
  `changelog/unreleased/`, and the generated `CHANGELOG.md` follows Keep a Changelog. An existing
  `CHANGELOG.md` is kept without rewriting by moving it to `changelog/archive.md`.
- **The current changelog check is removed everywhere for now.** That is `check-changelog.py`, 308
  lines, byte-identical in the three agent repositories and run by each one's `build.yml`. No other
  repository had a check. It also leaves B53: the shared tool no longer carries it.

**So, until this requirement is met, nothing forces a change to bring an entry.** That is the price of
not rebuilding the old check on the old format, and it is paid on purpose.

## What logchange makes smaller

With one file per entry, the check becomes one question: **does the commit range add a file under
`changelog/unreleased/`?** The hardest part of the old script disappears by construction. It had to
tell *touching* `CHANGELOG.md` from *adding an entry to it*, because an earlier version was satisfied
by a commit that only rewrote a URL in the `[Unreleased]:` link line. A new file is an entry.

## The way to build it

1. **Propose it upstream to logchange first**, as a goal of its own or as an option of `lint`.
   Requiring an entry is exactly that tool's subject, and built there it serves everyone who uses
   it. Today `lint` checks only that the `changelog` directory exists and that its YAML is valid,
   and nothing in logchange (1.19.16) requires an entry.
2. **If upstream declines, a small fuinorg plugin**, in its own repository under
   `github.com/fuinorg`, since the operator's rule puts what is not Sokar-specific there. Either way
   the plugin that repository would have held becomes smaller, or unnecessary.

## What the check must keep from the old one

Each of these was in `check-changelog.py` because it had gone wrong once:

- **A waiver answers for its own commit only.** `[no changelog]` excuses the commit it is written on
  and nothing travelling with it. It used to be looked for across the whole push, and a
  docstring-only tip carrying the marker excused the two code commits under it.
- **Documentation is not exempt.** A typo and a rewrite of a setup guide are both documentation, and
  no diff tells them apart, so a person decides with the waiver. Editor settings and the licence are
  exempt.
- **A shallow clone must not make it pass.** A range that cannot be compared - an abbreviated SHA in a
  depth-1 clone - fails rather than being judged fine.

## Acceptance

- A range that changes what ships without adding an entry fails; the same range with an entry passes;
  a commit carrying the waiver passes for itself and excuses nothing else. Seen to fail: a push whose
  tip carries `[no changelog]` and whose earlier commits change code without an entry passes.
- Each of those is proven against the failure it exists for, by breaking the check and watching the
  matching case fail.
- It works in a shallow CI clone, and a range it cannot compare fails. Seen to fail: a depth-1 clone
  with an abbreviated SHA in the range passes.
- A repository that uses logchange gets the check by configuring the plugin, not by writing a check of
  its own. Seen to fail: a repository needs a script of its own beside the plugin configuration for
  the check to run.

## To be checked

- **What logchange's maintainers want:** a goal or a `lint` option, and what a waiver looks like in
  their model, where entries are files rather than lines.
- **Where "what ships or builds" is defined:** by convention, or configured per project.
- **Which repositories adopt logchange besides Sokar, and how.** The three agent repositories keep a
  changelog and inherit `org.fuin:pom`, so the parent POM would be the one place; `sokar-frontend` has no
  parent POM and would need its own. Both are decisions for later.
