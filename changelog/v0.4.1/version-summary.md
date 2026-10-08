<!-- @formatter:off -->
<!-- noinspection -->
<!-- Prevents auto format, for JetBrains IDE File > Settings > Editor > Code Style (Formatter Tab) > Turn formatter on/off with markers in code comments  -->

<!-- This file is automatically generate by logchange tool 🌳 🪓 => 🪵 -->
<!-- Visit https://github.com/logchange/logchange and leave a star 🌟 -->
<!-- !!! ⚠️ DO NOT MODIFY THIS FILE, YOUR CHANGES WILL BE LOST ⚠️ !!! -->


[0.4.1] - 2026-10-08
--------------------

### Added (18 changes)

- sokar approve names the commits and changes a task holds that are not at the gate, instead of only saying nothing waits 
- An online task is told what the build of its push did - each verdict and the jobs' logs arrive in /sokar/files, read from the host by a build reader found at run time, so the task holds no forge token - and a project names the forge, the vault entry and which logs under builds 
- The build runs the jar deploy on every push with both upload URLs at a dead local port, and sokar-release check-deploy reads it, so a tag's deploy repeats what main already did 
- A task's gate serves four requests at once and answers the next at once with 503, spools a push to disk instead of holding it, and runs with its git in a scope of 1 GiB memory, 256 tasks and half the default CPU weight 
- Hand a file to a running task: sokar task give puts it, whole and read-only, in /sokar/files, the daemon takes it in parts from a client on another machine, every hand-in is written down, and limits.hand_in sets the largest file 
- Initial public version 
- ci/leg-build.sh is what a rented machine builds of this tree, for the legs and for the machine images' proof, and the build checks every module it names against the reactor 
- The daemon's journal says how long each move of messages took, what started it and how the vault was read 
- An online task's gate fetches from the upstream before the agent fetches, at most once every five seconds, and refuses any branch but the task's own, a tag or a deletion 
- The packaging build reads the .deb and .rpm it wrote, Sokar's and the stub agent's, and fails when one carries another version than the project's as the packaging maps it 
- sokar project refresh, and RefreshProjects over the daemon, fetch a followed project now instead of at the next round 
- sokard serves its connections and runs its passes on virtual threads when SOKAR_THREADS=virtual, for measuring; platform threads stay the default 
- sokar approve, typed in the checkout a task was started from, shows the work waiting at the gate and after yes puts it on the branch sokar/<task> there, never on the checked-out branch and never to a remote; the guide in a task tells its agent to commit and push when done 
- The stub agent's working mode takes 'load': at every step a streamed request through its provider's socket and 8 MiB written to its disk 
- The stub agent works until stopped in a workspace holding .sokar-stub-works: a line on the screen, a commit, and every tenth step a push, for measuring a machine under many tasks 
- A task's agent is told what Sokar gives it - /sokar/files, the builds of its pushes, its mailbox - in one guide at /run/sokar/guide/README.md, and a line at its prompt names each file that arrives while it rests there 
- sokar task refresh, and RefreshTask over the daemon, bring a task's repository at the gate up to its source and tell its agent; starting a stopped task does the same 
- Sokar trusts the certificate authorities in ~/.config/sokar/ca-certificates.pem beside the built-in ones, for an organisation that inspects TLS or a provider of your own 

### Changed (16 changes)

- sokar-bom manages only what sokar publishes - sokar-wire, sokar-agent-api, sokar-build-api, sokar-acceptance-kit; JUnit, the build tools, Cucumber and sshj are managed by sokar-parent 
- A push that changes only documents - Markdown, mkdocs.yml, doc/ and issues/ - starts no build and leases no machine; the Shared rules workflow checks it 
- The build checks every page and file for a citation of an issue that goes stale, and the documentation chapter for a page off its navigation or a link the site cannot follow, with the shared build tools 0.4.1 
- A task started in a checkout takes the checkout's committed history, local commits included, and its approved work comes back into it; Sokar never reaches the checkout's remote, and two checkouts of one remote are two repositories 
- The message filter runs without its 200 ms settle, since every message is put in place by rename 
- The guide in a task says how to push: git push sokar, naming no branch, to the task's own place, $SOKAR_TASK_REF 
- The acceptance leg and the deploy of a handover are this repository's own module, acceptance/legs, built on sokar-machines; a regrouping of the modules changes them in the same commit 
- The command line's modules are submodules of apps/ and the packages' of dist/, every module has a README.md that sokar-release check-readmes holds, and no artifact's coordinates change 
- A release tag refuses every snapshot in the effective pom - a dependency, the imported BOM, a plugin or a plugin's dependency such as sokar-release - before it builds, with the build tools at 0.4.2, which bring the check 
- An online task pushes through its gate like a guarded one; the gate passes the task's own branch on to the upstream at once as sokar/<task>, and no container holds a key, an ssh-agent socket or a route to the upstream 
- The build takes sokar-parent 0.1.2 and with it sokar-buildtools 0.4.3, instead of a snapshot of the tools 
- The NullAway compile and the jdeb and rpm plugin versions come from sokar-parent, and exec-maven-plugin is 3.6.3 as it sets 
- A screen read costs a file, not a process in the task: the task's root writes the agent's screen when it changes into a directory the agent cannot write, and a listing reads it there 
- The build takes org.fuin.sokar:sokar-parent as its parent instead of org.fuin:pom, with every version it builds with and every published pom's licence unchanged 
- The task commands take a task by its container's name or by its own, and with no name, in a checkout a task was started from, that task; sokar task start there brings it back instead of starting another 
- The daemon's conversations hold what they read of the vault while vault.bin is unchanged and open, instead of decrypting it for every lookup; read once a move are the projects too 

