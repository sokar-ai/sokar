# 0008 — Start A Task

**Status:** open

The wizard covers the choices that exist and refuses the combinations that do not
work, which is most of the value: an agent given the wrong credential kind fails in a
way that looks exactly like a wrong key.

## Acceptance

- Project, agent, security class and credential type are all selectable.
- Interactive and unattended modes are both offered.
- A missing credential is reported before the container is built, not after.
- The command equivalent is shown, so the interface teaches the CLI.

## Notes

Starting is all this covers: listing, stopping and resuming already exist, in the CLI and over the daemon's socket.
