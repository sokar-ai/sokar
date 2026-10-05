# P08 — Provider Zhipu

**Status:** later.

**What must be true.** A task is served models from Zhipu, without the credential entering the
container.

## The shape

**Upstream.** https://open.bigmodel.cn in China, https://z.ai internationally - one vendor
behind two hosts, which is part of why the broker's upstream cannot be a constant.

**Reached how.** **An endpoint compatible with another vendor's dialect**, so an agent built for that vendor drives it.

**Authentication.** An API key, carried in a variable of its own that no agent definition names today.

**Can it be brokered?** Reachable in principle, but it makes the broker's upstream a per-credential value instead of a constant in the agent.

The agents that can reach it are listed in
[the comparison](https://github.com/sokar-ai/sokar-project/blob/main/doc/agents-and-providers.md).

## Acceptance

- A task reaches this provider through the broker, and the request arrives. Seen to fail: a request
  from a task to the broker's Zhipu route does not reach the upstream, for either host.
- The credential stays on the host; the container holds a task-scoped token. Seen to fail: a search
  of the container's environment, files and process arguments finds the real key.
- Which provider a task used is answerable afterwards from the task's own record. Seen to fail: the
  record of a task that used it does not name Zhipu.

## To be checked

- How the broker's upstream becomes a per-credential value rather than a constant in the agent, so
  one vendor behind two hosts can be served.
