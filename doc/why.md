# Why build this if Terok is so cool?

- **It's Java** — Sorry, I'm a Java developer and Python is not my world. Fixes and enhancements are simply easier for
  me here.
- **No Python runtime** — One signed native binary per host role. The package is files; nothing has to be installed on
  the host before Sokar will run.
- **APT/DNF packaging** — Installed with `apt` or `dnf`, under whatever policy your machines already have.
- **Easy to add an agent** — An agent is its own binary and its own package, discovered at runtime. Nothing in Sokar
  names it or depends on it, and the build fails if anything starts to. Adding one — including a proprietary agent that
  will never be upstreamed — means adding a directory, not patching Sokar.
- **Pinned, verifiable installs** — An agent CLI is fetched from a pinned URL and checked against a SHA-256 before it
  is allowed to run. No `curl | bash` in the middle of a tool whose job is containment.
- **Fast startup** — 1–2 ms cold start, against roughly 10 ms for the equivalent Python. Small numbers, but the OCI
  hooks fire twice per container start, so it sits on a path you notice.
