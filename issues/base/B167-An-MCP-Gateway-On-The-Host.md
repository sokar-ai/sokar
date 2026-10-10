# B167 — An MCP Gateway On The Host

**Status:** open.

**What must be true.** The MCP servers a project names are reached through a gateway on the host, which sees and
journals their tool lists and their calls, and attaches their credentials as the broker does, so the task holds none.

## Why

Sokar does not read MCP traffic today, so a changed tool set goes unnoticed, and an MCP server's credential has to be
in the task - the largest gap `doc/faq.md` and `doc/corporate-security.md` name plainly. The broker's principle carries
over: the task talks to the host, the host adds the secret and sees what passes. Comparable tools run such a gateway.
It is the ground `sokar-project` PJ15 (a tool does not change behind the person's back) builds on.

## The shape

- A project names its MCP servers; each is reached only through the gateway, and the firewall lets the task reach the
  gateway, not the server.
- The gateway journals each tool list and each call (tool name, arguments' size, outcome) per task (B154).
- A credential for an MCP server lives in the vault and is attached on the way out.
- Size: large.

## Acceptance

- Seen to fail first, then green: a task reaches a stub MCP server only through the gateway; its tool list and a call
  are in the journal; the server's token is not in the task.
- `doc/faq.md` and `doc/corporate-security.md` move the gap from "not covered" to what is covered.

## To be checked

- Which transports first (HTTP; stdio servers started on the host?).
- What the gateway does with a tool list that changed since the last start, together with PJ15.
