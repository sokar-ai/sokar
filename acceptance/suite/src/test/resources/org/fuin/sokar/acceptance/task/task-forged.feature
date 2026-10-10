Feature: A task is what Sokar made

  Anything that runs as the account can start a container with any name and any label. So a container is a task
  only when its id is the one Sokar recorded when it created it - 2026-10-04: "Sokar must not pick
  running containers by their name. Anybody could start such a container and Sokar would think it is its own."

  Scenario: a container named and labelled like a task is no task, and nothing is done to it
    # Any image the machine already holds will do; what matters is the name and the labels it is given.
    When a script runs "podman rm -f sokar-forged-task >/dev/null 2>&1; podman run -d --name sokar-forged-task --label org.fuin.sokar.project=forged --label org.fuin.sokar.class=guarded --label org.fuin.sokar.security-class=guarded --entrypoint sleep $(podman images --format '{{.Repository}}:{{.Tag}}' | grep -v '<none>' | head -1) 600 >/dev/null && echo started"
    Then it exits zero
    And its output contains "started"
    # Not a row of the table, but named under it as what it is: decided on 2026-10-10, so that "No tasks" never hides a
    # container whose removal removes what is in it.
    When a script runs "sokar task list; sokar task list | grep -c '^sokar-forged-task'; echo rows $?"
    Then its output contains "not listed: sokar-forged-task"
    And its output contains "no container id"
    And its output contains "rows 1"
    When a script runs "sokar task stop sokar-forged-task"
    Then it exits non-zero
    When a script runs "podman ps --format '{{.Names}}' | grep -c '^sokar-forged-task$'"
    Then its output contains "1"
    When a script runs "podman rm -f sokar-forged-task >/dev/null && echo gone"
    Then it exits zero
    And its output contains "gone"
