# B159 — Sokar Offers Instead Of Naming A Command

**Status:** being built: the helper (`Offer`) and the unmet host key at a start; B158 (`vault login`) was the first piece.

**What must be true.** Where something is missing or in the wrong state and Sokar today tells the person which command
to type, it offers the remedy at a terminal and carries on; without a terminal it refuses as today, naming the command.

## Why

Found in the joint test, 2026-10-10, at `vault login` (B158). A read-only review of `sokar` found about seventy places
of the same kind: each makes the person read a sentence, type the command it names, and start again.

## The design, for every place

The pattern: check the precondition; if something is missing, offer the remedy; the remedy is **the existing function
of the command a person would type today** (the same code, no copy); check the precondition again; carry on in the
normal flow.

1. **One shared helper.** Each check returns what is missing, the remedy (the command's function) and the question.
   The helper decides alike everywhere: at a terminal ask and run it, without one refuse naming the command, as today.
2. **Check again after the remedy**, never assume it worked. Declined or failed: today's refusal.
3. **Destructive remedies** - discard, delete, leave an account behind at a service - default to "no" and first show
   what is affected.
4. **Scripts:** `--no-input`, or no terminal: no question, today's message. `--yes` takes harmless remedies without
   asking, never destructive ones.
5. **Several gaps in a row** - a passphrase, a key and a host key at one start - are asked one after another, and the
   start happens once at the end instead of failing three times.

## Acceptance: the places

Line numbers are as of `sokar` on 2026-10-10, before this push round; each is checked when it is built.

### (a) Ask, then do it - at a terminal

**Starting a task**
- Hooks missing or pointing nowhere: offer `sokar setup`'s hook step (`TaskLaunch.java:1266`, `:1268`).
- The upstream's host key unknown: show the fingerprints, the person confirms, carry on (`StartCheck.java:421`,
  `TaskLaunch.java:589`, `WorkspaceSetup.java:143`).
- Earlier work waits at the gate: offer another name, such as `NAME-2` (`WorkspaceSetup.java:118`).
- No agent, or no gate: "start a shell instead?" (`TaskLaunch.java:701`, `:721`).
- No sign-in in the vault and the agent can sign in: run the login (`TaskLaunch.java:1339`, `StartCheck.java:607`;
  meets B158).
- Choose from a list instead of naming an option: the repository (`TaskLaunch.java:1462`), the provider
  (`SelectedProvider.java:92`), the agent (`GrantEntry.java:73`), the project (`ShieldEgressCommand.java:106/266`,
  `TalkPassCommand.java:60`, `TalkHoldCommand.java:69`), the task (`TaskTarget.java:130/135`).

**Removing a task**
- Still running: "stop and remove?" (`TaskRemoveCommand.java:97`).
- Holds work not pushed: rescue / discard / cancel (`TaskRemoveCommand.java:115`).
- `--rescue` on a stopped task: "start it to push the work?" (`TaskRemoveCommand.java:121`).
- A task from before a restart: "copy its workspace to ./recovered and remove it?" (`TaskResumeCommand.java:153/181`,
  `TaskInventory.java:587`).
- Unknown content that `--force` would discard unseen: a question defaulting to "no" (`TaskRemoveCommand.java:108`).

**Projects and clearing up**
- Running tasks to stop first: offer it (`ProjectUnfollowCommand.java:118`, `VaultClearing.java:159`).
- Unreviewed work to destroy: a question defaulting to "no" (`ProjectUnfollowCommand.java:110`, `Clearing.java:130`).
- An account at a service left behind: a question defaulting to "no" (`VaultClearing.java:105`).
- Only listed, "again with --yes": "remove these?" (`ClearCommand.java:84`, `PruneCommand.java:76`).
- A signed history rewritten: ask only at a terminal (`Reconcile.java:414`).

**Vault and credentials**
- No vault: offer `vault init` (`VaultUnlockCommand.java:111`, `CredentialDeclarations.java:201/273`).
- An entry under the agent's name instead of the provider's: rename it without entering it again
  (`VaultPutCommand.java:219`, `CredentialChoice.java:88`).
- The vault's copy older than the agent's: "update it?" (`CredentialChoice.java:144`).
- Nothing to import: offer `vault login` instead (`CredentialImport.java:107`).
- A secret left over: "remove it?" (`CredentialDeclarations.java:418`).
- A grant only the service can revoke: "revoke it at the service?" (`VaultRemoveCommand.java:81/103`).

**The gate and the rest**
- The target branch taken: take the suggested branch (`GateApproveCommand.java:87`).
- Somebody else's pre-push hook: offer to append the line (`GateProtectCommand.java:117`).
- A mirror already there: move it aside under a dated name (`GitGate.java:350`).
- A task not in the conversation: offer to start it again (`MessageRelease.java:290`).

