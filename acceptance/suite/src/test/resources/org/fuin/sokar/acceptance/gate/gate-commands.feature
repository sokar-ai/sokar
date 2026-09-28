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
    # The project is named, so what is still missing is the push - which is what this is about.
    # Without it the first thing missing would be the project, and that is a different sentence.
    Given a terminal on the machine
    When I run "sokar gate <command> --project nothing-here"
    Then the terminal shows "issing required parameter"

    Examples:
      | command |
      | review  |
      | approve |
      | reject  |

  Scenario: the pre-push guard stops a push of agent work nobody approved, and nothing else
    # Git's behaviour rather than Sokar's, which is why a real push is what is checked: the two bugs
    # found building this - a rev-list that answered zero every time, and a worktree whose hooks live
    # elsewhere - both installed cleanly and fired for nobody.
    When a script runs:
      """
      dir=$(mktemp -d)
      agent=$(sokar agents --verbose | awk '/^[^ ]/ { row = ($1 == "stub") } row && $1 == "commits:" { print }' | sed 's/.*<\(.*\)>.*/\1/')
      git init -q --initial-branch=main "$dir/work" && git init -q --bare --initial-branch=main "$dir/up.git"
      git -C "$dir/work" remote add origin "$dir/up.git"
      git -C "$dir/work" -c user.name=Me -c user.email=me@example.com commit -q --allow-empty -m mine
      sokar gate protect --repo "$dir/work" >/dev/null && test -x "$dir/work/.git/hooks/pre-push" && echo "a hook git runs is installed"
      git -C "$dir/work" push -q origin main && echo "a person's own push went through"
      git -C "$dir/work" -c user.name=Agent -c user.email="$agent" commit -q --allow-empty -m "agent work"
      git -C "$dir/work" push -q origin main 2>&1 || echo "the agent's push was stopped"
      git -C "$dir/work" push -q --no-verify origin main && echo "--no-verify went past it"
      # core.hooksPath silently disables every hook in a repository; installing without saying so
      # would leave somebody believing they were protected.
      mkdir "$dir/elsewhere" && git -C "$dir/work" config core.hooksPath "$dir/elsewhere"
      sokar gate protect --repo "$dir/work" 2>&1
      rm -rf "$dir"
      """
    Then its output contains "a hook git runs is installed"
    And its output contains "a person's own push went through"
    And its output contains "an agent wrote"
    And its output contains "the agent's push was stopped"
    And its output contains "--no-verify went past it"
    And its output contains "not protected"

  Scenario: a push that carries no agent's commit passes the check, quietly when asked to
    When a script runs:
      """
      zero=0000000000000000000000000000000000000000
      echo "refs/heads/x $zero refs/heads/x $zero" | sokar gate check; echo "exit $?"
      echo "refs/heads/x $zero refs/heads/x $zero" | sokar gate check --quiet | sed 's/^/quiet: /'
      """
    Then its output contains "nothing an agent wrote is in this push"
    And its output contains "exit 0"
    And its output does not contain "quiet: "

  Scenario Outline: a gate command about a project this machine does not have names what it has
    When a script runs "<command>"
    Then it exits non-zero
    And its output contains "no project 'no-such-project' here"

    Examples:
      | command                                                           |
      | sokar gate pending --project no-such-project                      |
      | sokar gate checkout nope --project no-such-project                |
      | sokar gate backup /tmp/sokar-acceptance.bundle --project no-such-project  |
      | sokar gate restore /tmp/sokar-acceptance.bundle --project no-such-project |