### Fixed (30 changes)

- Opening the vault fetches at once every followed project that waited for it, and sends the messages that waited, rather than at the next round 
- A conversation's long-poll answer is one line in the daemon's journal 
- The published sokar-bom opts in to Central on its publishing plugins rather than by properties, so a repository that took it as its parent no longer inherits being published 
- The build helper asks the forge again within seconds of the vault changing after it refused a credential, instead of after ten minutes 
- An online task whose project names a forge says why its builds are not followed when the helper that follows them never started or has ended 
- An online task whose builds cannot be read before its first push - the vault shut, its entry missing, the forge refusing - says why in the task's builds and the helper's log, rather than nothing 
- A followed project whose file names no upstream gets a deploy key for its own repository, for the address this machine follows it from 
- Removing an online task, or starting a new one of its name, drops what it left at its gate; a new guarded or offline task is refused while work of an earlier one of its name waits there 
- A task's credential proxy holds about 44 MB instead of 186 MB: a collection after the vault opens, and a young generation of 16 MiB for every sokar helper 
- Stopping a task stops its helpers with every process they started, so a build reader no longer outlives its task 
- What a leg installs and fetches is the tree's own, ci/leg-install.sh and ci/leg-binaries, and the build checks every path in them against the modules and their image names 
- A task listing reads the attached agents' screens several at once, so List no longer takes about 100 ms for every running task 
- The daemon's waits on a transport rest five seconds when the transport came back at once with nothing, so a task with no account, or a shut vault, no longer keeps a core busy 
- What waited for the vault moves as soon as it opens, also right after the daemon started, rather than at the first timed pass a minute on 
- A message a task writes, and one a transport hands in, moves at once - filter, send, delivery - instead of waiting for every conversation's transport to be asked; the read marks follow each delivery 
- The daemon watches for messages on an account whose mail directory comes after its start, and a person's message written with talk say is noticed at once 
- An online task whose workspace fetched nothing from its upstream does not start: it says git's own words and the ssh key lent, and the container is kept for a look; a resumed task with work starts as before 
- An online task's ssh-agent signs with the key Sokar finds for its upstream, such as the project's deploy key, not only ssh.default; with none, the start names what it looked for and that a push from inside the task is refused 
- An online task whose upstream is an ssh address reaches that host on the port the address names, 22 for git@host, so it fetches and pushes - and no other host gets that port 
- sokar project list shows the security class of the project file in force, not that of a task started under an earlier one 
- A task brought back after a restart starts again; the screen's directory the restart emptied is made again before its container starts 
- Review over the daemon without a base compares with the mirror's default branch, as its contract says, instead of showing only the push's last commit 
- A review at the gate shows what the task changed since its work and the branch last met, no longer the upstream's newer work as removed when another task's start had moved the mirror on 
- sokar task start <name> outside a checkout brings back an existing task by either of its names without its project named 
- Stopping or removing a task whose record is gone answers that it is no task, rather than failing with a stack trace in failures.log 
- The build stub's documentation and example answers say that heads is keyed by the branch at the forge, sokar/<task> for an online task 
- sokar talk peers says a person is mentioned in the room, and reached in their direct chat only with via direct 
- How far a repository's mirror is behind its upstream is measured with the repository's credential from the vault, so a private upstream over ssh no longer answers Permission denied 
- A task's workspace gives up on a remote that does not answer within 60 seconds and says so, instead of holding the launch for five minutes without a word 
- In a task's workspace the branch tracks the task's own place, so git status says up to date right after a push and ahead by N before one 


