@slow
Feature: Being inside a task, the way a person is

  These drive a real container: they build an image, start a task, and type into the shell it
  opens. Minutes rather than seconds, and tagged so an ordinary run can leave them out.

  What makes them worth the time is that nothing else can see any of it. The workspace, the
  prompt, what a bare push does and what leaving the shell decides are all things that exist only
  once somebody is inside.

  Scenario: the workspace holds the project, not just a .git directory
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "cd ~/accept && sokar task run --attach shell"
    And I wait for the shell inside the container
    And I run "ls -A /workspace"
    Then the terminal shows "README.md"
    And the terminal shows ".git"
    When I run "exit"
    Then the terminal shows "ready$"

  Scenario: the prompt inside a task says which task it is
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "cd ~/accept && sokar task run --attach shell"
    And I wait for the shell inside the container
    Then the terminal shows "sokar[accept/shell]"
    When I run "exit"
    Then the terminal shows "ready$"

  Scenario: a bare push inside a task reaches the gate, not a branch in the mirror
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "cd ~/accept && sokar task run --attach shell"
    And I wait for the shell inside the container
    And I run "cd /workspace && echo work > NEW.md && git add -A && git commit -q -m work && git push"
    Then the terminal shows "refs/sokar/incoming"
    When I run "exit"
    Then the terminal shows "ready$"

  Scenario: leaving a shell with work in it keeps the task rather than discarding it
    Given a project called "accept" with a file in it
    And a terminal on the machine
    When I run "cd ~/accept && sokar task run --attach shell"
    And I wait for the shell inside the container
    And I run "echo uncommitted >> /workspace/README.md"
    And I run "exit"
    Then the terminal shows "never reached the gate"
