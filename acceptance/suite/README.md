# Acceptance Suite

This repository's own scenarios: what a person does at a terminal with Sokar, run against a real
machine through the [kit](../kit/README.md).

```
./mvnw -pl acceptance/suite verify \
    -Dsokar.acceptance.host=user@host \
    -Dsokar.acceptance.key=$HOME/.ssh/your_key
```

Without `sokar.acceptance.host` the module is skipped, so an ordinary build is unaffected. The
report lands in `acceptance/suite/target/acceptance.html`, and on GitHub as inline annotations and
a job summary - see the kit's README for both.

## What it covers

Every command a person types, and what a task is promised end to end: its image, its hardening, the
credential it never sees, the names and ports it may reach, the gate its work leaves by, and what a
refused start leaves behind. A scenario can drive a terminal - the offer to start a stopped task,
color on work that exists nowhere else, a passphrase read without being echoed all exist only
there - or assert the absence of one, which is half of every such behavior.

## Writing a scenario

Every step here is the kit's; this module has no glue of its own, which is the property that made
the kit possible. The two vocabularies - a person watching, a script nobody watches - are described
there.

## What it costs

Each scenario talks to a real machine over ssh. A scenario that waits for something that never
comes takes the full patience of `Terminal` (30s) before it fails, so a wrong expectation is slow
rather than instant. Scenarios that need a task built are minutes, not seconds - which is the
reason to keep asking whether a case belongs here or in a unit test.
