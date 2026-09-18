# The project file

Everything `project.yml` can say, in one file, with what each key is for.

**You do not have to write this.** `sokar task start` in a directory without one offers to write a
working file for you, and the wizard asks only what it cannot work out. This page is for when you
want to know what else is possible, or why the wizard chose what it chose.

**Only `project.name`, `project.security_class` and `image.base_image` are required.** Everything
else has a default, and the defaults are the ones most projects want.

**A project is not a repository.** It is a named unit of work over one or more of them: its own,
which holds this file, and any it names under `repositories`. `sokar project list` says which a
project has; `sokar task start --repository <name>` says which a task is for.

```yaml
project:

  # Required. Lower-case letters, digits and hyphens, at most 63 characters.
  #
  # It ends up in image tags, container names and nftables set names, all of which are stricter
  # than YAML - so it is refused here rather than three layers down where the error would name
  # none of that. The container for a task is 'sokar-<name>-<task>'.
  name: "myproject"

  # Optional. For people, not for Sokar. Shown where the project is listed.
  description: "The thing that talks to the payment provider"

  # Required: offline, guarded or online. What the agent is trusted with.
  #
  #   offline  no network at all. Declaring egress is refused rather than ignored.
  #   guarded  the agent reaches what egress names, pushes to a gate, and holds no
  #            credential for the real upstream.
  #   online   the agent's remote IS the upstream. Needs 'upstream' below.
  security_class: "guarded"

  # Where approved work goes, for the project's OWN repository. Required for online, optional
  # otherwise.
  #
  # In guarded the agent never sees this: it pushes to Sokar's gate, and the gate forwards what
  # somebody approved. That is the whole point of the class - the credential stays outside the
  # container.
  upstream: "git@github.com:you/myproject.git"

# Optional. The other repositories this project's work happens in.
#
# A project is a unit of work over ONE OR MORE repositories, not a second name for one. Its own
# repository - the one holding this file, the planning and the issues - is always there and is
# named after the project; these are the ones its agents change. A project that names none is a
# project still being planned, which is a legitimate state rather than a broken one.
#
# A task works on exactly one of them, chosen at 'sokar task start --repository <name>'. There is
# no default, not even here where there are three: a project that grows a repository would
# otherwise silently change what an existing command does.
#
# Each keeps its own mirror, its own gate and its own review branch, so nothing about review
# changes. Coordination between repositories happens between tasks, by message - the tasks of one
# project can address each other by task name without anybody writing a peer list.
repositories:

  backend:
    # Optional, exactly as above. A repository with no upstream is one whose work stays on this
    # machine - fine, and not the same as forgetting it.
    upstream: "git@github.com:you/backend.git"
    # Optional. For people.
    description: "The thing that talks to the payment provider"

  frontend:
    upstream: "git@github.com:you/frontend.git"

    # Optional. What THIS repository's tooling needs, ADDED to the project's egress below.
    #
    # Added, never replacing: a repository's declaration must not take away what the project
    # granted. So a frontend that drives its build through Maven keeps 'maven' without repeating
    # it, and adds only what is particular to it. Naming a set in both places names it once.
    #
    # To make a repository reach LESS, move the set out of the project's block into the
    # repositories that do need it. Then that is written down too.
    egress:
      sets: [nodejs]
      domains: ["registry.internal.example"]

    # Optional. What a task on THIS repository may consume, REPLACING the project's key by key.
    #
    # Not added - a limit is one number, and two memory limits for one container is not something
    # podman can be asked for. A key nobody writes here means THE PROJECT'S, not the default: a
    # repository naming only 'pids' keeps the project's memory, including one the project
    # deliberately raised. 'none' opts out of a limit and is not the same as saying nothing.
    limits:
      memory: "16g"

image:

  # Required. What the task image is built from. Any image a package manager can be found in:
  # apt, apk and dnf are handled, and a base with none of them has to ship curl, git, ssh and
  # tmux itself.
  base_image: "ubuntu:24.04"

  # Optional. Extra Containerfile lines, run as root while the image is built.
  #
  # For what the work needs and the base image lacks. Keep it small: everything here is rebuilt
  # whenever it changes, and a long snippet is a slow first start for everybody.
  snippet: |
    RUN apt-get update && apt-get install -y --no-install-recommends jq \
        && rm -rf /var/lib/apt/lists/*

  # Optional, and mutually exclusive with 'snippet'. The same thing in a file of its own, for a
  # snippet long enough that its own syntax highlighting is worth having.
  # snippet_file: "Containerfile.fragment"

  # Optional. Where apt fetches from while the image is built.
  #
  # Default: http://azure.archive.ubuntu.com/ubuntu/ - one source, not a list. Measured on a
  # rented machine while Ubuntu's archive was disrupted: that mirror served a 19 MB index in
  # 0.08s and archive.ubuntu.com managed 2.2 MB in 60s. apt spreads its requests over every URI
  # it is given rather than holding the second in reserve, so a slow mirror beside a fast one
  # costs every build - with both, one 'apt-get update' took 4m03s instead of 1s.
  #
  # http rather than https: Ubuntu's mirrors serve no TLS, and apt takes its integrity from the
  # signed Release file rather than from the transport. An https URL here never answers.
  #
  # Name your own when your network prefers one, or when you have a mirror inside it. Only the
  # URIs line of the base image's sources is replaced; suites, components and the signing key are
  # left exactly as they were.
  package_sources:
    - "http://azure.archive.ubuntu.com/ubuntu/"

# Optional. What the task may reach. Nothing that is not named here resolves at all - a blocked
# name is NXDOMAIN rather than a refused connection, because a name that does not resolve is the
# one failure every tool already handles.
egress:

  # Curated sets, by name. 'sokar shield sets' lists what this machine has.
  sets: [os-packages-debian, git-hosting, maven]

  # Anything else, by name. Ports 80 and 443 only, at the addresses the name resolves to.
  domains: ["nexus.corp.example"]

# Optional. What one task may consume. These are the defaults.
limits:

  # "none" opts out on purpose - which is a decision somebody made, not an oversight.
  memory: "8g"

  # Process count. The number that stops a fork bomb taking the machine with it.
  pids: 2048

  # Unset means no CPU limit. A number, as podman takes it.
  cpus: "2.0"
```

## What is deliberately not in here

**Nothing about agents or providers.** Which agent runs, which provider serves it and which
credential it uses are given per run, not per project - `sokar task start --agent claude
--provider openrouter`. A project file that named an agent would be a project file that stops
working when somebody installs a different one.

**Nothing secret.** The file sits in the operator's directory, usually beside a git checkout, and
is meant to be committed. Credentials live in the vault and reach a task through the broker; none
of them is ever written here or into a container.

**Nothing about where things are put.** The workspace, the state directory and the gate mirror are
Sokar's, and where they live is Sokar's answer - `sokar doctor` says where, on the machine you
are asking.
