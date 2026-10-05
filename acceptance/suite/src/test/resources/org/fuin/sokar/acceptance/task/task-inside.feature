@slow
Feature: Being inside a task, the way a person is
  # --agent is named because this machine may have more than one installed. CI builds and installs
  # only the stub, so the scenarios passed there without it and could never pass on a machine that
  # also carries a real agent - which is every machine a person develops on. Which agent is in the
  # image is irrelevant to everything below; being silent about it was a hidden dependency on the
  # CI environment's roster.

  These drive a real container: they build an image, start a task, and type into the shell it
  opens. Minutes rather than seconds, and tagged so an ordinary run can leave them out.

  What makes them worth the time is that nothing else can see any of it. The workspace, the
  prompt, what a bare push does and what leaving the shell decides are all things that exist only
  once somebody is inside.

  Every scenario removes its task at the end. It did not have to before the lifecycle cut: every
  run made its own container and leaving the shell removed it. Now there is one container per
  project and task and it is kept when you leave, so a scenario that does not clean up hands the
  next one a task that is already running - which 'start' refuses, correctly, and which reads as
  a hang while the harness waits for a prompt that will never come.

  Scenario: the workspace holds the project, not just a .git directory
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "sokar task start shell --project accept --repository accept --agent stub --attach shell"
    And I wait for the shell inside the container
    And I run "ls -A /workspace"
    Then the terminal shows "README.md"
    And the terminal shows ".git"
    When I run "exit"
    Then the terminal shows "ready$"
    And a script runs "sokar task remove sokar-accept-shell --force"

  Scenario: the prompt inside a task says which task it is
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "sokar task start shell --project accept --repository accept --agent stub --attach shell"
    And I wait for the shell inside the container
    Then the terminal shows "sokar[accept/shell]"
    When I run "exit"
    Then the terminal shows "ready$"
    And a script runs "sokar task remove sokar-accept-shell --force"

  Scenario: a bare push inside a task reaches the gate, not a branch in the mirror
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "sokar task start shell --project accept --repository accept --agent stub --attach shell"
    And I wait for the shell inside the container
    And I run "cd /workspace && echo work > NEW.md && git add -A && git commit -q -m work && git push"
    Then the terminal shows "refs/sokar/incoming"
    When I run "exit"
    Then the terminal shows "ready$"
    And a script runs "sokar task remove sokar-accept-shell --force"

  # --rm, because that is what the scenario is about. Keeping became the DEFAULT at the lifecycle
  # cut, so without it nothing was ever going to be removed, there was nothing to refuse, and the
  # message this asserts had no reason to be printed. The scenario has been silently describing
  # pre-cut behaviour ever since - which nobody could notice, because it runs nowhere.
  Scenario: leaving a shell with work in it keeps the task rather than discarding it
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "sokar task start shell --project accept --repository accept --agent stub --attach shell --rm"
    And I wait for the shell inside the container
    And I run "echo uncommitted >> /workspace/README.md"
    And I run "exit"
    Then the terminal shows "never reached the gate"
    And a script runs "sokar task remove sokar-accept-shell --force"
