# B50 — Whose Business The Files Sokar Leaves Behind Are

**Status:** soon

**What must be true.** Every file Sokar writes is readable by whoever it concerns, and by nobody
else - including on machines that already ran tasks.

## Why

Found by asking of every file a package writes at runtime not *"is it a secret"* but *"whose
business is it"* - a question that came from the interface's own review and that nothing here had
asked.

### What happens today

Measured on the Ubuntu test VM, on a machine that had run real tasks:

    drwxrwxr-x  ~/.local/share/sokar
    drwxrwxr-x  ~/.local/share/sokar/mirrors/<project>.git
    -rw-rw-r--  ~/.local/share/sokar/mirrors/<project>.git/config, packed-refs, HEAD, …
    -rw-rw-r--  ~/.local/share/sokar/projects/<name>
    -rw-rw-r--  ~/.local/share/sokar/upstream/<name>

**Nobody chose those modes. They are the shell's umask**, which on this machine happens to be 002.

What is in them is not incidental:

- **The gate mirror holds the work an agent pushed and nobody has reviewed yet.** That is the thing
  [B13](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) exists to keep from leaving unseen, and it
  is the whole source of every project this machine has touched.
- **The registry** names every project and the path to its project file.
- **The upstream records** say what each project's upstream is and how far behind it has fallen.

### Why this is not already covered

**Sokar asks this question elsewhere and answers it deliberately.** The runtime directory is
`0700`, with a comment saying the directory *is* the access control and that a window in which it
is traversable is the whole hole. The vault file is `0600`. The proxy and ssh-agent sockets are
permissive **inside** a `0700` directory, and that is reasoned about at length.

The durable directory beside them got the umask.

**What covers it today is not ours.** This machine's home is `0750`, so nobody else traverses it.
Change the home's mode, add a second person to the user's group, or run with `umask 022` on a
machine where `/home/<user>` is `0755`, and the cover is gone. **A protection you did not set is
not one you can rely on**, and the inconsistency is the tell: the same question was asked and
answered two directories away.


## The shape

1. **Sokar sets the mode of every directory it creates**, rather than inheriting one. The durable
   directory holds work and source, so it is owner-only, for the same reason the runtime directory
   already is.
2. **A file written into it is owner-only too.** A `0700` directory with `0664` files inside is
   safe only while the directory holds; defence that depends on exactly one thing is how the
   umask got in.
3. **An existing installation is corrected, not just new ones.** This is the part that makes it
   work rather than a line in a constructor: every machine that has run a task already has these
   directories at whatever mode it had then, and a fix that only applies to a fresh install leaves
   every real machine as it is.
4. **The correction is visible.** Silently changing modes under an operator is its own surprise -
   `sokar doctor` is where this belongs: it says what is wrong, and the fix is something a person
   runs knowing what it does.
5. **Nothing is loosened to make this work.** The sockets are permissive on purpose, inside a
   locked directory, and that reasoning is measured and stays. This requirement is about the
   directories nobody reasoned about.

## Acceptance

- On a machine that has run a task, every directory and file Sokar created under its data
  directory is owner-only, asserted against a real run rather than against a constructor.
- A machine that ran tasks under an older Sokar is corrected, and the correction is reported rather
  than performed silently.
- `sokar doctor` fails, and names the paths, when any of them is readable by anyone else.
- The socket directories keep the modes they have, with their existing reasoning intact - a test
  asserts the permissive socket inside the `0700` directory still is what it is.
- Setting `umask 022` before a run changes nothing about the result.
- **Seen to fail:** the real-run test goes red when any directory or file under the data directory
  is readable by group or others, including a run under `umask 002` or `022`; a test that starts
  from a data directory at the old modes goes red when it is not corrected or the correction is not
  reported; `sokar doctor`'s test goes red when a loosened path is not named; the socket test goes
  red when the socket's mode changes.

## To be checked

- **Whether the umask is worth defending against directly.** Setting the mode explicitly after
  creating a path is correct and racy: between `createDirectories` and `setPosixFilePermissions`
  there is a window. `Files.createDirectory` with a `PosixFilePermissions` attribute closes it for
  a directory Sokar creates itself; a parent created by `createDirectories` is the awkward case,
  and it is the one that matters here.
- **What `podman` and `git` create underneath.** The mirror is a git repository made by `git`, and
  the build directory is filled by `podman build`. Sokar can set the mode of the directory it hands
  them; what they write inside is theirs, and whether that needs a pass afterwards has to be
  measured rather than assumed.
- **Whether the same question has a second answer elsewhere.** This one was found by looking at a
  machine rather than at the source. The log files, the state directories under the runtime path,
  and `~/sokar/reviews` have not been looked at the same way.
- **What a shared machine means for this.** Two developers on one machine are two nodes with two
  data directories, which is the design - but only if neither can read the other's. That is the
  case this requirement is really about, and nobody has tried it.
