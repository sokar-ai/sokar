# B128 — A Gate A Task Cannot Exhaust

**Status:** now.

**What must be true.** What a task's agent can make its gate do on the host is bounded: how many requests it serves
at once, how much of a push it holds in memory, and how much memory, how many processes and how much CPU the gate and
the `git` it starts may take together. An agent that sends requests in a loop or pushes as much as it can slows its
own gate down, and nothing else on the machine.

## Why

Every task has a gate of its own on the host, reachable only from its container and only with its token - and the
agent holds that token. Read from the code (`GitHttpServer`, 2026-10-08): every request gets a thread of its own, with
no limit on how many run at once and no limit on how fast they come; every request starts a `git upload-pack` or
`git receive-pack` on the host, as the person's user and outside the container's limits (8 GB memory, 2048
processes); and a request's body is read into memory whole, up to 1 GiB. No resource limit for the gate's process was
found. So a few parallel large pushes, or many small requests at once, take memory and processes from `sokard`, from
every other task and from the machine. They reach no data and no key, but they can stop the machine's work.

The same holds for every class with a gate: `guarded` today, and `online` once it pushes through the host (B127).

## Acceptance

- **Requests at once:** a gate serves at most a few requests at a time; one beyond that is answered at once with
  `503`, not queued without end. Seen to fail: fifty requests at once from a container start fifty `git` processes.
- **A push is streamed, not held:** a push's body goes to `git receive-pack` as it arrives, or to a file, never into
  memory whole; the size limit stays. Seen to fail: a gate whose memory grows by the size of a large push.
- **The gate in bounds of its own:** the gate and every `git` it starts run in a scope with a memory limit, a process
  limit and a CPU weight below `sokard`'s, the way a task's container has them; exceeding one ends what exceeded it,
  never `sokard` or another task. Seen to fail: a gate whose `git` processes are not in its scope.
- `doc/security.md` says what a task can make its gate take on the host, and where that ends.

## To be checked

- How many requests at once a gate needs for an agent that fetches and pushes in parallel (a guess: four).
- Whether the scope is one per task or one slice for all gates.
