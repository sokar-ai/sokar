# B46 — The Conversation A Restart Loses

**Status:** open, written 2026-09-11. It is the half of continuing that
[B43](B43-Tasks-After-The-Machine-Restarts.md) and [B44](B44-One-Way-To-Start-Work.md) do not
cover: both bring the *container* back, neither brings the *conversation* back. It covers a task
somebody drives at a terminal as well as an unattended run, decided on 2026-09-11.

**Where the other half lives.** Where the session id is - a field in the first event of a headless run, the session files of an attached one - is declared by each agent package, and that work was handed to the agent repositories on 2026-09-13 as sokar-claude-code 010, sokar-pi 008 and sokar-omp 008. It is **blocked by this file**: there is no manifest field to declare it in until this one builds it.

## What happens today

A task that comes back comes back empty-headed. The workspace is there, the branch is there, the
uncommitted changes are there - and the agent starts a new session, knowing none of what it had
worked out before the machine went down. A person who left an agent halfway through a refactor
gets the files and not the reasoning, which is the expensive half.

The agents already say they can do better. Every definition declares it, and the reader keeps it:

```yaml
session:
  supports_resume: true
  resume_flag: "--resume"
```

`AgentDefinition.supportsResume` and `resumeFlag` exist, are read from the agent's own YAML, and
are used by nothing. What is missing is not the flag. It is the *argument*: continuing names a
session, and nothing on this machine holds a name.

**The name is already on the wire, and it is thrown away on purpose.** In headless mode the agent
emits stream-json, whose first event is a `system` event carrying the id of the session it just
opened. The formatter that turns those events into something a person reads drops it, at
`ClaudeStreamJsonFormatter:44`:

```java
case "system" -> null;
```

That is the right call *for a formatter* - the event holds nothing worth showing - and it is
exactly where the one durable thing in it goes out. The id crosses into the host, is looked at,
and is discarded, several times a session.

## What must be true

1. **Where an agent names its sessions, Sokar records the id of the session running in a task**,
   and the record belongs to the task rather than to the container: it survives `stop`, survives a
   restart, survives a reboot, and is destroyed by `remove` along with everything else the task
   owns. That puts it in the durable task state B44 deferred, not in `/run/user/<uid>`, which a
   reboot wipes.
2. **The id is taken from what the host already owns.** Nothing new crosses out of the
   container for it. Headless runs are the easy half: the agent's output is already written to
   `task.log` in the container's state directory by the runtime, and that path already parses it
   event by event, so this reads a field from an event it already reads. A task somebody drives by
   hand writes a terminal instead, and there the id is read from the agent's own session files in
   the config directory Sokar already writes into the container - still a thing the host looks at,
   still nothing the agent is given a way to send.
3. **Where the id is, in either mode, is declared by the agent package** - beside
   `supports_resume` and `resume_flag`, in the agent's own YAML. Nothing outside `agents/` may
   name an agent, and *"the id is in the `system` event's `session_id`"* and *"the sessions are
   files under this directory"* are both facts about one agent, not about Sokar.
4. **Continuing is what `start` does, not a fourth verb.** B44 settled the vocabulary: `start`,
   `stop`, `remove`. A task with a recorded id, whose agent supports resuming, starts by
   continuing; the report says which of the two happened, the way `StartAction` already says
   `CREATE` against `RESUME`.
5. **An agent that cannot name its sessions is not guessed at.** No declaration, no recorded id,
   no claim that anything continued - the start reports a fresh session, and an interface can say
   so rather than implying the agent picked up where it left off.
6. **A session id is an identifier and not a credential**, and passing it as a command-line
   argument is therefore allowed - written down here so the rule that keeps secrets off the
   command line is not re-argued against it every time somebody reads the launcher. It names a
   transcript on this machine; it authorizes nothing and buys nothing to anybody who reads it.
   What it *does* leak is that a task exists and roughly when it started, which the container name
   leaks already.
7. **The recorded id reaches the contract**, so an interface can say whether continuing is
   possible before offering it, rather than offering it and finding out. It is a field on `Task`,
   which is the cheap kind of change the IDL already permits.

## Acceptance criteria

- A task is started, does some work, and its machine is rebooted. Starting it again continues the
  same conversation: the agent is asked something that only the earlier turns answer, and it
  answers. **Asserted for both modes** - an unattended run and a task somebody drove at a
  terminal - because the two read the id from different places and only the second proves the
  harder one.
- The recorded id survives `stop` and `start` and is gone after `remove`, each asserted.
- An agent whose definition declares no session id is started and stopped and started again. It
  reports a fresh session; nothing anywhere claims a continuation.
- The `system` event's id is read through the agent's own declaration. A test that changes the
  declared key and nothing else changes which field is picked up - which is what proves the
  knowledge lives in the package.
- `grep` over everything outside `agents/` finds no event name, no field name and no agent name
  belonging to this feature.

## To be checked

- **What happens to a recorded id when the agent is updated under it?** A02 moves an agent's CLI
  version without asking. Whether a session written by the old version is continuable by the new
  one is the agent's business and not ours, but the failure mode is ours: a continuation that
  fails because the transcript is from an older format must report that and start fresh, rather
  than failing the start.
- **Does `--rescue` keep it?** `remove --rescue` pushes what the workspace holds before destroying
  the task. If a session id is part of what makes the work recoverable, it may belong in whatever
  rescue leaves behind - or it may be meaningless there, because the container it referred to is
  gone.
- **One declaration for both modes, or two?** An event and a key describe the headless route; a
  directory and a file shape describe the other. If the id is the same string in both - and for at
  least one agent it visibly is, because the file is named after it - a single declaration with
  two ways of finding the same thing is honest. If it is not, a definition has to say so, and
  whatever reads it has to know which mode it is in.
- **One task, one session, or several?** A long-lived task may run many sessions over its life.
  Recording only the last is the simple answer and is probably right; recording a list is the one
  that supports *"go back to what it was doing on Tuesday"*, and nobody has asked for that.
