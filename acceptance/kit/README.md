# Acceptance Kit

Drives a real machine over ssh from a Cucumber scenario - as a person at a terminal, and as a
script that nobody is watching - and reports where GitHub shows it. Published to Maven Central as
`org.fuin.sokar:sokar-acceptance-kit`, so a repository that is not this one resolves it in test
scope with no checkout of Sokar.

Two consumers today: [this repository's own suite](../suite/README.md), and each agent repository's
acceptance suite. What they share is everything here; what differs is their feature files.

## Using it from another repository

One dependency, test scope. It brings Cucumber, the JUnit platform, the ssh client and AssertJ with
it.

```xml
<dependency>
    <groupId>org.fuin.sokar</groupId>
    <artifactId>sokar-acceptance-kit</artifactId>
    <version>${sokar.version}</version>
    <scope>test</scope>
</dependency>
```

A JUnit suite that names the kit's glue package, and your own beside it if you have one:

```java
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("org/example/acceptance")
@ConfigurationParameter(key = "cucumber.glue",
        value = "org.fuin.sokar.acceptance, org.example.acceptance")
public class AcceptanceIT { }
```

Failsafe, with these system properties. The suite is off unless `sokar.acceptance.host` is given,
so an ordinary build is unaffected:

| property | meaning |
|---|---|
| `sokar.acceptance.host` | The machine. Naming one is what turns the suite on. |
| `sokar.acceptance.user` | Who to connect as. |
| `sokar.acceptance.key` | Private key file. Or `SOKAR_ACCEPTANCE_KEY` in the environment, holding the key itself - which is how CI does it, so that no key is ever written to a filesystem. |
| `sokar.acceptance.as` | Whose Sokar to drive, when it is not the connecting user. |
| `sokar.acceptance.features` | Where feature files live, repository-relative, so an annotation lands on the right line. Default `src/test/resources/`. |
| `cucumber.plugin` | Include `org.fuin.sokar.acceptance.GitHubReport` for the two GitHub surfaces below. |

## Two vocabularies

Steps live in three classes - `TerminalSteps` for the machine and the terminal, `SokarSteps` for the
vault and task setup a scenario needs before the thing it is about, `PackageSteps` for what a
package put on the machine - and the difference between the two halves is the point:

- **`Given a terminal on the machine` / `When I run "..."`** - a person is watching. A real pty,
  100 columns by 40 rows, `xterm-256color`, echo off.
- **`When a script runs "..."`** - nobody is. No pty, so `isTerminal()` is false at the far end.

Half of what a suite exists for is "does not ask when nobody is there", and a suite that always
allocates a pty tests one side of every one of them.

`${NAME}` in a command is replaced from the environment of the machine *running the suite* before
it is typed - for a model or a version CI chooses per run. A missing variable fails the step by
name rather than typing the placeholder.

## A secret never appears, and the steps are shaped so it cannot

A credential enters the machine through a terminal with echo off (`When I type the value of
"VARIABLE"`) or through a script's standard input (`When a script runs "..." with the value of
"VARIABLE" on standard input`). Never on a command line: argv is readable by every process on the
machine.

Every assertion about a secret is by the **name of the variable** - `the terminal does not show
the value of "VARIABLE"`, `its output does not contain the value of "VARIABLE"` - and fails with a
boolean, not a diff. An assertion that failed by printing the expected value would put the
credential into the run log and into the annotation on the feature file.

`Given the environment variable "VARIABLE" is set` **skips** a scenario the runner cannot prove, so
a fork or a machine without the credential gets fewer scenarios rather than a red run that says
nothing about the product. Skipped shows as skipped in the summary; it is not counted as passed.

## One connection for the run, a terminal per scenario

`Machine.shared()` is the run's single ssh connection, opened the first time a scenario asks and
closed after the last. A terminal is a channel on it and is the scenario's own. A connection per
scenario was measured at about eighty connects in two minutes, and a rented machine's sshd refused
two of them; nothing was wrong with the machine or the product.

## Adding steps of your own

Take the same `World` in your constructor. It is the machine, the terminal and the last script's
output for one scenario, handed to every glue class by the picocontainer factory - which is why
that dependency is here: without it each glue class gets its own instance and two steps that look
like they share a terminal are typing into different shells.

```java
public class ClaudeSteps {
    private final World world;
    public ClaudeSteps(World world) { this.world = world; }

    @Then("the agent answers")
    public void theAgentAnswers() throws IOException {
        world.terminal().await("SOKARLIVE", Duration.ofSeconds(240));
    }
}
```

A suite made only of the kit's steps needs no glue class at all.

## The report contract

`GitHubReport` writes two things, and **each has its own switch** - treating them as one is how a
local run either prints workflow commands nobody can see or silently writes nothing:

- **Inline annotations**, when `GITHUB_ACTIONS` is set. One line per failed scenario:

  ```
  ::error file=<features>/<path>.feature,line=<scenario line>,title=<scenario>::<message>
  ```

  The file is repository-relative (`sokar.acceptance.features` plus the classpath path), the line is
  the **scenario's**, not a step's - the sentence somebody wrote is what they have to change - and
  the message is the step's own, capped at 900 characters with `... (see the run log)`, because
  GitHub truncates an uncapped one at a point nobody chose. Newlines, `%`, commas and colons are
  escaped as workflow commands require.

- **A job summary**, when `GITHUB_STEP_SUMMARY` names a file. Appended, so several modules in one
  job stack rather than overwrite:

  ```
  ## Acceptance

  Run against a real machine, over a real terminal.

  | | Feature | Scenario | | Time |
  |---|---|---|---|---:|
  | :white_check_mark: | `task/task-commands.feature` | every task command answers for itself | 10/10 | 3120ms |
  | :x: | `vault/vault-commands.feature` | putting a credential needs a name | | 402ms |

  **41 of 42 passed.**
  ```

  One row per **scenario**, with `n/m` where an outline has examples: an outline of ten is one
  sentence somebody wrote, and ten identical rows is a summary nobody reads to the end. Skipped
  is `:fast_forward:`.

**What must reach the reader, whatever the format.** The frontend found this by making a scenario
fail on purpose: its annotations and summary were correctly formatted and said only *"Test failed.
See exception logs above."*, because the expectation, the actual and the author's own reason were
in an event the report was not reading - and it had been so since the report was written, because
nobody reads a report of a green run. A report satisfies this contract only if a failure carries
all three: **what was expected, what was found, and why the author cared** (the `as(...)` on an
assertion here, the `reason` on an expectation there). Make one fail on purpose before trusting it.

This is the contract the frontend's report follows too, in Dart, from its own test JSON: the same
two surfaces, the same escaping, one row per unit - theirs is the requirement rather than the
scenario, because the id on its `Feature:` line is why the file is there.

To see either locally:

```
GITHUB_ACTIONS=true GITHUB_STEP_SUMMARY=/tmp/summary.md ./mvnw -pl acceptance/suite verify -D...
```

## What will not change without a note here

`Terminal`'s size, term type, echo mode and the two patiences are what a scenario is written
against; a published kit is a promise about that shape. The constants are public and documented on
the class, and a change to any of them is a change to every consumer's scenarios.
