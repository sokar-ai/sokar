# F08 — Task Creation And Modes

**Status:** open

Starting work against a project, choosing how a person intends to be involved in it.

## Acceptance

- Work can be started from the interface with a name, an agent and a mode, without
  typing a command.
- The modes on offer are distinguished by the person's role in each: driving it
  interactively, working with it through a richer session, or leaving it to run
  unattended against a written prompt.
- An unattended run collects its prompt before starting, and the prompt is retained
  with the work afterwards.
- Choices that cannot work together are refused at the point of choosing, with the
  reason named.
- A missing credential is reported before anything is built or started.
- Completed or failed unattended work can be continued with a new prompt without
  recreating it from scratch.

## Notes

This absorbed the central requirement for starting work and adds the follow-up path, which is
what turns a finished unattended run into a conversation rather than a one-shot. Starting is a
single daemon call, so the interface and the CLI cannot offer different things.
