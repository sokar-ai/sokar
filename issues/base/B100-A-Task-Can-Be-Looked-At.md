# B100 — A Task Can Be Looked At

**Status:** soon.

**What must be true.** A person sees and drives what a task runs on its virtual screen, through a
loopback-only view; nothing of the box runs on their computer.

## Why

Written beside B95 (a container to try waiting work in). The interface's half is in `sokar-frontend`.

The operator tests the interface on his own computer with the guided walk: the agent answers in a panel and changes
the code live, with hot reload and Dart's MCP server. An agent in a Sokar task cannot do that - it reaches neither
the host's files nor its tools - and the person cannot reach what it built to try it.

## The shape agreed

- **Everything stays in the box.** The project's image carries what the work needs - for the interface the Flutter
  SDK and the Dart MCP server, the hosts they fetch from declared. The agent starts the app itself, on a virtual
  screen inside the task (Xvfb), and drives it with its own tools there: hot reload, MCP, the guided walk's panel and
  walk file, all local to the task.
- **Sokar offers that virtual screen as a view**: a VNC stream published on the host's loopback only, reached from a
  person's computer through ssh as the daemon is. Pixels and input only; no code from the box runs on the person's
  computer.
- **Not a web build in the person's browser**: that would run the agent's JavaScript with the person's network - a
  way out past the egress policy for unreviewed work.
- **The same view on a review container** (B95, `gate try`): the person tries the pending push itself - what would
  be approved, not the agent's working tree - then approves or rejects.
- **The interface** opens it from "View" on a task and on a review container, first in an external VNC viewer;
  embedding it is later.

## Acceptance

- An agent in a task starts the app on a virtual screen inside the task and drives it with its own
  tools there - hot reload, MCP, the guided walk's panel and walk file - with nothing reached on the
  host. Seen to fail: the walk cannot run in a task whose image carries the Flutter SDK and the Dart
  MCP server.
- The virtual screen is offered as a VNC stream published on the host's loopback only, and a person
  reaches it from their own computer through ssh as the daemon is reached. Seen to fail: a check of
  the published port finds it bound to anything but loopback.
- Only pixels and input cross; no code from the box runs on the person's computer, and no web build
  is served to their browser. Seen to fail: anything other than the VNC stream is offered for the
  view.
- The same view is offered on a review container (B95, `gate try`), showing the pending push rather
  than the agent's working tree. Seen to fail: the view on a review container shows the working
  tree's state rather than the pushed commit.
- The view is authenticated beyond being on loopback. Seen to fail: a viewer without the credential
  connects.

## To be checked

- How the view is switched on: per project in `project.yml`, or per task at its start.
- How it is authenticated beyond loopback: a one-time password handed to the viewer.
- Which VNC server goes into the image, and what of it the firewall and the hooks must allow.