**Ask for secrets and details directly** - as `vault put` already asks without echo, and B133 asks the passphrase - and
store them in the vault, then carry on:
- A provider's key missing at a start: "Key for openrouter (not shown):", store, start (`TaskLaunch.java:1341`).
- A vault entry missing (`CredentialDeclarations.java:246`, `GitCredentialNames.java:127`); an ssh key asked as a path.
- An agent without a login of its own: ask the key instead of pointing at `vault put` (`AgentLogin.java:156`).
- Grant settings that are not built in (`VaultAuthorizeCommand.java:259`, `GrantEntry.java:55/61`): ask the client id
  and, where it has one, the secret.
- No vault but a terminal (`SetupCommand.java:147`, `DoctorCommand.java:427`): ask a passphrase twice and make the vault.
- An address or file missing (`OwnRepository.java:56`, `ProjectDefaultCommand.java:55`, `WorkspaceSetup.java:91`):
  offer the current checkout, or ask the path.

### (b) Do it without asking

- Show the information instead of pointing at a command: why a project does not apply (`TaskLaunch.java:440`,
  `ProjectSource.java:117`); the tasks to choose from (`TaskGiveCommand.java:65`, `TaskTakeBackCommand.java:53`,
  `TaskLabelCommand.java:73`, `TaskAttachCommand.java:161`); the projects followed (`ProjectRefreshCommand.java:42`);
  the names of the egress sets (`ShieldEgressCommand.java:303`).
- Register outdated hooks again after an update (`TaskLaunch.java:1275`).
- Keep the new passphrase after a change at once (`VaultPassphraseCommand.java:119`); retry the keyring
  (`VaultPassphraseCommand.java:124`, `VaultLockCommand.java:68`).
- Drop a stale kept passphrase and ask again (`VaultFile.java:515`).
- Restart only the build helper rather than the whole task (`TaskInventory.java:622/629`).

### (c) Stays a message

Only where nobody can answer, or a question would be wrong:
- **No terminal and no person** (the daemon, the broker, helpers, a git hook, a message to the agent, a script):
  `LoginRenewal.java:96/109`, `TokenPurchase.java:126/281`, `TaskWatchBuildsCommand.java:192`, `VaultProxy.java:357`,
  `GateCheckCommand.java:226-230`, `TaskMethods.java:387`, `TaskAttachCommand.java:173`,
  `TransportLifecycle.java:464`. The message then names the command that asks at a terminal.
- **A review or trust decision that must not be made in passing:** `CheckoutApproval.java:168`, `GitGate.java:940`,
  `GateException.java:51`, `ProjectEnrollCommand.java:69/76`.
- **Destructive, or changing the person's own files:** `VaultInitCommand.java:63` (a vault exists),
  `VaultHeader.java:163` (an old vault version), `StoredKey.java:84` (would rewrite the key file).
- **A plain report or a usage error:** `DoctorCommand.java:443/446/456`, `TaskListCommand.java:80`,
  `TaskStatusCommand.java:184`, `Clearing.java:120`, `VaultUnlockCommand.java:85`, `TaskLaunch.java:1249`.

### Already covered

- **B133**, the vault opening itself: `VaultClearing.java:70`, `Clearing.java:299`, `Prune.java:456`,
  `TaskResumeCommand.java:170`, `TaskRunCommand.java:512`, `CredentialWiring.java:178`, `StartCheck.java:599`,
  `ProvidersCommand.java:66`, `AgentLogin.java:200/353`, `VaultRevokeCommand.java:45`, `DoctorCommand.java:293`. To be
  checked without a terminal, as the daemon reaches them: `DeployKeys.java:162`, `TransportLifecycle.java:468/524`,
  `Conversations.java:141`.
- **B132**, the grant at a start: `StartCheck.java:290`, `TaskLaunch.java:1336/1370/~660`; `VaultServeCommand.java:287`
  is the same refusal from the broker after the start.
- **B158**, `vault login` on an agent already signed in: `AgentLogin.java:175`.

## Tests

Each place: with a terminal stood in, the remedy offered, run, and the precondition checked again; declined, and
without a terminal, today's refusal; a destructive one defaulting to "no". The helper's own decision tested once for
terminal, no terminal, `--no-input` and `--yes`.

## Built so far, 2026-10-10

- **The helper, `Offer`** (`app-base`): rules 1-4 as above, for every place; `OfferTest`. Rule 5 holds by each place
  calling it in turn before the start goes on.
- **Task start, an unmet host key:** the keys the host offers are shown and the person is asked to trust one, no by
  default, never by `--yes` (`HostKeyOfferTest`). `task start` takes `--no-input` and `--yes`.
- **Task start, a missing credential:** the command the refusal names - `vault authorize`, `vault login`, or
  `vault put`, which asks a key without echo - runs on this terminal as a child of the start (`context.exec`), the
  credential is looked for again, and the start goes on (`CredentialOfferTest`). One command for all three keeps
  rule 1: the remedy is the command's own code.
- **Choosing from a list, `Offer.choose`:** the options shown by number, one asked; never taken by `--yes`. First
  place: several agents and none named at a start - asked once and kept for the whole start (`OfferTest`).
- **Task start, hooks:** nothing to offer. A start already registers missing and outdated hooks without asking;
  `DANGLING` means the hook binaries are not where this Sokar has them, which neither `sokar setup` nor anything Sokar
  can run makes good - only installing the package again does, so it stays a message.

