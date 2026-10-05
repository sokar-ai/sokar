# B76 — The Code Checked Against The Skills It Was Written Without

**Status:** now; every module has been read, what is left is each section's **Open** list.

**What must be true.** Every Java module has been read against the skills that apply to it, and
each finding is either fixed with a test that was watched to fail, or declined with the reason
written down.

## Why

Every agent opens this issue in its own repository; this is Sokar's.

`AGENTS.md` names the skills this repository expects - **GraalVM**, and the **Java** set - and says
where they come from: `https://fuinorg.jfrog.io/artifactory/agent-skills/`. **Nothing had been
written with them.** They were not installed in the harness, and the section that asks for them
sits a thousand lines into a file an agent reads for the rule it needs.

Measured on the first module that was checked, `sokar-release`, written the same day without them:
reading it against `java-code-review` and `security-audit` found **two defects that its 104 tests
had not**, both fixed in `4a0e933`:

- **A credential went wherever a pom property pointed.** `GITHUB_TOKEN` was sent to the configured
  address, so a typo or a hostile change to one property sent a CI token to another host. It now goes
  to `https://api.github.com` only.
- **A fault answered the question.** An unexpected exception left the JVM with exit 1, which the
  callers read as "a person must look" or "they disagree". It now exits 2, unanswered.

**Tests that pass are not evidence that the code was reviewed** - they are evidence that it does what
its author thought of. The skills are a second author's list of what to think of.

### How to get them

**The harness here does not load them**, and this session could not write to its skills
directory. Reading is enough: download each package as `AGENTS.md` *How to get them* says, check its
SHA-256 against the repository's, unpack it anywhere, and read `SKILL.md`.

## The shape

**Every Java module in this repository**, read against:

    java-code-review    null safety, exceptions, collections, resources, API shape
    test-quality        what the tests prove, not how many there are
    security-audit      secrets, input from outside, what reaches another host
    concurrency-review  the daemon, the proxies, the watchers - shared state and threads
    clean-code, solid-principles
    graal               the modules built as native images: metadata, class initialization,
                        resources - the properties this project has learnt the expensive way

### Done so far

    sokar-release      java-code-review, test-quality, security-audit                  4a0e933
    sokar-supervisor   java-code-review, security-audit, concurrency-review,
                       test-quality, clean-code, solid-principles                      the commit below
    sokar-vault        the same six, and graal for its FFM                             the commit below
    sokard             concurrency-review, security-audit, java-code-review,
                       test-quality, graal                                             (this commit)
    sokar-hooks,       security-audit, graal, java-code-review, test-quality,
    sokar-shield       concurrency-review                                              (this commit)
    sokar-gate         security-audit, java-code-review, concurrency-review,
                       test-quality                                                    ac40a26
    sokar-agent-api,   security-audit, java-code-review, concurrency-review,
    sokar-agent-stub   test-quality                                                    ac40a26
    sokar-core         security-audit, java-code-review, concurrency-review,
                       test-quality, graal                                             ac40a26
    sokar-clearance,   security-audit, java-code-review, concurrency-review,
    sokar-runtime,     test-quality                                                    ac40a26
    sokar-wire
    sokar (app)        security-audit, java-code-review, concurrency-review,
                       test-quality                                                    ac40a26
    the acceptance     test-quality, security-audit, java-code-review,
    kit and suite      concurrency-review                                              ac40a26

"The commit below" is the one that also lets a leg's image cache report never fail the leg.

The order is settled: credentials and threads first, so a finding there never waits behind a style
note - `supervisor`, `vault`, `daemon`, `hooks` and `shield`, `gate`, `clearance`/`runtime`/`wire`,
`core`, the agent modules, `app` by area, the acceptance kit. The tooling modules are read in
`sokar-buildtools`, where they now live.

### `sokar-supervisor`

Fixed, each with a test watched to fail:

- **The real key could reach the broker's log in full.** A request target that is not a valid address
  made the address parser repeat it - after the key had been put in. It is parsed before the key is
  added, and refused with a 400 that repeats nothing.
- **A token request in another spelling went out with the real credential.** `grant_type=refresh%5Ftoken`,
  a JSON `\u005f` and a gzipped body passed the refusal. The body is now read as it came, form-decoded,
  JSON-unescaped, and unpacked when it is `gzip` or `deflate`.
- **The single flight held only for a purchase that succeeded.** Callers waiting behind a refusal each
  asked again; a grant the service had ended was spent once per request. A refusal is answered from
  memory for five seconds, an ended grant until it is authorized anew.
