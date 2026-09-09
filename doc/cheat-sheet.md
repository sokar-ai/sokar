# Sokar Cheat Sheet

What to type, arranged by what you are trying to do. For the command tree itself, see
[commands](commands.md); `sokar <command> --help` is always the authority.

Throughout: **TASK** is a container name as `sokar task list` shows it, and a command that needs
one will list the names it would have taken if you leave it out.

---

## Set up a machine

```
sokar doctor                     # can this machine run a task? each failure names its fix
sokar setup                      # register the OCI hooks (a task run does this for you)
sokar agents                     # which agents are installed
```

## Get a credential in

Three routes, and they are not interchangeable.

```
sokar vault login claude         # run the agent's own login in a throwaway container
sokar vault import claude        # copy what an already-signed-in install holds
sokar vault put anthropic        # store a value you already have, read from stdin
sokar vault list                 # the names it holds, never the values
```

`login` is the one that needs nothing installed on the machine. Use `import` when the agent is
already signed in here — a second login would replace what that install is using.

## Open and close the store

```
sokar vault unlock --for 30m     # cache the passphrase for a while
sokar vault lock                 # drop it; the next command asks again
sokar vault passphrase           # re-encrypt under a new passphrase
```

There is no default bound on `--for`. A bound that arrived as a default would start asking people
for a passphrase they never used to be asked for.

## Run something

```
sokar task run                                  # interactive shell in a fresh container
sokar task run --attach agent                   # start the agent, shell when it exits
sokar task run --prompt "fix the failing test"  # unattended, no terminal
sokar task run --keep                           # leave the container in place afterwards
sokar task prepare                              # build the image without starting a task
```

Leaving the shell removes the container — unless it holds work that never reached the gate, and
then it is kept and says so. Ctrl-C keeps it too: an interrupted run is not a finished one.

## Find and re-enter a task

```
sokar task list                  # what exists, and whether it is up
sokar task attach TASK           # go into a running one (offers to start a stopped one)
sokar task resume TASK           # start a stopped one again, workspace intact
sokar task label TASK "..."      # a caption to tell several apart
```

## End a task

```
sokar task stop TASK             # stop it, keep it resumable
sokar task stop TASK --purge     # remove it as well — refuses if it holds unpushed work
sokar task stop TASK --rescue    # push that work to the gate first
sokar task stop TASK --purge --force   # discard it anyway
sokar panic                      # stop everything, remove nothing
```

`--purge` destroys the workspace: it lives in the container, not on the host.

## Review what an agent pushed

```
sokar gate pending               # what is waiting, and for how long
sokar gate review NAME           # what it would change
sokar gate checkout NAME         # open it as a copy you can read
sokar gate approve NAME          # forward it to the real upstream
sokar gate reject NAME           # discard it
```

Nothing reaches the upstream without passing through here.

```
sokar gate protect               # pre-push hook in your own checkout
sokar gate backup FILE           # the mirror as one verifiable bundle
```

## See and change what a task may reach

```
sokar shield egress                          # what this project may reach
sokar shield egress --add-set github         # add a curated set
sokar shield egress --add-domain example.com --dry-run
sokar shield sets                            # the sets a project can name
sokar task clearance TASK prompt             # ask about each block while it runs
sokar task clearance TASK allow              # or allow, deny, off
```

## When something is wrong

| Symptom | Try |
|---|---|
| a command refuses and names no task | `sokar task list` — or leave the name out and it lists them |
| the agent cannot authenticate | `sokar vault list`, then `sokar doctor` |
| a container starts and does nothing | its egress log, under `$XDG_RUNTIME_DIR/sokar/TASK/` — something it needs is blocked |
| a push never arrives | `sokar gate pending` — it is waiting for review |
| "hooks are not registered" | `sokar setup`, though a task run does it for you |
| everything at once | `sokar panic`, which removes nothing |
