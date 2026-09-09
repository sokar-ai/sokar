# Acceptance

What a person does at a terminal, run against a real machine.

```
./mvnw -pl acceptance verify \
    -Dsokar.acceptance.host=192.168.122.174 \
    -Dsokar.acceptance.key=$HOME/.ssh/your_key
```

Without `sokar.acceptance.host` the module is skipped, so an ordinary build is unaffected. The
report lands in `acceptance/target/acceptance.html`.

| property | meaning |
|---|---|
| `sokar.acceptance.host` | The machine. Naming one is what turns the suite on. |
| `sokar.acceptance.user` | Who to connect as. Default `claude`. |
| `sokar.acceptance.key` | Private key file. |
| `sokar.acceptance.as` | Whose Sokar to drive, when it is not the connecting user. |

## Why this exists beside `buildtools/e2e-tier1.sh`

That suite runs commands over ssh **without a pty**, so `isTerminal()` is false in everything it
does. Every behaviour gated on that is invisible to it — the offer to start a stopped task, colour
on work that exists nowhere else, a passphrase read without being echoed — and each of those was
checked by hand until it was written down here.

`Terminal` allocates a real pty with a fixed size and `ECHO` off. The size is fixed because a
program may wrap or paginate differently at 80 columns than at 200 and "it looked right on my
terminal" is not a result. `ECHO` is off because the far end echoes what it chooses to: with local
echo as well, an assertion cannot tell which of the two put the text there — which is exactly the
question when checking that a credential was not echoed.

`Machine.run` is the other half, deliberately: **half of these behaviours are "does not ask when
nobody is there"**, and a suite that always allocates a pty tests one side of every one of them.

## Writing a scenario

Steps live in `TerminalSteps`. Two vocabularies, and the difference is the point:

- **`Given a terminal on the machine` / `When I run "..."`** — a person is watching.
- **`When a script runs "..."`** — nobody is.

## What it costs

Each scenario talks to a real machine over ssh. A scenario that waits for something that never
comes takes the full patience of `Terminal` (30s) before it fails, so a wrong expectation is slow
rather than instant. Scenarios that need a task built are minutes, not seconds — which is the
reason to keep asking whether a case belongs here or in a unit test.
