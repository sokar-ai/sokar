# Base Requirements

What the product must do below the interface: the CLI, the daemon and the guarantees they make.
One file per requirement, each carrying its own acceptance criteria so it can be judged done or
not done.

**Open question** counts the unresolved items in the file's own *To be checked* or *Still open*
section - something whose answer could change what the requirement says, or whether it survives at
all. A question that has been answered is not listed, because it is no longer in the file.

The count is what is left, not what was ever asked. Several built requirements still carry
questions; that is the ordinary state of a requirement here rather than a sign it is unfinished.

## Work, grouped by when

Grouped by when, ordered within each group; the number is only the file's identity. Why a
requirement exists is in its own file - this table says only what it is, where it stands and when
it is due. Where the position is not obvious, the last section says why.

**Status** is one of four, and each is taken from the requirement's own status line rather than
guessed at here:

| | |
|---|---|
| `open` | Nothing of it is built. |
| `decided` | The design question is answered and no code is written - which is a different thing from open, because it is ready to start. |
| `in progress` | Part of it is built. The file says which part. |
| `built` | Every acceptance criterion is met, and a question is still open that could change what the requirement says. |

There is deliberately no value for *met*. **A requirement that is finished is deleted**, along with
its row, in the same change that finishes it - and whatever it measured that outlives it is written
into [AGENTS.md](../../AGENTS.md) or `doc/` **first**. An index holds what is still to do.

**Blocks** names the issues in other repositories that cannot be worked on until that row lands. The agent repositories and the interface mark the same relation from their side as *blocked by*, so a dependency is visible from both ends and neither side has to remember the other's. A row that blocks something elsewhere costs more to leave waiting than its position here suggests: the waiting happens in another repository's queue.

