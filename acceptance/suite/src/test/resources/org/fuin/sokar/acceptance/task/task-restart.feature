@slow @restart
Feature: What a task is after the machine restarts
  # --agent is named because this machine may have more than one installed. CI builds and installs
  # only the stub, so the scenarios passed there without it and could never pass on a machine that
  # also carries a real agent - which is every machine a person develops on. Which agent is in the
  # image is irrelevant to everything below; being silent about it was a hidden dependency on the
  # CI environment's roster.

  A task's sockets live under $XDG_RUNTIME_DIR, which the system clears when the user's last
  session ends. So a restart is not an edge case: it is when Sokar has to say what it can no
  longer do, about work that is still there.

  Two defects were found by hand on the far side of a reboot on 2026-09-09, and one of them was
  in the code written to survive one. Nothing else in this suite can see any of it.

  # The four scenarios below share one container ON PURPOSE - the second asks what starting it
  # again says, and the last two ask what its logs and its status say - so only the first creates
  # it and only the last removes it. What that costs is idempotence, and it cost a run: a previous
  # run left the container behind in exactly the state a reboot puts it in, and the next run's
  # first scenario waited 480s for a shell that sokar was quite correctly refusing to give it.
  # Removing it first is what makes this file runnable twice on the same machine, which B27 names
  # as the property the interesting failures hide behind.

  Scenario: a task that outlived the machine still says what it belongs to
    Given a script runs "sokar task remove sokar-restarted-shell --force"
    And a project called "restarted" with a file in it
    And a terminal on the machine
    When I run "cd ~/restarted && sokar task start --agent stub --attach shell"
    And I wait for the shell inside the container
    And I run "exit"
    And the machine restarts
    Then a script running "sokar task list" mentions "restarted"
    And a script running "sokar task list" mentions "offline"

  Scenario: starting it again says what happened rather than what podman said
    Given a terminal on the machine
    When I run "sokar task list"
    And the machine restarts
    Then a script running "cd ~/restarted && sokar task start --agent stub --detach" mentions "before this machine restarted"

  Scenario: its logs say the machine restarted rather than that nothing was written
    Then a script running "sokar task logs $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "machine has restarted"

  Scenario: what it held cannot be read, and does not read as nothing
    Then a script running "sokar task status $(sokar task list | grep -o 'sokar-restarted[^ ]*' | head -1)" mentions "cannot be read"
    # The last of the four, so this is where the shared container goes. A suite that leaves one
    # behind is a suite whose next run starts from a state nobody chose.
    And a script runs "sokar task remove sokar-restarted-shell --force"
