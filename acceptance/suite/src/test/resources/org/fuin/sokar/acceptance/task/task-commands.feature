Feature: The task commands

  Ten commands. This covers that each exists and refuses well; what a task actually does to a
  machine is in task-lifecycle, which is slow and needs one.

  Scenario Outline: every task command answers for itself
    When a script runs "sokar task <command> --help"
    Then it exits zero

    Examples:
      | command   |
      | start     |
      | list      |
      | stop      |
      | remove    |
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
      | remove  |
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

  Scenario: preparing an image says what it would build, and a dry run builds nothing
    Given a project called "prepared" of class "guarded" with a file in it
    When a script runs "sokar task prepare --project prepared --agent stub --dry-run"
    Then it exits zero
    And its output has a line matching "agent +stub.*"
    And its output has a line matching "rebuild +reuse whatever is still valid"
    And its output contains "nothing was built"

  Scenario: preparing a project this machine does not have names what it has
    When a script runs "sokar task prepare --project no-such-project --dry-run"
    Then it exits non-zero
    And its output contains "no project 'no-such-project' here"

  Scenario Outline: changing a task's clearance refuses what is not a task or not a mode
    When a script runs "sokar task clearance <task> <mode> --dry-run"
    Then it exits non-zero
    And its output contains "<says>"

    Examples:
      | task                | mode  | says                                 |
      | sokar-nope-t-1      | bogus | expected one of allow, deny, off, prompt |
      | not-a-task          | allow | not-a-task is not a Sokar task       |
      | sokar-nope-t-1      | allow | nothing here knows sokar-nope-t-1    |
