# Sokar Cheat Sheet

What to type, arranged by what you are trying to do. For the command tree itself, see
[commands](commands.md); `sokar <command> --help` is always the authority. Every key a project file can carry is in
[the project file](project-file.md).

Throughout: **TASK** is a container name as `sokar task list` shows it, and a command that needs
one will list the names it would have taken if you leave it out.

---

## Set up a machine

```
sokar doctor                     # can this machine run a task? each failure names its fix
sokar setup                      # register the OCI hooks (starting a task does this for you)
sokar agents                     # which agents are installed
```

## Get a credential in

Three routes, and they are not interchangeable.

```
sokar vault login claude         # run the agent's own login in a throwaway container
sokar vault import claude        # copy what an already-signed-in install holds
sokar providers                  # the names a credential goes under, and what the vault holds
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

There is no default bound on `--for`. A default bound would ask for a passphrase in the middle of
work that never set one.

## Describe a project

`project.yml` lives in the project's own git repository. You write it once and commit it; a machine
gets it by following that repository, and Sokar writes one nowhere.

```
sokar project follow myproject <git-url> --signed-by "ssh-ed25519 AAAA..."
```

```yaml
project:
  name: "myproject"
  security_class: "guarded"
  # upstream: "git@github.com:you/myproject.git"   # required by online; optional otherwise
image:
  base_image: "ubuntu:24.04"
egress:                     # what the build may reach; nothing else resolves
  sets: [os-packages-debian, git-hosting]
  # domains: ["nexus.corp.example"]   # a private mirror, if you have one
limits:                     # optional; these are the defaults
  memory: "8g"              # "none" to opt out on purpose
  pids: 2048
  # cpus: "2.0"             # unset means no CPU limit
```

Only `name`, `security_class` and `base_image` are required; everything else has a default. Every
key it can carry is in [the project file](project-file.md).

## Run something

```
# --repository says which of the project's repositories the work is for. It is always
# named: the project's own repository is called after the project, so that is what a
# project with only its own takes. 'sokar project list' says what a project has.
# -p names the PROJECT and -r the repository in it. Both are names, never paths:
# which project a task belongs to must not depend on the directory you stand in.
# 'sokar project list' prints what this machine has.
sokar task start -p myproject -r myproject        # interactive shell; creates it or brings it back
sokar task start -p myproject -r backend --attach agent   # start the agent, shell when it exits
sokar task start -p myproject -r backend --prompt "fix the failing test"  # unattended
sokar task start -p myproject -r myproject --detach       # start it and keep your prompt
sokar task start -p myproject -r myproject --rm           # throw the container away when you leave
sokar task prepare                                # build the image without starting a task
```

One verb, and it decides from the task's state: nothing there, it is created; stopped, it comes
back with the workspace it had; already running, it says so and names `attach`.

Leaving the shell **keeps** the container. `--rm` is how you say otherwise.

## Find and re-enter a task

```
sokar task list                  # what exists, whether it is up, and for how long
sokar task status TASK           # everything about one, including uncommitted work
sokar task logs TASK             # which logs its helpers on this machine wrote
sokar task logs TASK gate.log -f # follow one
sokar task attach TASK           # go into a running one (offers to start a stopped one)
sokar task label TASK "..."      # a caption to tell several apart
```

## End a task

```
sokar task stop TASK             # stop it and its helpers; everything it holds stays
sokar task remove TASK           # remove it — refuses if it is up or holds unpushed work
sokar task remove TASK --rescue  # push that work to the gate first, then remove
sokar task remove TASK --force   # stop it if it runs, and discard the work anyway
sokar panic                      # stop everything, remove nothing
```

`remove` destroys the workspace: it lives in the container, not on the host. `stop` never does —
that is why they are two verbs and not one with a flag.

## Push from inside a task

```
git push                         # goes to the gate; the workspace is configured for it
```

A bare push in a task workspace lands on the task's gate ref, not on a branch in the mirror. From
outside, `sokar task stop TASK --rescue` does the same thing for you while it runs.

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
| is there work in there I would lose? | `sokar task status TASK` — while it is still running |
| a container starts and does nothing | `sokar task logs TASK` — something it needs is probably blocked |
| a push never arrives | `sokar gate pending` — it is waiting for review |
| "hooks are not registered" | `sokar setup`, though starting a task does it for you |
| everything at once | `sokar panic`, which removes nothing |