- **An expiry of zero, a negative one or a huge one** bought a token per request or threw out of the
  exchange unanswered; it is treated as unstated.
- **A device flow with an interval of nothing** polled without a pause and never ran out of time; it
  waits at least a second.
- **A reason from elsewhere was written into the error answer's JSON as it came**; it is quoted.
- Three Javadoc blocks sat on the wrong member.

Declined, with the reason:

- **The relay's accept loop does not back off** when accepting keeps failing. That happens only when
  the process runs out of file descriptors, a relay lives per task in that task's process, and no test
  can bring it about without exhausting the machine's descriptors.
- **`TokenSource` throws `TokenPurchase.Refused`.** `Refused` and `Ended` are the vocabulary every
  source of a token answers in; moving them renames across modules and changes no behaviour.
- **`VaultProxy` is one class for the whole exchange.** Splitting the answer path out would make it
  testable without a socket; the socket tests prove it as it is, and the split is a refactoring for
  its own sake now.
- **A `zstd` or `br` request body is not read** for a token request: the JDK cannot unpack them, an
  agent compresses its requests that way, and an answer carrying a token is still withheld.

Where the skills and this repository differ, the repository's rule holds: test methods are named as
sentences; absence is JSpecify's `@Nullable` checked by NullAway, not `Optional`; comments say why,
with the measurement; an empty catch is accepted when it says why.

### `sokar-vault`

Fixed, each with a test watched to fail:

- **A credential stored while a device unlocked was lost.** `enroll`, `revoke` and `used` read the
  vault, changed it and wrote it back without the lock `update` holds; `used` runs on every device
  unlock. Every read-modify-write now goes through one locked method.
- **A passphrase beyond ASCII typed at the star prompt** became one character per byte, so it opened
  nothing the same passphrase made any other way. The bytes of a character are read as one, and a
  backspace takes back a character.
- **A truncated RSA key file** threw a stack trace, or - cut inside its last number - was padded with
  zeros and accepted, and the forge then refused every signature. The DER reader checks every length
  against what is left.
- **A field length near the largest int** overflowed the OpenSSH reader's bounds check.
- **A vault entry printed its secret** through the record's own `toString`; it says its kind and length.
- A Javadoc block sat on the wrong method.

Moved, with the reason:

- **Copies of the passphrase and keys left in memory** - the keyring's strings and native buffers, a
  device key and the master key on paths that throw - are B23's, which owns what a managed runtime
  can and cannot wipe; none of it can be observed from a test.

Declined, with the reason:

- **A header with many passphrase slots** makes opening run Argon2 once per slot, past the cost cap.
  Writing such a header needs the account the vault belongs to, which can already do worse.

Still to do in this module: three tests that do not prove their guard - the keyring's session-link
case never removes the link, the failed-write case throws before anything is written, and nothing
tests two processes against the file lock.

### `sokard`

Fixed, each with a test watched to fail:

- **Follow records written by two threads of the daemon at once** - a follow over the socket and the
  follow pass - threw, because a file lock does not wait for a thread of the same process. The socket
  answered "null", or the pass dropped its record. A lock per directory now goes around the file lock.
- **A log tail counted its position by encoding decoded text again**: one byte that was not UTF-8 put
  the position past the end, and the log was sent again every 200 ms; a line longer than one read was
  never passed. Counted in bytes now, and a read that is one long line goes as it is.
- **`Talk` replayed the machine's whole message history** to every client that connected; the
  contract says it is live only.
- **A message to `''` or `'..'`** reached directories that exist and made a mailbox there; a name that
  is no task's is `NoSuchTask`, as everywhere.
- **A URL, an upstream or a host from the socket** reached git and ssh-keyscan as an argument, and one
  starting with a dash is an option there (`--upload-pack=...` is a command git runs). Such a value is
  `InvalidParameter` before any program sees it.
- The test that a second daemon takes over a stale socket never met one; it makes one now.

Fixed without a test: the pending-work bundle is written in an owner-only directory of its own, not
in a name freed in the shared `/tmp`. Racing for that name needs a second user, which no test here has.

Declined, with the reason:

- **`RestoreBackup` takes any bundle the client names.** The socket is the account's own, so the
  client is the account, which can restore any bundle it has at the terminal too; the restore's
  `HOLDS_WORK` refusal still protects work nobody reviewed.

Closed in `wire`: **a stream noticed a client had gone only when it next sent**, so a client that
reconnected left `Watch` and `Prompts` asking podman every half second for the daemon's life. A stream
now asks between its waits whether its client is still there - a read that does not wait - and ends.

