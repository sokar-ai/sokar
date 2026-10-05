# B38 — How Far Something That Got Through Can Get

**Status:** later; blocked by sokar B37 and B39 (origin marking) and sokar B26 (the install count).

**What must be true.** Content Sokar itself delivers into a task carries its origin, so the agent reads it as
data from a known source and a reviewer sees where it came from; and "how far did it get" has an answer after the
task is gone, including what it installed.

Content that arrives by `git clone` is never seen by Sokar and stays unmarkable; that is the difference worth
stating. B14's messages already arrive as A2A messages from a named peer, in the inbox and never through the
channel that carries the operator's instruction. What B37 (a build log) and B39 (a handed file) deliver is
decided with them, against this file. How the origin is labeled is not Sokar's to decide: fencing conventions
differ per agent, so it is a field in the agent's definition, not a branch here.

## Acceptance

- Content Sokar delivers into a task - a build log, a handed file, an artifact - carries its origin in the form
  the agent's definition declares, and `sokar gate review` shows the same origin beside what came of it. Seen
  to fail: an agent definition that changes its declared form and nothing else leaves the delivered text
  unchanged, or a delivery that reaches the task unlabeled.
- After a task is removed, what it installed (the `podman diff` count taken before removal) can be read by a
  person, beside what it reached for and what it pushed. Seen to fail: `task remove`, then no place that
  answers what the task installed.

## To be checked

- Whether the record can answer "how far did it get" after a reboot: B26 says host-side logs live on tmpfs and a
  reboot deletes them, which would leave the question this file is named after, in the ordinary case, nowhere to
  look.