### Now

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B157 | [Doctor Says Its Findings In Colour](B157-Doctor-Says-Its-Findings-In-Colour.md) | implemented here | - | `sokar doctor` colours a failure red, a warning yellow and what is fine green, beside the same words, and only on a terminal without `NO_COLOR`. | - | found in the joint test, 2026-10-10: the failing line had to be searched for |
| B137 | [An Offline Project Never Connects](B137-An-Offline-Project-Never-Connects.md) | implemented here; the suite's round trip open | - | An offline project makes no connection out, from the task or from the host; a repository comes in as a file and the work leaves the same way. | - | decided on 2026-10-09; the host cloned the mirror once over the network |
| B102 | [A Vault Cleared In One Step](B102-A-Vault-Cleared-In-One-Step.md) | now | - | `sokar vault clear --yes` on a real machine leaves it as a new one: vault, cached passphrase and device shares, and a transport's accounts gone, and `vault init` starts afresh. | - | the operator, 2026-10-03 |
| B76 | [The Code Checked Against The Skills It Was Written Without](B76-The-Code-Checked-Against-The-Skills-It-Was-Written-Without.md) | open | - | Every Java module has been read against the skills that apply to it, and each finding is fixed with a test watched to fail or declined with its reason. | - | [note](#b76) |
| B121 | [A Message Record Without A Chain](B121-A-Message-Record-Without-A-Chain.md) | now | - | A task's message record is the host's plain state - filtered, held, refused, delivered - with no hash chain; what was said, and in which order, is the homeserver's to keep. | - | the operator, 2026-10-06 |
| B122 | [No Direct Chat Between Tasks](B122-No-Direct-Chat-Between-Tasks.md) | now | - | Two tasks never talk in a direct chat: a direct chat is only between a task and a person who joined the project's conversation, so the operator reads what tasks say to each other. | - | the operator, 2026-10-06 |
| B125 | [What A Daemon Costs, And On Which Threads](B125-What-A-Daemon-Costs-And-On-Which-Threads.md) | now | - | What one `sokard` costs is measured idle and at work, and its loops run on the kind of thread that the measurement and B79's fault support, decided with numbers. | virtual or platform, after the B79 scenario | - |
| B126 | [One Source For A Repository, Both Ways](B126-One-Source-For-A-Repository-Both-Ways.md) | implemented here; the walk is open | - | A task's repository has one source - the checkout it was started in, or a remote - and its work comes from there and goes back there; a checkout's remote is the person's own to pull and push. | which branches a checkout gives the mirror | - |
| B127 | [An Online Task Pushes Through The Host](B127-An-Online-Task-Pushes-Through-The-Host.md) | implemented here; the acceptance run is open | - | An `online` task reaches its upstream only through the gate on the host, with no credential or socket in its container: the host fills the gate from the upstream, fetches through on every fetch, and passes the task's own branch on while the agent's push runs; the classes differ only in approve versus at once | The branch's name at the forge; how long a push waits on a slow forge | No credential in a container is true for all three classes, the agent reaches no more than its branch, and a local `file://` upstream works |
| B128 | [A Gate A Task Cannot Exhaust](B128-A-Gate-A-Task-Cannot-Exhaust.md) | implemented here; the acceptance run is open | - | What a task's agent can make its gate do on the host is bounded: a few requests at once (`503` beyond), a push streamed rather than held in memory, and the gate with its `git` in a scope with memory, process and CPU limits | How many requests at once; one scope per task or one slice | The agent holds its gate's token, and today it can take memory and processes from `sokard` and every other task |

### Soon

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B138 | [A Cost And Token Ceiling Per Task](B138-A-Cost-And-Token-Ceiling-Per-Task.md) | open | - | A task cannot spend more than its project allows: the broker stops forwarding to the provider once a task's token or cost ceiling is reached, and says so to the task and to the person. | How cost is known per provider and model | a review of security guidelines, 2026-10-09 |
| B139 | [An Alert On What Looks Wrong](B139-An-Alert-On-What-Looks-Wrong.md) | open | - | Sokar raises an alert on anomalies a person should see - very large prompt bodies, many clearance attempts, unusual name patterns under declared domains - and a project may choose to contain the task on an alert: freeze or stop it and revoke its token. | Near B26 and B38 | a review of security guidelines, 2026-10-09 |
| B140 | [A Secret Scan Of The Workspace At Start](B140-A-Secret-Scan-Of-The-Workspace-At-Start.md) | open | - | When a task starts, its workspace is scanned for credentials, and a found one is named to the person before the agent works. | Which detector | a review of security guidelines, 2026-10-09 |
| B141 | [New Dependencies Checked Before Review](B141-New-Dependencies-Checked-Before-Review.md) | open | - | A dependency the agent's work adds is marked in the review and checked: that it exists in its registry, how old it is, and whether its name is close to a well-known one (slopsquatting). | Which registries first | a review of security guidelines, 2026-10-09 |
| B146 | [One Stop Of Every Task On A Node](B146-One-Stop-Of-Every-Task-On-A-Node.md) | open | - | One command stops every task of an account on a machine at once, and the stop is journalled. | Whether it also revokes the tasks' tokens | a review of security guidelines, 2026-10-09 |
| B148 | [Token Counting Per Task](B148-Token-Counting-Per-Task.md) | open | - | The broker counts each task's tokens per request, from the provider's own answer, and keeps the counts structured per task. | Providers that do not report usage in their answer | a review of security guidelines, 2026-10-09 |
| B149 | [A Local Provider Template](B149-A-Local-Provider-Template.md) | open | - | Sokar ships an example provider definition for a model served on this machine, an OpenAI-compatible endpoint on the host, so a person can keep prompts on the machine; choosing and running the model stays theirs. | How a task reaches a host port while its firewall allows none | a review of security guidelines, 2026-10-09 |
| B150 | [The Whole Diff In Review](B150-The-Whole-Diff-In-Review.md) | open | - | A review shows the whole diff, or says plainly that and where it was cut. | The message size limit of the socket for a review | a review of security guidelines, 2026-10-09 |
| B154 | [A Tamper-Evident Event Log Per Node](B154-A-Tamper-Evident-Event-Log-Per-Node.md) | open | - | Every security-relevant event of a node - approve, reject, clearance, hand-in, runtime extensions, and every change of permissions (a clearance at run time, `--credential`, a change of `project.yml`) - is written to a log that shows when it was changed afterwards. | It replaces what B121 removes from the message record | a review of security guidelines, 2026-10-09 |
| B136 | [An Approve Bound To What Was Reviewed](B136-An-Approve-Bound-To-What-Was-Reviewed.md) | open | - | What `sokar gate approve` forwards is the commit the person read; a push between review and approve is never forwarded unseen. | whether a stopped task needs asking | a security review found the plain approve takes whatever waits |
| B108 | [The Narrowed Message Schema Enforced](B108-The-Narrowed-Message-Schema-Enforced.md) | open | sokar-message-sluice, if the filter checks it | B14's narrowed A2A schema - fixed kinds and data parts, a Sokar extension URI - enforced, and the agents told so. | which side checks what | the operator, 2026-10-04 |
| B98 | [The Image A Signature Names](B98-The-Image-A-Signature-Names.md) | open | - | A signed project's image is the image it names: a digest, said when missing, recorded when built. | signature policy or digest alone | Codex's review (PJ19) |
| B99 | [Packages Signed And Checked](B99-Packages-Signed-And-Checked.md) | open | the agent repositories | Every RPM is signed, and every place that sets up the repository checks it. | - | Codex's review (PJ19), shared with the agents |
| B100 | [A Task Can Be Looked At](B100-A-Task-Can-Be-Looked-At.md) | open | sokar-frontend | A person sees and drives what a task runs on its virtual screen, through a loopback-only view; nothing of the box runs on their computer. | how it is switched on and authenticated | the operator, 2026-10-03 |
| B95 | [A Container To Try Waiting Work In](B95-A-Container-To-Try-Waiting-Work-In.md) | open | sokar-frontend | `gate try <task> -- <command>` runs a command against waiting work in a fresh container with the project's egress and no way back, its result beside the review; later an IDE attaches there, never on the host. | two - its limits, and recording the result | |
| B79 | [A Daemon That Answers Nothing](B79-A-Daemon-That-Answers-Nothing.md) | soon | - | A call on the daemon's socket, or on an agent's, is answered or refused within a bound, and when one is not, the reason is known rather than guessed. | 2 | [note](#b79) |
| B117 | [What Clearing Leaves For A Person](B117-What-Clearing-Leaves-For-A-Person.md) | soon | - | A person who clears a project or the account learns of every deploy key handed to a forge for it, an unfollowed project's included, and of unreviewed work before it goes. | 2 | |
| B118 | [An Attached Agent Said At Rest Or Working](B118-An-Attached-Agent-Said-At-Rest-Or-Working.md) | soon | - | `sokar task status` and `list` say `at rest` or `working` for an attached session, read from its screen against the declared `session.at_rest`, as `waiting` is read today. | 2 | the operator, 2026-10-05 |
| B119 | [A Loop Seen In An Unattended Task's Log](B119-A-Loop-Seen-In-An-Unattended-Tasks-Log.md) | soon | - | A wait in the acceptance kit stops when an unattended task's agent repeats a failing tool call its screen does not show, read from `task.log` against a declared marker. | 1 | the operator, 2026-10-05 |
| B120 | [A Wake Waits For Rest That Holds](B120-A-Wake-Waits-For-Rest-That-Holds.md) | soon | - | The wake line is typed only when `at_rest` held on two looks a short while apart, never into a running turn. | 1 | the operator, 2026-10-05 |
| B51 | [The Tests That Run Nowhere](B51-The-Tests-That-Run-Nowhere.md) | open | - | A test that cannot run anywhere in the pipeline says so, rather than reporting itself as skipped on this run. | one | [note](#b51) |
| B55 | [The Changelog Entry A Change Has To Bring](B55-The-Changelog-Entry-A-Change-Has-To-Bring.md) | open | - | A change to what ships or builds brings a changelog entry, or says on purpose that it does not, and the build checks it. | three, and the first is what logchange's maintainers want | [note](#b55) |
| B50 | [Whose Business The Files Sokar Leaves Behind Are](B50-Whose-Business-The-Files-Sokar-Leaves-Behind-Are.md) | open | - | Every file Sokar writes is readable by whoever it concerns, and by nobody else - including on machines that already ran tasks. | four, and the first is whether the umask is fought directly | [note](#b50) |
| B13 | [Unreviewed Work Leaving By The Side Door](B13-Unreviewed-Work-Leaving-By-The-Side-Door.md) | in progress | - | Work that reaches the upstream without passing the gate is prevented or reported, not silently possible. | two, and the guard is built | |
| B40 | [The Domain That May Bind The Socket](B40-The-Domain-That-May-Bind-The-Socket.md) | open | - | Only Sokar may bind a socket carrying Sokar's label, so a task that connects to one reaches Sokar. | four, and the first decides the shape | [note](#b40) |
| B10 | [What An Egress Set Can Express](B10-What-An-Egress-Set-Can-Express.md) | decided | - | A destination that cannot be written as a host name is supported or refused, never silently unreachable. | one | |
| B34 | [What The Resolver Can Actually Do](B34-What-The-Resolver-Can-Actually-Do.md) | open | - | A machine says what its resolver can do and what follows for a task, and a refusal names what was missing. | two, and the first may end it | |
| B48 | [The Overview That Stays Open](B48-The-Overview-That-Stays-Open.md) | open | - | A person at a terminal watches their tasks change, from the same answer the interface reads, without a loop they wrote themselves. | four, and the first two decide its shape | [note](#b48) |
| B33 | [A Task's Own Fetches](B33-A-Tasks-Own-Fetches.md) | open | - | A task can fetch from the forges its work depends on, and a failure to do so is never reported as a credential problem. | three, and the first is whether it reproduces | |
| B26 | [What This Machine Has Been Doing](B26-What-This-Machine-Has-Been-Doing.md) | open | - | A machine can say what it has done, for longer than the tasks themselves existed. | three, and four are decided | [note](#b26) |
| B49 | [What The Build Trusts To Run Beside Its Secrets](B49-What-The-Build-Trusts-To-Run-Beside-Its-Secrets.md) | implemented here; open elsewhere | - | Nothing runs beside this product's credentials that was fetched by a name its owner may repoint. | decided 2026-09-29; the agent repositories and the interface remain | [note](#b49) |
| B30 | [Credentials The Broker Has To Fetch](B30-Credentials-The-Broker-Has-To-Fetch.md) | soon; the key-based kinds wait for a service that needs them | - | A task uses a credential authenticated by a key (`private_key_jwt`, mTLS), the broker signing on the host, and a `client_credentials` purchase is seen to succeed end to end. | 3 | |
| B31 | [An Authorization A Person Grants Once](B31-An-Authorization-A-Person-Grants-Once.md) | device code flow built | sokar-frontend F31 | A person grants an authorization once, out of band, while the work waits. | one | |
| B46 | [The Conversation A Restart Loses](B46-The-Conversation-A-Restart-Loses.md) | soon | - | After a real reboot, `task start --restarted` continues the conversation the agent was having, attended or unattended, or says plainly that it cannot. | 3 | [note](#b46) |
| B135 | [The SELinux Module Loaded By The Package](B135-The-SELinux-Module-Loaded-By-The-Package.md) | decided | - | Where SELinux is enabled, installing the package loads Sokar's policy module and removing it removes the module, so a task reaches the vault proxy with no manual step. | 3 | the operator, 2026-10-09 |
| B134 | [A Task Terminal As The Terminal Outside](B134-A-Task-Terminal-As-The-Terminal-Outside.md) | implemented here; the per-agent check open | - | An agent in a task behaves at the keyboard and on the screen as in a plain terminal - scrolling, Esc, modified keys, colours, clipboard, links, focus, images - measured per agent first, then closed in the task's tmux configuration, each kept by a test. | 3, and the first is the measurement | the operator, 2026-10-09 |
| B133 | [A Shut Vault Opened Where It Is Needed](B133-A-Shut-Vault-Opened-Where-It-Is-Needed.md) | implemented here; the suite scenario open | - | At a terminal, every command that needs a shut vault asks for the passphrase itself and carries on; a task's start unlocks it as 'sokar vault unlock' does; without a terminal it refuses as today. | 5 | the operator, 2026-10-09 |
| B132 | [A Subscription Granted With One Command](B132-A-Subscription-Granted-With-One-Command.md) | implemented here, but for the offer at a start | the agent repositories, for their sign-in in the description file | `sokar vault authorize <provider>` creates the entry from the provider's own grant settings on an empty vault, a task refused for want of a credential names the command, and the OAuth app a grant goes through is decided with its owner. | 3 | the operator, 2026-10-09 |
| B131 | [The Oldest System A Package Promises](B131-The-Oldest-System-A-Package-Promises.md) | implemented here | sokar-build-github GH01 | The packages declare the C library and zlib the binaries need, a build needing more fails before it is published, and the oldest systems are written down: Ubuntu 26.04, Debian 13, Fedora 43 and 44. | 4 | the operator, 2026-10-09 |
| B130 | [Two Refusals By Name](B130-Two-Refusals-By-Name.md) | implemented here | sokar-intellij IJ02, sokar-frontend | `BranchExists(branch, at)` for an approve onto earlier work, and `EarlierWorkWaits(task, commit, subject)` for a start whose name still has work at the gate, in one contract change. | - | a returning task name is answered by name, with the next step to offer |
| B129 | [An Agent Found By Its Description](B129-An-Agent-Found-By-Its-Description.md) | implemented here; the package check's install open | the agent repositories, once the old names go | An agent of another vendor is found by one description file under `agents.d/`, under the vendor's own names. | 2: the old file names beside it; transports and build readers the same way | a vendor's package, executable and directory need not carry Sokar's name |

### Later

| # | Requirement | Status | Blocks | What must be true | Open question | Why here |
|---|---|---|---|---|---|---|
| B142 | [A Misuse Test Suite As A Release Condition](B142-A-Misuse-Test-Suite-As-A-Release-Condition.md) | open | - | A release passes a suite of misuse scenarios - escaping the container, exfiltration past the firewall and the broker, bypassing the approval - before it is made. | Which scenarios first | a review of security guidelines, 2026-10-09 |
| B143 | [Sokar In A Data Protection And ISDS Concept](B143-Sokar-In-A-Data-Protection-And-ISDS-Concept.md) | open | - | A documentation page says what Sokar provides technically for a data protection and information security concept, what the organisation still has to regulate, and what to back up (the vault file, the journal). | - | a review of security guidelines, 2026-10-09 |
| B144 | [AI Provenance In The Commit](B144-AI-Provenance-In-The-Commit.md) | open | - | Approved work carries in its commit who made it: a trailer with the agent, the provider, the model and the task, set on the host at approve, not by the agent. | Whether a rebased or squashed history keeps them | a review of security guidelines, 2026-10-09 |
| B145 | [Four Eyes At The Gate](B145-Four-Eyes-At-The-Gate.md) | open | - | A project may require that the person who started a task does not approve its work alone: a second person approves. | How a person is identified on one machine and across machines (enrolled principals) | a review of security guidelines, 2026-10-09 |
| B147 | [A Wall-Clock Limit For Unattended Runs](B147-A-Wall-Clock-Limit-For-Unattended-Runs.md) | open | - | An unattended run ends at a time limit its project or its start sets, and says so. | Whether a task with a person attached is exempt | a review of security guidelines, 2026-10-09 |
| B151 | [The Providers A Project Allows](B151-The-Providers-A-Project-Allows.md) | open | - | A project names the providers its tasks may use, and a start with another is refused. | Near PJ16, which sets this per machine | a review of security guidelines, 2026-10-09 |
| B152 | [Say What Offline Still Reaches](B152-Say-What-Offline-Still-Reaches.md) | open | - | Every page that describes `offline` says that its agent still reaches its AI provider, so "nothing leaves" is not read as a data protection statement. | - | a review of security guidelines, 2026-10-09 |
| B153 | [Unused Slots And Keys Reported](B153-Unused-Slots-And-Keys-Reported.md) | open | - | A device slot of the vault or a deploy key not used for a set period is reported, and may be locked. | Near B117 | a review of security guidelines, 2026-10-09 |
| B155 | [The Logs Checked And Documented](B155-The-Logs-Checked-And-Documented.md) | open | - | What broker, gate and journals write is documented field by field: no prompt contents, tokens or personal data, or redacted; where each log lies and who can read it. | - | a review of security guidelines, 2026-10-09 |
| B156 | [sokar inventory](B156-sokar-inventory.md) | open | - | `sokar inventory` lists the agents, providers, models, credentials and their last use on this machine, also as JSON for central collection; the register itself stays the organisation's. | Which fields an organisation's register needs | a review of security guidelines, 2026-10-09 |
| B123 | [Message Transports Behind A Published API](B123-Message-Transports-Behind-A-Published-API.md) | later | sokar-message-matrix, which moves to it | A message transport is written against a published, versioned Java API, as an agent is, and lives in a repository of its own. | 3 | - |
| B112 | [A Nickname For Each Agent](B112-A-Nickname-For-Each-Agent.md) | open | sokar-frontend F88 | An agent's nickname is its task's label: named with @, said in its card, and its display name in the conversation. | - | the operator, walk 10, 2026-10-04: Later |
| B101 | [A Mode A Start Asks For By Name](B101-A-Mode-A-Start-Asks-For-By-Name.md) | open | sokar-omp OM19 | An agent declares named modes, and a start asks for one by name; nothing reaches the agent its definition did not write. | - | a read-only review run, 2026-10-03 |
| B86 | [Messages Over Matrix, The Sokar Half](B86-Messages-Over-Matrix-The-Sokar-Half.md) | later; blocked by sokar-message-matrix MX12 | - | Several machines run one project on one central homeserver, each admitted by a person, acting only on its own accounts, and revocable alone. | 3 | |
| B14 | [Talking Between Tasks](B14-Talking-Between-Tasks.md) | later; blocked by sokar B86 (another machine) | - | A person addresses a group of peers as one, and a message reaches a task of the same project on another machine with the guarantees it has on one. | - | [note](#b14) |
| B38 | [How Far Something That Got Through Can Get](B38-How-Far-Something-That-Got-Through-Can-Get.md) | later; blocked by sokar B26 | - | Content Sokar delivers into a task carries its origin, and how far a task got has an answer after it is gone, including what it installed. | 1 | [note](#b38) |
| B18 | [Storing A Credential From Elsewhere](B18-Storing-A-Credential-From-Elsewhere.md) | open | - | A credential can be stored from an interface, the reply never carries the value back, and no path logs, echoes or records it. | two, plus `Login` held open as nice to have | |
| B06 | [Remote Access](B06-Remote-Access.md) | open | - | Tasks on another machine are usable over an encrypted tunnel, without the daemon ever binding a network port. | four | |
| B15 | [Handing Artifacts Between Tasks](B15-Handing-Artifacts-Between-Tasks.md) | open | - | What a task builds can reach another task of the project wherever it runs, only where the project file switches it on and the task is not started with it off, carried in the project's own conversation beside its messages, never to another project or in a direct chat, recorded on the host and never reachable from a task; intermediate results only, never the work's result. | two, both settled by a transport |  |
| B23 | [Secrets In This Process's Memory](B23-Secrets-In-This-Process-Memory.md) | open | - | A credential's plaintext exists in as few places and for as short a time as a managed runtime allows, and what cannot be achieved is written down rather than implied. | three, and one is a one-line fix | |
| B41 | [What a Packaged Agent Installs](B41-What-A-Packaged-Agent-Installs.md) | open | - | `--supply-chain` says what a packaged agent's package ships, read from the bill that package installed. | two, and the first is whose job the reading is | |
| B56 | [Programs Sokar Runs That Could Be Calls](B56-Programs-Sokar-Runs-That-Could-Be-Calls.md) | open | - | Every external program Sokar starts either has a reason to stay a program or is replaced by a call proven on a JVM and in the native image. Nice to have, re-checked with Java and GraalVM releases rather than scheduled. | four, and all are re-checks | |
| B59 | [A Kernel Of Its Own For A Task](B59-A-Kernel-Of-Its-Own-For-A-Task.md) | open | - | Whether a task can run under a runtime that gives it its own kernel is measured rather than assumed - the ruleset, the resolver, the hooks, the sockets and the terminal each have an answer - and the default does not move until it fails closed. Nice to have, an experiment first. | six to measure, five to check, and the first two decide the rest | |
| B63 | [A Share That Is Not Stored At All](B63-A-Share-That-Is-Not-Stored-At-All.md) | open | - | On a desktop a keyslot's share sits in a keystore any process running as that user can read, so a device is worth no more than the account it runs under until the share is derived from a token rather than stored. | two | |
| B116 | [A Log Read Back In Windows](B116-A-Log-Read-Back-In-Windows.md) | later | - | A client reads a task's log back in windows before an offset, and the daemon caps a line's length and says what it cut. | - | |

### Two things that dissolved rather than becoming requirements

Both are recorded where somebody will look rather than dropped.

**Provisioning a machine** is refused and could not have worked anyway - a machine that is not
ready has no daemon to ask - and it is argued in
[the decisions](../../doc/decisions.md#doctor-diagnoses-and-never-repairs). **Routing
credentials to projects** describes a relation that does not exist, and it is argued in
[B18](B18-Storing-A-Credential-From-Elsewhere.md); restated correctly it is the agent roster, which
was already refused.

**And instructions for an agent are not Sokar's business at all.** They live in the repository,
checked in or not, and Sokar does not know what they are called - `CLAUDE.md`, `AGENTS.md`,
something an agent invents next year. It cannot merge them, because how an agent combines several
is that agent's rule and not ours. A team running more than one agent has to agree upfront how
instructions are stored in their repository; that agreement is theirs to make.

A hardcoded list of filenames would have been worse than nothing: for a feature whose only job is
to show what an agent was told, a name that goes out of date produces a confident *"no
instructions"* for a task that had them, and a blank that looks like an answer is the failure this
project keeps writing down. That is the fourth of the same shape, after the agent roster, hardware
access and key routing - each time the honest answer was *"that is not a thing this system has"*,
and saying so cost a paragraph and bought a screen that is not lying.

## To be checked

Two open questions are worth knowing before any of this is planned in detail, because each
changes what gets built rather than only how:

- Whether a person away from the machine can be reached at all ([B06](B06-Remote-Access.md)).
  The transport itself is settled - the socket survives an ssh forward, measured - so what is left
  is the half that decides how much of F20 and F23 is real, and how long a clearance prompt should
  wait for somebody who is not there. Both are in the
  [interface's own set](https://github.com/sokar-ai/sokar-frontend/blob/main/issues/README.md) and
  are named by number rather than linked by file: a requirement that is met is deleted there, so a
  link to one breaks exactly when it is finished.
- Whether the guarantees can be re-derived at all on a second platform (`sokar-project` PJ25). It decides whether that project offers the same
  product or a weaker one wearing the same name, and it constrains what may be added to the
  daemon's contract.

## Why here

Only the placements that are not obvious from the files themselves. Ranking is a property of the
set, so it lives here and nowhere else.

<a id="b76"></a>**B76 is in Now.** The house skills were never used, and the first module read against them had two defects its tests had not found: a token sent wherever a property pointed, and a crash that answered the question. Every agent opens the same issue in its own repository.

<a id="b26"></a>**B26 is where it is** because it is a new subsystem rather than a small change, and
above the rest of Soon because the gap is total rather than partial.

<a id="b14"></a>**B14 is in Later, and B15 waits beside it.** What is left of B14 - groups, and a
message between machines - waits for several machines on one homeserver, which is B86's remaining
half. B15 reuses B14's policy, which is built on one machine.

<a id="b38"></a>**B38 is in Later because what is left waits for something else.** Its
buildable half - the review - and its writing are done. Marking the origin of delivered content is
decided with the deliveries still to come, and keeping what a removed task installed needs B26's
record. It is kept because B14 and B15 would otherwise each re-argue it from scratch.

<a id="b40"></a>**B40 is in Soon rather than Now** although it is small and needs nobody's
decision. What it closes is a second lock on a door whose first lock is the uid: the process it
guards against is already running as the operator and can already read the vault file. It is worth
doing and it is not urgent.

<a id="b46"></a>**B46 is in Soon although continuing a session is built.** What is left is proving it across a real reboot on a rented machine, never the shared VM, and three questions that could change what a declaration says.

<a id="b48"></a>**B48 is in Soon because it is what makes the waiting reading, which is built, worth having on a machine somebody is logged into.** It is the smallest requirement in this list - the daemon already streams changes and the wire client already consumes a stream; what is missing is a caller. The state most worth watching for is the one the daemon now produces - Task.screen and lastMessage - so a view built now is built around the right column.

<a id="b49"></a>**B49 is in Soon although nothing is broken by it.** Everything works; what is wrong is what it would take for that to stop being true: third-party actions fetched by a name their owner may repoint, in jobs holding the publishing credentials. It is implemented here; what is left is the agent repositories and the interface.

<a id="b50"></a>**B50 is in Soon, beside B49, because it is the same omission in a different place.** Neither is a mistake in something somebody wrote; both are what happens where nobody wrote anything - an action fetched by a name nobody pinned, a directory left at a mode nobody chose. What it exposes is covered today by the home directory being 0750, which is luck rather than design, and the mirror holds work that has not been reviewed.

<a id="b79"></a>**B79 is at the top of Soon and not in Now because nothing can be done until it is seen
again.** The likeliest cause is taken away and the daemon can now dump its threads; what is left is
reading that dump the next time a daemon answers nothing.

<a id="b51"></a>**B51 is at the top of Soon's open work although it breaks nothing today.** What it costs is
the ability to tell "did not apply" from "could not apply" - and this repository has already paid
once for not being able to: eleven acceptance scenarios sat green and unexecuted until the day they
were run and turned up three faults. Every further green build makes the two skipped tests look more
settled than they are.

<a id="b55"></a>**B55 is in Soon rather than Now because its gap is deliberate.** The
agent repositories adopted a changelog tool and dropped the old check, so for now nothing forces an
entry. The first step is a proposal to that tool's maintainers, not code here, and nothing is
blocked by it.
