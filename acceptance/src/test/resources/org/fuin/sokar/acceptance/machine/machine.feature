Feature: The commands a machine answers about itself

  Every group's entry point, and the five commands that take no group. A command that vanished,
  was renamed, or stopped parsing its own options shows up here before anybody types it.

  Scenario Outline: every command group answers for itself
    When a script runs "sokar <group> --help"
    Then it exits zero
    And its output contains "<mentions>"

    Examples:
      | group  | mentions  |
      | task   | run       |
      | shield | egress    |
      | vault  | login     |
      | gate   | pending   |
      | daemon | connect   |

  Scenario Outline: every top-level command answers for itself
    When a script runs "sokar <command> --help"
    Then it exits zero

    Examples:
      | command  |
      | agents   |
      | projects |
      | setup    |
      | doctor   |
      | panic    |

  Scenario: doctor reports whether this machine can run a task
    When a script runs "sokar doctor"
    Then it exits zero
    And its output contains "hooks registered"
    And its output contains "podman"

  Scenario: panic says what it would stop without stopping anything
    When a script runs "sokar panic --dry-run"
    Then it exits zero

  Scenario: agents lists what is installed
    When a script runs "sokar agents"
    Then it exits zero

  Scenario: an unknown command is answered with what does exist
    When a script runs "sokar nonsense"
    Then it exits non-zero
    And its output contains "has no command called"

  Scenario: a command typed in the wrong place is found where it lives
    When a script runs "sokar unlock"
    Then it exits non-zero
    And its output contains "vault unlock"
