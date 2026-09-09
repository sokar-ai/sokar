Feature: What a person sees at a terminal

  The suite that came before this one runs commands over ssh with no pty, so isTerminal() is
  false in everything it does. Every scenario here is one that suite cannot see, and every one
  of them was checked by hand at least once before it was written down.

  Scenario: a command that needs a name says which names it would have taken
    Given a terminal on the machine
    When I run "sokar task resume"
    Then the terminal shows "missing required parameter"

  Scenario: a name that names nothing is not a task with no logs
    Given a terminal on the machine
    When I run "sokar task logs sokar-does-not-exist"
    Then the terminal shows "there is no task called sokar-does-not-exist"

  Scenario: doctor reports what this machine can do
    Given a terminal on the machine
    When I run "sokar doctor"
    Then the terminal shows "hooks registered"

  Scenario: the commands the documentation names are the ones that exist
    Given a terminal on the machine
    When I run "sokar task --help"
    Then the terminal shows "status"
    And the terminal shows "logs"
    And the terminal shows "attach"

  Scenario: a script is never asked a question
    When a script runs "sokar task attach sokar-nothing-here"
    Then it exits non-zero
    And its output does not contain "[Y/n]"

  Scenario: nothing is painted when the output is not a terminal
    When a script runs "sokar task list"
    Then its output contains no escape sequences
