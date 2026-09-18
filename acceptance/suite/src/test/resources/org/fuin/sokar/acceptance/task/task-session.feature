@slow
Feature: Leaving a task and coming back to it
  # --agent is named because this machine may have more than one installed. CI builds and installs
  # only the stub, so the scenarios passed there without it and could never pass on a machine that
  # also carries a real agent - which is every machine a person develops on. Which agent is in the
  # image is irrelevant to everything below; being silent about it was a hidden dependency on the
  # CI environment's roster.

  A task keeps a tmux session, so returning to one and starting one for the first time are the
  same operation - which is what makes coming back reliable rather than a second path that is
  exercised less often.

  What that is worth is only visible across a disconnect: that the work carries on with nobody
  watching, and that what it printed while nobody was watching is still there afterwards.

  Scenario: work carries on while nobody is attached, and is still there on return
    Given a project called "session" with a file in it
    And a terminal on the machine
    When I run "cd ~/session && sokar task start --repository session --agent stub --attach shell --detach"
    And I run "sokar task attach $(sokar task list | grep -o 'sokar-session[^ ]*' | head -1)"
    And I wait for the session inside the container
    And I run "echo MARKER-BEFORE-LEAVING"
    And I run "(sleep 120 && echo LATE) > /tmp/carried-on 2>&1 &"
    Then the terminal shows "MARKER-BEFORE-LEAVING"

    When I log off
    And a terminal on the machine
    And I run "sokar task attach $(sokar task list | grep -o 'sokar-session[^ ]*' | head -1)"
    And I wait for the session inside the container
    Then the terminal shows "MARKER-BEFORE-LEAVING"

  Scenario: the process started before the disconnect is still running after it
    Given a terminal on the machine
    When I run "sokar task attach $(sokar task list | grep -o 'sokar-session[^ ]*' | head -1)"
    And I wait for the session inside the container
    And I run "pgrep -f 'sleep 120' >/dev/null && echo STILL-RUNNING || echo GONE"
    Then the terminal shows "STILL-RUNNING"

  Scenario: the task is cleaned up afterwards
    When a script runs "sokar task remove $(sokar task list | grep -o 'sokar-session[^ ]*' | head -1) --force"
    Then it exits zero
