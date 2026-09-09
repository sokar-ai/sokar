Feature: The shield commands

  What a task may reach, and what happened when it tried.

  Scenario Outline: every shield command answers for itself
    When a script runs "sokar shield <command> --help"
    Then it exits zero

    Examples:
      | command   |
      | read      |
      | watch     |
      | dns       |
      | subscribe |
      | sets      |
      | egress    |

  Scenario: the curated sets are readable without a project
    When a script runs "sokar shield sets"
    Then it exits zero

  Scenario: an unknown set name stops rather than being ignored
    When a script runs "sokar shield egress --add-set no-such-set --dry-run"
    Then it exits non-zero
