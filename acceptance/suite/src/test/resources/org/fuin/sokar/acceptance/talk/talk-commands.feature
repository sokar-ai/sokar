Feature: The talk commands

  Nine commands for the conversations between tasks. Most act on one task's mailbox, and the first
  thing each must do is refuse a task that has none, rather than act on nothing. The rest is asked of
  one running task: what is held, what a person writes into it, how a peer is held and released, and
  whether the record still checks out. The kit removes the task and its project afterwards.

  Scenario Outline: every talk command answers for itself
    When a script runs "sokar talk <command> --help"
    Then it exits zero

    Examples:
      | command |
      | peers   |
      | pass    |
      | held    |
      | read    |
      | release |
      | hold    |
      | verify  |
      | say     |
      | key     |

  Scenario: this machine's signing key is given as a peer would write it down, and only its public half
    When a script runs "sokar talk key --as sokar-acceptance"
    Then it exits zero
    And its output contains "sokar-acceptance"
    And its output contains "ssh-"
    And its output does not contain "PRIVATE KEY"

  Scenario Outline: a command about a task that has no mailbox says so rather than acting on nothing
    When a script runs "sokar talk <command> sokar-acceptance-no-such-task <more>"
    Then it exits non-zero
    And its output contains "sokar-acceptance-no-such-task has no mailbox"

    Examples:
      | command | more     |
      | held    |          |
      | verify  |          |
      | pass    |          |
      | hold    | somebody |
      | read    | a-message |
      | release | a-message |

  @slow
  Scenario: a running task's conversation, from what is held to whether its record checks out
    Given the suite runs as an unprivileged user
    And the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "talk" of class "guarded" with a file in it
    When a task is started in "talk" for the "stub" agent and left running
    And a script runs "sokar talk peers --project talk"
    Then it exits zero
    And its output contains "no peers"
    When a script runs "sokar talk held {task}" about the task
    Then it exits zero
    And its output contains "nothing held"
    # What a person writes goes in through standard input, as a secret would, and out through the filter.
    When a script runs "printf 'from the acceptance suite' | sokar talk say {task} somebody" about the task
    Then it exits zero
    And its output contains "the next pass puts it through the filter"
    When a script runs "sokar talk pass {task}" about the task
    Then it exits zero
    And its output contains "taken"
    # No --project: the task's own is found, and it is what refuses a mode that lets unread work leave.
    When a script runs "sokar talk hold {task} somebody" about the task
    Then it exits zero
    And its output contains "somebody   mode prompt, held"
    When a script runs "sokar talk hold {task} somebody --mode allow" about the task
    Then it exits non-zero
    And its output contains "unread_work_may_leave: true"
    When a script runs "sokar talk hold {task} somebody --release" about the task
    Then it exits zero
    And its output contains "not held"
    # Nothing held answers to it, and nothing a filter refused ever would.
    When a script runs "sokar talk read {task} no-such-message" about the task
    Then it exits non-zero
    And its output contains "is called 'no-such-message'"
    When a script runs "sokar talk release {task} no-such-message" about the task
    Then it exits non-zero
    And its output contains "is called 'no-such-message'"
    When a script runs "sokar talk verify {task}" about the task
    Then it exits zero
    And its output contains "intact"
