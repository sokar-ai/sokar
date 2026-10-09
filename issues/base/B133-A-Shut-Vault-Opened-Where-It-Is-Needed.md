# B133 — A Shut Vault Opened Where It Is Needed

**Status:** decided.

**What must be true.** At a terminal, a command that needs a shut vault asks for the passphrase itself and carries
on. Nobody is sent off to type `sokar vault unlock` and then the first command again. Without a terminal it still
refuses and names `sokar vault unlock`.

## Why

The operator, 2026-10-09: when Sokar knows the vault has to be opened, it should open it in the same command. Today
some commands ask and others refuse with "'sokar vault unlock'". `vault login` even runs the whole login first and
fails afterwards: "logged in, but the vault is locked". With B132's offer at `task start`, a first start on a fresh
machine becomes one command: the passphrase, the grant, the task.

## Where it is today

**Already asking for this one command** (`SokarContext.requirePassphrase()`: the keyring, then `Vault passphrase:`):
`vault put`, `vault authorize`, `vault import`, `vault list`, `vault remove`, `credentials deploy-key`.

**Refusing on a shut vault, at a terminal too** (in `sokar` at `b672aba4`):

    task start           StartCheck, CredentialWiring    "'sokar vault unlock' and start it again"
    task run             TaskRunCommand                  the tasks need their tokens, the vault is shut
    task resume          TaskResumeCommand               "Unlock it with 'sokar vault unlock' ..."
    task watch-builds    TaskWatchBuildsCommand          "the vault is shut, so Sokar can ..."
    vault login          AgentLogin                      after the login: "logged in, but the vault is locked"
    vault revoke         VaultRevokeCommand              "unlock it first: sokar vault unlock"
    projects follow,     Reconcile                       a private repository: "This account's vault is shut,
      projects refresh                                   so nothing here holds a credential"
    clear, prune         Clearing, VaultClearing, Prune  "'sokar vault unlock' and clear again"
    providers            ProvidersCommand                hides what the vault holds, names the unlock
    the messaging        TransportLifecycle,             a transport and its conversations wait; these run in
      transport          Conversations                   the daemon, without a terminal

`doctor` reports a shut vault and names the unlock; that is a report, and stays one. `vault passphrase` names
`vault unlock` with the new passphrase after a change; that stays too.

## The shape

1. **One place decides, `SokarContext`:** a command that needs the vault asks for an opener. If the vault is shut
   and standard input and output are a terminal, it asks `Vault passphrase:` once, as `requirePassphrase()` does
   today. Without a terminal it refuses with the words of today, naming `sokar vault unlock`, so a script, a pipe,
   the daemon and the watchers behave as now.
2. **For this command only, or for the time `vault unlock` gives.** Commands that need the vault only while they run
   (`vault revoke`, `providers`, `clear`, `prune`, `projects follow`/`refresh`, `task watch-builds`) open it for
   that run. `task start`, `task run` and `task resume` need it afterwards, since the broker spends the tokens for
   the task's life. For them the answer unlocks the vault **as `sokar vault unlock` does, for the same time**. The
   prompt says so: `Vault passphrase (unlocks it for <time>, as 'sokar vault unlock'):`.
3. **`vault login` asks before the login.** It checks the vault first, asks if it is shut, and only then starts the
   login container. A wrong passphrase stops it before anything ran.
4. **A wrong passphrase** is asked again up to three times at a terminal, then refused as `vault unlock` refuses.
5. **The docs:** "Sokar asks for the passphrase when the vault is shut" instead of "has to be open"
   (`doc/credentials.md`, `doc/commands.md`). `sokar vault unlock` stays, for scripts and for opening it ahead of
   time.

## Acceptance

- At a terminal on a shut vault: `task start --provider <p>` asks once and starts the task, and the vault is still
  open afterwards for the time `vault unlock` gives. `vault login` asks before its container starts. `vault revoke`
  and `providers` ask and do their work, and the vault is shut again afterwards. Seen to fail first: today's
  refusals, in the command tests with a terminal stood in.
- Without a terminal (standard input not a TTY): the same commands refuse as today and name `sokar vault unlock`,
  and nothing waits for input.
- The acceptance kit's step that types `sokar vault unlock` before a start is kept for the no-terminal path, and one
  scenario starts a task on a shut vault by answering the prompt.