### `sokar-gate`

Fixed, each with a test watched to fail:

- **What was approved was the ref as it stood at the approval, not what was reviewed.** The task's gate stays
  up while a person reads, so a push made after the review reached the upstream unseen, and one landing
  between the forward and the clean-up was deleted. `approve` and `approveSigned` pin the commit, take the one
  reviewed (`--commit`, `Approve`'s `commit`) and refuse when the ref has moved; the ref is removed only while
  it still holds what went.
- **Every task could read every other task's work.** The mirror is the project's; served whole, one agent
  listed and fetched another's unreviewed ref. A task's gate hides all incoming refs but its own and its
  rescue - by name, so `task-1` does not reveal `task-10`.
- **A ref name ending in a Unicode space** compared equal to the task's own ref after Java's trimming, and git
  made a second ref of it. Only the line end is taken off, as receive-pack does.
- **A tag or a blob pushed to an incoming ref** made the pending list throw for every task of the project. It
  is listed as "not a commit", and a date no instant can hold no longer throws.
- **A workflow whose path git quotes** - a byte beyond ASCII, a `"` - ranked ordinary. Paths are read with `-z`.
- **A git that hung held its request for ever**: the limit started only once its output had closed. It runs
  from the start, and git is ended whatever ends the request.
- **A request body was read without a limit**; beyond a gigabyte it is refused before it is held.
- **A refused ref name reached the log as sent**, with line breaks and escape sequences; control characters are
  written out.

Declined, with the reason:

- **No limit on how slowly a request's headers arrive.** A gate serves one task on loopback; only the
  network fallback binds more widely, and the JDK server's limit is process-wide and unmeasured here.
- **A response is held whole in memory.** It is a fetch of the project's own mirror, whose size is the
  repository's; streaming it is a rewrite of the server for a case not seen.

### The agent modules, and one more in `sokar-supervisor`

Fixed, each with a test watched to fail:

- **The real API key could go to a host the agent chose.** The broker forwarded to the upstream with the agent's
  request target appended as it came: `@other:<port>/x` made the upstream's host the user part, and
  `.attacker.example/x` extended it. Only a path is forwarded, and only to the upstream's own host and port.
- **A broken adapter could hang every task start or break the lookup for all agents.** A word with no line end
  held the readiness wait for ever, the handshake had no limit, and a connection failure escaped the list as
  another kind of exception, leaving the agents started before it running. The wait reads what is there, the
  handshake is bounded, every failure is the agent's own, and its client and socket are cleaned up.
  **The bound itself was first a defect**, found by Agent Smith's Pi suite under load: the handshake waited on a
  virtual thread, so on a machine of two processors an agent that answered in a tenth of a second was given up
  on, and the executor was never closed. It waits on a platform thread of its own, ended with each handshake.
- **`mode: 0644` installed a file as 0420**, YAML reading it in octal; a mode must be written as a string.
- **An install target, a reason or a packaged target could start another build instruction** - `;`, a line
  break, a space. Each is a plain path, or one line.
- **A provider file of a person's own could take a packaged provider over without a word**; `sokar providers`
  says which file was set aside for which.

Declined, with the reason:

- **A provider's `authQuery` map is not copied**, and **a session id may begin with `-`**: the map is never
  changed after reading, and the id is quoted and stays inside the container it came from.

### `sokar-core`

Fixed, each with a test watched to fail:

- **An upstream in the project file could run a command on the host.** It reached `git fetch`, `clone` and
  `push` as written, and `--upload-pack=<command>` ran at the next task start (reproduced). The project file
  refuses an upstream or `base_image` that starts with `-`, uses `ext::` or `fd::`, or holds a space or a line
  break; and the gate puts `--end-of-options` before every address, so no other way in can.
- **`snippet_file` read any file the person can** - an absolute path, `../`, a link - into the image or an error
  on screen. It is read from the repository and nowhere else.
- **A transport's name under `mail.transports` became a path** that clearing a project deleted under; it is
  named as a peer's transport is.
- **A command's time limit did not hold**: only the child was ended, and its output was waited for without
  a limit while a grandchild held it open. The descendants are ended at the limit, and the output is taken
  after a short grace. **A child that did not read its input** blocked the caller, or failed a command that had
  succeeded. **Output was collected without a limit**; its end is kept, with how much was cut.
- **A credential covered whatever began like its match** - `https://github.com` covered
  `https://github.com.evil.example`. It covers its host and what is below it.

Declined, with the reason:

- **`prctl` is called without saying it is variadic.** Undefined by the ABI, it is right on both architectures
  Sokar builds for; changing the downcall changes its native-image registration, which CI checks, for no
  behaviour that differs here.

### `sokar-clearance`, `sokar-runtime` and `sokar-wire`

Fixed, each with a test watched to fail:

- **A person's answer from a client was undone by the notification running out.** The timeout wrote over the
  ALLOW given from an interface while the notification waited: the firewall was open, the hub said it was not,
  and after a resume it was never allowed again. An answer from elsewhere stands; one at the notification
  itself came last, and stands too.
- **The name the agent queried went into the desktop notification as it came** - markup, a right-to-left
  override that made it read as another host, a length that pushed the warning out of view. Markup is escaped,
  control and format characters written out, and a long name cut in the middle, the address kept.
- **An agent could keep a person answering one question after another**, one per port. A run asks at most 20;
  beyond, a new destination stays blocked unasked, recorded as `limit`, and a client can still answer it.
- **A report reached every subscriber with whatever its sender added**, `verdict` and `source` included, and
  read like an answer. It carries what was blocked and nothing else.
- **Two projects could share a container name** - `web` with task `app-x`, `web-app` with task `x` - and a
  start brought back the other project's task with its workspace and egress. The project label decides whose
  it is, at a start and among a task's peers.
- **The JSON reader** overflowed the stack on deep nesting (now refused past 256), read `1e999` as Infinity and
  wrote it back as no JSON, accepted a key given twice when its first value was `null`, and a `\u` escape with
  a sign.
- **A stream's check whether its client was still there buffered without limit**; past a message's limit the
  client is answered as gone. Clearance's own `Subscribe` ends with its client too.
- **A label went between single quotes as it came** in a task window's script; it is quoted as a word.

Fixed without a test, with the reason:

- **The desktop prompt registered a signal handler per question and never removed it.** It is closed when the
  question is done; showing it takes a session bus, which neither the build nor CI has.
- **The clearance journal was readable by others until its first line was written.** It is created owner-only;
  the window cannot be observed after the fact.

Declined, with the reason:

- **A client that half-closes its side after a streaming call is answered as gone.** No client here does it,
  and the end of input is the one sign a stream has.

### `sokar-hooks` and `sokar-shield`

Fixed, each with a test watched to fail:

- **A domain in an egress set file reached the resolver's configuration unchecked.** A project's
  domains were checked and a set's were not, so `"#"` in a set became `server=/#/...`: every name
  resolved, and every answer opened its ports with nobody asked. A set's domains are checked as a
  project's are, by the one check both now share.
- **The firewall hook answered any stage but `createRuntime` as done**, a missing one too, so a
  descriptor without the stage or with another started the container with no ruleset. Only
  `poststop` has nothing to do; anything else refuses the container.
- **An allowed address could be a range or more than an address.** The sets take intervals, so
  `0.0.0.0/0` opened everything, and nft reads a `;` in its arguments as a further command. Only one
  literal address is allowed or withdrawn.
- **A hook caught only exceptions**: an `Error` left no line in the hook log and refused the container
  even for a hook that only reports. Everything is caught at that boundary now.

Also: the resolver's class comment claimed it closed DNS exfiltration, which `doc/security.md` rightly
says it does not; `doc/reach.md` promised ports beyond 80 and 443 that no project key offers; two
Javadoc blocks sat on the wrong member; two unused imports.

Open:

- **A hung `nft` is killed by the runtime's own timeout before the hook's**, so the container is
  refused - which is right - but the hook log says nothing about why. Needs a seam for the program it
  runs before it can be tested.
- **The netlink socket leaks its descriptor when binding fails**; the process exits right after.

### `sokar` (the command line and the daemon's logic)

Read by area - the task's start, its messages, projects and following, the gate's commands, clearing. Fixed, each
with a test watched to fail:

- **A follow kept the key it pinned when the follow was not applied**, so a refused or failed follow still
  trusted the signer it named. The pins are put back. A followed address that git would read as an option is
  refused, and the fetch puts `--end-of-options` before it.
- **The signature on a followed project was checked against any project's lines**, and on the ref rather
  than the commit that was then applied. It is checked against the project's own lines, on the commit.
- **What a task wrote reached the person's terminal as written**: names of held messages, a transport's
  error, a review's file names. Terminal controls are shown escaped wherever `talk`, `gate pending` and
  `gate review` print them.
- **A task's box could be made to reach outside it.** A message file that was a link was read through it; a
  box whose directories the task replaced with links led the host to read, sign and delete, or write,
  wherever its account can. Links are not followed, and nothing is taken from or put into a box that holds
  one. A message is read up to a limit, its role from the bytes signed, not from a second read.
- **Two writers changed one record at once.** The daemon and the command line both pass a box, record a
  message and decide a held one; a project is reconciled, a task resumed and an image built from either.
  Each now holds a lock the other respects, in one process and across them.
- **Projects whose names meet shared what was theirs**: `web` with `app-api` and `web-app` with `api` had one
  deploy key and one container name, so one project's start brought back the other's task and clearing one
  deleted the other's key. A key is named by project and repository apart, a key kept under the old name
  goes only to the repository it was made for, and a container is taken by its project label.
- **A start at an unknown host was checked against the project file only**, not against `--upstream`; a
  host was known by any entry that named it among others, or on another port; `host:path` without a user
  was not seen as ssh. Each is asked as the start will connect.
- **A refused start left an account in the conversation**, and a failed start left its container behind.
  The account is made after the last refusal, and a failed start removes what it made.
- **A mirror was seeded from the directory the command ran in** when that was another project's checkout.
  It is seeded only from this project's own.
- **Clearing the account stopped at the first project that held work**, with the ones before it already
  gone, and a project whose removal failed still lost its follow and its keys. Clearing the account is all
  or nothing, and a failed project keeps what belongs to it.
- **The ssh command and the credential helper given to git were joined unquoted**, so a state directory
  with a space broke every fetch and a key path with `;` ran as shell. Each path is one shell word.

Open:

- **A peer can deliver the same message id twice across passes** when the first copy was held; the
  record knows only what was handed to the task.
- **The message watch has no answer when the machine's inotify watches run out**; it falls back to the
  pass every few seconds, which is correct but not said anywhere.
- **Pruning expired tokens and lending one can meet**; the lent token may be pruned before it is used.
  Harmless - the use fails and is retried - but not tested.

### The acceptance kit and suite

Read for what makes a suite lie: a step that passes when what it checks did not happen. Fixed, each with a test
watched to fail where it can be one without a machine, and measured on the machine where it cannot:

- **The check that a passphrase was not echoed could not fail.** The kit's terminals have echo off at the
  machine's end, so a prompt that left echo on showed nothing either. The vault's steps type at a terminal
  that echoes, as a person's does.
- **A firewall check passed when the firewall could not be read**, and a port probe that could not run read
  as closed. The scenario shows the address entered the set first, says so when the set cannot be read, and
  tells a probe that did not run from a closed port.
- **A task counted as started on its container line alone**, so a start that failed after it passed. The
  start's exit status is part of it.
- **The summary counted undefined, pending and ambiguous scenarios as passed**, and only failed ones were
  annotated: a renamed kit step made an agent repository's scenarios undefined, and the summary said every one
  passed. They are counted apart and annotated.
- **`its output mentions one of` passed for anything** when the list held an empty entry; it is refused.
- **Failure messages printed whole outputs**, where a secret typed or put a moment before can be. They pass
  through the scenario's redaction, the terminal's timeout too.
- **One account that could not be reached left every later account's projects behind** at cleanup. Each is
  cleaned up on its own. The reboot request's own answer was lost to a backgrounded list. A security class and
  a package name reached a shell unquoted.

Declined, with the reason:

- **`is a snapshot that the next build supersedes` passes for a release.** The agent repositories run that
  step on every leg, a release's too, where there is no next snapshot; it says `RELEASE` and the version.

Open:

- **A remote command has no time limit**: one hung `podman exec` waits for the job's limit and loses every
  report. The ssh client is `sokar-buildtools`'; the limit belongs there.
- **`ends within N seconds` asserts only that the limit was not reached**, so an immediate refusal passes it;
  every scenario here asserts what was said after it, a repository using the kit may not.

## Acceptance

- A list of the modules, each with the skills it was read against and the commit that closed it.
- **Every fix carries a test proven to fail** by undoing the fix, as this repository already requires.
- **Where a skill and this repository disagree, the repository's measurement wins and the
  disagreement is written here** - for example `test-quality` asks for `@DisplayName`, and this
  repository names test methods as sentences.
- A skill's claim that this build's own behavior contradicts is recorded with the measurement. The
  first one: `sokar-release` guarded against the JDK HTTP client forwarding `Authorization` across a
  redirect; measured on JDK 25, it drops the header when the host changes, and a test now holds it
  to that.
- **Seen to fail:** each fix's test, run with the fix undone, goes red; a module listed above
  without its skills and closing commit means this issue is not done.
