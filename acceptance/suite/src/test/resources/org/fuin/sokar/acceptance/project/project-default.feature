@slow
Feature: Work starts without a project, in the project every machine has

  A task started in a checked-out repository with no project named goes into 'default', whose settings are
  Sokar's; nothing about it can be widened, and the name belongs to no followed project.

  Scenario: a task starts in a checkout with no project named, and 'default' cannot be given more
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    When a script runs:
      """
      base=$(mktemp -d)
      repo="dflt$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      git clone -q "$base/$repo.git" "$base/$repo"
      cd "$base/$repo"
      timeout 300 sokar task start probe --agent stub --detach --clearance deny > "$base/start" 2>&1
      echo "start exit $?"
      grep -o "added to 'default' as '$repo'" "$base/start"
      sokar project default | grep -c "^$repo " | sed 's/^/listed in default: /'
      sokar task list | grep -c "sokar-default-probe" | sed 's/^/a task in default: /'
      sokar shield egress --project default --add-domain example.org 2>&1 | grep -o "a project repository is the way"
      sokar project follow default "$base/$repo.git" --unverified 2>&1 | grep -o "no followed project may be called that"
      sokar project unfollow default 2>&1 | grep -o "is always there and is never removed"
      sokar task remove sokar-default-probe --force > /dev/null 2>&1
      sokar project default remove "$repo"
      sokar project default | grep -c "^$repo " | sed 's/^/still listed: /'
      """
    Then its output contains "start exit 0"
    And its output contains "added to 'default' as 'dflt"
    And its output contains "listed in default: 1"
    And its output contains "a task in default: 1"
    And its output contains "a project repository is the way"
    And its output contains "no followed project may be called that"
    And its output contains "is always there and is never removed"
    And its output contains "still listed: 0"

  Scenario: approved work in 'default' reaches an ssh origin with the developer's own key, which the task never holds
    # The developer's key is a throwaway one in an ssh agent, allowed into this account only while the scenario
    # runs; the origin is this machine itself over ssh. Its host key is vouched for from the machine's own key
    # file, the way a person compares a fingerprint, never accepted on first use. The repository's source is the
    # origin, added to 'default' by its address: a task started from a checkout takes the checkout as its source,
    # and its approved work lands there, never at the origin.
    Given the environment variable "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" is set
    And a vault of this scenario's own, unlocked with the passphrase "acceptance"
    And the vault holds the value of "SOKAR_ACCEPTANCE_FAKE_CREDENTIAL" as "anthropic" of kind "api-key"
    When a script runs:
      """
      base=$(mktemp -d)
      repo="dssh$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      git init -q --bare --initial-branch=main "$base/$repo.git"
      git init -q --initial-branch=main "$base/seed"
      git -C "$base/seed" -c user.name=T -c user.email=t@example.org commit -q --allow-empty -m 'first'
      git -C "$base/seed" push -q "$base/$repo.git" HEAD:main
      ssh-keygen -q -t ed25519 -N '' -C "sokar-acceptance-$repo" -f "$base/key"
      # Taken back whatever happens below: the key is allowed into this account for this scenario only.
      trap 'sed -i "/sokar-acceptance-$repo\$/d" ~/.ssh/authorized_keys; ssh-agent -k > /dev/null 2>&1' EXIT
      cat "$base/key.pub" >> ~/.ssh/authorized_keys
      eval "$(ssh-agent -s)" > /dev/null && ssh-add -q "$base/key"
      origin="ssh://$USER@127.0.0.1$base/$repo.git"
      sokar project default add "$origin" --name "$repo"
      # Before anybody vouched for the host: an agent's task has no gate, so it is not started at all.
      sed -i '/^127\.0\.0\.1 /d' "${XDG_STATE_HOME:-$HOME/.local/state}/sokar/known_hosts" 2>/dev/null
      (cd "$base" && timeout 300 sokar task start early -p default -r "$repo" --agent stub --detach --clearance deny > "$base/early" 2>&1; echo "unvouched start exit $?")
      grep -o "nothing was created" "$base/early"
      grep -o "this machine has never met 127.0.0.1" "$base/early"
      grep -o "sokar credentials trust-host 127.0.0.1" "$base/early"
      podman container exists sokar-default-early && echo "a container exists" || echo "no container"
      sokar credentials trust-host 127.0.0.1 \
          --fingerprint "$(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub | awk '{ print $2 }')" > /dev/null
      cd "$base"
      timeout 300 sokar task start sshwork -p default -r "$repo" --agent stub --detach --clearance deny > "$base/start" 2>&1
      echo "start exit $?"
      # Detached, so the start returns before the workspace is cloned from the gate.
      for i in $(seq 1 120); do podman exec sokar-default-sshwork git -C /workspace rev-parse HEAD > /dev/null 2>&1 && break; sleep 1; done
      podman exec sokar-default-sshwork sh -c 'cd /workspace && echo "done by the agent" >> README.md && git add -A && git -c user.email=agent@localhost -c user.name=agent commit -qm "acceptance: work for an ssh origin" && timeout 30 git push -q sokar HEAD:"$SOKAR_TASK_REF"'
      echo "pushed to the gate $?"
      podman exec sokar-default-sshwork sh -c 'env | grep -c "^SSH_AUTH_SOCK=" ; grep -rl "sokar-acceptance-" "$HOME" /workspace 2>/dev/null | wc -l' | tr '\n' ' ' | sed 's/^/in the task (agent socket, key): /'
      echo
      # As a person types it: no repository named. The task's own record says which one it worked on.
      sokar gate pending --project default | grep -c "^$repo  *sshwork " | sed 's/^/listed as pending: /'
      sokar gate pending | grep -c "^default  *$repo  *sshwork " | sed 's/^/listed with nothing named: /'
      sokar gate approve sshwork --project default --branch sokar/sshwork > "$base/approve" 2>&1
      echo "approve exit $?"
      git --git-dir="$base/$repo.git" log --format='%s' -1 sokar/sshwork | sed 's/^/arrived: /'
      sokar task remove sokar-default-sshwork --force > /dev/null 2>&1
      sokar project default remove "$repo" > /dev/null
      ssh-agent -k > /dev/null
      sed -i "/sokar-acceptance-$repo\$/d" ~/.ssh/authorized_keys
      grep -c "sokar-acceptance-$repo" ~/.ssh/authorized_keys | sed 's/^/key left allowed: /'
      """
    Then its output contains "unvouched start exit 69"
    And its output contains "nothing was created"
    And its output contains "no container"
    And its output contains "this machine has never met 127.0.0.1"
    And its output contains "sokar credentials trust-host 127.0.0.1"
    And its output contains "start exit 0"
    And its output contains "in 'default' as dssh"
    And its output contains "pushed to the gate 0"
    And its output contains "in the task (agent socket, key): 0 0"
    And its output contains "listed as pending: 1"
    And its output contains "listed with nothing named: 1"
    And its output contains "approve exit 0"
    And its output contains "arrived: acceptance: work for an ssh origin"
    And its output contains "key left allowed: 0"
