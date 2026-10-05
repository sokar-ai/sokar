@slow
Feature: A machine joins a project in one step a person can follow

  Its message key's public half goes into the project's machine-signers as a change waiting in the project's
  gate; a person reads it - a signer list first - and merges it signed with their own key. No secret leaves
  the machine and no key is copied by hand.

  Scenario: a machine's enrolment is reviewed as dangerous and merged as a signed commit
    # The deploy key's secret half goes into the vault.
    Given a vault of this scenario's own, unlocked with the passphrase "acceptance"
    # Asked of the daemon below, as an interface asks it.
    And a daemon of this scenario's own
    When a script runs:
      """
      base=$(mktemp -d)
      ssh-keygen -q -t ed25519 -N '' -C project -f "$base/key"
      git init -q --bare --initial-branch=main "$base/upstream.git"
      git init -q --initial-branch=main "$base/seed"
      printf '%s\n' 'project:' '  name: "enrolling"' '  security_class: "guarded"' "  upstream: \"$base/upstream.git\"" \
          'image:' '  base_image: "ubuntu:24.04"' > "$base/seed/project.yml"
      git -C "$base/seed" add project.yml
      git -C "$base/seed" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
          -c user.signingkey="$base/key.pub" commit -q -S -m 'the project'
      git -C "$base/seed" push -q "$base/upstream.git" HEAD:main
      sokar project follow enrolling "$base/upstream.git" --signed-by "$(cut -d' ' -f1,2 "$base/key.pub")"; echo "follow exit $?"
      sokar project enroll enrolling --as sokar@acceptance; echo "enroll exit $?"
      sokar gate pending -p enrolling
      sokar gate review enroll-sokar-acceptance -p enrolling | head -12
      # The interface's way: the pending work leaves as a bundle, for a merge signed on the person's computer.
      printf '%s\0' '{"method":"org.fuin.sokar.Tasks1.PendingBundle","parameters":{"project":"enrolling","name":"enroll-sokar-acceptance"}}' \
          | timeout 20 sokar daemon connect | tr '\0' '\n' | head -1 \
          | python3 -c 'import json,sys,base64; r=json.load(sys.stdin); open(sys.argv[1],"wb").write(base64.b64decode(r["parameters"]["bundle"]))' "$base/pending.bundle"
      git -C "$base/seed" fetch -q "$base/pending.bundle" refs/sokar/incoming/enroll-sokar-acceptance && echo "bundle fetched"
      printf '%s\0' '{"method":"org.fuin.sokar.Tasks1.Landed","parameters":{"project":"enrolling","name":"enroll-sokar-acceptance","branch":"main"}}' \
          | timeout 20 sokar daemon connect | tr '\0' '\n' | head -1 | grep -o '"landed":false\|not on the upstream.s main yet' | sed 's/^/before the push: /' 
      printf '[user]\n\tname = Person\n\temail = person@example.org\n\tsigningkey = %s\n[gpg]\n\tformat = ssh\n' \
          "$base/key.pub" > "$base/gitconfig"
      GIT_CONFIG_GLOBAL="$base/gitconfig" sokar gate approve enroll-sokar-acceptance -p enrolling --signed; echo "approve exit $?"
      git --git-dir "$base/upstream.git" show main:machine-signers | cut -d' ' -f1,2 | sed 's/^/upstream signer: /'
      git --git-dir "$base/upstream.git" cat-file -p main | grep -c '^gpgsig ' | sed 's/^/signed merges: /'
      # Forge access: nothing authenticates to a path on this machine, so no key is made for one.
      sokar credentials deploy-key enrolling; echo "deploy-key on a path exit $?"
      sokar vault list | grep -c 'deploy:enrolling:enrolling' | sed 's/^/left in the vault: /'
      # One deploy key per machine for a forge: its secret half in the vault, its public half printed once.
      git init -q --initial-branch=main "$base/forged"
      printf '%s\n' 'project:' '  name: "forged"' '  security_class: "guarded"' \
          '  upstream: "ssh://git@forge.sokar-acceptance.invalid/team/forged.git"' 'image:' '  base_image: "ubuntu:24.04"' \
          > "$base/forged/project.yml"
      git -C "$base/forged" add project.yml
      git -C "$base/forged" -c user.name=P -c user.email=p@example.org commit -q -m 'a project with a forge'
      sokar project follow forged "$base/forged" --unverified >/dev/null
      sokar credentials deploy-key forged > "$base/k1"; echo "deploy-key exit $?"
      sokar credentials deploy-key forged > "$base/k2"
      grep -c 'ssh-ed25519 ' "$base/k1" | sed 's/^/deploy keys shown: /'
      [ "$(grep -o 'ssh-ed25519 [^ ]*' "$base/k1")" = "$(grep -o 'ssh-ed25519 [^ ]*' "$base/k2")" ] \
          && echo "the same deploy key again" || { cat "$base/k1" "$base/k2"; }
      sokar vault list | grep -c 'deploy:forged:forged' | sed 's/^/in the vault: /'
      sokar credentials check ssh://git@forge.sokar-acceptance.invalid/team/forged.git | grep -c 'deploy:forged:forged' \
          | sed 's/^/declared for the forge: /'
      grep -c 'read-only' "$base/k1" | sed 's/^/read-only for its own repository: /'
      # Unfollowing forgets the key here and names it for removal at the forge, which is what grants access.
      sokar project unfollow forged --force | grep "remove at the forge" | sed 's/SHA256:[^ ]*/SHA256:x/'
      sokar vault list | grep -c 'deploy:forged:forged' | sed 's/^/after unfollow in the vault: /' 
      sokar project unfollow enrolling --force >/dev/null 2>&1
      rm -rf "$base"
      """
    Then its output contains "follow exit 0"
    And its output contains "enrolment enroll-sokar-acceptance waits for a person in enrolling's gate"
    And its output contains "this machine sokar@acceptance  SHA256:"
    And its output contains "configuration signed by  SHA256:"
    And its output contains "enroll exit 0"
    And its output contains "enroll-sokar-acceptance"
    And its output contains "signer list"
    And its output contains "bundle fetched"
    And its output contains 'before the push: "landed":false'
    And its output contains "before the push: not on the upstream's main yet"
    And its output contains "approve exit 0"
    And its output contains "upstream signer: sokar@acceptance ssh-ed25519"
    And its output contains "signed merges: 1"
    And its output contains "is a path on this machine"
    And its output contains "deploy-key on a path exit 64"
    And its output contains "left in the vault: 0"
    And its output contains "deploy-key exit 0"
    And its output contains "deploy keys shown: 1"
    And its output contains "the same deploy key again"
    And its output contains "in the vault: 1"
    And its output contains "declared for the forge: 1"
    And its output contains "read-only for its own repository: 1"
    And its output contains "remove at the forge: 'sokar "
    And its output contains "forged/forged' SHA256:x on ssh://git@forge.sokar-acceptance.invalid/team/forged.git"
    And its output contains "after unfollow in the vault: 0"
