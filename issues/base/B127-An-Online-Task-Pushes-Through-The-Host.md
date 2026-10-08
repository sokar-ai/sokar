# B127 — An Online Task Pushes Through The Host

**Status:** now.

**What must be true.** A task of class `online` reaches its upstream the way a `guarded` one does: only through the
gate on the host, and with no credential and no socket of one in its container. The host fills the gate from the
upstream and fetches from it again whenever the agent fetches. A push of the agent goes into the gate and, while the
push is still running, the host passes the task's branch on to the upstream with the key the vault lends for it. The
agent's push succeeds only when that one did. The two classes differ only in what the host does with a push: `guarded`
keeps it until a person approves it, `online` passes it on at once. Sokar is the go-between, and the agent cannot reach
more at the forge than its own branch.

## Why

Today an `online` task clones from the upstream inside its container, and pushes there itself. For that it gets an
ssh-agent socket that answers the forge's login with the real key from the vault, and the firewall opens the
upstream's ssh port. The key never leaves the vault, but for as long as the task runs the agent can use it for
everything the key may do: push to `main`, push with force, set tags, delete branches, and, with a person's key such as
`ssh.default`, read and write every other repository that key reaches. `doc/security.md` says so in its table
("Credential in the container: ssh-agent socket"), and `doc/credentials.md` calls the key "the git signing key", which
reads as if it only signed commits. It is the key the push logs in with.

To the agent, pushing to the gate and pushing to the forge look the same. What differs is who holds the power to
publish. Moving that power to the host makes "no credential in a container" true for all three classes, keeps what
`online` is for - the push reaches the forge at once, its CI runs, and the build's verdicts come back into the task
- and lets the host refuse what no task should do at a forge.

It also makes a local upstream work: the host can read a `file://` repository on the same machine, which a container
never can, so the acceptance suite's online fixture is a real one again. The builds scenario's fixture is such a
repository, and since an online task whose fetch fails is refused at its start, it could not start any more.

## Acceptance

- **Filled on the host:** an `online` task's gate is filled from the upstream on the host, with the vault's credential
  for it, and its workspace is cloned from the gate. Its container has no ssh-agent socket, no `SSH_AUTH_SOCK`, and
  no firewall rule for the upstream's address or port. `doc/security.md` says `none` for `online` under "Credential in
  the container". Seen to fail: today's start, which mounts the socket and opens the port.
- **Fetched through:** a fetch of the agent from the gate first brings the gate up to the upstream, so what the agent
  fetches is the upstream as it is now. Seen to fail: a commit pushed to the upstream after the task started, which a
  fetch of the agent must bring.
- **Passed on while the push runs:** a push to the task's own branch is passed on to the same branch at the upstream
  before the agent's push returns. A refusal by the forge - a conflict, a protected branch, a key it does not take -
  fails the agent's push, with what the forge said. Seen to fail: a push the forge refuses that the agent sees as
  having gone through.
- **Only the task's own branch:** the gate refuses, and nothing reaches the forge, for a push to any other branch
  (`main` included), a tag, a deleted branch, and any repository but the task's. A push with force to the task's own
  branch is passed on with force, since an agent that rebases needs it. Seen to fail: each of these against a gate
  that passes everything on.
- **A local upstream:** an online project whose upstream is a `file://` repository on the host starts a task that
  holds its history and whose push arrives there. The builds scenario (`task-builds.feature`) runs on it again.
- **What follows a push stays:** the build helper follows the branch the host passed on, and its verdicts arrive in
  `/sokar/files` as before.
- **The documents say it for all three classes:** wherever a document says that no credential is in a container, or
  names the ssh-agent socket as the exception, it says "none" for every class, and where `online` work goes says that
  it goes through the host. Today these are `doc/security.md` (the table of the classes, its row "Credential in the
  container", and the `online` section), `doc/reach.md` (its row "The ssh signing key"), `doc/credentials.md` (the
  "git signing key" served as `SSH_AUTH_SOCK`, and "A key never leaves the vault"), `doc/how-it-works.md` ("Signing
  commits works the same way"), `doc/decisions.md` (the ssh-agent socket among what a container is given),
  `README.md` ("The key stays on the host"), and the guide in a task. A search for `ssh-agent`, `SSH_AUTH_SOCK` and
  "in the container" over `doc/` and `README.md` finds no claim left that only two classes keep.

## To be checked

- The name of the task's branch at the forge: `sokar/<task>`, as `approve` names it, or the name the agent chose.
- What the gate answers when the forge is slow or cannot be reached: the agent's push waits how long, and fails with
  what.
- Online tasks started before this keep their socket until they are removed; they are only the tests' own.
