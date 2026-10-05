# B79 — A Daemon That Answers Nothing

**Status:** soon.

**What must be true.** A call on the daemon's socket, or on an agent's, is answered or refused within a bound,
whatever the process's own background work is doing - and when one is not, the reason is known rather than
guessed.

What is known: a daemon of an acceptance scenario's own, started after another was killed with SIGKILL and with
about fifteen projects followed, answered `GetInfo` and then no call for four minutes, logging nothing and using
six seconds of CPU; a scenario asking a daemon for `Agents` and `Start` hung the same way twice in one day on two
VMs, and passed on reruns. Agents built against the old API hung the same way, three times in 1200 lookups,
one printing "still running; since it was ready it wrote nothing" - stuck, not slow and not dead. The likeliest
cause - background passes and connection reads on virtual threads sharing two carriers - is taken away, but the
old stub never hung either in 1600 lookups, so that it was the cause is inferred, not measured.

## Acceptance

- The next time a daemon or an agent answers nothing, its thread dump (`SIGQUIT`) is read and names where the
  call waits. Seen to fail: a hang with no dump taken, or a dump that shows every connection thread idle.
- A full acceptance run on a two-CPU VM, started right after a daemon is killed and with fifteen or more
  projects followed, answers every call within its bound; repeated until either a hang is seen or enough runs
  pass to say the mitigation holds.

## To be checked

- Whether the first follow pass after a start, with many projects followed, keeps calls waiting.
- On the fedora VM the daemon once started a second `sokar-agent-stub serve` on the socket the first one held;
  whether that is the same fault or another.
