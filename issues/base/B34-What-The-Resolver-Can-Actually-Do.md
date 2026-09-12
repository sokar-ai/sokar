# B34 — What The Resolver Can Actually Do

**Status:** open, and a design idea rather than a defect. Taken from the reference implementation's
0.8.0a11 on 2026-09-09.

## Where this project is

`sokar doctor` asks one question about the resolver and answers it with a boolean:

```
dnsmasq nftset      yes
```

Without `nftset` support a declared domain resolves and is then dropped - names work and nothing
reaches anything - so the check exists and `task run` refuses the machine. That is correct and it
is all there is: a host either has the one dnsmasq this project can use, or it cannot run tasks.

## What they did

Four of their repositories moved together to **DNS capability tiers**: a tier says *what it
provides*, and names *which dnsmasq binary to run*. The check stops being "is this the right
dnsmasq" and becomes "what can this machine's resolver do, and what follows from that".

## Why it might be worth having

**A refusal is the right answer only while there is nothing else to offer.** Today a host whose
dnsmasq lacks nftset cannot run a task at all. A tier model makes room for two things this project
has no answer to:

- A machine that can enforce egress by name in some weaker way, offered as a lesser tier with the
  difference stated - rather than refused outright.
- A machine that has a usable dnsmasq somewhere other than where this project looks, which today
  reads as "not supported".

**And it makes the refusal itself better.** "This dnsmasq cannot do nftset, so a task would resolve
names and reach nothing" is a sentence a tier model produces for free; today the sentence is in a
doctor line and the code has a boolean.

## What must be true

**A machine says what its resolver can do, and what follows for a task; a machine that cannot
enforce egress by name is refused with the reason rather than with a flag.**

## Acceptance

- The resolver's capability is a value with a name, not a boolean, and `doctor` reports it.
- Each capability states what a task gets and what it does not.
- A machine that cannot enforce a declared domain is still refused, and the refusal says which
  capability was missing and what it would have provided.
- Where more than one resolver binary could serve, the one chosen is reported rather than assumed.

## Notes

**This is a generalisation, not a fix.** Nothing is broken; the boolean is correct for every
machine currently supported. The value is in the machines that are refused today, and nobody has
counted them.

**Borrowed deliberately, not copied.** The reference implementation moved four repositories to do
this, which is evidence it was worth doing there. This project has one resolver, one hook and one
check, so the same idea is much smaller here - and the smaller version may be a named enum and two
sentences rather than a subsystem.

## To be checked

- **Are there real machines this would rescue?** Debian and Ubuntu ship dnsmasq with nftset;
  Fedora does. If every supported host passes the boolean, this buys wording and nothing else -
  which is a reason to write the wording and stop.
- **Whether a lesser tier can exist at all here.** Sokar's whole egress story is nftables sets
  filled by the resolver. A tier that cannot do that may have nothing to offer a task, in which
  case the honest model has two values and one of them is "refused".
