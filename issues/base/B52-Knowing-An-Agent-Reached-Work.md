# B52 — Knowing An Agent Reached Work

**Status:** open, written 2026-09-13. Requested by Agent Smith when first-run consent moved from
Sokar to the agent repositories: their tasks `sokar-claude-code` CC09, `sokar-pi` PI07 and
`sokar-omp` OM07 each depend on it.

## What it is for

Each agent repository now has an acceptance task that must **fail when a new agent release adds a
dialog**. All three need the same assertion: *the agent reached work without being asked anything*.

It belongs in Sokar's acceptance kit because the scenarios in those repositories have no glue of
their own - every step they use comes from the kit. Written in each repository it would be three
copies of one check, drifting in three directions.

## The question this is really about

**The kit cannot know what "reached work" looks like for a given agent.** Claude Code arrives at a
prompt with a footer; Pi and Oh My Pi each look different. A step keyed on any one agent's screen
is the stub problem again: it proves what one agent does and is silent about the rest.

The check has to be **"reached work, and nothing came first"**, not **"the dialogs we know about
are absent"**. The second passes the day a release adds a new question, which is the day it exists
for.

**Most likely the agent declares what reaching work looks like**, the way B47 has an agent declare
what waiting looks like - a manifest field, read by the kit, so that no text about any agent is
written into Sokar. That is a contract decision, and the contract is Sokar's.

**The headless half may need nothing new.** An unattended run that completes within a bound is
already expressible with the existing step *"a task nobody is watching is started in … for the …
agent"*; a run parked at a menu is one that did not complete. Whether that is enough is part of
this requirement, not an assumption it can start from.

## What must be true

**A scenario in an agent repository can prove its agent reached work without a prompt, attached and
unattended, and the step fails when anything asks first - without the kit containing a word about
any agent.**

## Acceptance

- An attached step passes for an agent that shows its declared marker with nothing before it.
- **The same step fails for a fake agent that shows one question before the marker** - proven with
  that fake, because an agent with no dialogs cannot show that the step notices one.
- An agent that declares no marker makes the step fail and say it *cannot tell*, rather than pass.
- An unattended run that stops at a question fails within a stated bound instead of waiting it out.
- No step in the kit names an agent, and no agent-specific text appears in it.

## Decided by the operator, 2026-09-27

1. **What an agent declares: a marker in its manifest**, like B47's waiting patterns, read by the
   kit. The agent stays declarative. An agent whose ready state has no stable text declares no
   marker, and the step fails saying it *cannot tell*.
2. **What counts as having been asked: the marker not appearing within a bound.** The kit types
   nothing; a question blocks the marker, so the step fails and prints the screen. Banners and update
   notices before the marker do not count - that is the robustness chosen over failing fastest.
3. **The bound belongs to the agent, with a default in the kit.** The agent knows how long its own
   start takes; the kit only needs a value when the agent is silent about it.
