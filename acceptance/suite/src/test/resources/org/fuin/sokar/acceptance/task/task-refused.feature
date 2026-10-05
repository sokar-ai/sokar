@slow
Feature: A task that must not start, and what its refusal leaves behind

  A refusal is only worth having if it creates nothing on its way out. A start that failed after its
  broker was listening, or an unattended run refused after its gate mirror was made, is the old
  failure with a different exit code - and the next run fails for a reason that has nothing to do
  with what changed.

  Scenario: a start names the repository it is for, and a dry run needs none
    Given a project called "chosen" of class "guarded" with a file in it
    # Sokar never picks, not even where the project has exactly one; the refusal names where to look.
    When a script runs "sokar task start --project chosen --agent stub --detach --clearance deny"
    Then it exits non-zero
    And its output contains "--repository"
    And its output contains "chosen"
    # A dry run starts nothing, so what it reports without one is the plan every repository shares.
    When a script runs "sokar task start --project chosen --agent stub --dry-run"
    Then it exits zero

  Scenario: a task whose image cannot be built fails, and leaves no helper and no pid behind
    # Broken on purpose so the failure comes after the broker is listening and before a container
    # exists - the gap no poststop hook covers, because it only fires for a container that ran.
    When a script runs:
      """
      dir=$(mktemp -d) && cd "$dir" && git init -q -b main .
      printf '%s\n' 'project:' '  name: "unbuildable"' '  security_class: "guarded"' 'image:' \
          '  base_image: "sokar-no-such-base-image:0"' > project.yml
      git add -A && git -c user.email=t@example.com -c user.name=T commit -q -m configuration
      sokar project follow unbuildable "$dir" --unverified >/dev/null
      sokar task start --project unbuildable --repository unbuildable --agent stub --detach --clearance deny >/dev/null 2>&1 \
          && echo "the start reported success" || echo "the start failed"
      # The name is put together here, so this script's own command line does not match it.
      p=unbuildable
      pgrep -f "[s]okar-$p-" >/dev/null && echo "a helper is still running" || echo "no helper is running"
      find "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar" -maxdepth 2 -path "*sokar-$p-*" -name '*.pid' | grep -q . \
          && echo "a pid file claims a live helper" || echo "no pid file is left"
      sokar project unfollow unbuildable --force >/dev/null 2>&1
      rm -rf "$dir" "${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/sokar/sokar-$p-"*
      """
    Then its output contains "the start failed"
    And its output contains "no helper is running"
    And its output contains "no pid file is left"

  Scenario: an unattended run with a locked vault is refused, and creates nothing
    # The one case where Sokar refuses instead of warning: nobody is watching, so the failure would be
    # found later by somebody who did not start it, with a workspace and a container to clear up.
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    And a project called "locked" of class "guarded" with a file in it
    # The vault still holds the credential; what is missing is the ability to read it.
    When a script runs:
      """
      rm -rf "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/mirrors/locked.git"
      sokar vault lock >/dev/null
      sokar task start unattended --project locked --repository locked --agent stub --clearance deny -P "say hello and stop"
      echo "exit $?"
      test -d "${XDG_DATA_HOME:-$HOME/.local/share}/sokar/mirrors/locked.git" && echo "a gate mirror was made" || echo "no gate mirror was made"
      podman ps -a --format '{{.Names}}' | grep -q '^sokar-locked-unattended' && echo "a container was made" || echo "no container was made"
      """
    Then its output contains "the vault is locked"
    And its output does not contain "exit 0"
    And its output contains "no gate mirror was made"
    And its output contains "no container was made"
