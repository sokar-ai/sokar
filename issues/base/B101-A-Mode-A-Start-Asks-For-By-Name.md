# B101 — A Mode A Start Asks For By Name

**Status:** later

**What must be true.** A person starting a task can ask for one of the agent's named modes, such as
`read-only`, and nothing reaches the agent that its definition did not write.

## Why

Asked for when a read-only review run was wanted in a task with OMP, and `sokar task start` had no way to say
"read only". The agent's side is `sokar-omp` OM19.

### What is missing

A start gives an agent only what its definition declares - `--model`, `--max-turns`, and Sokar's own `--minutes` -
and that is deliberate: the definition sets the flags that decide what an agent may do (`--auto-approve` among
them), and an option that passed any flag through would let a start undo them. So a run that should only read and
write one report had nothing but its prompt to say so, although OMP itself can enable only named tools.

## Acceptance

- **An agent definition can declare named modes**, each a set of its own flags - say `read-only`, enabling only its
  reading and searching tools and one write.
- **A start asks for a mode by name** (`--mode read-only`); a name the agent does not declare is refused, and no flag
  reaches the agent that its definition did not write.
- **`sokar agents --verbose` lists the modes**, and the interface offers them.
- **Seen to fail:** a test that starts with `--mode` naming an undeclared mode goes red when the start is not
  refused; a test that reads the agent's command line after a start with a mode goes red when it holds a flag the
  definition did not write; a test of `sokar agents --verbose` goes red when a declared mode is missing.
