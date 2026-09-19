# B73 — The Anchor Arrives With The Follow

**Status:** built on 2026-09-19. The key a project's configuration is signed with is
given when the project is followed, in the same command - and a machine may follow one without an
anchor, as long as it says so.

## Two things that are wrong with pinning today

**There is no command.** The key goes into `~/.config/sokar/configuration_signers` by hand, in
OpenSSH's `allowed_signers` format. For a step every machine needs once, an editor is the wrong
interface - and an interface that cannot see this machine's filesystem cannot do it at all.

**It is a separate step for no reason.** The anchor has to come from the person rather than from the
repository - that is the whole of what "out of band" means, and reading it out of the clone it is
supposed to authenticate would make the signature decoration. But *the same command* is a different
channel from *the same repository*. Typing the key beside the URL gives up nothing.

## What must be true

1. **`--signed-by` on follow.** `sokar project follow acme <url> --signed-by "ssh-ed25519 AAAA..."`
   pins that key for that project and follows in one command. A fingerprint is accepted where the
   key is already known to the machine.
2. **The refusal is the way to the key.** A follow with no anchor answers `UNKNOWN_KEY` and names
   the fingerprint. A person compares it with what they were told and repeats the command with
   `--signed-by`. That is stronger than pinning beforehand, because pinning beforehand is usually
   pasting without looking.
3. **`--unverified` is allowed, and loud.** A machine may follow a repository without an anchor. It
   is a state, not an error, and it is reported wherever the project is: in `doctor`, in
   `Following()`, on `Projects().following`, in the words *whoever can push to this repository
   decides what tasks here may reach*.
4. **Unverified is per project, never per machine.** One project without an anchor must not quieten
   another that has one.
5. **Turning verification back on is one command**, and applies from the next reconciliation.
6. **A key is still never read from the repository it verifies.** Not from a file in it, not from a
   commit in it, not from a branch in it. This is the one rule with no exception.

## Built, 2026-09-19

**`--signed-by` pins and follows in one command.** It appends to the pinned signers rather than
replacing them, because a machine follows several projects and they need not share a key.

**`--unverified` follows without an anchor**, and is reported as a state in `project following`, in
`doctor` - where it makes the probe *degraded* rather than failing, since somebody asked for it -
and on `Projects().following.unverified`.

**`--unverified` with `--signed-by` is refused.** Both is not a stricter setting; it is two
different instructions, and letting one quietly win would be the wrong kind of helpful.

**Unverified skips the check, not the reading.** A file that is not a project is still `UNUSABLE`:
nobody checked *who* wrote it, which is not the same as anything goes. Found by writing that test
rather than by reasoning about it.

## Acceptance

- Following with `--signed-by` pins and follows in one command, and a second follow of the same
  project with a different key is refused rather than repointing it quietly. **Met.**
- A follow with no anchor names the key's fingerprint, and repeating it with that fingerprint
  succeeds.
- `--unverified` follows, applies, and reports itself as unverified in every place the project's
  state is shown. **Met.**
- A project followed unverified beside one followed with an anchor does not change what is reported
  about the second. **Met**, and tested.
- No form of any command reads a signing key out of the repository being verified. **Met** - the
  key comes from the command line and nowhere else.

## Notes

**Why unverified is allowed at all.** Access to a git repository is already authenticated, and in
practice people apply what comes out of an authenticated clone without checking signatures - Flux
and Argo both make signature verification a switch that is mostly off. The honest reading is that
the marginal value of signing over "I cloned from a forge I authenticated to" is smaller than a
blanket requirement suggests.

**What it is not zero, though.** Authentication proves who *you* are; it does not say who wrote the
content. Without a signature the rule is *whoever may push here decides what your agents may
reach*; with one it is *whoever holds the signing key*. The second set is much smaller - it excludes
every CI token with write access, every leaked colleague's credential, and the forge itself.

**And one case is Sokar's own.** An agent can hold a task on the project's own repository - that is
what planning work is - so it edits the very file this verifies. *"An agent may propose
configuration and never put it in force"* is true exactly while the signing key is not on the
machine the agent runs on. The gate is the other control, and it is a person reading a diff: the
realistic failure is not a hostile agent but thirty lines of markdown with one `domains:` line in
them.

**Measured while writing this**, because it is easy to describe wrongly: with git 2.53 the principal
in `allowed_signers` is a label, not a check - `zork`, `alice` and `alice@example.com` all verify a
commit whose key is in the file, and a key that is not in it fails whatever the principal says. So
the file means *these keys may sign configuration*. The signer's email still belongs in it: it is
what `git log --show-signature` prints.
