@slow @restart
Feature: What a task is after the machine restarts

  A task's sockets live under $XDG_RUNTIME_DIR, which the system clears when the user's last
  session ends. So a restart is not an edge case: it is when Sokar has to say what it can no
  longer do, about work that is still there.

  Two defects were found by hand on the far side of a reboot on 2026-09-09, and one of them was
  in the code written to survive one. Nothing else in this suite can see any of it.

  Scenario: a task that outlived the machine still says what it belongs to
    Given a project called "restarted" with a file in it
    And a terminal on the machine
    When I run "cd ~/restarted && sokar task start --attach shell"
    And I wait for the shell inside the container
    And I run "exit"
    And the machine restarts
    Then a script running "sokar task list" mentions "restarted"
    And a script running "sokar task list" mentions "offline"

  Scenario: starting it again says what happened rather than what podman said
    Given a terminal on the machine
    When I run "sokar task list"
    And the machine restarts
    Then a script running "cd ~/restarted && sokar task start --detach" mentions "before this machine restarted"

  Scenario: its logs say the machine restarted rather than that nothing was written
    Then a script running "sokar task logs $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "machine has restarted"

  Scenario: what it held cannot be read, and does not read as nothing
    Then a script running "sokar task status $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "cannot be read"
