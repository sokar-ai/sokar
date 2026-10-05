Feature: The project commands

  A project comes to be on a machine by the machine following its repository, and by nothing else.
  So the follow is where a mistake has to stop - not at the first task start, minutes later and
  somewhere else - and a check of a follow must record nothing.

  Scenario Outline: every project command answers for itself
    When a script runs "sokar project <command> --help"
    Then it exits zero

    Examples:
      | command   |
      | follow    |
      | unfollow  |
      | following |
      | list      |

  Scenario: a check says a follow would work, and records nothing
    When a script runs:
      """
      dir=$(mktemp -d) && cd "$dir" && git init -q -b main .
      printf '%s\n' 'project:' '  name: "checked"' '  security_class: "guarded"' 'image:' '  base_image: "ubuntu:24.04"' > project.yml
      git add -A && git -c user.email=t@example.com -c user.name=T commit -q -m configuration
      sokar project follow checked "$dir" --unverified --dry-run
      echo "exit $?"
      rm -rf "$dir"
      """
    Then its output contains "ready"
    And its output contains "exit 0"
    When a script runs "sokar project following"
    Then its output does not contain "checked"

  Scenario: a set this machine does not have stops the follow
    When a script runs:
      """
      dir=$(mktemp -d) && cd "$dir" && git init -q -b main .
      printf '%s\n' 'project:' '  name: "misspelled"' '  security_class: "guarded"' 'image:' '  base_image: "ubuntu:24.04"' \
          'egress:' '  sets: [mvn]' > project.yml
      git add -A && git -c user.email=t@example.com -c user.name=T commit -q -m configuration
      sokar project follow misspelled "$dir" --unverified
      echo "exit $?"
      sokar project unfollow misspelled --force >/dev/null 2>&1
      rm -rf "$dir"
      """
    Then its output contains "egress set"
    And its output does not contain "exit 0"

  Scenario: a followed project is listed before anything ran in it, marked as unverified
    # Before any task: a list assembled from mirrors, tasks and a registry once came back empty for a
    # project that had only been followed. And "unverified" is the one line that says who decides what
    # a task here may reach - with no signature checked, whoever can push to its repository.
    Given a project called "listed" of class "guarded" with a file in it
    When a script runs "sokar project list"
    Then it exits zero
    And its output contains "listed"
    And its output contains "unverified"
    When a script runs "sokar project following"
    Then it exits zero
    And its output has a line matching "listed +[0-9a-f]+ +applied +.*"
    And its output contains "unverified"

  Scenario: a name this machine does not have is refused, with the names it has
    Given a project called "listed" of class "guarded" with a file in it
    When a script runs "sokar project unfollow not-a-project-here --dry-run"
    Then it exits non-zero
    And its output contains "no project called 'not-a-project-here'"
    And its output contains "listed"

  Scenario: unfollowing says what goes before it goes, and then the project is gone
    Given a project called "dropped" of class "guarded" with a file in it
    When a script runs "sokar project unfollow dropped --dry-run"
    Then it exits zero
    And its output contains "would remove dropped and stop following"
    When a script runs "sokar project list"
    Then its output contains "dropped"
    When a script runs "sokar project unfollow dropped"
    Then it exits zero
    And its output contains "no longer following"
    When a script runs "sokar project list"
    Then its output does not contain "dropped"
