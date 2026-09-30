# B89 — What An Interface Needs To Make A Project From A Repository

**Status:** open, written 2026-09-30 from `sokar-project` PJ13 at the operator's word, relayed by Agent
Coordinator. PJ13's decisions are the operator's. The rest of PJ13 - forge login, listing, cloning,
editing, signing, pushing, registering and removing deploy keys, binding and removing machines - is
`sokar-frontend`'s.

## What must be true

**A person turns a repository they can already reach into a Sokar project, and binds a machine to it,
without copying a URL, a key or a token by hand.** The interface does the forge's part on the person's own
computer; a machine contributes only what it makes itself and what it knows about itself.

## What `sokar` builds

1. **`credentials deploy-key` as a daemon method**, `DeployKey(project, repository?, readOnly?, new?)`,
   answering the public half and its fingerprint, so the interface registers it at the forge with the
   person's login. **A read-only choice:** a machine reads the project's own repository and never writes it
   (PJ13 decision 7), so the key for the project's own repository is asked for read-only; only work
   repositories get write access. The command takes `--read-only` too. The key's title for the forge is
   `sokar <machine> <project>/<repository>` (decision 3), answered beside it.
2. **This machine's public message key over the socket**, `MessageKey() -> (principal, key, fingerprint)`,
   so the interface writes this machine's `allowed_signers` line itself, signed with the person's key
   (decision 7). `project enroll` with `gate approve --signed` stays the way for a person without it.
3. **The project file's schema as a daemon method**, generated from `ProjectSchema` (B88), so the
   interface builds its editor from what this machine's Sokar knows (decision 6); and **a check of a draft
   `project.yml`** against this machine: what Sokar refuses (YAML that does not parse, a key or value the
   schema refuses - these block the commit), and what only this machine lacks (a destination, a vault
   entry, a transport or an agent not here - these warn), each said as such.
4. **Unfollow and unenroll say which keys to remove**: the deploy keys this machine made for the project's
   repositories (by title and fingerprint) and its message key's `allowed_signers` line, so the interface
   removes them at the forge and from the project (decision 13). Unfollow drops this machine's deploy keys
   for the project from its vault.

## Notes

- **`gate approve --signed` signs through a forwarded agent** (decision 5): the person's signing key never
  sits on a machine. As built in B87 it signs with whatever the approving user's git names; the daemon's
  `Approve(signed)` reaches an agent only if its socket is in the daemon's environment. Whether the
  interface forwards an agent for it is `sokar-frontend`'s.

## Acceptance

- The interface registers a machine's deploy key at the forge from `DeployKey`'s answer alone, and the key
  for the project's own repository is read-only.
- The interface writes a machine's `allowed_signers` line from `MessageKey` alone.
- The editor refuses exactly what `follow --dry-run` on that machine would refuse, and warns for what only
  that machine lacks.
- After unfollow, the interface knows every key to remove at the forge, and the machine's vault holds none
  of that project's deploy keys.
