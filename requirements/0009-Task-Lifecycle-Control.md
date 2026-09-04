# 0009 — Task Lifecycle Control

**Status:** open

Today a task can be started and nothing else. Stopping one means finding its
container by name and using the runtime directly, which is both undiscoverable and
easy to get wrong.

## Acceptance

- List, stop and resume exist in the CLI and over the API.
- Stopping reaps every sidecar the task started, verified, not assumed.
- Resuming a task reuses its workspace rather than starting over.
- Stopping a task that has already gone is not an error.

## Notes

A CLI gap first, an interface feature second. The interface cannot offer what the
domain does not have.
