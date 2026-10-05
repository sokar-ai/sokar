# P09 — A Native Agent And A Provider It Does Not Know

**Status:** later.

**What must be true.** An agent that speaks `native` either reaches a provider whose name it does not recognize,
through the first dialect that provider declares, or is refused with a reason before a task starts.

## Acceptance

- A provider file with a name no agent knows, declaring a dialect a `native` agent can speak, is chosen with
  `sokar task start --provider <name>`, and the task authenticates through the broker. Seen to fail: the agent
  ignoring the redirected endpoint, or the start succeeding and the agent answering that it does not know the
  provider.

## To be checked

- Whether `dialect: native` works for a provider the agent does not recognize by name, or `sokar agents --verbose`
  should pair a `native` agent only with the providers it names.
