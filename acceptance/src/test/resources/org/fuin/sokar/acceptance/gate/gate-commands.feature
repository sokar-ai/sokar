Feature: The gate commands

  Ten commands. Nothing an agent pushes reaches a real upstream without passing through here, so
  the refusals are the product rather than an edge of it.

  Scenario Outline: every gate command answers for itself
    When a script runs "sokar gate <command> --help"
    Then it exits zero

    Examples:
      | command  |
      | serve    |
      | pending  |
      | review   |
      | approve  |
      | reject   |
      | backup   |
      | restore  |
      | checkout |
      | protect  |
      | check    |

  Scenario Outline: acting on a push needs to say which one
    Given a terminal on the machine
    When I run "sokar gate <command>"
    Then the terminal shows "missing required parameter"

    Examples:
      | command |
      | review  |
      | approve |
      | reject  |
