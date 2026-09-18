# B61 — A Declared Refusal That Nothing Enforces

**Status:** open, found on 2026-09-18 while answering the agent repositories' question whether a
refused domain would actually be blocked.

An agent definition may declare `refused_domains` - *"domains the agent is known to ask for and is
deliberately not given - telemetry and crash reporting"*. **Nothing enforces them.** The field is
read by `AgentDefinitionReader`, reported by `AgentsCommand`, `EgressReport` and the daemon's
`Agents` method, and it reaches the firewall and the resolver nowhere at all.

So an operator reading `sokar agents` is told a name is refused, and the task can reach it.

## Why it is worse than an absent feature

**The declaration is what makes it a promise.** The field's own javadoc says it exists *"so that a
test can tell a policy choice from an oversight, and so an operator can see what is refused"* - and
both halves are true only if something refuses. A domain that is merely absent from
`allowed_domains` is at least honestly unmentioned; a domain listed as refused says a decision was
taken and acted on.

**And a parent's allowance already carries its children.** The resolver matches by domain, so
`claude.ai` in `allowed_domains` admits `downloads.claude.ai`: the name resolves, its addresses go
into the firewall's allow set as they are answered, and the agent reaches it. That is not a defect -
it is what `server=/domain/` and `nftset=/domain/` mean - but it is exactly the case
`refused_domains` reads as covering.

**Measured, 2026-09-18:** an agent updated itself inside a task from a host under an allowed parent.
The agent repository stopped it in the agent's own configuration, which is the right fix for that
agent and no fix at all for the mechanism.

## What must be true

- **A name declared as refused does not resolve inside the task**, even when an allowed domain is its
  parent, and the refusal is rendered from the same declaration the report prints.
- **A refused name never puts an address into the allow set**, because it never resolves.
- **The report and the enforcement cannot drift**: one declaration, read once, used for both. A test
  fails if a name appears in `sokar agents` as refused and resolves in a task.
- **The product says what the refusal is worth**, because it is a name-level refusal and not an
  address-level one: where a refused host shares an address with an allowed one - the ordinary case
  behind a content delivery network - the address stays reachable, and nothing here pretends
  otherwise.
- A project can refuse a name as an agent can, and a refusal wins over an allowance in either place.

## How it would be done

The resolver already has the mechanism, and the configuration is already generated per task:
`dnsmasq` matches the longest domain, so a more specific `address=/<refused>/` answering `NXDOMAIN`
overrides the `server=/<parent>/` line that allows the parent, and the refused name never reaches the
`nftset=` line that fills the allow set. It is a rendering change in `DnsPolicy` plus the same
grammar check the allowed domains already get.

## To be checked

- **Whether a refusal should also be a clearance prompt** rather than a silent `NXDOMAIN`. An agent
  that asks for its telemetry host and is told the name does not exist behaves differently from one
  told it may not have it, and the audit trail differs too.
- **Whether project-level refusals belong in `project.yml` now or later.** An agent declares what it
  is known to ask for; an operator may want to refuse something the agent never declared.
