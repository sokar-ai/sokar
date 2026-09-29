@slow @restart
Feature: What a task is after the machine restarts
  # --agent is named because this machine may have more than one installed. CI builds and installs
  # only the stub, so the scenarios passed there without it and could never pass on a machine that
  # also carries a real agent - which is every machine a person develops on. Which agent is in the
  # image is irrelevant to everything below; being silent about it was a hidden dependency on the
  # CI environment's roster.

  A task's sockets live under $XDG_RUNTIME_DIR, which the system clears when the user's last
  session ends. So a restart is not an edge case: it is when Sokar has to say what happened, about
  work that is still there - and, since what is knowledge about a task is saved where a restart
  does not reach, bring the task back whole when it is started.

  Two defects were found by hand on the far side of a reboot on 2026-09-09, and one of them was
  in the code written to survive one. Nothing else in this suite can see any of it.

  # The four scenarios below share one container ON PURPOSE - the middle two ask what its logs and
  # its status say while it is down, and the last starts it again - so only the first creates it
  # and only the last removes it. What that costs is idempotence, and it cost a run: a previous
  # run left the container behind in exactly the state a reboot puts it in, and the next run's
  # first scenario waited 480s for a shell that sokar was quite correctly refusing to give it.
  # Removing it first is what makes this file runnable twice on the same machine - the property
  # the interesting failures hide behind.

  Scenario: a task that outlived the machine still says what it belongs to, and why it is down
    Given a script runs "sokar task remove sokar-restarted-shell --force"
    And a project called "restarted" with a file in it
    And a terminal on the machine
    When I run "sokar task start --project restarted --repository restarted --agent stub --attach shell"
    And I wait for the shell inside the container
    And I run "exit"
    And the machine restarts
    Then a script running "sokar task list" mentions "restarted"
    And a script running "sokar task list" mentions "offline"
    And a script running "sokar task list" mentions "down because the machine restarted"

  Scenario: its logs say the machine restarted rather than that nothing was written
    Then a script running "sokar task logs $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "machine has restarted"

  Scenario: what it held cannot be read, and does not read as nothing
    # Nothing stopped it - the restart did - so no note of what it held was written on the way down.
    Then a script running "sokar task status $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "cannot be read"

  Scenario: starting it again says plainly that it cannot come back whole, and why
    # This machine's account keeps no vault of its own - every scenario brings its own and takes it
    # away - so the gate token this task's container holds was never kept, and starting it again
    # has to refuse rather than bring back a task whose pushes would fail. A task whose tokens were
    # kept comes back whole; task-reboot-reproduced.feature proves that on every leg.
    Then a script running "sokar task start --project restarted --repository restarted --agent stub --detach" mentions "cannot come back"
    # The last of the four, so this is where the shared container goes. A suite that leaves one
    # behind is a suite whose next run starts from a state nobody chose.
    And a script runs "sokar task remove sokar-restarted-shell --force"
