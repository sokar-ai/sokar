Feature: A project is followed with the key a person names, the same over the socket as at the terminal

  The key comes from the person, never out of the repository it verifies. A whole key is pinned as it came;
  a fingerprint pins the key that signed the commit turned away, once its fingerprint is the one named. A dry
  run records nothing, not even the key.

  Scenario: an interface follows a signed project by its fingerprint, and a dry run pins nothing
    Given a daemon of this scenario's own
    When a script runs:
      """
      base=$(mktemp -d)
      # A name of its own: the account outlives the scenario, and one left followed by an earlier run
      # refused this one for a different address rather than for the key.
      name="signed-by-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      signers="${XDG_CONFIG_HOME:-$HOME/.config}/sokar/project-signers"
      ssh-keygen -q -t ed25519 -N '' -C project -f "$base/key"
      key=$(cut -d' ' -f1,2 "$base/key.pub")
      fingerprint=$(ssh-keygen -lf "$base/key.pub" | cut -d' ' -f2)
      git init -q --initial-branch=main "$base/signed"
      printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' \
          'image:' '  base_image: "ubuntu:24.04"' > "$base/signed/project.yml"
      git -C "$base/signed" add project.yml
      git -C "$base/signed" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
          -c user.signingkey="$base/key.pub" commit -q -S -m 'the project'
      ask() {
          printf '%s\0' "{\"method\":\"org.fuin.sokar.Tasks1.Follow\",\"parameters\":$1}" \
              | timeout 60 sokar daemon connect | tr '\0' '\n' | head -1
      }
      pinned() { cat "$signers" 2>/dev/null | grep -c "${key#* }"; }
      sokar project follow "$name" "$base/signed" --signed-by "$key" --dry-run >/dev/null; echo "cli dry run exit $?"
      echo "pinned after the terminal's dry run: $(pinned)"
      ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$key\",\"dryRun\":true}" \
          | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/dry run by key: /'
      ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$fingerprint\",\"dryRun\":true}" \
          | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/dry run by fingerprint: /'
      echo "pinned after the dry runs: $(pinned)"
      ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}" \
          | grep -o 'not by SHA256:A*' | sed 's/^/another key: /'
      sokar project following 2>/dev/null | grep -c "^$name " | sed 's/^/followed after another key: /'
      ask "{\"name\":\"$name\",\"url\":\"$base/signed\",\"signedBy\":\"$fingerprint\"}" \
          | grep -o '"outcome":"[A-Z_]*"' | sed 's/^/follow by fingerprint: /'
      echo "pinned after the follow: $(pinned)"
      ask "{\"name\":\"$name\",\"url\":\"$base/signed\"}" | grep -q "\"signer\":\"$fingerprint\"" \
          && echo "an applied follow names its signer"
      sokar project unfollow "$name" >/dev/null 2>&1
      """
    Then its output contains "cli dry run exit 0"
    And its output contains "pinned after the terminal's dry run: 0"
    And its output contains "dry run by key: \"outcome\":\"READY\""
    And its output contains "dry run by fingerprint: \"outcome\":\"READY\""
    And its output contains "pinned after the dry runs: 0"
    And its output contains "another key: not by SHA256:A"
    And its output contains "followed after another key: 0"
    And its output contains "follow by fingerprint: \"outcome\":\"APPLIED\""
    And its output contains "pinned after the follow: 1"
    And its output contains "an applied follow names its signer"

  Scenario: a pinned key hands on to the key its commit names in project.signers, and no key vouches for itself
    Given a daemon of this scenario's own
    When a script runs:
      """
      base=$(mktemp -d)
      name="handed-$(basename "$base" | tr 'A-Z' 'a-z' | tr -dc 'a-z0-9')"
      signers="${XDG_CONFIG_HOME:-$HOME/.config}/sokar/project-signers"
      for k in old successor stranger; do ssh-keygen -q -t ed25519 -N '' -C "$k" -f "$base/$k"; done
      key() { cut -d' ' -f1,2 "$base/$1.pub"; }
      git init -q --initial-branch=main "$base/repo"
      write() {
          printf '%s\n' 'project:' "  name: \"$name\"" '  security_class: "guarded"' > "$base/repo/project.yml"
          if [ $# -gt 0 ]; then
              echo '  signers:' >> "$base/repo/project.yml"
              for k in "$@"; do echo "    - \"$(key "$k")\"" >> "$base/repo/project.yml"; done
          fi
          printf '%s\n' 'image:' '  base_image: "ubuntu:24.04"' >> "$base/repo/project.yml"
      }
      signed() {
          git -C "$base/repo" add project.yml
          git -C "$base/repo" -c user.name=Person -c user.email=person@example.org -c gpg.format=ssh \
              -c user.signingkey="$base/$1.pub" commit -q -S --allow-empty -m "$2"
      }
      follow() { sokar project follow "$name" "$base/repo" 2>&1 | head -3 | tr '\n' ' '; echo; }
      write; signed old 'the project'
      sokar project follow "$name" "$base/repo" --signed-by "$(key old)" >/dev/null 2>&1; echo "followed exit $?"
      write old successor; signed old 'the old key hands on'
      echo "hand-on: $(follow)"
      echo "successor pinned: $(grep -c "$(key successor | cut -d' ' -f2)" "$signers")"
      write successor; signed successor 'the successor alone retires the old key'
      echo "retired: $(follow)"
      echo "old pinned: $(grep -c "$(key old | cut -d' ' -f2)" "$signers")"
      write successor stranger; signed stranger 'a key adds itself'
      echo "self-added: $(follow)"
      echo "stranger pinned: $(grep -c "$(key stranger | cut -d' ' -f2)" "$signers")"
      sokar project unfollow "$name" >/dev/null 2>&1
      """
    Then its output contains "followed exit 0"
    And its output contains "successor pinned: 1"
    And its output contains "old pinned: 0"
    And its output contains "changes project.signers and is not signed by a key in force before it"
    And its output contains "stranger pinned: 0"
