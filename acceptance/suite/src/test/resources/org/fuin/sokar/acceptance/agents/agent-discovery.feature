Feature: Which agent runs, when a machine has more than one copy of it

  Sokar finds an agent it was never linked against, by its binary. Two copies under different file
  names that call themselves the same agent resolve to the one found first - the precedence every
  other part of Sokar follows - and the other is named. Wrong invisibly otherwise: both copies look
  plausible, and the only symptom is a run behaving unlike the version on the screen.

  Scenario: an installed agent is found
    When a script runs "sokar agents"
    Then it exits zero
    And its output has a line matching "stub +sokar-stub-cli +.*"

  Scenario: the copy found first is the one that runs, and the other one is named
    # Asserted on the FROM column - the binary that actually runs - and not only on the "ignored"
    # line: that message is built from the losing side and reads the same whichever side won.
    When a script runs:
      """
      from=$(sokar agents | awk '$1 == "stub" { print $NF }')
      extra=$(mktemp -d)
      cp "$from" "$extra/sokar-agent-zz-stub"
      sokar agents --directory "$extra" > "$extra/said" 2>&1
      echo "exit $?"
      runs=$(awk '$1 == "stub" { print $NF }' "$extra/said")
      [ "$runs" = "$extra/sokar-agent-zz-stub" ] && echo "the copy found first runs" || echo "$runs runs"
      grep -q "^ignored .*$from\$" "$extra/said" && echo "the other copy is named"
      rm -rf "$extra"
      """
    Then its output contains "the copy found first runs"
    And its output contains "the other copy is named"
    # Nothing is broken - one copy runs - so nothing reading the exit code may take it for a failure.
    And its output contains "exit 0"
