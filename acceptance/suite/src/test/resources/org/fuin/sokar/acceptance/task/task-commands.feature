Feature: The task commands

  Ten commands. This covers that each exists and refuses well; what a task actually does to a
  machine is in task-lifecycle, which is slow and needs one.

  Scenario Outline: every task command answers for itself
    When a script runs "sokar task <command> --help"
    Then it exits zero

    Examples:
      | command   |
      | run       |
      | list      |
      | stop      |
      | resume    |
      | label     |
      | attach    |
      | status    |
      | logs      |
      | prepare   |
      | clearance |

  Scenario: list has a column for every fact it promises
    When a script runs "sokar task list"
    Then it exits zero

  Scenario Outline: a command that needs a task name says which it would have taken
    Given a terminal on the machine
    When I run "sokar task <command>"
    Then the terminal shows "missing required parameter"

    Examples:
      | command |
      | resume  |
      | attach  |
      | status  |
      | logs    |
      | stop    |
      | label   |

  Scenario Outline: a name that is not a task is refused rather than guessed at
    When a script runs "sokar task <command> somebody-elses-container"
    Then it exits non-zero
    And its output contains "not a task"

    Examples:
      | command |
      | status  |
      | logs    |
      | attach  |
      | stop    |

  Scenario: a task-shaped name that names nothing is not a task with no logs
    When a script runs "sokar task logs sokar-does-not-exist"
    Then it exits non-zero
    And its output contains "there is no task called sokar-does-not-exist"

  Scenario: a script is never offered a question
    When a script runs "sokar task attach sokar-nothing-here"
    Then it exits non-zero
    And its output does not contain "[Y/n]"
