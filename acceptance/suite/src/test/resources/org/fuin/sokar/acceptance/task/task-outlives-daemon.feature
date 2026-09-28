@slow
Feature: A task outlives the daemon that started it

  A task ends when something asks for that task to end - not when the daemon that started it is
  stopped, restarted or lost. The daemon is a unit whose control group systemd stops whole, and a task
  started through it had its monitor, its network, its resolver and its helpers there: stopping the
  daemon left the task Exited (143). Every one of them now runs in a scope of its own, and a record
  of each carries its start time, so removing the task reaps them.

  The daemon here is this scenario's own unit, so stopping it is what stopping sokard.service is; the
  account's daemon is started again afterwards.

  Background:
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "outlived" of class "guarded" with a file in it
    And a daemon of this scenario's own

  Scenario: a task started through the daemon keeps running when the daemon is stopped, restarted or lost
    When a script runs:
      """
      printf '%s\0' '{"method":"org.fuin.sokar.Tasks1.Start","parameters":{"task":"outlives","project":"outlived","agent":"stub","repository":"outlived","clearance":"deny","keep":true}}' \
          | timeout 600 sokar daemon connect 2>/dev/null | tr '\0' '\n' | head -1 | grep -oE '"container":"[^"]*"'
      """
    Then its output contains '"container":"sokar-outlived-outlives"'
    # A test that fails if a task's process is ever found in the daemon's control group again.
    When a script runs:
      """
      c=sokar-outlived-outlives; state="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/$c"
      for pid in $(podman inspect --format '{{.State.ConmonPid}}' "$c") $(cut -d' ' -f1 "$state"/*.pid 2>/dev/null); do
          grep -q 'sokar-acceptance-sokard.service' "/proc/$pid/cgroup" 2>/dev/null && echo "in the daemon's group: $pid $(tr '\0' ' ' < /proc/$pid/cmdline)"
          grep -q '/sokar.slice/' "/proc/$pid/cgroup" 2>/dev/null && echo "in a scope of its own: $pid"
      done
      """
    Then its output contains "in a scope of its own"
    And its output does not contain "in the daemon's group"
    When the daemon's unit is stopped
    And a script runs:
      """
      c=sokar-outlived-outlives; state="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/$c"
      podman ps --filter "name=^$c\$" --format '{{.Status}}' | sed 's/^/container: /'
      for f in "$state"/*.pid; do kill -0 "$(cut -d' ' -f1 "$f")" 2>/dev/null && echo "alive: $(basename "$f" .pid)" || echo "gone: $(basename "$f" .pid)"; done
      podman exec "$c" sh -c 'cd /workspace && timeout 20 git ls-remote sokar >/dev/null' && echo "the gate answers"
      """
    Then its output contains "container: Up"
    And its output contains "alive: gate"
    And its output does not contain "gone:"
    And its output contains "the gate answers"
    When the daemon is started again
    And the daemon crashes and systemd restarts it
    And a script runs:
      """
      c=sokar-outlived-outlives
      podman ps --filter "name=^$c\$" --format '{{.Status}}' | sed 's/^/container: /'
      podman exec "$c" sh -c 'cd /workspace && timeout 20 git ls-remote sokar >/dev/null' && echo "the gate answers"
      """
    Then its output contains "container: Up"
    And its output contains "the gate answers"
    # A task's scopes end when the task ends: nothing of it outlives its removal.
    When a script runs:
      """
      # Put together here, so this script's own command line does not match what it looks for.
      p=outlived; c="sokar-$p-outlives"
      sokar task remove "$c" --force >/dev/null 2>&1
      sleep 2
      systemctl --user list-units --type=scope --all --no-legend --plain | grep -F "sokar $c" | sed 's/^/left: /'
      pgrep -af "[s]okar-$p-outlives" | sed 's/^/process left: /'
      true
      """
    Then its output does not contain "left:"
