Feature: The vault commands

  Eleven commands, and the ones that matter most here are the refusals: this is the group where a
  mistake ends with a credential somewhere it should not be.

  Scenario Outline: every vault command answers for itself
    When a script runs "sokar vault <command> --help"
    Then it exits zero

    Examples:
      | command    |
      | put        |
      | import     |
      | list       |
      | remove     |
      | serve      |
      | relay      |
      | agent      |
      | unlock     |
      | lock       |
      | passphrase |
      | login      |

  Scenario: a store that cannot be read says why rather than answering an empty list
    # Three states and only one of them is "there are no credentials": a store that was never
    # created, one that is locked, and one that is open and empty. The first two must say so, or
    # absence reads as "nothing is stored here" - which is this project's most repeated mistake.
    When a script runs "sokar vault list"
    Then its output mentions one of "no vault, passphrase, credential"

  Scenario: putting a credential needs a name
    Given a terminal on the machine
    When I run "sokar vault put"
    Then the terminal shows "missing required parameter"

  Scenario: an agent that does not say how to log in is answered, not guessed at
    When a script runs "sokar vault login no-such-agent"
    Then it exits non-zero

  Scenario: no command prints a credential in its own help
    When a script runs "sokar vault put --help"
    Then its output does not contain "sk-ant"
