# F18 — Emergency Stop

**Status:** open

One action that cuts everything off, available at all times, for the moment when
somebody realises something is badly wrong and does not yet know what.

## Acceptance

- The action is visible on the main view at all times, not only in a menu.
- It is also reachable by name from the command finder and from the keyboard.
- Invoking it cuts every form of access at once — network reach, credential access and
  repository access — rather than requiring several separate actions.
- It states plainly what it did and what state the machine is now in.
- Recovering afterwards is possible from the interface, and the steps are named.
- It cannot be triggered accidentally by an adjacent action, and it is never the
  default choice in any dialog.

## Notes

Related: [Recovery And Panic](../base/B07-Recovery-And-Panic.md). Speed is the whole
feature: anything that takes a person more than one action is not an emergency stop.
