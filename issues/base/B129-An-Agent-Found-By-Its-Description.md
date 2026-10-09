# B129 — An Agent Found By Its Description

**Status:** open.

**What must be true.** An agent of another vendor is found once its package is installed, under the vendor's own
names: the package, the executable and the place it lives need not start with `sokar`. The package puts one small
description file into a directory `sokar` reads, and the file says where the agent's executable is.

## Why

Today an agent is found by its file name alone (`AgentDirectory`): an executable whose name starts with
`sokar-agent-`, in `~/.local/share/sokar/agents/` or `/usr/libexec/sokar/agents/`. That ties every vendor's program to
a name and a directory that are Sokar's. A link with that name pointing at the vendor's program would work, but then
the name is still the contract, and an agent is what a directory listing happens to show. A description file makes it
something the vendor states on purpose.

## The shape

- Two directories, read in this order: the account's own (`~/.local/share/sokar/agents.d/`) and the system's
  (`/usr/share/sokar/agents.d/`). The system directory belongs to root, as a package installs it. A description in the
  account's directory wins over one of the same name in the system's, as an agent in the account's directory does
  today, so a person can try their own build without root.
- One file per agent, `<name>.yaml`, with the absolute path of the executable and nothing a vendor would have to
  repeat from the agent itself. The agent's definition stays where it is, answered by the agent:
  `executable: /opt/acme/bin/coder`.
- An agent is taken only when the path is absolute and names a regular file that is executable. A description that
  names anything else is said by `sokar agents` and `sokar doctor` with its file and the reason, and is not taken.
- `sokar agents --verbose` says for each agent which description found it and where its executable is. `sokar doctor`
  says what an account's description hides, as it does today for a binary.
- `agents/README.md` gains a section for a vendor: the package's name is free, the executable may live anywhere, and
  the one file under `agents.d/` is the whole registration. `doc/getting-started.md`, `doc/running.md`,
  `doc/build.md` and `doc/commands.md` say where agents are found.

## Acceptance

- An agent installed as `/opt/acme/bin/coder`, with `/usr/share/sokar/agents.d/acme.yaml` naming it and no file of
  Sokar's name anywhere, is listed by `sokar agents` and starts a task. The package check installs such an agent in a
  clean Debian and a clean Fedora and finds it there.
- Seen to fail first:
  - a description with a relative path, a path to a directory or to a file that is not executable is refused and
    said, and no agent is taken from it;
  - a description in the account's directory hides the system's of the same name, and `sokar doctor` says so;
  - removing the description file removes the agent from `sokar agents`, though its executable is still there.

## To be checked

- Whether the `sokar-agent-*` file names are still read beside the descriptions, for the agent packages already
  released, and until when; or whether those packages move to a description in their next release.
- Whether the transports (`/usr/libexec/sokar/transports`) and the build readers (`/usr/libexec/sokar/builds`) are
  found the same way, by a description of their own kind.
