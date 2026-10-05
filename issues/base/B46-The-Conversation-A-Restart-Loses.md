# B46 — The Conversation A Restart Loses

**Status:** soon.

**What must be true.** After a real reboot, `sokar task start --restarted` brings back a task that continues
the conversation its agent was having - in an unattended run and in a task somebody drove at a terminal - and a
task whose conversation cannot continue says so plainly instead of presenting itself as resumed.

## Acceptance

- A `@restart` scenario on a rented machine (never the shared VM): a task does some work, the machine is
  rebooted, and `start` continues the same conversation - the agent is asked something only the earlier turns
  answer, and answers it - asserted for both an unattended run and a driven one. Seen to fail: a fresh
  session, a start refused for want of the task's saved state, or tokens missing from the vault.
- In the same scenario, an agent whose definition declares no session id reports a fresh session after the
  reboot, and nothing claims a continuation.

## To be checked

- **One declaration for both modes, or two?** An event and a key describe the headless route; a directory and a
  file shape describe the driven one. If the id is the same string in both - for at least one agent it is,
  because the file is named after it - one declaration with two ways of finding it is honest; if not, a
  definition has to say which mode it describes.
- **What a task started with a prompt and left to run should be after a reboot.** Its agent's work is gone;
  bringing the container back does not bring the work back, and presenting it as resumed is worse than leaving
  it down. `--restarted` brings it back like any other today.
- **Whether the vault prompt after a reboot can be avoided** for a task holding no credential of its own. It
  still needs its gate token, which is in the vault, so probably not; nobody has looked.
